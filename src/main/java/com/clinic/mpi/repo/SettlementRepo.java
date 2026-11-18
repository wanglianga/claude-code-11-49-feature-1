package com.clinic.mpi.repo;

import com.clinic.mpi.domain.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SettlementRepo extends JpaRepository<Settlement, UUID> {

    List<Settlement> findByIdentityId(UUID identityId);

    List<Settlement> findAllByOrderByCreatedAtDesc();
}
