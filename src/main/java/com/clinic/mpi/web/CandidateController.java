package com.clinic.mpi.web;

import com.clinic.mpi.domain.CandidatePair;
import com.clinic.mpi.repo.CandidatePairRepo;
import com.clinic.mpi.service.NotFoundException;
import com.clinic.mpi.service.ResolutionService;
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

@Tag(name = "候选匹配", description = "匹配评分生成的候选重复对（尚在审核的身份推断）")
@RestController
@RequestMapping("/api/candidates")
public class CandidateController {

    private final CandidatePairRepo candidateRepo;
    private final ResolutionService resolutionService;

    public CandidateController(CandidatePairRepo candidateRepo, ResolutionService resolutionService) {
        this.candidateRepo = candidateRepo;
        this.resolutionService = resolutionService;
    }

    @Operation(summary = "候选对列表", description = "status=PENDING 看待处理候选（按评分降序）")
    @GetMapping
    public List<CandidatePair> list(@RequestParam(required = false) String status) {
        if (status != null && !status.isBlank()) {
            return candidateRepo.findByStatusOrderByScoreDesc(
                    CandidatePair.Status.valueOf(status.toUpperCase()));
        }
        return candidateRepo.findAllByOrderByCreatedAtDesc();
    }

    @Operation(summary = "候选对详情")
    @GetMapping("/{id}")
    public CandidatePair get(@PathVariable UUID id) {
        return candidateRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("候选对不存在: " + id));
    }

    @Operation(summary = "拒绝候选对", description = "人工认定不是同一人（如家庭共用号码），不改变图谱")
    @PostMapping("/{id}/reject")
    public CandidatePair reject(@PathVariable UUID id,
                                @RequestBody(required = false) Dtos.RejectCandidateRequest req,
                                @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        return resolutionService.rejectCandidate(id, req == null ? null : req.reason(), operator);
    }
}
