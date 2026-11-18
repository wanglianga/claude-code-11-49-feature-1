package com.clinic.mpi.service;

import com.clinic.mpi.domain.MpiLink;
import com.clinic.mpi.domain.Snapshot;
import com.clinic.mpi.domain.SnapshotMapping;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.repo.MpiLinkRepo;
import com.clinic.mpi.repo.SnapshotMappingRepo;
import com.clinic.mpi.repo.SnapshotRepo;
import com.clinic.mpi.repo.SourceIdentityRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 业务快照：发布快照固定当前图谱版本并物化映射；候选快照由影响分析生成、
 * 待业务确认。下游只能读取 PUBLISHED 快照。
 */
@Service
public class SnapshotService {

    private final SnapshotRepo snapshotRepo;
    private final SnapshotMappingRepo mappingRepo;
    private final SourceIdentityRepo identityRepo;
    private final MpiLinkRepo linkRepo;
    private final GraphService graphService;

    public SnapshotService(SnapshotRepo snapshotRepo, SnapshotMappingRepo mappingRepo,
                           SourceIdentityRepo identityRepo, MpiLinkRepo linkRepo,
                           GraphService graphService) {
        this.snapshotRepo = snapshotRepo;
        this.mappingRepo = mappingRepo;
        this.identityRepo = identityRepo;
        this.linkRepo = linkRepo;
        this.graphService = graphService;
    }

    /** 业务运行启动时发布快照：固定当前图谱版本。 */
    @Transactional
    public Snapshot publish(Snapshot.BusinessType businessType, String label, String operatorId) {
        Snapshot snapshot = new Snapshot();
        snapshot.businessType = businessType;
        snapshot.graphVersion = graphService.currentVersion();
        snapshot.status = Snapshot.Status.PUBLISHED;
        snapshot.label = label;
        snapshot.createdBy = operatorId;
        snapshot.confirmedBy = operatorId;
        snapshot.confirmedAt = Instant.now();
        snapshotRepo.save(snapshot);
        materialize(snapshot);
        return snapshot;
    }

    /** 影响分析生成候选快照（固定决议产生的新版本），供业务确认。 */
    @Transactional
    public Snapshot createCandidate(Snapshot.BusinessType businessType, long graphVersion,
                                    UUID originResolutionId, String operatorId) {
        Snapshot snapshot = new Snapshot();
        snapshot.businessType = businessType;
        snapshot.graphVersion = graphVersion;
        snapshot.status = Snapshot.Status.CANDIDATE;
        snapshot.label = "决议 " + originResolutionId + " 触发的候选快照";
        snapshot.createdBy = operatorId;
        snapshot.originResolutionId = originResolutionId;
        snapshotRepo.save(snapshot);
        materialize(snapshot);
        return snapshot;
    }

    /** 物化指定图谱版本下全部来源身份的归属映射。 */
    @Transactional
    public void materialize(Snapshot snapshot) {
        for (SourceIdentity identity : identityRepo.findAll()) {
            linkRepo.resolveAt(identity.id, snapshot.graphVersion).ifPresent(link -> {
                SnapshotMapping mapping = new SnapshotMapping();
                mapping.snapshotId = snapshot.id;
                mapping.sourceSystem = identity.sourceSystem;
                mapping.sourcePatientId = identity.sourcePatientId;
                mapping.identityId = identity.id;
                mapping.masterId = link.masterId;
                mappingRepo.save(mapping);
            });
        }
    }

    /** 下游通过已发布快照解析归属；候选快照（尚在审核）不可读。 */
    @Transactional(readOnly = true)
    public SnapshotMapping resolve(UUID snapshotId, String sourceSystem, String sourcePatientId) {
        Snapshot snapshot = get(snapshotId);
        if (snapshot.status != Snapshot.Status.PUBLISHED) {
            throw new ConflictException("快照 " + snapshotId + " 状态为 " + snapshot.status
                    + "，尚在审核，下游不可读取");
        }
        return mappingRepo.findBySnapshotIdAndSourceSystemAndSourcePatientId(
                        snapshotId, sourceSystem, sourcePatientId)
                .orElseThrow(() -> new NotFoundException(
                        "快照中不存在映射: " + sourceSystem + "/" + sourcePatientId));
    }

    /** 某图谱版本下主体的主联系电话（取最早接入的成员身份）。 */
    @Transactional(readOnly = true)
    public String primaryPhoneAt(UUID masterId, long version) {
        List<UUID> memberIds = linkRepo.membersAt(masterId, version).stream()
                .map(l -> l.identityId).toList();
        return identityRepo.findAllById(memberIds).stream()
                .min(Comparator.comparing(i -> i.createdAt))
                .map(i -> i.phone).orElse(null);
    }

    @Transactional(readOnly = true)
    public Snapshot get(UUID id) {
        return snapshotRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("快照不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<Snapshot> list() {
        return snapshotRepo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<SnapshotMapping> mappingsOf(UUID snapshotId) {
        get(snapshotId);
        return mappingRepo.findBySnapshotId(snapshotId);
    }

    @Transactional
    public Snapshot save(Snapshot snapshot) {
        return snapshotRepo.save(snapshot);
    }
}
