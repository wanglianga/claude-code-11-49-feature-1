package com.clinic.mpi.repo;

import com.clinic.mpi.domain.ImpactItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ImpactItemRepo extends JpaRepository<ImpactItem, UUID> {

    List<ImpactItem> findAllByOrderByCreatedAtDesc();

    List<ImpactItem> findBySnapshotId(UUID snapshotId);

    List<ImpactItem> findByResolutionId(UUID resolutionId);

    List<ImpactItem> findBySnapshotIdAndStatus(UUID snapshotId, ImpactItem.Status status);
}
