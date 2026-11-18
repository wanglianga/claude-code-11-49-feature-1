package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** 来源身份字段更正的审计记录（如证件号更正）。 */
@Entity
@Table(name = "identity_correction")
public class IdentityCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID identityId;

    /** 被更正的字段：idCardNo / phone / fullName / birthDate / gender */
    @Column(nullable = false, length = 32)
    public String field;

    @Column(length = 128)
    public String oldValue;

    @Column(length = 128)
    public String newValue;

    @Column(length = 500)
    public String note;

    @Column(nullable = false, length = 64)
    public String operatorId;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
