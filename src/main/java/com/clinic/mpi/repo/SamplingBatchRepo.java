package com.clinic.mpi.repo;

import com.clinic.mpi.domain.SamplingBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SamplingBatchRepo extends JpaRepository<SamplingBatch, UUID> {

    List<SamplingBatch> findAllByOrderByCreatedAtDesc();

    Optional<SamplingBatch> findByCode(String code);

    boolean existsByCode(String code);
}
