package com.clinic.mpi.service;

import com.clinic.mpi.domain.CandidatePair;
import com.clinic.mpi.domain.GraphState;
import com.clinic.mpi.domain.MpiLink;
import com.clinic.mpi.domain.MpiMaster;
import com.clinic.mpi.domain.Resolution;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.repo.CandidatePairRepo;
import com.clinic.mpi.repo.MpiLinkRepo;
import com.clinic.mpi.repo.MpiMasterRepo;
import com.clinic.mpi.repo.ResolutionRepo;
import com.clinic.mpi.repo.SourceIdentityRepo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 身份决议：合并与拆分。
 *
 * <p>并发控制：提交时携带操作员所见的图谱版本 expectedGraphVersion；
 * 事务内先对全局图谱状态行加写锁（先提交者锁定该对及其相邻关系），
 * 再校验版本——版本不一致即拒绝（409），后提交者必须重新检查当前图谱，
 * 因此不会形成一人同时并入两个互斥主体。</p>
 */
@Service
public class ResolutionService {

    private final ResolutionRepo resolutionRepo;
    private final CandidatePairRepo candidateRepo;
    private final SourceIdentityRepo identityRepo;
    private final MpiMasterRepo masterRepo;
    private final MpiLinkRepo linkRepo;
    private final GraphService graphService;
    private final ImpactService impactService;
    private final ObjectMapper objectMapper;

    public ResolutionService(ResolutionRepo resolutionRepo, CandidatePairRepo candidateRepo,
                             SourceIdentityRepo identityRepo, MpiMasterRepo masterRepo,
                             MpiLinkRepo linkRepo, GraphService graphService,
                             ImpactService impactService, ObjectMapper objectMapper) {
        this.resolutionRepo = resolutionRepo;
        this.candidateRepo = candidateRepo;
        this.identityRepo = identityRepo;
        this.masterRepo = masterRepo;
        this.linkRepo = linkRepo;
        this.graphService = graphService;
        this.impactService = impactService;
        this.objectMapper = objectMapper;
    }

    /**
     * 合并：将两个身份所在的全部主体成员并入一个新主体。
     * 可通过候选对发起，也可直接指定两个身份（人工认定）。
     */
    @Transactional
    public Resolution merge(UUID candidateId, List<UUID> identityIds,
                            long expectedGraphVersion, String reason, String operatorId) {
        GraphState state = lockAndCheckVersion(expectedGraphVersion);

        CandidatePair pair = null;
        List<UUID> ids;
        if (candidateId != null) {
            pair = candidateRepo.lockById(candidateId)
                    .orElseThrow(() -> new NotFoundException("候选对不存在: " + candidateId));
            if (pair.status != CandidatePair.Status.PENDING) {
                throw new ConflictException("候选对已被处理（状态 " + pair.status
                        + "），请刷新后重新检查当前图谱", state.version);
            }
            ids = List.of(pair.identityAId, pair.identityBId);
        } else {
            if (identityIds == null || identityIds.size() != 2
                    || identityIds.get(0).equals(identityIds.get(1))) {
                throw new BadRequestException("合并需要指定两个不同的身份");
            }
            ids = identityIds;
        }

        UUID masterA = graphService.currentMasterOf(ids.get(0));
        UUID masterB = graphService.currentMasterOf(ids.get(1));
        if (masterA.equals(masterB)) {
            throw new ConflictException("两个身份已属同一主体 " + masterA + "，无需合并", state.version);
        }

        // 锁定两个主体的全部当前链接（相邻关系），关闭旧链接并整体迁入新主体
        List<MpiLink> currentLinks = linkRepo.lockCurrentByMasterIds(List.of(masterA, masterB));
        long newVersion = state.version + 1;

        Resolution resolution = new Resolution();
        resolution.type = Resolution.Type.MERGE;
        resolution.operatorId = operatorId;
        resolution.graphVersion = newVersion;
        resolution.expectedGraphVersion = expectedGraphVersion;
        resolution.reason = reason;
        resolution.candidateId = candidateId;
        resolutionRepo.save(resolution);

        MpiMaster newMaster = new MpiMaster();
        newMaster.origin = MpiMaster.Origin.MERGE.name();
        newMaster.createdByResolutionId = resolution.id;
        masterRepo.save(newMaster);

        List<ImpactService.ChangedIdentity> changes = new ArrayList<>();
        List<Map<String, Object>> moved = new ArrayList<>();
        for (MpiLink link : currentLinks) {
            link.validTo = newVersion;
            linkRepo.save(link);

            MpiLink newLink = new MpiLink();
            newLink.identityId = link.identityId;
            newLink.masterId = newMaster.id;
            newLink.validFrom = newVersion;
            newLink.resolutionId = resolution.id;
            linkRepo.save(newLink);

            SourceIdentity identity = identityRepo.findById(link.identityId)
                    .orElseThrow(() -> new NotFoundException("来源身份不存在: " + link.identityId));
            changes.add(new ImpactService.ChangedIdentity(
                    identity.id, identity.sourceSystem, identity.sourcePatientId,
                    link.masterId, newMaster.id));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("identityId", identity.id);
            entry.put("source", identity.sourceSystem + "/" + identity.sourcePatientId);
            entry.put("fromMaster", link.masterId);
            entry.put("toMaster", newMaster.id);
            moved.add(entry);
        }

        resolution.detail = toJson(Map.of(
                "newMaster", newMaster.id,
                "mergedMasters", List.of(masterA, masterB),
                "moved", moved));
        resolutionRepo.save(resolution);

        if (pair != null) {
            pair.status = CandidatePair.Status.MERGED;
            pair.decidedAt = Instant.now();
            pair.decidedBy = operatorId;
            pair.decisionReason = reason;
            candidateRepo.save(pair);
        }

        state.version = newVersion;
        impactService.onResolution(resolution, changes);
        return resolution;
    }

    /**
     * 拆分：把指定身份从主体中移出，组成一个新主体（误并纠正）。
     * 拆分集合必须是主体当前成员的非空真子集。
     */
    @Transactional
    public Resolution split(UUID masterId, List<UUID> splitOutIdentityIds,
                            long expectedGraphVersion, String reason, String operatorId) {
        GraphState state = lockAndCheckVersion(expectedGraphVersion);
        if (splitOutIdentityIds == null || splitOutIdentityIds.isEmpty()) {
            throw new BadRequestException("拆分需要指定至少一个移出身份");
        }
        graphService.getMaster(masterId);

        List<MpiLink> currentLinks = linkRepo.lockCurrentByMasterIds(List.of(masterId));
        Set<UUID> memberIds = new HashSet<>();
        for (MpiLink link : currentLinks) {
            memberIds.add(link.identityId);
        }
        Set<UUID> splitOut = new HashSet<>(splitOutIdentityIds);
        if (!memberIds.containsAll(splitOut)) {
            throw new BadRequestException("拆分身份必须全部属于主体 " + masterId + " 的当前成员");
        }
        if (splitOut.size() == memberIds.size()) {
            throw new BadRequestException("不能拆分出主体的全部成员（主体将为空）");
        }

        long newVersion = state.version + 1;
        Resolution resolution = new Resolution();
        resolution.type = Resolution.Type.SPLIT;
        resolution.operatorId = operatorId;
        resolution.graphVersion = newVersion;
        resolution.expectedGraphVersion = expectedGraphVersion;
        resolution.reason = reason;
        resolutionRepo.save(resolution);

        MpiMaster newMaster = new MpiMaster();
        newMaster.origin = MpiMaster.Origin.SPLIT.name();
        newMaster.createdByResolutionId = resolution.id;
        masterRepo.save(newMaster);

        List<ImpactService.ChangedIdentity> changes = new ArrayList<>();
        List<Map<String, Object>> moved = new ArrayList<>();
        for (MpiLink link : currentLinks) {
            if (!splitOut.contains(link.identityId)) {
                continue; // 留在原主体，链接不变
            }
            link.validTo = newVersion;
            linkRepo.save(link);

            MpiLink newLink = new MpiLink();
            newLink.identityId = link.identityId;
            newLink.masterId = newMaster.id;
            newLink.validFrom = newVersion;
            newLink.resolutionId = resolution.id;
            linkRepo.save(newLink);

            SourceIdentity identity = identityRepo.findById(link.identityId)
                    .orElseThrow(() -> new NotFoundException("来源身份不存在: " + link.identityId));
            changes.add(new ImpactService.ChangedIdentity(
                    identity.id, identity.sourceSystem, identity.sourcePatientId,
                    masterId, newMaster.id));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("identityId", identity.id);
            entry.put("source", identity.sourceSystem + "/" + identity.sourcePatientId);
            entry.put("fromMaster", masterId);
            entry.put("toMaster", newMaster.id);
            moved.add(entry);
        }

        resolution.detail = toJson(Map.of(
                "newMaster", newMaster.id,
                "splitFromMaster", masterId,
                "moved", moved));
        resolutionRepo.save(resolution);

        state.version = newVersion;
        impactService.onResolution(resolution, changes);
        return resolution;
    }

    /** 拒绝候选对（人工认定不是同一人，如家庭共用号码）。不改变图谱。 */
    @Transactional
    public CandidatePair rejectCandidate(UUID candidateId, String reason, String operatorId) {
        CandidatePair pair = candidateRepo.lockById(candidateId)
                .orElseThrow(() -> new NotFoundException("候选对不存在: " + candidateId));
        if (pair.status != CandidatePair.Status.PENDING) {
            throw new ConflictException("候选对已被处理（状态 " + pair.status + "）",
                    graphService.currentVersion());
        }
        pair.status = CandidatePair.Status.REJECTED;
        pair.decidedAt = Instant.now();
        pair.decidedBy = operatorId;
        pair.decisionReason = reason;
        return candidateRepo.save(pair);
    }

    private GraphState lockAndCheckVersion(long expectedGraphVersion) {
        GraphState state = graphService.lockForUpdate();
        if (state.version != expectedGraphVersion) {
            throw new ConflictException("图谱已变更：提交基于版本 " + expectedGraphVersion
                    + "，当前版本 " + state.version + "，请刷新后重新检查当前图谱再提交", state.version);
        }
        return state;
    }

    @Transactional(readOnly = true)
    public List<Resolution> list() {
        return resolutionRepo.findAllByOrderByGraphVersionDesc();
    }

    @Transactional(readOnly = true)
    public Resolution get(UUID id) {
        return resolutionRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("决议不存在: " + id));
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
