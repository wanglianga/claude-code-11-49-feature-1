package com.clinic.mpi.repo;

import com.clinic.mpi.domain.SnapshotMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SnapshotMappingRepo extends JpaRepository<SnapshotMapping, UUID> {

    List<SnapshotMapping> findBySnapshotId(UUID snapshotId);

    Optional<SnapshotMapping> findBySnapshotIdAndSourceSystemAndSourcePatientId(
            UUID snapshotId, String sourceSystem, String sourcePatientId);
}
