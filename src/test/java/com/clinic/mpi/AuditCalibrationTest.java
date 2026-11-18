package com.clinic.mpi;

import com.clinic.mpi.domain.CandidatePair;
import com.clinic.mpi.domain.CandidateSnapshot;
import com.clinic.mpi.domain.ReviewAnnotation;
import com.clinic.mpi.domain.ReviewEscalation;
import com.clinic.mpi.domain.SamplingBatch;
import com.clinic.mpi.domain.SamplingUnit;
import com.clinic.mpi.domain.ThresholdRelease;
import com.clinic.mpi.repo.CandidatePairRepo;
import com.clinic.mpi.repo.CandidateSnapshotRepo;
import com.clinic.mpi.repo.SamplingUnitRepo;
import com.clinic.mpi.service.CandidateSnapshotService;
import com.clinic.mpi.service.IdentityService;
import com.clinic.mpi.service.QualityService;
import com.clinic.mpi.service.ResolutionService;
import com.clinic.mpi.service.ReviewService;
import com.clinic.mpi.service.SamplingService;
import com.clinic.mpi.service.ThresholdReleaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 匹配质量校准与抽样审计验收测试（真实 PostgreSQL，ccdev_test 库，方法级回滚隔离）：
 * 新院区同名高发、家庭共用手机号、低分真实同人、审核样本补录、
 * 分层抽样权重、审核冲突复核、来源更正保留抽样框、阈值只作用后续候选、运营用户隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditCalibrationTest {

    @Autowired IdentityService identityService;
    @Autowired CandidateSnapshotService snapshotService;
    @Autowired SamplingService samplingService;
    @Autowired ReviewService reviewService;
    @Autowired QualityService qualityService;
    @Autowired ThresholdReleaseService thresholdReleaseService;
    @Autowired ResolutionService resolutionService;
    @Autowired CandidateSnapshotRepo snapshotRepo;
    @Autowired CandidatePairRepo candidateRepo;
    @Autowired SamplingUnitRepo unitRepo;
    @Autowired ObjectMapper objectMapper;
    @Autowired MockMvc mockMvc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String tag(String t) {
        return "AU-" + t + "-" + SEQ.incrementAndGet();
    }

    private String phone(String prefix) {
        return prefix + String.format("%08d", SEQ.incrementAndGet());
    }

    /** 接入一对同生日/同性别的身份并返回候选生成快照；证据不足（无候选）时返回 null。 */
    private CandidateSnapshot tryPair(String tag, String nameA, String nameB,
                                      String sharedPhone, String sharedIdCard, String campusB) {
        identityService.ingest("REG_T", tag + "-a", nameA, sharedPhone, sharedIdCard,
                "M", LocalDate.of(1990, 1, 1));
        var b = identityService.ingest("REG_" + campusB, tag + "-b", nameB, sharedPhone, sharedIdCard,
                "M", LocalDate.of(1990, 1, 1));
        List<CandidatePair> pairs = candidateRepo.findPendingInvolving(b.id);
        if (pairs.isEmpty()) {
            return null;
        }
        return snapshotRepo.findByCandidateId(pairs.get(0).id).orElseThrow();
    }

    /** 构造含三类不确定度分层的候选框，返回本方法生成的快照（全局框可能还含其他快照）。 */
    private List<CandidateSnapshot> buildFrame(int highRisk, int medium, int low) {
        List<CandidateSnapshot> snaps = new ArrayList<>();
        // HIGH：家庭共用手机号（同号不同名、姓名相似，0.40 边界分）
        for (int i = 0; i < highRisk; i++) {
            String t = tag("H");
            String shared = phone("188");
            identityService.ingest("REG_T", t + "-a", "家长" + i, shared,
                    null, "M", LocalDate.of(1960, 1, 1));
            var son = identityService.ingest("REG_T", t + "-b", "家长" + i + "子", shared,
                    null, "M", LocalDate.of(1988, 2, 2));
            var pair = candidateRepo.findPendingInvolving(son.id).get(0);
            snaps.add(snapshotRepo.findByCandidateId(pair.id).orElseThrow());
        }
        // MEDIUM：手机+姓名+生日+性别，0.60，无证件号
        for (int i = 0; i < medium; i++) {
            snaps.add(tryPair(tag("M"), "中等证据" + i, "中等证据" + i, phone("177"), null, "T"));
        }
        // LOW：证件号一致强证据，1.0
        for (int i = 0; i < low; i++) {
            snaps.add(tryPair(tag("L"), "强证据" + i, "强证据" + i, phone("166"), "ID-CARD-" + i, "T"));
        }
        return snaps;
    }

    private void annotateAll(SamplingBatch batch, ReviewAnnotation.Label label) {
        for (SamplingUnit unit : samplingService.unitsOf(batch.id)) {
            if (!unit.supplemental && unit.reviewStatus == SamplingUnit.ReviewStatus.PENDING) {
                reviewService.annotate(unit.id, label, "测试标注", "rv" + unit.id.toString().charAt(0));
            }
        }
    }

    @Test
    void snapshotFreezesEvidenceAndRiskFeaturesAtGeneration() throws Exception {
        String t = tag("risk");
        String shared = phone("155");
        identityService.ingest("REG_T", t + "-a", "李家长", shared, null,
                "M", LocalDate.of(1960, 1, 1));
        var son = identityService.ingest("REG_C", t + "-b", "李家长子", shared, null,
                "M", LocalDate.of(1988, 2, 2));
        CandidatePair pair = candidateRepo.findPendingInvolving(son.id).get(0);
        CandidateSnapshot snap = snapshotRepo.findByCandidateId(pair.id).orElseThrow();

        Map<?, ?> risks = objectMapper.readValue(snap.riskFeaturesJson, Map.class);
        assertThat(risks.get("familySharedPhone")).isEqualTo(true);
        assertThat(risks.get("crossCampus")).isEqualTo(true);
        assertThat(risks.get("weakEvidenceOnly")).isEqualTo(true);
        assertThat(snap.uncertaintyTier).isEqualTo("HIGH");
        assertThat(snap.highImpactSource).isTrue(); // 院区 C 属高影响新院区
        assertThat(snap.candidateVersion).isEqualTo(1);
        assertThat(snap.thresholdVersion).isNull(); // 基线阈值
        // 当时证据已固化（双侧无证件号）
        Map<?, ?> evidence = objectMapper.readValue(snap.evidenceJson, Map.class);
        assertThat(((Map<?, ?>) evidence.get("sideB")).get("idCardNo")).isNull();
    }

    @Test
    void nameChangeIsFlaggedAsRiskOnRegeneratedSnapshot() throws Exception {
        String t = tag("rename");
        String shared = phone("152");
        var a = identityService.ingest("REG_T", t + "-a", "曾用名", shared,
                null, "M", LocalDate.of(1991, 2, 2));
        identityService.ingest("REG_C", t + "-b", "曾用名", shared,
                null, "M", LocalDate.of(1991, 2, 2));
        CandidatePair first = candidateRepo.findPendingInvolving(a.id).get(0);
        CandidateSnapshot v1 = snapshotRepo.findByCandidateId(first.id).orElseThrow();
        assertThat(v1.candidateVersion).isEqualTo(1);

        // 姓名更正：旧候选作废，按新证据生成 v2 候选（同手机+生日+性别，0.40 边界分）
        identityService.correct(a.id, "fullName", "现用名", "患者改名后更正", "op1");
        CandidateSnapshot v2 = snapshotRepo.findByIdentityPair(v1.identityAId, v1.identityBId).stream()
                .reduce((x, y) -> y).orElseThrow();
        assertThat(v2.candidateVersion).isEqualTo(2);
        Map<?, ?> risks = objectMapper.readValue(v2.riskFeaturesJson, Map.class);
        assertThat(risks.get("nameChanged")).isEqualTo(true);
        assertThat(v2.uncertaintyTier).isEqualTo("HIGH");
        // 旧版本快照仍保留且不带事后标记
        Map<?, ?> oldRisks = objectMapper.readValue(v1.riskFeaturesJson, Map.class);
        assertThat(oldRisks.get("nameChanged")).isEqualTo(false);
    }

    @Test
    void highNameCollisionFlaggedInNewCampus() throws Exception {
        String base = tag("collision");
        String shared = phone("150");
        identityService.ingest("REG_T", base + "-1", "同名患者", shared,
                "ID-X1", "M", LocalDate.of(1992, 1, 1));
        identityService.ingest("REG_C", base + "-2", "同名患者", shared,
                "ID-X2", "M", LocalDate.of(1992, 1, 1));
        var c = identityService.ingest("REG_C", base + "-3", "同名患者", shared,
                "ID-X3", "M", LocalDate.of(1992, 1, 1));
        CandidateSnapshot snap = snapshotRepo.findByCandidateId(
                candidateRepo.findPendingInvolving(c.id).get(0).id).orElseThrow();
        Map<?, ?> risks = objectMapper.readValue(snap.riskFeaturesJson, Map.class);
        assertThat(risks.get("highNameCollision")).isEqualTo(true);
        assertThat(snap.uncertaintyTier).isEqualTo("HIGH");
    }

    @Test
    void srsBatchProducesEqualWeightsAndWeightedReport() {
        buildFrame(4, 4, 4);
        int n = 6;
        SamplingBatch batch = samplingService.createBatch(
                SamplingBatch.Strategy.SRS, n, 42L, "SRS 验收", "admin1");

        List<SamplingUnit> units = samplingService.unitsOf(batch.id);
        assertThat(units).hasSize(n);
        double expectedWeight = (double) batch.frameSize / n;
        assertThat(units).allSatisfy(u -> {
            assertThat(u.inclusionWeight).isEqualTo(expectedWeight);
            assertThat(u.supplemental).isFalse();
        });

        // 前 2 个拒绝、其余确认（等权下加权精确率 = (n-2)/n）
        List<SamplingUnit> pending = units.stream()
                .filter(u -> u.reviewStatus == SamplingUnit.ReviewStatus.PENDING).toList();
        reviewService.annotate(pending.get(0).id, ReviewAnnotation.Label.REJECTED, "家庭号", "rv1");
        reviewService.annotate(pending.get(1).id, ReviewAnnotation.Label.REJECTED, "同名异人", "rv2");
        for (int i = 2; i < pending.size(); i++) {
            reviewService.annotate(pending.get(i).id, ReviewAnnotation.Label.CONFIRMED, "同人", "rv" + i);
        }

        QualityService.QualityReport report = qualityService.report(batch.id);
        assertThat(report.overall().precision().point()).isEqualTo((double) (n - 2) / n);
        assertThat(report.overall().precision().ciLow()).isLessThan(report.overall().precision().point());
        assertThat(report.overall().precision().ciHigh()).isGreaterThan(report.overall().precision().point());
        assertThat(report.overall().inconclusiveRate().point()).isEqualTo(0.0);
        assertThat(report.overall().candidateCoverage().point()).isEqualTo(1.0); // 已审加权质量=N
        assertThat(report.overall().unreviewed().count()).isZero();
        assertThat(report.byCampus()).isNotEmpty();
        assertThat(report.byScoreBand()).isNotEmpty();
        assertThat(report.byEvidenceCombination()).isNotEmpty();
    }

    @Test
    void stratifiedBatchUsesStratumWeightsNotRawCounts() {
        buildFrame(6, 4, 2);
        int n = 9;
        SamplingBatch batch = samplingService.createBatch(
                SamplingBatch.Strategy.STRATIFIED, n, 7L, "分层验收", "admin1");

        List<SamplingUnit> units = samplingService.unitsOf(batch.id);
        assertThat(units).hasSize(n);

        // 不变量 1：每个样本权重 = 层框容量/层样本量 N_h/n_h
        Map<String, List<SamplingUnit>> byStratum = new java.util.LinkedHashMap<>();
        units.forEach(u -> byStratum.computeIfAbsent(u.stratumKey, k -> new ArrayList<>()).add(u));
        byStratum.forEach((key, list) -> {
            int nh = list.size();
            int nhFrame = list.get(0).stratumFrameCount;
            assertThat(nhFrame).isGreaterThanOrEqualTo(nh);
            assertThat(list).allSatisfy(u -> {
                assertThat(u.stratumSampleCount).isEqualTo(nh);
                assertThat(u.inclusionWeight).isEqualTo((double) nhFrame / nh);
            });
        });
        // 不变量 2：加权总量恒等 Σ_h n_h·(N_h/n_h) = 被代表层的框容量之和
        // （target 小于分层数时部分层零配额，未代表层计入未审核范围，不允许凭空赋权）
        double weightedTotal = units.stream().mapToDouble(u -> u.inclusionWeight).sum();
        double representedFrameMass = byStratum.values().stream()
                .mapToDouble(list -> list.get(0).stratumFrameCount).sum();
        assertThat(weightedTotal).isEqualTo(representedFrameMass, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(weightedTotal).isLessThanOrEqualTo((double) batch.frameSize);

        // 普查批次（n=N）：所有分层均被代表，加权总量严格等于抽样框 N
        SamplingBatch census = samplingService.createBatch(
                SamplingBatch.Strategy.STRATIFIED, batch.frameSize, 8L, "分层普查", "admin1");
        List<SamplingUnit> censusUnits = samplingService.unitsOf(census.id);
        assertThat(censusUnits).hasSize(batch.frameSize);
        assertThat(censusUnits.stream().mapToDouble(u -> u.inclusionWeight).sum())
                .isEqualTo((double) batch.frameSize, org.assertj.core.data.Offset.offset(1e-9));

        // 全部确认：刻意过采样不会把比例带偏，加权精确率为 1
        annotateAll(batch, ReviewAnnotation.Label.CONFIRMED);
        QualityService.QualityReport report = qualityService.report(batch.id);
        assertThat(report.overall().precision().point()).isEqualTo(1.0);
        assertThat(report.strategy()).isEqualTo("STRATIFIED");
        assertThat(report.overall().precision().method()).contains("分层");
        assertThat(report.byUncertainty()).isNotEmpty();
    }

    @Test
    void conflictingReviewersEscalateAndAdjudicationClosesUnit() throws Exception {
        buildFrame(2, 0, 0);
        SamplingBatch batch = samplingService.createBatch(
                SamplingBatch.Strategy.SRS, 2, 11L, "冲突验收", "admin1");
        SamplingUnit unit = samplingService.unitsOf(batch.id).get(0);

        reviewService.annotate(unit.id, ReviewAnnotation.Label.CONFIRMED, "审核员甲确认", "rv_a");
        reviewService.annotate(unit.id, ReviewAnnotation.Label.REJECTED, "审核员乙拒绝", "rv_b");

        SamplingUnit after = unitRepo.findById(unit.id).orElseThrow();
        assertThat(after.reviewStatus).isEqualTo(SamplingUnit.ReviewStatus.ESCALATED_CONFLICT);
        assertThat(reviewService.detail(unit.id).annotations()).hasSize(2); // 两条标注都保留

        // 运营/审核角色不能通过接口裁决（403），仅 ADMIN 可裁决
        mockMvc.perform(post("/api/audit/units/" + unit.id + "/adjudicate")
                        .header("X-Operator-Role", "REVIEWER")
                        .contentType("application/json")
                        .content("{\"outcome\":\"REJECTED\",\"note\":\"越权裁决\"}"))
                .andExpect(status().isForbidden());

        reviewService.adjudicate(unit.id, ReviewEscalation.Outcome.REJECTED, "按抽样时证据裁决拒绝", "admin1");
        assertThat(unitRepo.findById(unit.id).orElseThrow().reviewStatus)
                .isEqualTo(SamplingUnit.ReviewStatus.CLOSED);

        QualityService.QualityReport report = qualityService.report(batch.id);
        SamplingUnit closed = unitRepo.findById(unit.id).orElseThrow();
        assertThat(report.overall().weightedRejected()).isEqualTo(closed.inclusionWeight);
    }

    @Test
    void sourceCorrectionKeepsSamplingFrameAndFreezesOldEvidence() throws Exception {
        String t = tag("correct");
        // 无证件号的低分真实同人（0.50：姓名相似+手机+生日+性别）
        identityService.ingest("REG_T", t + "-a", "王芳", phone("147"), null,
                "F", LocalDate.of(1980, 6, 6));
        var b = identityService.ingest("REG_C", t + "-b", "王芳芳",
                identityService.list().stream()
                        .filter(i -> i.sourcePatientId.equals(t + "-a")).findFirst().orElseThrow().phone,
                null, "F", LocalDate.of(1980, 6, 6));
        CandidatePair oldPair = candidateRepo.findPendingInvolving(b.id).get(0);
        CandidateSnapshot oldSnap = snapshotRepo.findByCandidateId(oldPair.id).orElseThrow();
        assertThat(oldSnap.score).isBetween(0.49, 0.51);

        // 全框抽样（框内含启动播种快照），确保目标低分快照入样且权重为 1
        int frameN = snapshotService.activeFrame().size();
        SamplingBatch batch = samplingService.createBatch(
                SamplingBatch.Strategy.SRS, frameN, 23L, "更正前抽样", "admin1");
        SamplingUnit unit = samplingService.unitsOf(batch.id).stream()
                .filter(u -> u.candidateSnapshotId.equals(oldSnap.id)).findFirst().orElseThrow();

        // 抽样后来源补录证件号（日后新证据）
        identityService.correct(b.id, "idCardNo", "ID-NEW-" + t, "事后补录证件号", "op1");

        // 原抽样框保留：样本转 ESCALATED_CORRECTED，快照不删除
        assertThat(unitRepo.findById(unit.id).orElseThrow().reviewStatus)
                .isEqualTo(SamplingUnit.ReviewStatus.ESCALATED_CORRECTED);
        CandidateSnapshot kept = snapshotRepo.findById(oldSnap.id).orElseThrow();
        assertThat(kept.frameStatus).isEqualTo(CandidateSnapshot.FrameStatus.SOURCE_CORRECTED);
        // 固化证据不被日后证件号倒灌
        Map<?, ?> evidence = objectMapper.readValue(kept.evidenceJson, Map.class);
        assertThat(((Map<?, ?>) evidence.get("sideB")).get("idCardNo")).isNull();

        // 新证据生成新版本候选快照（版本号递增）
        int maxVersion = snapshotRepo.findByIdentityPair(oldSnap.identityAId, oldSnap.identityBId).stream()
                .mapToInt(s -> s.candidateVersion).max().orElseThrow();
        assertThat(maxVersion).isGreaterThan(oldSnap.candidateVersion);

        // 按抽样时证据裁决为确认（低分真实同人），不看新证件号
        reviewService.adjudicate(unit.id, ReviewEscalation.Outcome.CONFIRMED,
                "仅依据抽样时姓名相似+手机+生日+性别，确认同一就诊人", "admin1");
        assertThat(unitRepo.findById(unit.id).orElseThrow().reviewStatus)
                .isEqualTo(SamplingUnit.ReviewStatus.CLOSED);
    }

    @Test
    void candidateWithdrawnAfterSamplingEscalatesAndFrameSurvives() {
        buildFrame(3, 0, 0);
        SamplingBatch batch = samplingService.createBatch(
                SamplingBatch.Strategy.SRS, 2, 31L, "撤销验收", "admin1");
        SamplingUnit unit = samplingService.unitsOf(batch.id).get(0);
        UUID snapshotId = unit.candidateSnapshotId;

        // 候选在抽样后被人工拒绝（端到端事件链：ResolutionService → 候选快照模块）
        resolutionService.rejectCandidate(unit.candidateId, "人工拒绝撤销", "op1");

        assertThat(unitRepo.findById(unit.id).orElseThrow().reviewStatus)
                .isEqualTo(SamplingUnit.ReviewStatus.ESCALATED_WITHDRAWN);
        assertThat(snapshotRepo.findById(snapshotId)).isPresent();
        assertThat(snapshotRepo.findById(snapshotId).orElseThrow().frameStatus)
                .isEqualTo(CandidateSnapshot.FrameStatus.CANDIDATE_WITHDRAWN);
    }

    @Test
    void supplementalSamplesAreReportedSeparatelyAndExcludedFromWeighting() {
        buildFrame(4, 0, 0);
        SamplingBatch batch = samplingService.createBatch(
                SamplingBatch.Strategy.SRS, 2, 5L, "补录验收", "admin1");
        List<UUID> sampled = samplingService.unitsOf(batch.id).stream()
                .map(u -> u.candidateSnapshotId).toList();
        CandidateSnapshot outside = snapshotService.activeFrame().stream()
                .filter(s -> !sampled.contains(s.id)).findFirst().orElseThrow();

        SamplingUnit extra = samplingService.supplement(batch.id, outside.id, "rv1");
        assertThat(extra.supplemental).isTrue();
        assertThat(extra.inclusionWeight).isZero();

        annotateAll(batch, ReviewAnnotation.Label.CONFIRMED);
        reviewService.annotate(extra.id, ReviewAnnotation.Label.REJECTED, "补录个案", "rv9");

        QualityService.QualityReport report = qualityService.report(batch.id);
        assertThat(report.supplementalSampleCount()).isEqualTo(1);
        assertThat(report.supplemental().rejected()).isEqualTo(1);
        // 补录拒绝不进入总体加权：概率样本全确认，精确率仍为 1
        assertThat(report.overall().precision().point()).isEqualTo(1.0);
    }

    @Test
    void publishedThresholdOnlyAffectsFutureCandidatesAndSnapshots() {
        List<CandidateSnapshot> before = snapshotService.activeFrame();
        assertThat(before).allSatisfy(s -> assertThat(s.thresholdVersion).isNull());
        long candidateCountBefore = candidateRepo.count();

        ThresholdRelease high = thresholdReleaseService.publish(
                0.90, "校准后提高阈值，减少同名误配", "admin1");
        assertThat(high.versionNo).isGreaterThanOrEqualTo(1);

        // 0.60 分组合在新阈值下不再生成候选
        CandidateSnapshot suppressed = tryPair(tag("th"), "阈值测试", "阈值测试",
                phone("144"), null, "T"); // 0.60
        assertThat(suppressed).isNull();
        assertThat(candidateRepo.count()).isEqualTo(candidateCountBefore);

        // 调低阈值后同样证据组合恢复生成，且快照记录阈值版本
        thresholdReleaseService.publish(0.30, "回退阈值验证只作用后续候选", "admin1");
        CandidateSnapshot accepted = tryPair(tag("th2"), "阈值测试B", "阈值测试B",
                phone("143"), null, "T");
        assertThat(accepted).isNotNull();
        assertThat(accepted.score).isBetween(0.59, 0.61);
        assertThat(accepted.thresholdVersion).isNotNull();
        // 存量快照不被反写
        assertThat(before).allSatisfy(s ->
                assertThat(snapshotRepo.findById(s.id).orElseThrow().thresholdVersion).isNull());
    }

    @Test
    void opsRoleCannotReadCaseDetailsButCanReadAggregateReport() throws Exception {
        buildFrame(2, 0, 0);
        SamplingBatch batch = samplingService.createBatch(
                SamplingBatch.Strategy.SRS, 1, 77L, "权限验收", "admin1");
        annotateAll(batch, ReviewAnnotation.Label.CONFIRMED);

        // 普通运营用户：个案明细 403
        mockMvc.perform(get("/api/audit/candidate-snapshots")
                        .header("X-Operator-Role", "OPS"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit/worklist")) // 缺省角色即 OPS
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit/batches/" + batch.id + "/units"))
                .andExpect(status().isForbidden());

        // 审核角色可访问工作台
        mockMvc.perform(get("/api/audit/worklist")
                        .header("X-Operator-Role", "REVIEWER"))
                .andExpect(status().isOk());

        // 运营用户可读聚合报告，且报告不含任何候选/身份个案标识
        mockMvc.perform(get("/api/audit/reports/" + batch.id)
                        .header("X-Operator-Role", "OPS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall.precision.point").value(1.0))
                .andExpect(jsonPath("$.candidateId").doesNotExist())
                .andExpect(jsonPath("$.identityAId").doesNotExist())
                .andExpect(jsonPath("$.evidenceJson").doesNotExist());

        // 运营用户不能编排抽样或发布阈值
        mockMvc.perform(post("/api/audit/batches")
                        .header("X-Operator-Role", "OPS")
                        .contentType("application/json")
                        .content("{\"strategy\":\"SRS\",\"sampleSize\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/audit/thresholds")
                        .header("X-Operator-Role", "OPS")
                        .contentType("application/json")
                        .content("{\"matchThreshold\":0.5}"))
                .andExpect(status().isForbidden());
    }
}
