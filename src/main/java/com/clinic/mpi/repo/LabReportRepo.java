package com.clinic.mpi.repo;

import com.clinic.mpi.domain.LabReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LabReportRepo extends JpaRepository<LabReport, UUID> {

    List<LabReport> findByIdentityId(UUID identityId);

    List<LabReport> findAllByOrderByCreatedAtDesc();
}
