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
 * 复核裁决记录：样本在抽样后出现以下情形时，保留原抽样框并明确转入的复核状态：
 * <ol>
 *   <li>候选被撤销（候选对作废 / 被拒绝 / 已合并）；</li>
 *   <li>来源证据字段被更正（按抽样时固化证据复核，不看新字段）；</li>
 *   <li>审核员意见冲突（保留各自标注，高级复核裁决）。</li>
 * </ol>
 * 裁决结论只作为校准标签来源，不回写候选对与已发布业务快照。
 */
@Entity
@Table(name = "review_escalation", indexes = {
        @Index(name = "idx_escalation_unit", columnList = "samplingUnitId"),
        @Index(name = "idx_escalation_reason", columnList = "reasonType")
})
public class ReviewEscalation {

    public enum ReasonType {CANDIDATE_WITHDRAWN, SOURCE_CORRECTED, REVIEWER_CONFLICT}

    public enum Outcome {PENDING, CONFIRMED, REJECTED, INCONCLUSIVE}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID samplingUnitId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    public ReasonType reasonType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Outcome outcome = Outcome.PENDING;

    /** 触发时的上下文说明（如被更正字段、冲突的两名审核员） */
    @Column(length = 1000)
    public String detail;

    @Column(length = 64)
    public String adjudicatedBy;

    /** 裁决依据（只引用抽样时固化证据） */
    @Column(length = 1000)
    public String adjudicationNote;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public Instant adjudicatedAt;
}
