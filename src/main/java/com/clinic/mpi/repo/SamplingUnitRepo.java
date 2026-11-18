package com.clinic.mpi.repo;

import com.clinic.mpi.domain.SamplingUnit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SamplingUnitRepo extends JpaRepository<SamplingUnit, UUID> {

    List<SamplingUnit> findByBatchIdOrderByCreatedAtAsc(UUID batchId);

    List<SamplingUnit> findByBatchIdAndReviewStatus(UUID batchId, SamplingUnit.ReviewStatus status);

    List<SamplingUnit> findByCandidateSnapshotId(UUID candidateSnapshotId);

    Optional<SamplingUnit> findByBatchIdAndCandidateSnapshotId(UUID batchId, UUID candidateSnapshotId);

    boolean existsByBatchIdAndCandidateSnapshotId(UUID batchId, UUID candidateSnapshotId);

    long countByBatchId(UUID batchId);
}
