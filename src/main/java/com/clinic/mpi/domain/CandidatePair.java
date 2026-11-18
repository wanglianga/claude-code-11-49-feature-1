package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 候选重复对：由匹配评分生成，等待人工决议。
 * 候选对属于"尚在审核的身份推断"，下游不可见。
 */
@Entity
@Table(name = "candidate_pair")
public class CandidatePair {

    public enum Status {PENDING, MERGED, REJECTED, SUPERSEDED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID identityAId;

    @Column(nullable = false)
    public UUID identityBId;

    /** 匹配评分 0~1 */
    @Column(nullable = false)
    public double score;

    /** 命中证据（JSON 数组，如 ["证件号一致","手机号一致"]） */
    @Column(length = 2000)
    public String evidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status = Status.PENDING;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public Instant decidedAt;

    @Column(length = 64)
    public String decidedBy;

    @Column(length = 500)
    public String decisionReason;
}
