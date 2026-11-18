package com.clinic.mpi.repo;

import com.clinic.mpi.domain.MpiLink;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MpiLinkRepo extends JpaRepository<MpiLink, UUID> {

    Optional<MpiLink> findByIdentityIdAndValidToIsNull(UUID identityId);

    List<MpiLink> findByMasterIdAndValidToIsNull(UUID masterId);

    List<MpiLink> findByValidToIsNull();

    List<MpiLink> findByIdentityIdOrderByValidFrom(UUID identityId);

    List<MpiLink> findByMasterIdOrderByValidFrom(UUID masterId);

    /** 按业务时点（图谱版本）解析身份归属 */
    @Query("select l from MpiLink l where l.identityId = :identityId " +
            "and l.validFrom <= :version and (l.validTo is null or l.validTo > :version)")
    Optional<MpiLink> resolveAt(@Param("identityId") UUID identityId, @Param("version") long version);

    /** 解析某版本下主体的全部成员 */
    @Query("select l from MpiLink l where l.masterId = :masterId " +
            "and l.validFrom <= :version and (l.validTo is null or l.validTo > :version)")
    List<MpiLink> membersAt(@Param("masterId") UUID masterId, @Param("version") long version);

    /** 锁定一批主体的当前链接（合并/拆分时锁定相邻关系） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from MpiLink l where l.masterId in :masterIds and l.validTo is null")
    List<MpiLink> lockCurrentByMasterIds(@Param("masterIds") Collection<UUID> masterIds);
}
