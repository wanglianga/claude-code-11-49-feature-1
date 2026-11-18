package com.clinic.mpi.repo;

import com.clinic.mpi.domain.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AppointmentRepo extends JpaRepository<Appointment, UUID> {

    List<Appointment> findByIdentityId(UUID identityId);

    List<Appointment> findAllByOrderByCreatedAtDesc();
}
