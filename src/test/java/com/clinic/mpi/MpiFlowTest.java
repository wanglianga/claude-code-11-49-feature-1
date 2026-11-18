package com.clinic.mpi;

import com.clinic.mpi.domain.Appointment;
import com.clinic.mpi.domain.CandidatePair;
import com.clinic.mpi.domain.ImpactItem;
import com.clinic.mpi.domain.LabReport;
import com.clinic.mpi.domain.Resolution;
import com.clinic.mpi.domain.Settlement;
import com.clinic.mpi.domain.Snapshot;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.repo.CandidatePairRepo;
import com.clinic.mpi.repo.ImpactItemRepo;
import com.clinic.mpi.repo.SnapshotRepo;
import com.clinic.mpi.service.BizService;
import com.clinic.mpi.service.ConflictException;
import com.clinic.mpi.service.GraphService;
import com.clinic.mpi.service.IdentityService;
import com.clinic.mpi.service.ImpactService;
import com.clinic.mpi.service.ResolutionService;
import com.clinic.mpi.service.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验收场景集成测试（真实 PostgreSQL，ccdev_test 库）：
 * 误并拆分、并发决议、来源证件更正、关账后调整、快照不回写。
 */
@SpringBootTest
class MpiFlowTest {

    @Autowired IdentityService identityService;
    @Autowired ResolutionService resolutionService;
    @Autowired SnapshotService snapshotService;
    @Autowired ImpactService impactService;
    @Autowired BizService bizService;
    @Autowired GraphService graphService;
    @Autowired CandidatePairRepo candidateRepo;
    @Autowired ImpactItemRepo impactRepo;
    @Autowired SnapshotRepo snapshotRepo;

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 生成测试内唯一的来源就诊人编号，避免与播种数据互相干扰。 */
    private String uid(String tag) {
        return "T-" + tag + "-" + SEQ.incrementAndGet();
    }

    private SourceIdentity ingest(String patientId, String name, String phone, String idCard) {
        return identityService.ingest("REG_T", patientId, name, phone, idCard, "M",
                LocalDate.of(1990, 1, 1));
    }

    private CandidatePair pendingPairOf(UUID identityId) {
        List<CandidatePair> pending = candidateRepo.findPendingInvolving(identityId);
        assertThat(pending).as("应存在待处理候选对").isNotEmpty();
        return pending.get(0);
    }

    @Test
    void ingestCreatesSingletonMasterAndScoredCandidate() {
        String tag = uid("A");
        SourceIdentity a = ingest(tag + "-a", "测试甲", "13100000001", "ID-" + tag);
        SourceIdentity b = ingest(tag + "-b", "测试甲", "13100000001", "ID-" + tag);

        // 初始各自为单例主体
        assertThat(graphService.currentMasterOf(a.id))
                .isNotEqualTo(graphService.currentMasterOf(b.id));
        // 评分 1.0 的候选对（证据全命中）
        CandidatePair pair = pendingPairOf(a.id);
        assertThat(pair.score).isEqualTo(1.0);
        assertThat(pair.evidence).contains("证件号一致", "手机号一致", "姓名一致");
    }

    @Test
    void mergeBumpsVersionAndOldVersionStillResolvable() {
        String tag = uid("B");
        SourceIdentity a = ingest(tag + "-a", "测试乙", "13100000002", "ID-" + tag);
        SourceIdentity b = ingest(tag + "-b", "测试乙", "13100000002", "ID-" + tag);
        UUID masterA0 = graphService.currentMasterOf(a.id);
        UUID masterB0 = graphService.currentMasterOf(b.id);

        long v = graphService.currentVersion();
        Resolution r = resolutionService.merge(pendingPairOf(a.id).id, null, v, "重复建档合并", "op_test");

        assertThat(r.graphVersion).isEqualTo(v + 1);
        UUID merged = graphService.currentMasterOf(a.id);
        assertThat(graphService.currentMasterOf(b.id)).isEqualTo(merged);
        // 时态关系图：旧版本时点解析仍指向各自单例主体（历史可复现）
        assertThat(graphService.masterOfAt(a.id, v)).isEqualTo(masterA0);
        assertThat(graphService.masterOfAt(b.id, v)).isEqualTo(masterB0);
        assertThat(graphService.masterOfAt(a.id, v + 1)).isEqualTo(merged);
    }

    @Test
    void staleGraphVersionIsRejectedAndRecheckSucceeds() {
        String tag = uid("C");
        SourceIdentity a = ingest(tag + "-a", "测试丙", "13100000003", "ID-" + tag + "a");
        SourceIdentity b = ingest(tag + "-b", "测试丙", "13100000003", "ID-" + tag + "a");
        SourceIdentity c = ingest(tag + "-c", "测试丁", "13100000004", "ID-" + tag + "b");
        SourceIdentity d = ingest(tag + "-d", "测试丁", "13100000004", "ID-" + tag + "b");
        CandidatePair pair1 = pendingPairOf(a.id);
        CandidatePair pair2 = pendingPairOf(c.id);

        long v = graphService.currentVersion();
        resolutionService.merge(pair1.id, null, v, "先提交者", "op_1");

        // 后提交者持过期版本：409，且响应携带当前版本
        assertThatThrownBy(() -> resolutionService.merge(pair2.id, null, v, "后提交者", "op_2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("重新检查");

        // 重新检查当前图谱后提交成功
        long v2 = graphService.currentVersion();
        Resolution r = resolutionService.merge(pair2.id, null, v2, "重新检查后提交", "op_2");
        assertThat(r.graphVersion).isEqualTo(v2 + 1);
        assertThat(graphService.currentMasterOf(c.id))
                .isEqualTo(graphService.currentMasterOf(d.id));
    }

    @Test
    void concurrentResolutionsExactlyOneWins() throws Exception {
        String tag = uid("D");
        SourceIdentity a = ingest(tag + "-a", "测试戊", "13100000005", "ID-" + tag + "a");
        SourceIdentity b = ingest(tag + "-b", "测试戊", "13100000005", "ID-" + tag + "a");
        SourceIdentity c = ingest(tag + "-c", "测试己", "13100000006", "ID-" + tag + "b");
        SourceIdentity d = ingest(tag + "-d", "测试己", "13100000006", "ID-" + tag + "b");
        CandidatePair pair1 = pendingPairOf(a.id);
        CandidatePair pair2 = pendingPairOf(c.id);

        long v = graphService.currentVersion();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Object> result1 = new AtomicReference<>();
        AtomicReference<Object> result2 = new AtomicReference<>();

        Runnable task1 = () -> awaitAndRun(ready, go,
                () -> resolutionService.merge(pair1.id, null, v, "并发1", "op_1"), result1);
        Runnable task2 = () -> awaitAndRun(ready, go,
                () -> resolutionService.merge(pair2.id, null, v, "并发2", "op_2"), result2);
        new Thread(task1).start();
        new Thread(task2).start();
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        // 等待两个线程结束
        for (int i = 0; i < 100 && (result1.get() == null || result2.get() == null); i++) {
            Thread.sleep(100);
        }

        Object r1 = result1.get();
        Object r2 = result2.get();
        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();
        // 恰好一个成功、一个版本冲突：不会形成互斥的并行合并
        boolean r1Win = r1 instanceof Resolution && r2 instanceof ConflictException;
        boolean r2Win = r2 instanceof Resolution && r1 instanceof ConflictException;
        assertThat(r1Win || r2Win)
                .as("并发决议应恰好一个成功一个 409，实际: %s / %s",
                        r1.getClass().getSimpleName(), r2.getClass().getSimpleName())
                .isTrue();
    }

    private void awaitAndRun(CountDownLatch ready, CountDownLatch go,
                             java.util.function.Supplier<Object> action,
                             AtomicReference<Object> result) {
        try {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            result.compareAndSet(null, action.get());
        } catch (Exception e) {
            result.compareAndSet(null, e);
        }
    }

    @Test
    void splitAfterMistakenMergeRestoresIdentity() {
        String tag = uid("E");
        SourceIdentity a = ingest(tag + "-a", "测试庚", "13100000007", "ID-" + tag + "a");
        SourceIdentity b = ingest(tag + "-b", "测试庚", "13100000007", "ID-" + tag + "a");
        long v = graphService.currentVersion();
        Resolution merge = resolutionService.merge(pendingPairOf(a.id).id, null, v, "误并", "op_test");
        UUID merged = graphService.currentMasterOf(a.id);

        // 拆分：b 移出组成新主体
        Resolution split = resolutionService.split(merged, List.of(b.id),
                merge.graphVersion, "复核发现误并", "op_test");
        UUID bMaster = graphService.currentMasterOf(b.id);
        assertThat(bMaster).isNotEqualTo(merged);
        assertThat(graphService.currentMasterOf(a.id)).isEqualTo(merged);
        // 历史版本仍可复现误并期间的归属
        assertThat(graphService.masterOfAt(b.id, merge.graphVersion)).isEqualTo(merged);
        assertThat(graphService.masterOfAt(b.id, split.graphVersion)).isEqualTo(bMaster);
    }

    @Test
    void sourceCorrectionDoesNotRewritePublishedSnapshot() {
        String tag = uid("F");
        SourceIdentity a = ingest(tag + "-a", "测试辛", "13100000008", "ID-CORRECT");
        SourceIdentity b = identityService.ingest("LAB_T", tag + "-b", "测试辛",
                "13100000008", "ID-TYPO", "M", LocalDate.of(1990, 1, 1));

        Snapshot published = snapshotService.publish(
                Snapshot.BusinessType.ANALYTICS, "测试快照-" + tag, "op_test");
        UUID masterBefore = snapshotService.resolve(
                published.id, b.sourceSystem, b.sourcePatientId).masterId;

        // 来源系统更正证件号
        identityService.correct(b.id, "idCardNo", "ID-CORRECT", "末位录错更正", "op_test");

        // 已发布快照不回写
        assertThat(snapshotService.resolve(published.id, b.sourceSystem, b.sourcePatientId).masterId)
                .isEqualTo(masterBefore);
        // 更正后生成 1.0 分新候选对
        CandidatePair pair = pendingPairOf(a.id);
        assertThat(pair.score).isEqualTo(1.0);

        // 决议合并后：影响分析生成候选快照，未确认前下游不可读
        long v = graphService.currentVersion();
        Resolution r = resolutionService.merge(pair.id, null, v, "更正后确认同人", "op_test");
        List<ImpactItem> impacts = impactRepo.findByResolutionId(r.id);
        assertThat(impacts).isNotEmpty();
        UUID candidateSnapshotId = impacts.get(0).snapshotId;
        Snapshot candidate = snapshotRepo.findById(candidateSnapshotId).orElseThrow();
        assertThat(candidate.status).isEqualTo(Snapshot.Status.CANDIDATE);
        assertThatThrownBy(() -> snapshotService.resolve(
                candidateSnapshotId, b.sourceSystem, b.sourcePatientId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("尚在审核");

        // 业务确认后快照发布，下游可读到新归属
        impactService.confirmSnapshot(candidateSnapshotId, "op_test");
        assertThat(snapshotService.resolve(candidateSnapshotId, b.sourceSystem, b.sourcePatientId).masterId)
                .isEqualTo(graphService.currentMasterOf(b.id));
    }

    @Test
    void closedSettlementOnlyGetsAdjustmentList() {
        String tag = uid("G");
        SourceIdentity a = ingest(tag + "-a", "测试壬", "13100000009", "ID-" + tag);
        SourceIdentity b = ingest(tag + "-b", "测试壬", "13100000009", "ID-" + tag);

        Snapshot settleSnap = snapshotService.publish(
                Snapshot.BusinessType.SETTLEMENT, "结算快照-" + tag, "op_test");
        Settlement closed = bizService.createSettlement(
                settleSnap.id, a.sourceSystem, a.sourcePatientId,
                new BigDecimal("666.00"), "2026-10");
        bizService.closeSettlement(closed.id);
        UUID masterAtClose = closed.masterId;

        long v = graphService.currentVersion();
        Resolution r = resolutionService.merge(pendingPairOf(a.id).id, null, v, "合并", "op_test");

        // 已关账结算：只生成调整清单
        List<ImpactItem> impacts = impactRepo.findByResolutionId(r.id);
        ImpactItem adjustment = impacts.stream()
                .filter(i -> i.action == ImpactItem.Action.ADJUSTMENT_LIST)
                .findFirst().orElseThrow();
        assertThat(adjustment.businessId).isEqualTo(closed.id);

        // 业务确认候选快照：凭证不被改写，调整清单仍待人工确认
        UUID candidateSnapshotId = adjustment.snapshotId;
        impactService.confirmSnapshot(candidateSnapshotId, "op_test");
        Settlement after = bizService.settlements().stream()
                .filter(s -> s.id.equals(closed.id)).findFirst().orElseThrow();
        assertThat(after.masterId).isEqualTo(masterAtClose);
        assertThat(after.status).isEqualTo(Settlement.Status.CLOSED);
        assertThat(impactRepo.findById(adjustment.id).orElseThrow().status)
                .isEqualTo(ImpactItem.Status.PENDING);

        // 人工确认调整清单
        impactService.ack(adjustment.id, "财务已线下调整", "op_finance");
        assertThat(impactRepo.findById(adjustment.id).orElseThrow().status)
                .isEqualTo(ImpactItem.Status.ACKED);
    }

    @Test
    void confirmSnapshotAppliesBusinessRules() {
        String tag = uid("H");
        SourceIdentity a = ingest(tag + "-a", "测试癸", "13100000010", "ID-" + tag);
        SourceIdentity b = ingest(tag + "-b", "测试癸", "13199999999", "ID-" + tag);

        Snapshot apptSnap = snapshotService.publish(
                Snapshot.BusinessType.APPOINTMENT, "预约快照-" + tag, "op_test");
        Snapshot labSnap = snapshotService.publish(
                Snapshot.BusinessType.LAB, "检验快照-" + tag, "op_test");
        Snapshot settleSnap = snapshotService.publish(
                Snapshot.BusinessType.SETTLEMENT, "结算快照-" + tag, "op_test");

        // 预约挂在后接入的 b 上：合并确认后提醒电话应切换为主体主号码（a 的）
        Appointment appt = bizService.createAppointment(apptSnap.id, b.sourceSystem,
                b.sourcePatientId, Instant.now().plus(1, ChronoUnit.DAYS));
        LabReport report = bizService.createLabReport(labSnap.id, b.sourceSystem,
                b.sourcePatientId, "血常规");
        Settlement open = bizService.createSettlement(settleSnap.id, b.sourceSystem,
                b.sourcePatientId, new BigDecimal("100.00"), "2026-11");

        long v = graphService.currentVersion();
        Resolution r = resolutionService.merge(pendingPairOf(a.id).id, null, v, "合并", "op_test");
        UUID merged = graphService.currentMasterOf(a.id);

        impactRepo.findByResolutionId(r.id).stream()
                .map(item -> item.snapshotId)
                .distinct()
                .forEach(snapshotId -> impactService.confirmSnapshot(snapshotId, "op_test"));

        Appointment apptAfter = bizService.appointments().stream()
                .filter(x -> x.id.equals(appt.id)).findFirst().orElseThrow();
        assertThat(apptAfter.masterId).isEqualTo(merged);
        assertThat(apptAfter.contactPhone).isEqualTo("13100000010"); // 主体主号码（最早接入成员）

        LabReport reportAfter = bizService.labReports().stream()
                .filter(x -> x.id.equals(report.id)).findFirst().orElseThrow();
        assertThat(reportAfter.masterId).isEqualTo(merged);

        Settlement openAfter = bizService.settlements().stream()
                .filter(x -> x.id.equals(open.id)).findFirst().orElseThrow();
        assertThat(openAfter.masterId).isEqualTo(merged); // 未关账：可重指归属
    }

    @Test
    void rejectedCandidateKeepsIdentitiesSeparate() {
        String tag = uid("I");
        // 家庭共用号码：手机相同、姓名相似、证件不同 → 中等评分候选
        SourceIdentity father = identityService.ingest("REG_T", tag + "-a", "李测试",
                "13111111111", "ID-" + tag + "-F", "M", LocalDate.of(1960, 1, 1));
        SourceIdentity son = identityService.ingest("REG_T", tag + "-b", "李测试子",
                "13111111111", "ID-" + tag + "-S", "M", LocalDate.of(1988, 2, 2));

        CandidatePair pair = pendingPairOf(father.id);
        assertThat(pair.score).isBetween(0.39, 0.41);

        resolutionService.rejectCandidate(pair.id, "家庭共用号码，非同一就诊人", "op_test");
        assertThat(candidateRepo.findById(pair.id).orElseThrow().status)
                .isEqualTo(CandidatePair.Status.REJECTED);
        assertThat(graphService.currentMasterOf(father.id))
                .isNotEqualTo(graphService.currentMasterOf(son.id));
    }
}
