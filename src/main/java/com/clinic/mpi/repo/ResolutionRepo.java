package com.clinic.mpi.repo;

import com.clinic.mpi.domain.Resolution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ResolutionRepo extends JpaRepository<Resolution, UUID> {

    List<Resolution> findAllByOrderByGraphVersionDesc();
}
