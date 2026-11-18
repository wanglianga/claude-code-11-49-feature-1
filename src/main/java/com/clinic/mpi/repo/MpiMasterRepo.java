package com.clinic.mpi.repo;

import com.clinic.mpi.domain.MpiMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MpiMasterRepo extends JpaRepository<MpiMaster, UUID> {
}
