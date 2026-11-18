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
 * 抽样批次：抽样编排模块独立持有的状态。
 *
 * <p>两种抽样策略：</p>
 * <ul>
 *   <li>{@link Strategy#SRS}：按候选分数随机抽样（实现简单，监测整体变化，但审核量浪费在低风险区间）；</li>
 *   <li>{@link Strategy#STRATIFIED}：按不确定度与高影响来源分层优先抽样，更快发现关键误配；
 *       估计总体质量时必须使用各层抽样权重（N_h/n_h），不能把刻意多抽的边界样本当成全体比例。</li>
 * </ul>
 *
 * <p>抽样框在批次创建时冻结（记录框内候选总数与各层容量），后续候选撤销/字段更正不改变框，
 * 只在样本上流转复核状态。</p>
 */
@Entity
@Table(name = "sampling_batch", indexes = {
        @Index(name = "idx_sampbatch_strategy", columnList = "strategy")
})
public class SamplingBatch {

    public enum Strategy {SRS, STRATIFIED}

    public enum Status {OPEN, CLOSED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false, length = 64)
    public String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Strategy strategy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    public Status status = Status.OPEN;

    /** 抽样框冻结时的候选生成快照总数（总体 N） */
    @Column(nullable = false)
    public int frameSize;

    /** 实际抽出的样本量 n（含补录） */
    @Column(nullable = false)
    public int sampleSize;

    /** 分层容量 JSON：{stratumKey: {frameCount:N_h, sampled:n_h, weight:N_h/n_h}} */
    @Column(nullable = false, length = 4000)
    public String strataJson;

    /** 抽样种子（可复现） */
    @Column(nullable = false)
    public long seed;

    @Column(length = 500)
    public String note;

    @Column(nullable = false, length = 64)
    public String createdBy;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public Instant closedAt;
}
