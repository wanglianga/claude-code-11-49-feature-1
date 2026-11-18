package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 影响清单条目：一次身份决议对下游业务记录造成的影响。
 * 业务确认候选快照时按自身规则处理（自动切换 / 只登记调整清单 / 人工跟进）。
 */
@Entity
@Table(name = "impact_item")
public class ImpactItem {

    public enum Action {
        /** 预约提醒的归属主体与联系电话需切换 */
        REMIND_SWITCH,
        /** 归属主体可重新指定（未关账结算、未签发检验报告） */
        REATTRIBUTE,
        /** 已关账结算：只生成调整清单，凭证不改写 */
        ADJUSTMENT_LIST,
        /** 仅登记（分析口径变化、已定型报告、已发出提醒等需人工跟进） */
        INFO
    }

    public enum Status {PENDING, APPLIED, ACKED, SUPERSEDED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    /** 产生该影响的决议 */
    @Column(nullable = false)
    public UUID resolutionId;

    /** 所属的候选快照（业务确认后影响随之处理） */
    public UUID snapshotId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Snapshot.BusinessType businessType;

    /** 下游业务记录 ID；分析类影响为 null */
    public UUID businessId;

    @Column(length = 32)
    public String sourceSystem;

    @Column(length = 64)
    public String sourcePatientId;

    public UUID oldMasterId;

    public UUID newMasterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Action action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status = Status.PENDING;

    @Column(length = 1000)
    public String note;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public Instant processedAt;

    @Column(length = 64)
    public String processedBy;
}
