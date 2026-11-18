package com.clinic.mpi.repo;

import com.clinic.mpi.domain.ReviewAnnotation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ReviewAnnotationRepo extends JpaRepository<ReviewAnnotation, UUID> {

    List<ReviewAnnotation> findBySamplingUnitIdOrderByCreatedAtAsc(UUID samplingUnitId);

    @Query("select a from ReviewAnnotation a where a.samplingUnitId in " +
            "(select u.id from SamplingUnit u where u.batchId = :batchId) order by a.createdAt asc")
    List<ReviewAnnotation> findByBatchId(@Param("batchId") UUID batchId);

    long countBySamplingUnitId(UUID samplingUnitId);
}
