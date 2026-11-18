package com.clinic.mpi.service;

import com.clinic.mpi.domain.Appointment;
import com.clinic.mpi.domain.ImpactItem;
import com.clinic.mpi.domain.LabReport;
import com.clinic.mpi.domain.Resolution;
import com.clinic.mpi.domain.Settlement;
import com.clinic.mpi.domain.Snapshot;
import com.clinic.mpi.repo.AppointmentRepo;
import com.clinic.mpi.repo.ImpactItemRepo;
import com.clinic.mpi.repo.LabReportRepo;
import com.clinic.mpi.repo.SettlementRepo;
import com.clinic.mpi.repo.SnapshotRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 影响清单：身份决议发布后，计算对下游业务记录的影响并生成候选快照；
 * 业务确认候选快照时按自身规则领取（自动切换 / 只登记调整清单 / 人工跟进）。
 */
@Service
public class ImpactService {

    /** 一次决议中归属发生变化的身份。 */
    public record ChangedIdentity(UUID identityId, String sourceSystem, String sourcePatientId,
                                  UUID oldMasterId, UUID newMasterId) {
    }

    private final ImpactItemRepo impactRepo;
    private final AppointmentRepo appointmentRepo;
    private final SettlementRepo settlementRepo;
    private final LabReportRepo labReportRepo;
    private final SnapshotRepo snapshotRepo;
    private final SnapshotService snapshotService;

    public ImpactService(ImpactItemRepo impactRepo, AppointmentRepo appointmentRepo,
                         SettlementRepo settlementRepo, LabReportRepo labReportRepo,
                         SnapshotRepo snapshotRepo, SnapshotService snapshotService) {
        this.impactRepo = impactRepo;
        this.appointmentRepo = appointmentRepo;
        this.settlementRepo = settlementRepo;
        this.labReportRepo = labReportRepo;
        this.snapshotRepo = snapshotRepo;
        this.snapshotService = snapshotService;
    }

    /**
     * 决议发布后生成影响清单，并为受影响的业务类型生成候选快照。
     * 与决议处于同一事务：图谱变更与影响清单一同落库。
     */
    @Transactional
    public List<ImpactItem> onResolution(Resolution resolution, List<ChangedIdentity> changes) {
        Map<Snapshot.BusinessType, List<ImpactItem>> byType = new EnumMap<>(Snapshot.BusinessType.class);
        for (ChangedIdentity change : changes) {
            collectAppointmentImpacts(resolution, change, byType);
            collectSettlementImpacts(resolution, change, byType);
            collectLabImpacts(resolution, change, byType);
            // 经营分析：归属变化的身份即口径变化，登记供分析侧确认
            ImpactItem analytics = baseItem(resolution, change, Snapshot.BusinessType.ANALYTICS);
            analytics.action = ImpactItem.Action.INFO;
            analytics.note = "分析口径变化：来源身份 " + change.sourceSystem() + "/"
                    + change.sourcePatientId() + " 归属主体由 " + change.oldMasterId()
                    + " 变为 " + change.newMasterId() + "，新分析运行请使用确认后的快照";
            byType.computeIfAbsent(Snapshot.BusinessType.ANALYTICS, k -> new ArrayList<>()).add(analytics);
        }

        List<ImpactItem> saved = new ArrayList<>();
        for (Map.Entry<Snapshot.BusinessType, List<ImpactItem>> entry : byType.entrySet()) {
            // 同类型旧候选快照作废：业务只应确认最新候选
            supersedeCandidatesOf(entry.getKey());
            Snapshot candidate = snapshotService.createCandidate(
                    entry.getKey(), resolution.graphVersion, resolution.id, resolution.operatorId);
            for (ImpactItem item : entry.getValue()) {
                item.snapshotId = candidate.id;
                saved.add(impactRepo.save(item));
            }
        }
        return saved;
    }

    private void collectAppointmentImpacts(Resolution resolution, ChangedIdentity change,
                                           Map<Snapshot.BusinessType, List<ImpactItem>> byType) {
        for (Appointment appt : appointmentRepo.findByIdentityId(change.identityId())) {
            if (appt.masterId.equals(change.newMasterId())) {
                continue;
            }
            ImpactItem item = baseItem(resolution, change, Snapshot.BusinessType.APPOINTMENT);
            item.businessId = appt.id;
            item.oldMasterId = appt.masterId;
            if (appt.status == Appointment.Status.BOOKED) {
                item.action = ImpactItem.Action.REMIND_SWITCH;
                item.note = "预约提醒的归属主体与联系电话需切换到新主体";
            } else {
                item.action = ImpactItem.Action.INFO;
                item.note = "预约提醒已发出（电话 " + appt.remindedPhone + "），身份归属已变化，需人工跟进";
            }
            byType.computeIfAbsent(Snapshot.BusinessType.APPOINTMENT, k -> new ArrayList<>()).add(item);
        }
    }

    private void collectSettlementImpacts(Resolution resolution, ChangedIdentity change,
                                          Map<Snapshot.BusinessType, List<ImpactItem>> byType) {
        for (Settlement settlement : settlementRepo.findByIdentityId(change.identityId())) {
            if (settlement.masterId.equals(change.newMasterId())) {
                continue;
            }
            ImpactItem item = baseItem(resolution, change, Snapshot.BusinessType.SETTLEMENT);
            item.businessId = settlement.id;
            item.oldMasterId = settlement.masterId;
            if (settlement.status == Settlement.Status.CLOSED) {
                item.action = ImpactItem.Action.ADJUSTMENT_LIST;
                item.note = "结算单已关账（账期 " + settlement.period + "，金额 " + settlement.amount
                        + "），凭证不改写，仅生成调整清单供财务线下调整";
            } else {
                item.action = ImpactItem.Action.REATTRIBUTE;
                item.note = "未关账结算单可随确认快照重新指定归属主体";
            }
            byType.computeIfAbsent(Snapshot.BusinessType.SETTLEMENT, k -> new ArrayList<>()).add(item);
        }
    }

    private void collectLabImpacts(Resolution resolution, ChangedIdentity change,
                                   Map<Snapshot.BusinessType, List<ImpactItem>> byType) {
        for (LabReport report : labReportRepo.findByIdentityId(change.identityId())) {
            if (report.masterId.equals(change.newMasterId())) {
                continue;
            }
            ImpactItem item = baseItem(resolution, change, Snapshot.BusinessType.LAB);
            item.businessId = report.id;
            item.oldMasterId = report.masterId;
            if (report.status == LabReport.Status.PRELIMINARY) {
                item.action = ImpactItem.Action.REATTRIBUTE;
                item.note = "未签发检验报告可随确认快照重新指定归属主体";
            } else {
                item.action = ImpactItem.Action.INFO;
                item.note = "检验报告已签发（FINAL），归属变化仅登记，需人工处理";
            }
            byType.computeIfAbsent(Snapshot.BusinessType.LAB, k -> new ArrayList<>()).add(item);
        }
    }

    private ImpactItem baseItem(Resolution resolution, ChangedIdentity change,
                                Snapshot.BusinessType type) {
        ImpactItem item = new ImpactItem();
        item.resolutionId = resolution.id;
        item.businessType = type;
        item.sourceSystem = change.sourceSystem();
        item.sourcePatientId = change.sourcePatientId();
        item.oldMasterId = change.oldMasterId();
        item.newMasterId = change.newMasterId();
        return item;
    }

    private void supersedeCandidatesOf(Snapshot.BusinessType type) {
        for (Snapshot old : snapshotRepo.findByBusinessTypeAndStatus(
                type, Snapshot.Status.CANDIDATE)) {
            old.status = Snapshot.Status.SUPERSEDED;
            snapshotRepo.save(old);
            for (ImpactItem pending : impactRepo.findBySnapshotIdAndStatus(
                    old.id, ImpactItem.Status.PENDING)) {
                pending.status = ImpactItem.Status.SUPERSEDED;
                pending.note = (pending.note == null ? "" : pending.note + "；") + "候选快照被更新版本取代";
                impactRepo.save(pending);
            }
        }
    }

    /**
     * 业务确认候选快照（领取新快照）：按各业务规则处理影响项。
     * 预约：未提醒的切换主体与联系电话；结算：未关账的重指归属，已关账只留调整清单；
     * 检验：未签发的重指归属；分析：登记口径变化。
     */
    @Transactional
    public Snapshot confirmSnapshot(UUID snapshotId, String operatorId) {
        Snapshot snapshot = snapshotService.get(snapshotId);
        if (snapshot.status != Snapshot.Status.CANDIDATE) {
            throw new ConflictException("快照 " + snapshotId + " 状态为 " + snapshot.status
                    + "，只有候选（CANDIDATE）快照可以确认");
        }
        for (ImpactItem item : impactRepo.findBySnapshotIdAndStatus(snapshotId, ImpactItem.Status.PENDING)) {
            applyImpact(item, snapshot, operatorId);
        }
        snapshot.status = Snapshot.Status.PUBLISHED;
        snapshot.confirmedBy = operatorId;
        snapshot.confirmedAt = Instant.now();
        return snapshotRepo.save(snapshot);
    }

    private void applyImpact(ImpactItem item, Snapshot snapshot, String operatorId) {
        switch (item.action) {
            case REMIND_SWITCH -> {
                Appointment appt = appointmentRepo.findById(item.businessId)
                        .orElseThrow(() -> new NotFoundException("预约不存在: " + item.businessId));
                if (appt.status == Appointment.Status.BOOKED) {
                    appt.masterId = item.newMasterId;
                    appt.snapshotId = snapshot.id;
                    appt.contactPhone = snapshotService.primaryPhoneAt(
                            item.newMasterId, snapshot.graphVersion);
                    appointmentRepo.save(appt);
                    markApplied(item, operatorId, "预约归属与提醒电话已切换到新主体");
                } else {
                    item.note = item.note + "；确认时预约状态为 " + appt.status + "，不自动切换";
                }
            }
            case REATTRIBUTE -> {
                if (item.businessType == Snapshot.BusinessType.SETTLEMENT) {
                    Settlement settlement = settlementRepo.findById(item.businessId)
                            .orElseThrow(() -> new NotFoundException("结算单不存在: " + item.businessId));
                    if (settlement.status == Settlement.Status.OPEN) {
                        settlement.masterId = item.newMasterId;
                        settlement.snapshotId = snapshot.id;
                        settlementRepo.save(settlement);
                        markApplied(item, operatorId, "未关账结算单归属已更新");
                    }
                } else if (item.businessType == Snapshot.BusinessType.LAB) {
                    LabReport report = labReportRepo.findById(item.businessId)
                            .orElseThrow(() -> new NotFoundException("检验报告不存在: " + item.businessId));
                    if (report.status == LabReport.Status.PRELIMINARY) {
                        report.masterId = item.newMasterId;
                        report.snapshotId = snapshot.id;
                        labReportRepo.save(report);
                        markApplied(item, operatorId, "未签发检验报告归属已更新");
                    }
                }
            }
            case ADJUSTMENT_LIST -> {
                // 已关账凭证：主索引服务不改写，调整清单保持 PENDING 待财务人工确认
            }
            case INFO -> markApplied(item, operatorId, "已登记，供相关业务复核");
        }
        impactRepo.save(item);
    }

    private void markApplied(ImpactItem item, String operatorId, String note) {
        item.status = ImpactItem.Status.APPLIED;
        item.processedAt = Instant.now();
        item.processedBy = operatorId;
        item.note = (item.note == null ? "" : item.note + "；") + note;
    }

    /** 人工确认影响项（调整清单、已定型报告、已发出提醒等）。 */
    @Transactional
    public ImpactItem ack(UUID impactId, String note, String operatorId) {
        ImpactItem item = impactRepo.findById(impactId)
                .orElseThrow(() -> new NotFoundException("影响项不存在: " + impactId));
        if (item.status != ImpactItem.Status.PENDING) {
            throw new ConflictException("影响项状态为 " + item.status + "，只有待处理（PENDING）项可以确认");
        }
        item.status = ImpactItem.Status.ACKED;
        item.processedAt = Instant.now();
        item.processedBy = operatorId;
        if (note != null && !note.isBlank()) {
            item.note = (item.note == null ? "" : item.note + "；") + "人工确认: " + note;
        }
        return impactRepo.save(item);
    }

    /** 业务驳回候选快照：影响项作废，业务继续使用原快照。 */
    @Transactional
    public Snapshot rejectSnapshot(UUID snapshotId, String reason, String operatorId) {
        Snapshot snapshot = snapshotService.get(snapshotId);
        if (snapshot.status != Snapshot.Status.CANDIDATE) {
            throw new ConflictException("快照 " + snapshotId + " 状态为 " + snapshot.status
                    + "，只有候选（CANDIDATE）快照可以驳回");
        }
        snapshot.status = Snapshot.Status.REJECTED;
        snapshot.rejectReason = reason;
        snapshotRepo.save(snapshot);
        for (ImpactItem item : impactRepo.findBySnapshotIdAndStatus(snapshotId, ImpactItem.Status.PENDING)) {
            item.status = ImpactItem.Status.SUPERSEDED;
            item.note = (item.note == null ? "" : item.note + "；") + "候选快照被驳回: " + reason;
            impactRepo.save(item);
        }
        return snapshot;
    }

    @Transactional(readOnly = true)
    public List<ImpactItem> list() {
        return impactRepo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<ImpactItem> ofSnapshot(UUID snapshotId) {
        return impactRepo.findBySnapshotId(snapshotId);
    }

    @Transactional(readOnly = true)
    public List<ImpactItem> ofResolution(UUID resolutionId) {
        return impactRepo.findByResolutionId(resolutionId);
    }
}
