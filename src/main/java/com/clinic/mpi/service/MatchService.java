package com.clinic.mpi.service;

import com.clinic.mpi.domain.CandidatePair;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.repo.CandidatePairRepo;
import com.clinic.mpi.repo.SourceIdentityRepo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选匹配：按证据字段（证件号、手机号、姓名、出生日期、性别）加权评分，
 * 达到阈值生成候选重复对，等待人工决议。评分只产生"尚在审核的身份推断"，
 * 不直接改变图谱。
 */
@Service
public class MatchService {

    /** 候选对生成阈值 */
    public static final double THRESHOLD = 0.40;

    private final SourceIdentityRepo identityRepo;
    private final CandidatePairRepo candidateRepo;
    private final GraphService graphService;
    private final ObjectMapper objectMapper;

    public MatchService(SourceIdentityRepo identityRepo, CandidatePairRepo candidateRepo,
                        GraphService graphService, ObjectMapper objectMapper) {
        this.identityRepo = identityRepo;
        this.candidateRepo = candidateRepo;
        this.graphService = graphService;
        this.objectMapper = objectMapper;
    }

    /** 计算两身份的匹配评分与命中证据。 */
    public ScoreResult score(SourceIdentity a, SourceIdentity b) {
        double score = 0;
        List<String> evidence = new ArrayList<>();
        if (nonBlank(a.idCardNo) && a.idCardNo.equals(b.idCardNo)) {
            score += 0.55;
            evidence.add("证件号一致");
        }
        if (nonBlank(a.phone) && a.phone.equals(b.phone)) {
            score += 0.25;
            evidence.add("手机号一致");
        }
        if (nonBlank(a.fullName) && a.fullName.equals(b.fullName)) {
            score += 0.20;
            evidence.add("姓名一致");
        } else if (nonBlank(a.fullName) && nonBlank(b.fullName)
                && (a.fullName.contains(b.fullName) || b.fullName.contains(a.fullName))) {
            score += 0.10;
            evidence.add("姓名相似");
        }
        if (a.birthDate != null && a.birthDate.equals(b.birthDate)) {
            score += 0.10;
            evidence.add("出生日期一致");
        }
        if (nonBlank(a.gender) && a.gender.equals(b.gender)) {
            score += 0.05;
            evidence.add("性别一致");
        }
        return new ScoreResult(Math.min(1.0, score), evidence);
    }

    /**
     * 为新接入（或刚更正）的身份生成候选对。
     * 跳过：已属同一主体的身份、已存在待处理候选对的组合。
     */
    @Transactional
    public List<CandidatePair> generateCandidatesFor(SourceIdentity fresh) {
        List<CandidatePair> created = new ArrayList<>();
        for (SourceIdentity other : identityRepo.findAll()) {
            if (other.id.equals(fresh.id)) {
                continue;
            }
            if (sameMaster(fresh.id, other.id)) {
                continue;
            }
            if (candidateRepo.existsPendingBetween(fresh.id, other.id)) {
                continue;
            }
            ScoreResult result = score(fresh, other);
            // 浮点累加误差修正（如 0.25+0.10+0.05 实际为 0.39999...）
            if (result.score() >= THRESHOLD - 1e-9) {
                CandidatePair pair = new CandidatePair();
                pair.identityAId = fresh.id;
                pair.identityBId = other.id;
                pair.score = result.score();
                pair.evidence = toJson(result.evidence());
                created.add(candidateRepo.save(pair));
            }
        }
        return created;
    }

    private boolean sameMaster(java.util.UUID a, java.util.UUID b) {
        try {
            return graphService.currentMasterOf(a).equals(graphService.currentMasterOf(b));
        } catch (NotFoundException e) {
            return false;
        }
    }

    private String toJson(List<String> evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    public record ScoreResult(double score, List<String> evidence) {
    }
}
