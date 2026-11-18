package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 来源身份：各院区挂号 / 检验 / 结算系统上报的就诊人身份。
 * 同一来源系统内 sourcePatientId 唯一；证据字段（姓名/手机/证件）允许后续更正，
 * 更正历史保存在 identity_correction，已发布快照不回写。
 */
@Entity
@Table(name = "source_identity",
        uniqueConstraints = @UniqueConstraint(columnNames = {"sourceSystem", "sourcePatientId"}))
public class SourceIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    /** 来源系统，如 REG_A（院区A挂号）、REG_B、LAB_A（检验）、SETTLE_A（结算） */
    @Column(nullable = false, length = 32)
    public String sourceSystem;

    /** 来源系统内部的就诊人编号 */
    @Column(nullable = false, length = 64)
    public String sourcePatientId;

    @Column(nullable = false, length = 64)
    public String fullName;

    @Column(length = 32)
    public String phone;

    @Column(length = 32)
    public String idCardNo;

    @Column(length = 8)
    public String gender;

    public LocalDate birthDate;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
