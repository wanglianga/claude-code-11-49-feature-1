package com.clinic.mpi.repo;

import com.clinic.mpi.domain.SourceIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SourceIdentityRepo extends JpaRepository<SourceIdentity, UUID> {

    Optional<SourceIdentity> findBySourceSystemAndSourcePatientId(String sourceSystem, String sourcePatientId);

    boolean existsBySourceSystemAndSourcePatientId(String sourceSystem, String sourcePatientId);

    java.util.List<SourceIdentity> findByPhone(String phone);

    java.util.List<SourceIdentity> findByFullName(String fullName);
}
