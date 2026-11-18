package com.clinic.mpi.service;

import com.clinic.mpi.domain.GraphState;
import com.clinic.mpi.domain.MpiLink;
import com.clinic.mpi.domain.MpiMaster;
import com.clinic.mpi.repo.GraphStateRepo;
import com.clinic.mpi.repo.MpiLinkRepo;
import com.clinic.mpi.repo.MpiMasterRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 时态关系图核心：图谱版本、身份→主体的时点解析。
 * 所有变更（合并/拆分）通过 {@link #lockForUpdate()} 串行化。
 */
@Service
public class GraphService {

    private final GraphStateRepo graphStateRepo;
    private final MpiLinkRepo linkRepo;
    private final MpiMasterRepo masterRepo;

    public GraphService(GraphStateRepo graphStateRepo, MpiLinkRepo linkRepo, MpiMasterRepo masterRepo) {
        this.graphStateRepo = graphStateRepo;
        this.linkRepo = linkRepo;
        this.masterRepo = masterRepo;
    }

    @Transactional(readOnly = true)
    public long currentVersion() {
        return graphStateRepo.findById(GraphState.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("图谱状态未初始化"))
                .version;
    }

    /** 锁定全局图谱状态行（SELECT ... FOR UPDATE），串行化所有图谱变更。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public GraphState lockForUpdate() {
        return graphStateRepo.lockSingleton()
                .orElseThrow(() -> new IllegalStateException("图谱状态未初始化"));
    }

    /** 当前生效的主体。 */
    @Transactional(readOnly = true)
    public UUID currentMasterOf(UUID identityId) {
        return linkRepo.findByIdentityIdAndValidToIsNull(identityId)
                .map(l -> l.masterId)
                .orElseThrow(() -> new NotFoundException("身份不存在或无生效链接: " + identityId));
    }

    /** 按图谱版本解析身份归属（业务时点解析）。 */
    @Transactional(readOnly = true)
    public UUID masterOfAt(UUID identityId, long version) {
        return linkRepo.resolveAt(identityId, version)
                .map(l -> l.masterId)
                .orElseThrow(() -> new NotFoundException(
                        "身份 " + identityId + " 在图谱版本 " + version + " 下不存在"));
    }

    @Transactional(readOnly = true)
    public List<MpiLink> currentLinksOfMaster(UUID masterId) {
        return linkRepo.findByMasterIdAndValidToIsNull(masterId);
    }

    @Transactional(readOnly = true)
    public List<MpiLink> linkHistoryOfIdentity(UUID identityId) {
        return linkRepo.findByIdentityIdOrderByValidFrom(identityId);
    }

    @Transactional(readOnly = true)
    public List<MpiLink> linkHistoryOfMaster(UUID masterId) {
        return linkRepo.findByMasterIdOrderByValidFrom(masterId);
    }

    @Transactional(readOnly = true)
    public List<MpiLink> allCurrentLinks() {
        return linkRepo.findByValidToIsNull();
    }

    @Transactional(readOnly = true)
    public MpiMaster getMaster(UUID masterId) {
        return masterRepo.findById(masterId)
                .orElseThrow(() -> new NotFoundException("主体不存在: " + masterId));
    }
}
