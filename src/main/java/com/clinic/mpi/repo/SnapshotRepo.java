package com.clinic.mpi.repo;

import com.clinic.mpi.domain.Snapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SnapshotRepo extends JpaRepository<Snapshot, UUID> {

    List<Snapshot> findAllByOrderByCreatedAtDesc();

    List<Snapshot> findByBusinessTypeOrderByCreatedAtDesc(Snapshot.BusinessType businessType);

    List<Snapshot> findByBusinessTypeAndStatus(Snapshot.BusinessType businessType, Snapshot.Status status);
}
