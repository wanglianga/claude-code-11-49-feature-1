package com.clinic.mpi.seed;

import com.clinic.mpi.domain.GraphState;
import com.clinic.mpi.domain.Snapshot;
import com.clinic.mpi.repo.GraphStateRepo;
import com.clinic.mpi.service.BizService;
import com.clinic.mpi.service.IdentityService;
import com.clinic.mpi.service.SnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 本地样例数据（幂等：已有图谱状态时跳过）：
 * <ul>
 *   <li>多院重复挂号：张三在院区A（REG_A/P-A-001）与院区B（REG_B/P-B-001）重复建档；</li>
 *   <li>证件更正：赵六在检验系统（LAB_A/L-A-001）证件号末位录入错误，待更正后匹配；</li>
 *   <li>家庭共用号码：李四与李四中（父子）共用手机号，评分中等，应人工拒绝合并；</li>
 *   <li>误并素材：两个同名"陈晨"仅姓名相同，供人工误并→拆分回放。</li>
 * </ul>
 * 同时播种初始快照与预约、结算（含已关账）、检验报告等下游记录。
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final GraphStateRepo graphStateRepo;
    private final IdentityService identityService;
    private final SnapshotService snapshotService;
    private final BizService bizService;

    public DataSeeder(GraphStateRepo graphStateRepo, IdentityService identityService,
                      SnapshotService snapshotService, BizService bizService) {
        this.graphStateRepo = graphStateRepo;
        this.identityService = identityService;
        this.snapshotService = snapshotService;
        this.bizService = bizService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (graphStateRepo.count() > 0) {
            log.info("图谱已初始化，跳过样例数据播种");
            return;
        }
        log.info("播种本地样例数据...");
        graphStateRepo.save(new GraphState());

        // ---- 来源身份 ----
        // 场景1：多院重复挂号 —— 张三（同一人，两院区各自建档）
        identityService.ingest("REG_A", "P-A-001", "张三", "13800000001",
                "110101199001011234", "M", LocalDate.of(1990, 1, 1));
        identityService.ingest("REG_B", "P-B-001", "张三", "13800000001",
                "110101199001011234", "M", LocalDate.of(1990, 1, 1));

        // 场景2（误并素材）：两个同名"陈晨"，实际为不同人，仅姓名一致（评分低于阈值，不产生候选对）
        identityService.ingest("REG_A", "P-A-002", "陈晨", "13700000002",
                "110101198505054321", "F", LocalDate.of(1985, 5, 5));
        identityService.ingest("REG_B", "P-B-002", "陈晨", "13711110002",
                null, "F", LocalDate.of(1988, 8, 8));

        // 场景3：家庭共用号码 —— 李四与李四中（父子）共用手机号，评分中等，应人工拒绝
        identityService.ingest("REG_A", "P-A-003", "李四", "13900000003",
                "110101196001010011", "M", LocalDate.of(1960, 1, 1));
        identityService.ingest("REG_B", "P-B-003", "李四中", "13900000003",
                "110101198802020022", "M", LocalDate.of(1988, 2, 2));

        // 场景4：证件更正 —— 赵六在检验系统证件号末位录错（...110 应为 ...111）
        identityService.ingest("REG_A", "P-A-004", "赵六", "13600000004",
                "110101197703031111", "M", LocalDate.of(1977, 3, 3));
        identityService.ingest("LAB_A", "L-A-001", "赵六", "13600000004",
                "110101197703031110", "M", LocalDate.of(1977, 3, 3));
        identityService.ingest("SETTLE_A", "S-A-001", "赵六", "13600000004",
                "110101197703031111", "M", LocalDate.of(1977, 3, 3));

        // ---- 初始业务快照（固定图谱版本 v0，全部身份各自为单例主体） ----
        Snapshot apptSnap = snapshotService.publish(
                Snapshot.BusinessType.APPOINTMENT, "SN-INIT-APPT 初始预约快照", "seed");
        Snapshot settleSnap = snapshotService.publish(
                Snapshot.BusinessType.SETTLEMENT, "SN-INIT-SETTLE 初始结算快照", "seed");
        Snapshot labSnap = snapshotService.publish(
                Snapshot.BusinessType.LAB, "SN-INIT-LAB 初始检验快照", "seed");

        // ---- 下游业务记录 ----
        // 陈晨B（REG_B/P-B-002）的预约：误并后提醒将错发，拆分后应恢复
        bizService.createAppointment(apptSnap.id, "REG_B", "P-B-002",
                Instant.now().plus(3, ChronoUnit.DAYS));
        // 李四中（REG_B/P-B-003）的预约：家庭共用号码场景，拒绝合并后提醒不变
        bizService.createAppointment(apptSnap.id, "REG_B", "P-B-003",
                Instant.now().plus(5, ChronoUnit.DAYS));

        // 陈晨A（REG_A/P-A-002）的已关账结算单：拆分后只生成调整清单，凭证不改写
        var closedSettlement = bizService.createSettlement(
                settleSnap.id, "REG_A", "P-A-002", new BigDecimal("880.00"), "2026-10");
        bizService.closeSettlement(closedSettlement.id);
        // 赵六（SETTLE_A/S-A-001）的未关账结算单：合并后归属可随确认快照更新
        bizService.createSettlement(settleSnap.id, "SETTLE_A", "S-A-001",
                new BigDecimal("1200.00"), "2026-11");

        // 检验报告：陈晨A 血常规、赵六（检验系统）尿常规，均未签发
        bizService.createLabReport(labSnap.id, "REG_A", "P-A-002", "血常规");
        bizService.createLabReport(labSnap.id, "LAB_A", "L-A-001", "尿常规");

        log.info("样例数据播种完成：9 个来源身份、3 份初始快照、6 条下游业务记录");
    }
}
