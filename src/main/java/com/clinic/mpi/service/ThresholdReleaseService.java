package com.clinic.mpi.service;

import com.clinic.mpi.domain.ThresholdRelease;
import com.clinic.mpi.repo.ThresholdReleaseRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 阈值发布模块：独立持有匹配阈值状态，与候选生成 / 抽样 / 审核 / 质量估计模块解耦。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>新阈值通过发布流程生成新版本，{@code effectiveAfter} 之后创建的候选才适用；</li>
 *   <li>已发布业务快照与存量候选对不反写、不重算；</li>
 *   <li>同一时刻至多一个 PUBLISHED 版本生效；发布新版本自动退役旧版本（保留历史行）。</li>
 * </ul>
 */
@Service
public class ThresholdReleaseService {

    /** 内置基线阈值（与 MatchService 历史阈值一致），任何发布之前适用。 */
    public static final double BASELINE_THRESHOLD = 0.40;

    private final ThresholdReleaseRepo repo;

    public ThresholdReleaseService(ThresholdReleaseRepo repo) {
        this.repo = repo;
    }

    /** 当前生效阈值（无发布版本时返回内置基线）。 */
    @Transactional(readOnly = true)
    public double currentThreshold() {
        return repo.findByStatus(ThresholdRelease.Status.PUBLISHED)
                .map(t -> t.matchThreshold)
                .orElse(BASELINE_THRESHOLD);
    }

    /** 当前生效阈值的版本号（无发布版本时返回 null，表示基线）。 */
    @Transactional(readOnly = true)
    public Integer currentVersionNo() {
        return repo.findByStatus(ThresholdRelease.Status.PUBLISHED)
                .map(t -> t.versionNo)
                .orElse(null);
    }

    /** 发布新阈值：退役当前 PUBLISHED 版本，新版本即时生效（只影响之后生成的候选）。 */
    @Transactional
    public ThresholdRelease publish(double threshold, String rationale, String operatorId) {
        if (threshold <= 0 || threshold >= 1) {
            throw new BadRequestException("匹配阈值必须位于 (0, 1) 开区间内，实际: " + threshold);
        }
        repo.findByStatus(ThresholdRelease.Status.PUBLISHED).ifPresent(old -> {
            old.status = ThresholdRelease.Status.RETIRED;
            old.retiredBy = operatorId;
            old.retiredAt = Instant.now();
            repo.save(old);
        });

        int nextVersion = repo.findAllByOrderByVersionNoDesc().stream()
                .mapToInt(t -> t.versionNo).max().orElse(0) + 1;
        ThresholdRelease release = new ThresholdRelease();
        release.versionNo = nextVersion;
        release.matchThreshold = threshold;
        release.status = ThresholdRelease.Status.PUBLISHED;
        release.rationale = rationale;
        release.createdBy = operatorId;
        release.publishedBy = operatorId;
        release.publishedAt = Instant.now();
        // 生效起点 = 发布时刻；MatchService 仅对之后创建的候选读取本阈值
        release.effectiveAfter = release.publishedAt;
        return repo.save(release);
    }

    @Transactional(readOnly = true)
    public List<ThresholdRelease> list() {
        return repo.findAllByOrderByVersionNoDesc();
    }

    @Transactional(readOnly = true)
    public ThresholdRelease get(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new NotFoundException("阈值发布不存在: " + id));
    }
}
