package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 时态关系边：来源身份 → 主体的链接，带生效图谱版本区间 [validFrom, validTo)。
 * validTo 为 null 表示当前生效。查询某业务时点的身份归属即按版本区间解析。
 */
@Entity
@Table(name = "mpi_link")
public class MpiLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID identityId;

    @Column(nullable = false)
    public UUID masterId;

    /** 生效起始图谱版本（含） */
    @Column(nullable = false)
    public long validFrom;

    /** 失效图谱版本（不含）；null = 当前生效 */
    public Long validTo;

    /** 产生该链接的决议；初始接入为 null */
    public UUID resolutionId;
}
