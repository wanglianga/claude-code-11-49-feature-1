package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 下游业务记录：结算单。关账（CLOSED）后身份归属冻结，
 * 主索引侧的身份拆分只生成调整清单，绝不改写已关账凭证。
 */
@Entity
@Table(name = "settlement")
public class Settlement {

    public enum Status {OPEN, CLOSED}

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

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal amount;

    /** 账期，如 2026-10 */
    @Column(nullable = false, length = 16)
    public String period;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status = Status.OPEN;

    public Instant closedAt;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
