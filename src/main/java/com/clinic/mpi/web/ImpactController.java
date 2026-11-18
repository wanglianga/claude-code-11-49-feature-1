package com.clinic.mpi.web;

import com.clinic.mpi.domain.ImpactItem;
import com.clinic.mpi.service.ImpactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "影响清单", description = "身份变化对下游的影响项；调整清单等需人工确认")
@RestController
@RequestMapping("/api/impacts")
public class ImpactController {

    private final ImpactService impactService;

    public ImpactController(ImpactService impactService) {
        this.impactService = impactService;
    }

    @Operation(summary = "影响项列表", description = "可按 resolutionId / snapshotId 过滤")
    @GetMapping
    public List<ImpactItem> list(@RequestParam(required = false) UUID resolutionId,
                                 @RequestParam(required = false) UUID snapshotId) {
        if (resolutionId != null) {
            return impactService.ofResolution(resolutionId);
        }
        if (snapshotId != null) {
            return impactService.ofSnapshot(snapshotId);
        }
        return impactService.list();
    }

    @Operation(summary = "人工确认影响项", description = "调整清单、已定型报告、已发出提醒等人工处理后确认")
    @PostMapping("/{id}/ack")
    public ImpactItem ack(@PathVariable UUID id,
                          @RequestBody(required = false) Dtos.AckImpactRequest req,
                          @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        return impactService.ack(id, req == null ? null : req.note(), operator);
    }
}
