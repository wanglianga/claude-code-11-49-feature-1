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

import java.time.Instant;
import java.util.UUID;

/**
 * 候选生成快照：候选对生成那一刻的"可见证据 + 风险特征"固化记录。
 *
 * <p>校准模块只通过本表评价模型，绝不回读日后变更的来源字段——
 * 人工核验只能依据 {@link #evidenceJson} 与 {@link #riskFeaturesJson} 中固化的当时证据，
 * 日后新出现的证件号不能倒灌成"当时模型正确"。</p>
 *
 * <p>候选在抽样后被撤销（SUPERSEDED / REJECTED / MERGED）、来源字段被更正或审核员意见冲突时，
 * 原抽样框不删除，{@link #frameStatus} 明确转入何种复核状态。</p>
 */
@Entity
@Table(name = "candidate_snapshot", indexes = {
        @Index(name = "idx_candsnap_candidate", columnList = "candidateId"),
        @Index(name = "idx_candsnap_campus", columnList = "campusA,campusB"),
        @Index(name = "idx_candsnap_band", columnList = "scoreBand"),
        @Index(name = "idx_candsnap_status", columnList = "frameStatus")
})
public class CandidateSnapshot {

    /** 抽样框状态：抽样框一经固化不可删除，只能流转复核状态。 */
    public enum FrameStatus {
        /** 在框，可被抽样 */
        ACTIVE,
        /** 抽样后候选被撤销（作废/拒绝/合并），保留抽样框，转复核 */
        CANDIDATE_WITHDRAWN,
        /** 抽样后来源证据字段被更正，按当时证据复核 */
        SOURCE_CORRECTED,
        /** 审核员意见冲突，转高级复核 */
        CONFLICT_ESCALATED,
        /** 复核完成，结论冻结 */
        REVIEW_CLOSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    /** 关联候选对（候选对本身仍由匹配/决议模块持有状态，本表不反写） */
    @Column(nullable = false)
    public UUID candidateId;

    /** 候选版本：同一身份对每次重算递增，标识"第几版候选" */
    @Column(nullable = false)
    public int candidateVersion;

    /** 生成该候选时适用的阈值发布版本号（null 表示内置基线阈值） */
    public Integer thresholdVersion;

    public UUID identityAId;

    public UUID identityBId;

    /** 来源院区（来源系统前缀，如 REG_A → 院区A） */
    @Column(nullable = false, length = 32)
    public String campusA;

    @Column(nullable = false, length = 32)
    public String campusB;

    @Column(length = 64)
    public String sourceSystemA;

    @Column(length = 64)
    public String sourceSystemB;

    /** 生成时评分（固化，不随后续评分变化） */
    @Column(nullable = false)
    public double score;

    /** 分数区间，如 BAND_04_05（[0.40,0.50)） */
    @Column(nullable = false, length = 16)
    public String scoreBand;

    /** 证据组合指纹：命中证据排序后拼接，如 "姓名一致|手机号一致" */
    @Column(nullable = false, length = 300)
    public String evidenceKey;

    /** 当时可见证据 JSON（证据文案 + 两侧字段值快照） */
    @Column(nullable = false, length = 4000)
    public String evidenceJson;

    /** 风险特征 JSON：姓名变更、家庭共用手机号、同名高发院区、跨院区等标记 */
    @Column(nullable = false, length = 2000)
    public String riskFeaturesJson;

    /** 抽样分层键：院区对 | 证据组合 | 分数区间 | 不确定度层级 */
    @Column(nullable = false, length = 400)
    public String stratumKey;

    /** 不确定度分层：HIGH 不确定（边界分/高风险）优先抽样 */
    @Column(nullable = false, length = 8)
    public String uncertaintyTier;

    /** 高影响来源标记（新院区等高误配影响来源，分层抽样优先） */
    @Column(nullable = false)
    public boolean highImpactSource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    public FrameStatus frameStatus = FrameStatus.ACTIVE;

    /** 状态流转说明（如"来源身份 idCardNo 于 ... 更正，按原证据复核"） */
    @Column(length = 500)
    public String frameStatusReason;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public Instant statusChangedAt;
}
