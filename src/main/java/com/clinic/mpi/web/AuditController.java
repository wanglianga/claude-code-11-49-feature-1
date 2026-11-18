package com.clinic.mpi.web;

import com.clinic.mpi.domain.CandidateSnapshot;
import com.clinic.mpi.domain.ReviewAnnotation;
import com.clinic.mpi.domain.ReviewEscalation;
import com.clinic.mpi.domain.SamplingBatch;
import com.clinic.mpi.domain.SamplingUnit;
import com.clinic.mpi.domain.ThresholdRelease;
import com.clinic.mpi.service.AuditRoles;
import com.clinic.mpi.service.CandidateSnapshotService;
import com.clinic.mpi.service.ForbiddenException;
import com.clinic.mpi.service.QualityService;
import com.clinic.mpi.service.ReviewService;
import com.clinic.mpi.service.SamplingService;
import com.clinic.mpi.service.ThresholdReleaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 匹配质量校准与抽样审计接口。
 *
 * <p>角色通过请求头 {@code X-Operator-Role} 传递（缺省按最小权限 OPS 处理）：</p>
 * <ul>
 *   <li>ADMIN：抽样编排、阈值发布、高级复核裁决；</li>
 *   <li>REVIEWER：审核工作台、标注、样本补录；</li>
 *   <li>OPS（普通运营用户）：只能读取聚合质量报告，任何审核个案明细返回 403。</li>
 * </ul>
 */
@Tag(name = "匹配质量校准与抽样审计",
        description = "候选生成快照、随机/分层抽样、人工核验标注、加权质量报告、阈值发布；个案明细对运营用户隔离")
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final SamplingService samplingService;
    private final ReviewService reviewService;
    private final QualityService qualityService;
    private final CandidateSnapshotService snapshotService;
    private final ThresholdReleaseService thresholdReleaseService;

    public AuditController(SamplingService samplingService, ReviewService reviewService,
                           QualityService qualityService, CandidateSnapshotService snapshotService,
                           ThresholdReleaseService thresholdReleaseService) {
        this.samplingService = samplingService;
        this.reviewService = reviewService;
        this.qualityService = qualityService;
        this.snapshotService = snapshotService;
        this.thresholdReleaseService = thresholdReleaseService;
    }

    private static String role(String role) {
        return role == null || role.isBlank() ? AuditRoles.OPS : role.trim().toUpperCase();
    }

    private void requireReviewer(String role) {
        if (!AuditRoles.canReview(role)) {
            throw new ForbiddenException("当前角色 " + role + " 无权访问审核个案明细（仅 REVIEWER/ADMIN）");
        }
    }

    private void requireAdmin(String role) {
        if (!AuditRoles.isAdmin(role)) {
            throw new ForbiddenException("当前角色 " + role + " 无权执行该操作（仅 ADMIN）");
        }
    }

    // ---- 候选生成快照（审核角色可见，含当时证据与风险特征） ----

    @Operation(summary = "候选生成快照列表", description = "按来源院区/证据组合/分数区间/风险特征固化的候选快照；运营用户禁止访问（403）")
    @GetMapping("/candidate-snapshots")
    public List<CandidateSnapshot> snapshots(
            @RequestHeader(name = "X-Operator-Role", defaultValue = "OPS") String role) {
        requireReviewer(role(role));
        return snapshotService.list();
    }

    @Operation(summary = "候选生成快照详情")
    @GetMapping("/candidate-snapshots/{id}")
    public CandidateSnapshot snapshot(@PathVariable UUID id,
                                      @RequestHeader(name = "X-Operator-Role", defaultValue = "OPS") String role) {
        requireReviewer(role(role));
        return snapshotService.get(id);
    }

    // ---- 抽样编排 ----

    @Operation(summary = "创建抽样批次",
            description = "strategy=SRS 按分数随机抽样；STRATIFIED 按不确定度/高影响来源分层优先抽样，样本带 N_h/n_h 权重")
    @PostMapping("/batches")
    public SamplingBatch createBatch(@Valid @RequestBody Dtos.CreateBatchRequest req,
                                     @RequestHeader(name = "X-Operator-Role", defaultValue = "OPS") String role,
                                     @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        requireAdmin(role(role));
        SamplingBatch.Strategy strategy;
        try {
            strategy = SamplingBatch.Strategy.valueOf(req.strategy().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new com.clinic.mpi.service.BadRequestException(
                    "strategy 仅支持 SRS 或 STRATIFIED，实际: " + req.strategy());
        }
        return samplingService.createBatch(strategy, req.sampleSize(), req.seed(), req.note(), operator);
    }

    @Operation(summary = "抽样批次列表（聚合元数据，各角色可见）")
    @GetMapping("/batches")
    public List<SamplingBatch> batches() {
        return samplingService.listBatches();
    }

    @Operation(summary = "抽样批次详情")
    @GetMapping("/batches/{id}")
    public SamplingBatch batch(@PathVariable UUID id) {
        return samplingService.getBatch(id);
    }

    @Operation(summary = "批次样本列表（个案级，运营用户 403）")
    @GetMapping("/batches/{id}/units")
    public List<SamplingUnit> units(@PathVariable UUID id,
                                    @RequestHeader(name = "X-Operator-Role", defaultValue = "OPS") String role) {
        requireReviewer(role(role));
        return samplingService.unitsOf(id);
    }

    @Operation(summary = "审核样本补录",
            description = "把框内未抽中快照追加为补录样本（非概率样本，权重记 0，质量报告单列不入总体估计）")
    @PostMapping("/batches/{id}/supplement")
    public SamplingUnit supplement(@PathVariable UUID id,
                                   @Valid @RequestBody Dtos.SupplementRequest req,
                                   @RequestHeader(name = "X-Operator-Role", defaultValue = "OPS") String role,
                                   @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        requireReviewer(role(role));
        return samplingService.supplement(id, req.candidateSnapshotId(), operator);
    }

    @Operation(summary = "关闭抽样批次")
    @PostMapping("/batches/{id}/close")
    public SamplingBatch closeBatch(@PathVariable UUID id,
                                    @RequestHeader(name = "X-Operator-Role", defaultValue = "OPS") String role,
                                    @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        requireAdmin(role(role));
        return samplingService.closeBatch(id, operator);
    }

    // ---- 审核标注 ----

    @Operation(summary = "审核工作台", description = "待标注/待复核样本；运营用户 403")
    @GetMapping("/worklist")
    public List<SamplingUnit> worklist(@RequestParam(required = false) UUID batchId,
                                       @RequestHeader(name = "X-Operator-Role", defaultValue = "OPS") String role) {
        requireReviewer(role(role));
        return reviewService.worklist(batchId);
    }

    @Operation(summary = "样本审核详情",
            description = "含候选生成时固化证据、风险特征、全部标注与复核记录；只评价当时可见证据；运营用户 403")
    @GetMapping("/units/{id}")
    public ReviewService.UnitReviewDetail unitDetail(
            @PathVariable UUID id,
            @RequestHeader(name = "X-Operator-Role", defaultValue = "OPS") String role) {
        requireReviewer(role(role));
        return reviewService.detail(id);
    }

    @Operation(summary = "提交人工核验标注",
            description = "label=CONFIRMED/REJECTED/INCONCLUSIVE；只评价候选生成时可见证据，标注不可修改；意见冲突自动转高级复核")
    @PostMapping("/units/{id}/annotations")
    public ReviewAnnotation annotate(@PathVariable UUID id,
                                     @Valid @RequestBody Dtos.AnnotateRequest req,
                                     @RequestHeader(name = "X-Operator-Role", defaultValue = "OPS") String role,
                                     @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        requireReviewer(role(role));
        ReviewAnnotation.Label label;
        try {
            label = ReviewAnnotation.Label.valueOf(req.label().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new com.clinic.mpi.service.BadRequestException(
                    "label 仅支持 CONFIRMED/REJECTED/INCONCLUSIVE，实际: " + req.label());
        }
        return reviewService.annotate(id, label, req.note(), operator);
    }

    @Operation(summary = "高级复核裁决（ADMIN）",
            description = "候选撤销/来源更正/审核冲突样本的最终裁决；裁决只引用抽样时固化证据，结论冻结")
    @PostMapping("/units/{id}/adjudicate")
    public ReviewEscalation adjudicate(@PathVariable UUID id,
                                       @Valid @RequestBody Dtos.AdjudicateRequest req,
                                       @RequestHeader(name = "X-Operator-Role", defaultValue = "OPS") String role,
                                       @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        requireAdmin(role(role));
        ReviewEscalation.Outcome outcome;
        try {
            outcome = ReviewEscalation.Outcome.valueOf(req.outcome().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new com.clinic.mpi.service.BadRequestException(
                    "outcome 仅支持 CONFIRMED/REJECTED/INCONCLUSIVE，实际: " + req.outcome());
        }
        return reviewService.adjudicate(id, outcome, req.note(), operator);
    }

    // ---- 质量报告（聚合，各角色可见，无个案明细） ----

    @Operation(summary = "批次质量报告",
            description = "加权精确率/待定率/候选覆盖与置信区间，区分可验证表现、未审核范围、各来源不确定性；仅聚合量，不含个案明细")
    @GetMapping("/reports/{batchId}")
    public QualityService.QualityReport report(@PathVariable UUID batchId) {
        return qualityService.report(batchId);
    }

    // ---- 阈值发布（独立模块，只作用后续候选） ----

    @Operation(summary = "发布匹配阈值（ADMIN）",
            description = "新阈值只作用于发布之后生成的候选；存量候选与已发布业务快照不反写、不重算")
    @PostMapping("/thresholds")
    public ThresholdRelease publishThreshold(@Valid @RequestBody Dtos.PublishThresholdRequest req,
                                             @RequestHeader(name = "X-Operator-Role", defaultValue = "OPS") String role,
                                             @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        requireAdmin(role(role));
        return thresholdReleaseService.publish(req.matchThreshold(), req.rationale(), operator);
    }

    @Operation(summary = "阈值发布历史（各角色可见）")
    @GetMapping("/thresholds")
    public List<ThresholdRelease> thresholds() {
        return thresholdReleaseService.list();
    }
}
