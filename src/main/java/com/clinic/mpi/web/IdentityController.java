package com.clinic.mpi.web;

import com.clinic.mpi.domain.IdentityCorrection;
import com.clinic.mpi.domain.MpiLink;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.service.GraphService;
import com.clinic.mpi.service.IdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "来源身份", description = "来源系统身份接入、查询与证件更正")
@RestController
@RequestMapping("/api/source-identities")
public class IdentityController {

    private final IdentityService identityService;
    private final GraphService graphService;

    public IdentityController(IdentityService identityService, GraphService graphService) {
        this.identityService = identityService;
        this.graphService = graphService;
    }

    @Operation(summary = "接入来源身份", description = "登记来源系统就诊人身份，自动创建初始主体并触发候选匹配")
    @PostMapping
    public SourceIdentity ingest(@Valid @RequestBody Dtos.IngestRequest req,
                                 @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        return identityService.ingest(req.sourceSystem(), req.sourcePatientId(), req.fullName(),
                req.phone(), req.idCardNo(), req.gender(),
                req.birthDate() == null || req.birthDate().isBlank() ? null : LocalDate.parse(req.birthDate()));
    }

    @Operation(summary = "来源身份列表")
    @GetMapping
    public List<SourceIdentity> list() {
        return identityService.list();
    }

    @Operation(summary = "来源身份详情", description = "含当前归属主体、时态链接历史与更正记录")
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        SourceIdentity identity = identityService.get(id);
        List<MpiLink> links = graphService.linkHistoryOfIdentity(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identity", identity);
        body.put("currentMasterId", graphService.currentMasterOf(id));
        body.put("linkHistory", links);
        body.put("corrections", identityService.correctionsOf(id));
        return body;
    }

    @Operation(summary = "来源证件/字段更正",
            description = "更正证据字段并重跑匹配；已发布快照不回写，影响通过新决议与候选快照传递")
    @PostMapping("/{id}/corrections")
    public IdentityCorrection correct(@PathVariable UUID id,
                                      @Valid @RequestBody Dtos.CorrectionRequest req,
                                      @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        return identityService.correct(id, req.field(), req.newValue(), req.note(), operator);
    }
}
