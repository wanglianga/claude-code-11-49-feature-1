package com.clinic.mpi.web;

import com.clinic.mpi.domain.MpiLink;
import com.clinic.mpi.domain.MpiMaster;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.repo.SourceIdentityRepo;
import com.clinic.mpi.service.GraphService;
import com.clinic.mpi.service.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "时态关系图", description = "图谱版本、主体成员与按业务时点的身份解析")
@RestController
@RequestMapping("/api")
public class GraphController {

    private final GraphService graphService;
    private final SourceIdentityRepo identityRepo;

    public GraphController(GraphService graphService, SourceIdentityRepo identityRepo) {
        this.graphService = graphService;
        this.identityRepo = identityRepo;
    }

    @Operation(summary = "当前图谱版本", description = "决议提交前获取，作为 expectedGraphVersion")
    @GetMapping("/graph/version")
    public Map<String, Object> version() {
        return Map.of("graphVersion", graphService.currentVersion());
    }

    @Operation(summary = "当前图谱", description = "全部主体及其当前成员身份")
    @GetMapping("/graph/masters")
    public List<Map<String, Object>> masters() {
        Map<UUID, List<MpiLink>> byMaster = new LinkedHashMap<>();
        for (MpiLink link : graphService.allCurrentLinks()) {
            byMaster.computeIfAbsent(link.masterId, k -> new ArrayList<>()).add(link);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<UUID, List<MpiLink>> entry : byMaster.entrySet()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("masterId", entry.getKey());
            List<Map<String, Object>> members = new ArrayList<>();
            for (MpiLink link : entry.getValue()) {
                SourceIdentity identity = identityRepo.findById(link.identityId).orElse(null);
                Map<String, Object> member = new LinkedHashMap<>();
                member.put("identityId", link.identityId);
                if (identity != null) {
                    member.put("sourceSystem", identity.sourceSystem);
                    member.put("sourcePatientId", identity.sourcePatientId);
                    member.put("fullName", identity.fullName);
                }
                member.put("validFrom", link.validFrom);
                members.add(member);
            }
            view.put("members", members);
            result.add(view);
        }
        return result;
    }

    @Operation(summary = "主体详情", description = "含当前成员与全部时态链接历史")
    @GetMapping("/graph/masters/{id}")
    public Map<String, Object> master(@PathVariable UUID id) {
        MpiMaster master = graphService.getMaster(id);
        List<MpiLink> history = graphService.linkHistoryOfMaster(id);
        List<Map<String, Object>> links = new ArrayList<>();
        for (MpiLink link : history) {
            SourceIdentity identity = identityRepo.findById(link.identityId).orElse(null);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("identityId", link.identityId);
            if (identity != null) {
                item.put("sourceSystem", identity.sourceSystem);
                item.put("sourcePatientId", identity.sourcePatientId);
                item.put("fullName", identity.fullName);
            }
            item.put("validFrom", link.validFrom);
            item.put("validTo", link.validTo);
            item.put("resolutionId", link.resolutionId);
            links.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("master", master);
        body.put("links", links);
        return body;
    }

    @Operation(summary = "按时点解析身份归属",
            description = "atVersion 缺省为当前版本；用于复核某业务时点的身份归属")
    @GetMapping("/resolve")
    public Dtos.ResolveResponse resolve(@RequestParam String sourceSystem,
                                        @RequestParam String sourcePatientId,
                                        @RequestParam(required = false) Long atVersion) {
        SourceIdentity identity = identityRepo
                .findBySourceSystemAndSourcePatientId(sourceSystem, sourcePatientId)
                .orElseThrow(() -> new NotFoundException(
                        "来源身份不存在: " + sourceSystem + "/" + sourcePatientId));
        long version = atVersion == null ? graphService.currentVersion() : atVersion;
        UUID masterId = graphService.masterOfAt(identity.id, version);
        return new Dtos.ResolveResponse(sourceSystem, sourcePatientId, identity.id, masterId, version);
    }
}
