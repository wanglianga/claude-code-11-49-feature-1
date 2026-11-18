package com.clinic.mpi.repo;

import com.clinic.mpi.domain.CandidatePair;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidatePairRepo extends JpaRepository<CandidatePair, UUID> {

    List<CandidatePair> findByStatusOrderByScoreDesc(CandidatePair.Status status);

    List<CandidatePair> findAllByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CandidatePair p where p.id = :id")
    Optional<CandidatePair> lockById(@Param("id") UUID id);

    @Query("select p from CandidatePair p where p.status = 'PENDING' and " +
            "(p.identityAId = :identityId or p.identityBId = :identityId)")
    List<CandidatePair> findPendingInvolving(@Param("identityId") UUID identityId);

    @Query("select count(p) > 0 from CandidatePair p where p.status = 'PENDING' and " +
            "((p.identityAId = :a and p.identityBId = :b) or (p.identityAId = :b and p.identityBId = :a))")
    boolean existsPendingBetween(@Param("a") UUID a, @Param("b") UUID b);
}
