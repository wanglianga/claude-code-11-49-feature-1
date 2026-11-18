package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 主索引主体（golden record）。主体本身不保存就诊人属性，
 * 属性来自时态链接所指向的来源身份；主体永不删除，只通过链接变化体现合并与拆分。
 */
@Entity
@Table(name = "mpi_master")
public class MpiMaster {

    public enum Origin {INITIAL, MERGE, SPLIT}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    /** 产生该主体的决议；初始接入为 null */
    public UUID createdByResolutionId;

    @Column(nullable = false, length = 16)
    public String origin = Origin.INITIAL.name();
}
