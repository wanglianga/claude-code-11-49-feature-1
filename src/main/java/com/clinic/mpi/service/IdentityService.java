package com.clinic.mpi.service;

import com.clinic.mpi.domain.CandidatePair;
import com.clinic.mpi.domain.IdentityCorrection;
import com.clinic.mpi.domain.MpiLink;
import com.clinic.mpi.domain.MpiMaster;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.repo.CandidatePairRepo;
import com.clinic.mpi.repo.IdentityCorrectionRepo;
import com.clinic.mpi.repo.MpiLinkRepo;
import com.clinic.mpi.repo.MpiMasterRepo;
import com.clinic.mpi.repo.SourceIdentityRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 来源身份接入与更正。接入即为身份创建初始主体（单例主体）并触发候选匹配；
 * 更正只更新证据字段并重跑匹配，不回写任何已发布快照。
 */
@Service
public class IdentityService {

    private final SourceIdentityRepo identityRepo;
    private final IdentityCorrectionRepo correctionRepo;
    private final CandidatePairRepo candidateRepo;
    private final MpiMasterRepo masterRepo;
    private final MpiLinkRepo linkRepo;
    private final GraphService graphService;
    private final MatchService matchService;

    public IdentityService(SourceIdentityRepo identityRepo, IdentityCorrectionRepo correctionRepo,
                           CandidatePairRepo candidateRepo, MpiMasterRepo masterRepo,
                           MpiLinkRepo linkRepo, GraphService graphService, MatchService matchService) {
        this.identityRepo = identityRepo;
        this.correctionRepo = correctionRepo;
        this.candidateRepo = candidateRepo;
        this.masterRepo = masterRepo;
        this.linkRepo = linkRepo;
        this.graphService = graphService;
        this.matchService = matchService;
    }

    /** 接入来源身份：创建初始主体 + 当前版本生效的链接，并触发候选匹配。 */
    @Transactional
    public SourceIdentity ingest(String sourceSystem, String sourcePatientId, String fullName,
                                 String phone, String idCardNo, String gender, LocalDate birthDate) {
        if (identityRepo.existsBySourceSystemAndSourcePatientId(sourceSystem, sourcePatientId)) {
            throw new ConflictException("来源身份已存在: " + sourceSystem + "/" + sourcePatientId);
        }
        SourceIdentity identity = new SourceIdentity();
        identity.sourceSystem = sourceSystem;
        identity.sourcePatientId = sourcePatientId;
        identity.fullName = fullName;
        identity.phone = phone;
        identity.idCardNo = idCardNo;
        identity.gender = gender;
        identity.birthDate = birthDate;
        identity.createdAt = Instant.now();
        identityRepo.save(identity);

        MpiMaster master = new MpiMaster();
        master.origin = MpiMaster.Origin.INITIAL.name();
        masterRepo.save(master);

        MpiLink link = new MpiLink();
        link.identityId = identity.id;
        link.masterId = master.id;
        link.validFrom = graphService.currentVersion();
        linkRepo.save(link);

        matchService.generateCandidatesFor(identity);
        return identity;
    }

    /**
     * 来源系统更正证据字段（如证件号）。
     * 只更新身份本身并重跑匹配：已发布快照的物化映射不回写；
     * 原有待处理候选对标记为 SUPERSEDED，按新证据重新生成候选。
     */
    @Transactional
    public IdentityCorrection correct(UUID identityId, String field, String newValue,
                                      String note, String operatorId) {
        SourceIdentity identity = identityRepo.findById(identityId)
                .orElseThrow(() -> new NotFoundException("来源身份不存在: " + identityId));

        IdentityCorrection correction = new IdentityCorrection();
        correction.identityId = identity.id;
        correction.field = field;
        correction.newValue = newValue;
        correction.note = note;
        correction.operatorId = operatorId;

        switch (field) {
            case "idCardNo" -> {
                correction.oldValue = identity.idCardNo;
                identity.idCardNo = newValue;
            }
            case "phone" -> {
                correction.oldValue = identity.phone;
                identity.phone = newValue;
            }
            case "fullName" -> {
                correction.oldValue = identity.fullName;
                identity.fullName = newValue;
            }
            case "gender" -> {
                correction.oldValue = identity.gender;
                identity.gender = newValue;
            }
            case "birthDate" -> {
                correction.oldValue = identity.birthDate == null ? null : identity.birthDate.toString();
                identity.birthDate = newValue == null ? null : LocalDate.parse(newValue);
            }
            default -> throw new BadRequestException("不支持更正的字段: " + field
                    + "（支持 idCardNo/phone/fullName/gender/birthDate）");
        }
        identityRepo.save(identity);
        correctionRepo.save(correction);

        // 旧证据生成的待处理候选对作废，按新证据重跑匹配
        for (CandidatePair pair : candidateRepo.findPendingInvolving(identity.id)) {
            pair.status = CandidatePair.Status.SUPERSEDED;
            pair.decidedAt = Instant.now();
            pair.decidedBy = operatorId;
            pair.decisionReason = "来源身份字段更正，候选对基于旧证据生成，自动作废";
            candidateRepo.save(pair);
        }
        matchService.generateCandidatesFor(identity);
        return correction;
    }

    @Transactional(readOnly = true)
    public SourceIdentity get(UUID id) {
        return identityRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("来源身份不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<SourceIdentity> list() {
        return identityRepo.findAll();
    }

    @Transactional(readOnly = true)
    public List<IdentityCorrection> correctionsOf(UUID identityId) {
        return correctionRepo.findByIdentityIdOrderByCreatedAtDesc(identityId);
    }
}
