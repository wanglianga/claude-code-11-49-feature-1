package com.clinic.mpi.repo;

import com.clinic.mpi.domain.CandidateSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidateSnapshotRepo extends JpaRepository<CandidateSnapshot, UUID> {

    List<CandidateSnapshot> findByFrameStatusOrderByCreatedAtAsc(CandidateSnapshot.FrameStatus status);

    List<CandidateSnapshot> findAllByOrderByCreatedAtAsc();

    Optional<CandidateSnapshot> findByCandidateId(UUID candidateId);

    List<CandidateSnapshot> findByCandidateIdIn(List<UUID> candidateIds);

    long countByFrameStatus(CandidateSnapshot.FrameStatus status);

    List<CandidateSnapshot> findByStratumKey(String stratumKey);

    /** 同一身份对（不分方向）的历史候选快照，用于候选版本号递增。 */
    @Query("select s from CandidateSnapshot s where " +
            "(s.identityAId = :a and s.identityBId = :b) or " +
            "(s.identityAId = :b and s.identityBId = :a) order by s.candidateVersion asc")
    List<CandidateSnapshot> findByIdentityPair(@Param("a") UUID a, @Param("b") UUID b);
}
