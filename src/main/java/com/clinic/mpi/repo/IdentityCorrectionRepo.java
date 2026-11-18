package com.clinic.mpi.repo;

import com.clinic.mpi.domain.IdentityCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IdentityCorrectionRepo extends JpaRepository<IdentityCorrection, UUID> {

    List<IdentityCorrection> findByIdentityIdOrderByCreatedAtDesc(UUID identityId);
}
