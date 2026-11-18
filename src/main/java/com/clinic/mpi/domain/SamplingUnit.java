package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * 抽样单元：批次中被抽中的候选生成快照。
 *
 * <p>{@link #inclusionWeight} 为抽样权重（分层抽样 = 层容量/层样本量 N_h/n_h；简单随机 = N/n），
 * 质量估计必须按权重还原总体，不能把刻意多抽的边界样本当成全体比例。</p>
 *
 * <p>{@link #reviewStatus} 是审核流转状态，与 {@link ReviewAnnotation} 的标签分离：
 * 候选撤销、来源更正、意见冲突时样本保留在原抽样框内并转入复核状态。</p>
 */
@Entity
@Table(name = "sampling_unit",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_sampling_unit_batch_snapshot", columnNames = {"batchId", "candidateSnapshotId"}),
        indexes = {
                @Index(name = "idx_sampunit_batch", columnList = "batchId"),
                @Index(name = "idx_sampunit_review", columnList = "reviewStatus")
        })
public class SamplingUnit {

    public enum ReviewStatus {
        /** 待审核 */
        PENDING,
        /** 已出具单一审核标签（确认/拒绝/待定） */
        LABELED,
        /** 候选在抽样后被撤销，保留抽样框，转复核 */
        ESCALATED_WITHDRAWN,
        /** 来源字段在抽样后被更正，按抽样时证据复核 */
        ESCALATED_CORRECTED,
        /** 审核员意见冲突，转高级复核 */
        ESCALATED_CONFLICT,
        /** 高级复核完成，结论冻结 */
        CLOSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID batchId;

    @Column(nullable = false)
    public UUID candidateSnapshotId;

    /** 冗余候选对 ID，便于联查与审计（不写回候选对） */
    @Column(nullable = false)
    public UUID candidateId;

    /** 抽样时所属分层键 */
    @Column(nullable = false, length = 400)
    public String stratumKey;

    /** 抽样权重 N_h/n_h（SRS 时全样本相同 N/n） */
    @Column(nullable = false)
    public double inclusionWeight;

    /** 框内该层总体容量 N_h */
    @Column(nullable = false)
    public int stratumFrameCount;

    /** 框内该层抽出量 n_h */
    @Column(nullable = false)
    public int stratumSampleCount;

    /** 是否为审核样本补录（框外追加，权重单独记录） */
    @Column(nullable = false)
    public boolean supplemental;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    public ReviewStatus reviewStatus = ReviewStatus.PENDING;

    @Column(length = 500)
    public String escalateReason;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public Instant statusChangedAt;

    /** 复核关闭人 */
    @Column(length = 64)
    public String closedBy;
}
