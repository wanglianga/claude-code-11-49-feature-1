package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 快照映射：快照创建时把该图谱版本下"来源身份 → 主体"的解析结果物化保存，
 * 下游读取快照是纯查表，快速且可复核；物化行永不被回写。
 */
@Entity
@Table(name = "snapshot_mapping")
public class SnapshotMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID snapshotId;

    @Column(nullable = false, length = 32)
    public String sourceSystem;

    @Column(nullable = false, length = 64)
    public String sourcePatientId;

    @Column(nullable = false)
    public UUID identityId;

    @Column(nullable = false)
    public UUID masterId;
}
