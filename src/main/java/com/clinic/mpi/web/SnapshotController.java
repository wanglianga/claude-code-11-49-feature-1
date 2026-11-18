package com.clinic.mpi.web;

import com.clinic.mpi.domain.ImpactItem;
import com.clinic.mpi.domain.Snapshot;
import com.clinic.mpi.domain.SnapshotMapping;
import com.clinic.mpi.service.ImpactService;
import com.clinic.mpi.service.SnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "业务快照", description = "快照发布、查询与候选快照确认；下游只读已发布快照")
@RestController
@RequestMapping("/api/snapshots")
public class SnapshotController {

    private final SnapshotService snapshotService;
    private final ImpactService impactService;

    public SnapshotController(SnapshotService snapshotService, ImpactService impactService) {
        this.snapshotService = snapshotService;
        this.impactService = impactService;
    }

    @Operation(summary = "发布快照", description = "业务运行启动时固定当前图谱版本（APPOINTMENT/SETTLEMENT/LAB/ANALYTICS）")
    @PostMapping
    public Snapshot publish(@Valid @RequestBody Dtos.PublishSnapshotRequest req,
                            @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        return snapshotService.publish(
                Snapshot.BusinessType.valueOf(req.businessType().toUpperCase()), req.label(), operator);
    }

    @Operation(summary = "快照列表")
    @GetMapping
    public List<Snapshot> list() {
        return snapshotService.list();
    }

    @Operation(summary = "快照详情", description = "含影响条目")
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        Snapshot snapshot = snapshotService.get(id);
        List<ImpactItem> impacts = impactService.ofSnapshot(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshot", snapshot);
        body.put("impacts", impacts);
        return body;
    }

    @Operation(summary = "快照映射清单", description = "物化的 来源身份→主体 映射，可复核")
    @GetMapping("/{id}/mappings")
    public List<SnapshotMapping> mappings(@PathVariable UUID id) {
        return snapshotService.mappingsOf(id);
    }

    @Operation(summary = "通过快照解析归属", description = "仅 PUBLISHED 快照可读；候选快照返回 409")
    @GetMapping("/{id}/resolve")
    public Dtos.ResolveResponse resolve(@PathVariable UUID id,
                                        @RequestParam String sourceSystem,
                                        @RequestParam String sourcePatientId) {
        SnapshotMapping mapping = snapshotService.resolve(id, sourceSystem, sourcePatientId);
        Snapshot snapshot = snapshotService.get(id);
        return new Dtos.ResolveResponse(sourceSystem, sourcePatientId,
                mapping.identityId, mapping.masterId, snapshot.graphVersion);
    }

    @Operation(summary = "确认候选快照", description = "业务领取新快照：按业务规则处理影响项")
    @PostMapping("/{id}/confirm")
    public Snapshot confirm(@PathVariable UUID id,
                            @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        return impactService.confirmSnapshot(id, operator);
    }

    @Operation(summary = "驳回候选快照", description = "业务继续使用原快照，影响项作废")
    @PostMapping("/{id}/reject")
    public Snapshot reject(@PathVariable UUID id,
                           @RequestBody(required = false) Dtos.RejectSnapshotRequest req,
                           @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        return impactService.rejectSnapshot(id, req == null ? null : req.reason(), operator);
    }
}
