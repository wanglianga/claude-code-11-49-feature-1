package com.clinic.mpi.repo;

import com.clinic.mpi.domain.ThresholdRelease;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ThresholdReleaseRepo extends JpaRepository<ThresholdRelease, UUID> {

    Optional<ThresholdRelease> findByStatus(ThresholdRelease.Status status);

    List<ThresholdRelease> findAllByOrderByVersionNoDesc();

    boolean existsByVersionNo(int versionNo);
}
