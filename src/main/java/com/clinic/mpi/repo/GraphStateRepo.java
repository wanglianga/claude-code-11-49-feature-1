package com.clinic.mpi.repo;

import com.clinic.mpi.domain.GraphState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface GraphStateRepo extends JpaRepository<GraphState, Integer> {

    /** 悲观写锁：所有图谱变更（合并/拆分）串行化，先提交者锁定，后提交者做版本校验 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GraphState g where g.id = 1")
    Optional<GraphState> lockSingleton();
}
