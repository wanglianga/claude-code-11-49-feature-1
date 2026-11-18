package com.clinic.mpi.web;

import com.clinic.mpi.domain.Resolution;
import com.clinic.mpi.service.ResolutionService;
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

import java.util.List;
import java.util.UUID;

@Tag(name = "人工决议", description = "合并 / 拆分决议；携带 expectedGraphVersion 做乐观并发控制")
@RestController
@RequestMapping("/api/resolutions")
public class ResolutionController {

    private final ResolutionService resolutionService;

    public ResolutionController(ResolutionService resolutionService) {
        this.resolutionService = resolutionService;
    }

    @Operation(summary = "合并决议",
            description = "将两个身份所在主体并入新主体。先提交者锁定该对及相邻关系；"
                    + "版本过期返回 409，后提交者必须重新检查当前图谱")
    @PostMapping("/merge")
    public Resolution merge(@Valid @RequestBody Dtos.MergeRequest req,
                            @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        return resolutionService.merge(req.candidateId(), req.identityIds(),
                req.expectedGraphVersion(), req.reason(), operator);
    }

    @Operation(summary = "拆分决议", description = "把指定身份从主体移出组成新主体（误并纠正）")
    @PostMapping("/split")
    public Resolution split(@Valid @RequestBody Dtos.SplitRequest req,
                            @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        return resolutionService.split(req.masterId(), req.splitOutIdentityIds(),
                req.expectedGraphVersion(), req.reason(), operator);
    }

    @Operation(summary = "决议历史")
    @GetMapping
    public List<Resolution> list() {
        return resolutionService.list();
    }

    @Operation(summary = "决议详情", description = "detail 为 JSON：涉及身份、源主体、目标主体")
    @GetMapping("/{id}")
    public Resolution get(@PathVariable UUID id) {
        return resolutionService.get(id);
    }
}
