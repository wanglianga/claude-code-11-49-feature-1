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
 * 人工核验标注：经授权审核员对抽中候选的标签。
 *
 * <p>标签三分类：</p>
 * <ul>
 *   <li>{@link Label#CONFIRMED}：依据候选生成时可见证据，确认是同一就诊人；</li>
 *   <li>{@link Label#REJECTED}：依据当时证据拒绝（如家庭共用手机号、同名异人）；</li>
 *   <li>{@link Label#INCONCLUSIVE}：暂不能判定（证据不足/相互矛盾），计入待定率而非掩盖为精确率。</li>
 * </ul>
 *
 * <p>标注只评价 {@link #candidateVersion} 版本候选生成时可见的证据（关联 CandidateSnapshot 固化值），
 * 不得用日后新出现的证件号倒灌。标注本身不可修改；审核员意见不一致时保留各自标注，
 * 样本转 {@link SamplingUnit.ReviewStatus#ESCALATED_CONFLICT} 并由高级复核裁决。</p>
 */
@Entity
@Table(name = "review_annotation", indexes = {
        @Index(name = "idx_review_unit", columnList = "samplingUnitId"),
        @Index(name = "idx_review_label", columnList = "label")
})
public class ReviewAnnotation {

    public enum Label {CONFIRMED, REJECTED, INCONCLUSIVE}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID samplingUnitId;

    /** 标注所针对的候选生成快照（当时证据版本） */
    @Column(nullable = false)
    public UUID candidateSnapshotId;

    /** 冗余候选版本，明确标注评价的是"第几版候选" */
    @Column(nullable = false)
    public int candidateVersion;

    @Column(nullable = false, length = 64)
    public String reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Label label;

    /** 审核依据说明：只能引用快照中固化的当时证据，不得引用日后字段 */
    @Column(length = 1000)
    public String note;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
