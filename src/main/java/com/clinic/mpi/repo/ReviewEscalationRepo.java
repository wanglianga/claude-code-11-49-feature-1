package com.clinic.mpi.repo;

import com.clinic.mpi.domain.ReviewEscalation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewEscalationRepo extends JpaRepository<ReviewEscalation, UUID> {

    List<ReviewEscalation> findBySamplingUnitIdOrderByCreatedAtAsc(UUID samplingUnitId);

    List<ReviewEscalation> findByOutcome(ReviewEscalation.Outcome outcome);
}
