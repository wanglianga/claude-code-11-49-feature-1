package com.clinic.mpi.service;

import com.clinic.mpi.domain.CandidateSnapshot;
import com.clinic.mpi.domain.ReviewAnnotation;
import com.clinic.mpi.domain.ReviewEscalation;
import com.clinic.mpi.domain.SamplingUnit;
import com.clinic.mpi.repo.CandidateSnapshotRepo;
import com.clinic.mpi.repo.ReviewAnnotationRepo;
import com.clinic.mpi.repo.ReviewEscalationRepo;
import com.clinic.mpi.repo.SamplingUnitRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 审核标注模块：经授权审核员对抽中候选出具确认 / 拒绝 / 暂不能判定标签。
 *
 * <p>铁律：标注只评价候选生成时可见的证据（{@link CandidateSnapshot} 固化值），
 * 服务层不读取来源身份的当前字段，因此日后新出现的证件号无法倒灌成"当时模型正确"。</p>
 *
 * <p>标注追加保存、不可修改；两名审核员标签不一致时保留各自标注，
 * 样本转 {@link SamplingUnit.ReviewStatus#ESCALATED_CONFLICT} 并由 ADMIN 高级复核裁决。
 * 候选撤销 / 来源更正触发的复核同样在此裁决，裁决后样本关闭、结论冻结。</p>
 */
@Service
public class ReviewService {

    private final SamplingUnitRepo unitRepo;
    private final CandidateSnapshotRepo snapshotRepo;
    private final ReviewAnnotationRepo annotationRepo;
    private final ReviewEscalationRepo escalationRepo;
    private final CandidateSnapshotService snapshotService;

    public ReviewService(SamplingUnitRepo unitRepo, CandidateSnapshotRepo snapshotRepo,
                         ReviewAnnotationRepo annotationRepo, ReviewEscalationRepo escalationRepo,
                         CandidateSnapshotService snapshotService) {
        this.unitRepo = unitRepo;
        this.snapshotRepo = snapshotRepo;
        this.annotationRepo = annotationRepo;
        this.escalationRepo = escalationRepo;
        this.snapshotService = snapshotService;
    }

    /**
     * 提交审核标注。
     *
     * @throws ConflictException 样本已关闭冻结，或批次已关闭
     */
    @Transactional
    public ReviewAnnotation annotate(UUID unitId, ReviewAnnotation.Label label,
                                     String note, String reviewerId) {
        SamplingUnit unit = unitRepo.findById(unitId)
                .orElseThrow(() -> new NotFoundException("抽样单元不存在: " + unitId));
        if (unit.reviewStatus == SamplingUnit.ReviewStatus.CLOSED) {
            throw new ConflictException("样本已复核关闭，结论冻结，不能再标注");
        }
        CandidateSnapshot snap = snapshotRepo.findById(unit.candidateSnapshotId)
                .orElseThrow(() -> new NotFoundException("候选生成快照不存在: " + unit.candidateSnapshotId));

        // 同一审核员对同一样本重复标注视为冲突风险，直接拒绝（如需改判走高级复核）
        boolean sameReviewer = annotationRepo.findBySamplingUnitIdOrderByCreatedAtAsc(unit.id).stream()
                .anyMatch(a -> a.reviewerId.equals(reviewerId));
        if (sameReviewer) {
            throw new ConflictException("审核员 " + reviewerId + " 已标注该样本；改判须由高级复核裁决");
        }

        ReviewAnnotation annotation = new ReviewAnnotation();
        annotation.samplingUnitId = unit.id;
        annotation.candidateSnapshotId = snap.id;
        annotation.candidateVersion = snap.candidateVersion;
        annotation.reviewerId = reviewerId;
        annotation.label = label;
        annotation.note = note;
        ReviewAnnotation saved = annotationRepo.save(annotation);

        List<ReviewAnnotation> all = annotationRepo.findBySamplingUnitIdOrderByCreatedAtAsc(unit.id);
        Set<ReviewAnnotation.Label> distinctLabels = new java.util.HashSet<>(all.stream()
                .map(a -> a.label).toList());
        if (distinctLabels.size() > 1) {
            // 审核员意见冲突：保留各自标注，转高级复核，原抽样框不动
            String detail = "审核员意见冲突: " + all.stream()
                    .map(a -> a.reviewerId + "=" + a.label).reduce((x, y) -> x + ", " + y).orElse("");
            snapshotService.onReviewerConflict(unit, detail);
        } else if (unit.reviewStatus == SamplingUnit.ReviewStatus.PENDING) {
            unit.reviewStatus = SamplingUnit.ReviewStatus.LABELED;
            unit.statusChangedAt = Instant.now();
            unitRepo.save(unit);
        }
        return saved;
    }

    /**
     * 高级复核裁决（ADMIN）：处理候选撤销 / 来源更正 / 意见冲突的样本。
     * 裁决只引用抽样时固化证据；裁决后样本关闭、快照转 REVIEW_CLOSED，结论冻结。
     */
    @Transactional
    public ReviewEscalation adjudicate(UUID unitId, ReviewEscalation.Outcome outcome,
                                       String note, String adminId) {
        SamplingUnit unit = unitRepo.findById(unitId)
                .orElseThrow(() -> new NotFoundException("抽样单元不存在: " + unitId));
        List<ReviewEscalation> escalations = escalationRepo.findBySamplingUnitIdOrderByCreatedAtAsc(unit.id);
        ReviewEscalation escalation = escalations.isEmpty() ? null : escalations.get(escalations.size() - 1);
        if (escalation == null) {
            throw new ConflictException("样本不在复核状态，无需裁决");
        }
        if (escalation.outcome != ReviewEscalation.Outcome.PENDING) {
            throw new ConflictException("该复核已有裁决结论，不能重复裁决");
        }
        escalation.outcome = outcome;
        escalation.adjudicatedBy = adminId;
        escalation.adjudicationNote = note;
        escalation.adjudicatedAt = Instant.now();
        escalationRepo.save(escalation);

        CandidateSnapshot snap = snapshotService.get(unit.candidateSnapshotId);
        snapshotService.closeReview(snap, adminId);
        return escalation;
    }

    /** 审核工作台：待标注与待复核样本（仅授权审核角色可调用，控制器层校验）。 */
    @Transactional(readOnly = true)
    public List<SamplingUnit> worklist(UUID batchId) {
        if (batchId != null) {
            return unitRepo.findByBatchIdOrderByCreatedAtAsc(batchId).stream()
                    .filter(u -> u.reviewStatus != SamplingUnit.ReviewStatus.CLOSED)
                    .toList();
        }
        return unitRepo.findAll().stream()
                .filter(u -> u.reviewStatus != SamplingUnit.ReviewStatus.CLOSED)
                .toList();
    }

    /** 样本审核详情：固化证据视图 + 全部标注 + 复核记录（审核角色专用，不对运营用户开放）。 */
    @Transactional(readOnly = true)
    public UnitReviewDetail detail(UUID unitId) {
        SamplingUnit unit = unitRepo.findById(unitId)
                .orElseThrow(() -> new NotFoundException("抽样单元不存在: " + unitId));
        CandidateSnapshot snap = snapshotRepo.findById(unit.candidateSnapshotId)
                .orElseThrow(() -> new NotFoundException("候选生成快照不存在: " + unit.candidateSnapshotId));
        List<ReviewAnnotation> annotations =
                annotationRepo.findBySamplingUnitIdOrderByCreatedAtAsc(unit.id);
        List<ReviewEscalation> escalations =
                escalationRepo.findBySamplingUnitIdOrderByCreatedAtAsc(unit.id);
        return new UnitReviewDetail(unit, snap, annotations, escalations);
    }

    /** 样本审核详情聚合。 */
    public record UnitReviewDetail(SamplingUnit unit, CandidateSnapshot snapshot,
                                   List<ReviewAnnotation> annotations,
                                   List<ReviewEscalation> escalations) {
    }
}
