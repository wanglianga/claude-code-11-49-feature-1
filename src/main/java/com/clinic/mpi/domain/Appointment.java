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
 * 下游业务记录：预约。创建时通过已发布快照解析主体并固定引用快照；
 * 提醒电话在身份变化且业务确认新快照后按规则切换。
 */
@Entity
@Table(name = "appointment")
public class Appointment {

    public enum Status {BOOKED, REMINDED, CANCELLED}

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

    @Column(length = 32)
    public String contactPhone;

    @Column(nullable = false)
    public Instant apptTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status = Status.BOOKED;

    public Instant remindedAt;

    @Column(length = 32)
    public String remindedPhone;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
