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
 * 匹配阈值发布：阈值模块独立持有状态，与候选生成、抽样、审核、质量估计模块解耦。
 *
 * <p>关键约束：新阈值只能作用于<strong>后续生成</strong>的候选（{@link #effectiveAfter}
 * 之后创建的候选对才读取新阈值）；不反写任何已发布业务快照，也不重算存量候选对。
 * 每次发布追加新版本行，历史阈值永久保留，便于复现某候选当时适用的阈值。</p>
 */
@Entity
@Table(name = "threshold_release", indexes = {
        @Index(name = "idx_threshold_status", columnList = "status"),
        @Index(name = "idx_threshold_effective", columnList = "effectiveAfter")
})
public class ThresholdRelease {

    public enum Status {DRAFT, PUBLISHED, RETIRED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    /** 阈值版本号，递增（同一时刻至多一个 PUBLISHED 版本生效） */
    @Column(nullable = false, unique = true)
    public int versionNo;

    /** 候选生成阈值（评分 ≥ 该值才生成候选对） */
    @Column(nullable = false)
    public double matchThreshold;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status = Status.DRAFT;

    /** 发布依据（如引用某质量报告批次与校准结论） */
    @Column(length = 1000)
    public String rationale;

    @Column(nullable = false, length = 64)
    public String createdBy;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Column(length = 64)
    public String publishedBy;

    public Instant publishedAt;

    /** 生效起点：仅 createdAt > 该时刻的候选生成适用本阈值；存量候选与快照不受影响 */
    @Column(nullable = false)
    public Instant effectiveAfter;

    @Column(length = 64)
    public String retiredBy;

    public Instant retiredAt;
}
