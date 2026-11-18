package com.clinic.mpi.service;

import com.clinic.mpi.domain.CandidatePair;
import com.clinic.mpi.domain.CandidateSnapshot;
import com.clinic.mpi.domain.IdentityCorrection;
import com.clinic.mpi.domain.ReviewEscalation;
import com.clinic.mpi.domain.SamplingUnit;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.repo.CandidateSnapshotRepo;
import com.clinic.mpi.repo.IdentityCorrectionRepo;
import com.clinic.mpi.repo.ReviewEscalationRepo;
import com.clinic.mpi.repo.SamplingUnitRepo;
import com.clinic.mpi.repo.SourceIdentityRepo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 候选生成快照模块：候选对生成时固化"当时可见证据 + 风险特征 + 候选版本"，
 * 并在候选撤销、来源更正、审核冲突时保留原抽样框、流转复核状态。
 *
 * <p>校准与审计只读本表固化值，不回读日后变更的来源字段，
 * 因此日后新出现的证件号不可能倒灌成"当时模型正确"。</p>
 */
@Service
public class CandidateSnapshotService {

    /** 证据字段更正才触发复核状态流转（非证据字段不影响候选证据）。 */
    private static final Set<String> EVIDENCE_FIELDS =
            Set.of("idCardNo", "phone", "fullName", "gender", "birthDate");

    private final CandidateSnapshotRepo snapshotRepo;
    private final SamplingUnitRepo unitRepo;
    private final ReviewEscalationRepo escalationRepo;
    private final SourceIdentityRepo identityRepo;
    private final IdentityCorrectionRepo correctionRepo;
    private final ObjectMapper objectMapper;

    /** 高影响来源院区（新院区接入区），分层抽样优先；可经配置覆盖。 */
    private final Set<String> highImpactCampuses;

    public CandidateSnapshotService(CandidateSnapshotRepo snapshotRepo, SamplingUnitRepo unitRepo,
                                    ReviewEscalationRepo escalationRepo, SourceIdentityRepo identityRepo,
                                    IdentityCorrectionRepo correctionRepo, ObjectMapper objectMapper,
                                    @Value("${audit.high-impact-campuses:B,C}") String highImpactCampuses) {
        this.snapshotRepo = snapshotRepo;
        this.unitRepo = unitRepo;
        this.escalationRepo = escalationRepo;
        this.identityRepo = identityRepo;
        this.correctionRepo = correctionRepo;
        this.objectMapper = objectMapper;
        this.highImpactCampuses = Set.of(highImpactCampuses.split("\\s*,\\s*"));
    }

    /** 候选生成时固化快照。 */
    @Transactional
    public CandidateSnapshot record(CandidatePair pair, SourceIdentity a, SourceIdentity b,
                                    List<String> evidence, Integer thresholdVersion) {
        CandidateSnapshot snap = new CandidateSnapshot();
        snap.candidateId = pair.id;
        snap.candidateVersion = nextVersion(a.id, b.id);
        snap.identityAId = a.id;
        snap.identityBId = b.id;
        snap.sourceSystemA = a.sourceSystem;
        snap.sourceSystemB = b.sourceSystem;
        snap.campusA = campusOf(a.sourceSystem);
        snap.campusB = campusOf(b.sourceSystem);
        snap.score = pair.score;
        snap.scoreBand = scoreBand(pair.score);
        snap.evidenceKey = evidenceFingerprint(evidence);
        snap.evidenceJson = toJson(buildEvidenceView(a, b, evidence));
        Map<String, Object> risks = riskFeatures(a, b, evidence);
        snap.riskFeaturesJson = toJson(risks);
        snap.uncertaintyTier = uncertaintyTier(pair.score, risks);
        snap.highImpactSource = highImpactCampuses.contains(snap.campusA)
                || highImpactCampuses.contains(snap.campusB);
        snap.stratumKey = snap.campusA + "|" + snap.campusB + "|" + snap.evidenceKey
                + "|" + snap.scoreBand + "|" + snap.uncertaintyTier;
        snap.thresholdVersion = thresholdVersion;
        return snapshotRepo.save(snap);
    }

    /**
     * 候选被撤销（作废 SUPERSEDED / 人工拒绝 REJECTED / 已合并 MERGED）。
     * 已抽样的：保留抽样框，样本转 ESCALATED_WITHDRAWN 并登记复核；
     * 未抽样的：快照退出在框状态，不再进入后续抽样批次。
     */
    @Transactional
    public void onCandidateWithdrawn(UUID candidateId, String reason) {
        snapshotRepo.findByCandidateId(candidateId).ifPresent(snap -> {
            if (snap.frameStatus != CandidateSnapshot.FrameStatus.ACTIVE) {
                return; // 已流转的保留首次状态与原抽样框
            }
            snap.frameStatus = CandidateSnapshot.FrameStatus.CANDIDATE_WITHDRAWN;
            snap.frameStatusReason = reason;
            snap.statusChangedAt = Instant.now();
            snapshotRepo.save(snap);
            escalateSampled(snap, ReviewEscalation.ReasonType.CANDIDATE_WITHDRAWN, reason,
                    SamplingUnit.ReviewStatus.ESCALATED_WITHDRAWN);
        });
    }

    /**
     * 来源身份证据字段被更正。已抽样样本转 ESCALATED_CORRECTED，
     * 复核只允许使用快照中固化的当时证据；未抽样快照退出在框状态。
     */
    @Transactional
    public void onIdentityCorrected(UUID identityId, String field) {
        if (!EVIDENCE_FIELDS.contains(field)) {
            return;
        }
        List<IdentityCorrection> corrections = correctionRepo.findByIdentityIdOrderByCreatedAtDesc(identityId);
        String detail = corrections.isEmpty() ? field : field + " 更正（最近一次: " + corrections.get(0).note + "）";
        for (CandidateSnapshot snap : snapshotRepo.findAllByOrderByCreatedAtAsc()) {
            if (!identityId.equals(snap.identityAId) && !identityId.equals(snap.identityBId)) {
                continue;
            }
            if (snap.frameStatus != CandidateSnapshot.FrameStatus.ACTIVE) {
                continue;
            }
            snap.frameStatus = CandidateSnapshot.FrameStatus.SOURCE_CORRECTED;
            snap.frameStatusReason = "来源证据字段 " + detail + "，按抽样时固化证据复核";
            snap.statusChangedAt = Instant.now();
            snapshotRepo.save(snap);
            escalateSampled(snap, ReviewEscalation.ReasonType.SOURCE_CORRECTED,
                    snap.frameStatusReason, SamplingUnit.ReviewStatus.ESCALATED_CORRECTED);
        }
    }

    /** 审核员意见冲突：样本与快照转冲突复核。 */
    @Transactional
    public void onReviewerConflict(SamplingUnit unit, String detail) {
        CandidateSnapshot snap = snapshotRepo.findById(unit.candidateSnapshotId)
                .orElseThrow(() -> new NotFoundException("候选快照不存在: " + unit.candidateSnapshotId));
        unit.reviewStatus = SamplingUnit.ReviewStatus.ESCALATED_CONFLICT;
        unit.escalateReason = detail;
        unit.statusChangedAt = Instant.now();
        unitRepo.save(unit);

        if (snap.frameStatus == CandidateSnapshot.FrameStatus.ACTIVE) {
            snap.frameStatus = CandidateSnapshot.FrameStatus.CONFLICT_ESCALATED;
            snap.frameStatusReason = detail;
            snap.statusChangedAt = Instant.now();
            snapshotRepo.save(snap);
        }
        ReviewEscalation escalation = new ReviewEscalation();
        escalation.samplingUnitId = unit.id;
        escalation.reasonType = ReviewEscalation.ReasonType.REVIEWER_CONFLICT;
        escalation.detail = detail;
        escalationRepo.save(escalation);
    }

    /** 高级复核完成：冻结结论并关闭样本。 */
    @Transactional
    public void closeReview(CandidateSnapshot snap, String operatorId) {
        snap.frameStatus = CandidateSnapshot.FrameStatus.REVIEW_CLOSED;
        snap.statusChangedAt = Instant.now();
        snapshotRepo.save(snap);
        for (SamplingUnit unit : unitRepo.findByCandidateSnapshotId(snap.id)) {
            if (unit.reviewStatus != SamplingUnit.ReviewStatus.CLOSED) {
                unit.reviewStatus = SamplingUnit.ReviewStatus.CLOSED;
                unit.closedBy = operatorId;
                unit.statusChangedAt = Instant.now();
                unitRepo.save(unit);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<CandidateSnapshot> activeFrame() {
        return snapshotRepo.findByFrameStatusOrderByCreatedAtAsc(CandidateSnapshot.FrameStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public CandidateSnapshot get(UUID id) {
        return snapshotRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("候选生成快照不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<CandidateSnapshot> list() {
        return snapshotRepo.findAllByOrderByCreatedAtAsc();
    }

    // ---- 内部方法 ----

    private void escalateSampled(CandidateSnapshot snap, ReviewEscalation.ReasonType reasonType,
                                 String detail, SamplingUnit.ReviewStatus unitStatus) {
        for (SamplingUnit unit : unitRepo.findByCandidateSnapshotId(snap.id)) {
            if (unit.reviewStatus == SamplingUnit.ReviewStatus.CLOSED) {
                continue; // 已冻结结论不受后续事件影响
            }
            unit.reviewStatus = unitStatus;
            unit.escalateReason = detail;
            unit.statusChangedAt = Instant.now();
            unitRepo.save(unit);

            ReviewEscalation escalation = new ReviewEscalation();
            escalation.samplingUnitId = unit.id;
            escalation.reasonType = reasonType;
            escalation.detail = detail;
            escalationRepo.save(escalation);
        }
    }

    private int nextVersion(UUID a, UUID b) {
        return snapshotRepo.findByIdentityPair(a, b).stream()
                .mapToInt(s -> s.candidateVersion).max().orElse(0) + 1;
    }

    /** 来源系统 → 院区：取最后一个下划线后的段（REG_A → A，LAB_A → A）。 */
    public static String campusOf(String sourceSystem) {
        int idx = sourceSystem.lastIndexOf('_');
        return idx >= 0 ? sourceSystem.substring(idx + 1) : sourceSystem;
    }

    /** 分数区间：宽度 0.10，1.0 单独成档。 */
    public static String scoreBand(double score) {
        if (score >= 1.0) {
            return "BAND_10";
        }
        int lower = (int) Math.floor(score * 10);
        return String.format("BAND_%02d_%02d", lower, lower + 1);
    }

    private static String evidenceFingerprint(List<String> evidence) {
        return evidence.stream().sorted().reduce((x, y) -> x + "|" + y).orElse("无证据");
    }

    /**
     * 风险特征：
     * <ul>
     *   <li>nameChanged：任一侧姓名曾发生更正；</li>
     *   <li>familySharedPhone：手机号被不同姓名的身份共用（家庭共用联系方式）；</li>
     *   <li>highNameCollision：同名身份 ≥ 3 个（新院区同名高发）；</li>
     *   <li>crossCampus：两侧来自不同院区；</li>
     *   <li>idCardMissing：任一侧无证件号；</li>
     *   <li>weakEvidenceOnly：无证件号一致这一强证据。</li>
     * </ul>
     */
    private Map<String, Object> riskFeatures(SourceIdentity a, SourceIdentity b, List<String> evidence) {
        Map<String, Object> features = new LinkedHashMap<>();
        boolean nameChanged = !correctionRepo.findByIdentityIdOrderByCreatedAtDesc(a.id).stream()
                .filter(c -> "fullName".equals(c.field)).toList().isEmpty()
                || !correctionRepo.findByIdentityIdOrderByCreatedAtDesc(b.id).stream()
                .filter(c -> "fullName".equals(c.field)).toList().isEmpty();
        features.put("nameChanged", nameChanged);

        boolean familySharedPhone = false;
        if (a.phone != null && a.phone.equals(b.phone)) {
            // 共用手机号且出现 ≥2 个不同姓名：家庭共用联系方式（同名同号更可能是本人，不报此风险）
            long distinctNames = identityRepo.findByPhone(a.phone).stream()
                    .map(i -> i.fullName).filter(n -> n != null && !n.isBlank()).distinct().count();
            familySharedPhone = distinctNames >= 2;
        }
        features.put("familySharedPhone", familySharedPhone);

        long sameNameCount = 0;
        if (a.fullName != null && a.fullName.equals(b.fullName)) {
            sameNameCount = identityRepo.findByFullName(a.fullName).size();
        }
        features.put("highNameCollision", sameNameCount >= 3);
        features.put("sameNamePopulation", sameNameCount);

        features.put("crossCampus", !campusOf(a.sourceSystem).equals(campusOf(b.sourceSystem)));
        features.put("idCardMissing", a.idCardNo == null || a.idCardNo.isBlank()
                || b.idCardNo == null || b.idCardNo.isBlank());
        features.put("weakEvidenceOnly", !evidence.contains("证件号一致"));
        return features;
    }

    /** 不确定度层级：边界分或命中高风险特征 → HIGH，优先分层抽样。 */
    private String uncertaintyTier(double score, Map<String, Object> risks) {
        boolean highRisk = Boolean.TRUE.equals(risks.get("familySharedPhone"))
                || Boolean.TRUE.equals(risks.get("nameChanged"))
                || Boolean.TRUE.equals(risks.get("highNameCollision"));
        if (score < 0.60 || highRisk) {
            return "HIGH";
        }
        if (score < 0.85) {
            return "MEDIUM";
        }
        return "LOW";
    }

    /** 固化候选生成时两侧可见字段值（日后字段变更不影响此 JSON）。 */
    private Map<String, Object> buildEvidenceView(SourceIdentity a, SourceIdentity b,
                                                  List<String> evidence) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("hitEvidence", evidence);
        view.put("sideA", sideView(a));
        view.put("sideB", sideView(b));
        return view;
    }

    private Map<String, Object> sideView(SourceIdentity i) {
        Map<String, Object> side = new LinkedHashMap<>();
        side.put("sourceSystem", i.sourceSystem);
        side.put("sourcePatientId", i.sourcePatientId);
        side.put("fullName", i.fullName);
        side.put("phone", i.phone);
        side.put("idCardNo", i.idCardNo);
        side.put("gender", i.gender);
        side.put("birthDate", i.birthDate == null ? null : i.birthDate.toString());
        return side;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
