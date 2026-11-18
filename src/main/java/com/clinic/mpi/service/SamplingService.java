package com.clinic.mpi.service;

import com.clinic.mpi.domain.CandidateSnapshot;
import com.clinic.mpi.domain.SamplingBatch;
import com.clinic.mpi.domain.SamplingUnit;
import com.clinic.mpi.repo.SamplingBatchRepo;
import com.clinic.mpi.repo.SamplingUnitRepo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 抽样编排模块：独立持有抽样批次与样本状态。
 *
 * <p>两种策略：</p>
 * <ul>
 *   <li>{@link SamplingBatch.Strategy#SRS} 简单随机抽样：按候选分数顺序在整个抽样框内随机抽取，
 *       实施简单、能监测整体变化，但审核量会浪费在低风险区间；</li>
 *   <li>{@link SamplingBatch.Strategy#STRATIFIED} 分层抽样：按不确定度层级与高影响来源所在分层
 *       （分层键 = 院区对 | 证据组合 | 分数区间 | 不确定度）优先分配样本量，
 *       更快发现关键误配；估计总体时必须使用层权重 N_h/n_h 还原。</li>
 * </ul>
 *
 * <p>抽样框在批次创建时冻结：之后候选撤销 / 来源更正不改变框与权重，只在样本上流转复核状态。
 * 每个样本固化入框权重 {@code N_h/n_h}，质量估计按权重还原总体，
 * 不允许把刻意多抽的边界样本当成全体比例。</p>
 */
@Service
public class SamplingService {

    private final SamplingBatchRepo batchRepo;
    private final SamplingUnitRepo unitRepo;
    private final CandidateSnapshotService snapshotService;
    private final ObjectMapper objectMapper;

    public SamplingService(SamplingBatchRepo batchRepo, SamplingUnitRepo unitRepo,
                           CandidateSnapshotService snapshotService,
                           ObjectMapper objectMapper) {
        this.batchRepo = batchRepo;
        this.unitRepo = unitRepo;
        this.snapshotService = snapshotService;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建抽样批次并冻结抽样框。
     *
     * @param strategy   SRS 或 STRATIFIED
     * @param sampleSize 目标样本量（框不足时取全部）
     * @param seed       随机种子（可复现）
     */
    @Transactional
    public SamplingBatch createBatch(SamplingBatch.Strategy strategy, int sampleSize,
                                     Long seed, String note, String operatorId) {
        if (sampleSize <= 0) {
            throw new BadRequestException("样本量必须为正整数");
        }
        List<CandidateSnapshot> frame = snapshotService.activeFrame();
        if (frame.isEmpty()) {
            throw new ConflictException("当前抽样框为空（无在框候选生成快照），无法编排抽样");
        }

        long actualSeed = seed != null ? seed : System.nanoTime();
        Random random = new Random(actualSeed);

        SamplingBatch batch = new SamplingBatch();
        batch.code = "BATCH-" + Instant.now().toEpochMilli() + "-" + Math.abs((int) actualSeed);
        batch.strategy = strategy;
        batch.frameSize = frame.size();
        batch.seed = actualSeed;
        batch.note = note;
        batch.createdBy = operatorId;

        Map<String, StratumStat> strataStats = new LinkedHashMap<>();
        List<SamplingUnit> units = new ArrayList<>();
        int target = Math.min(sampleSize, frame.size());

        if (strategy == SamplingBatch.Strategy.SRS) {
            List<CandidateSnapshot> shuffled = new ArrayList<>(frame);
            java.util.Collections.shuffle(shuffled, random);
            List<CandidateSnapshot> picked = shuffled.subList(0, target);
            double weight = (double) frame.size() / target;
            for (CandidateSnapshot snap : picked) {
                units.add(buildUnit(batch, snap, "ALL(SRS)", weight, frame.size(), target, false));
            }
            strataStats.put("ALL(SRS)", new StratumStat(frame.size(), target, weight));
        } else {
            // 按分层键分组，高不确定度层排在前面（样本量紧张时优先保底）
            Map<String, List<CandidateSnapshot>> strata = new LinkedHashMap<>();
            frame.stream().sorted(Comparator.comparingInt(s -> tierRank(s.uncertaintyTier)))
                    .forEach(s -> strata.computeIfAbsent(s.stratumKey, k -> new ArrayList<>()).add(s));
            Map<String, Integer> allocation = allocate(strata, target);
            for (Map.Entry<String, List<CandidateSnapshot>> entry : strata.entrySet()) {
                String key = entry.getKey();
                List<CandidateSnapshot> pool = new ArrayList<>(entry.getValue());
                java.util.Collections.shuffle(pool, random);
                int nH = allocation.get(key);
                double weight = (double) pool.size() / nH;
                for (CandidateSnapshot snap : pool.subList(0, nH)) {
                    units.add(buildUnit(batch, snap, key, weight, pool.size(), nH, false));
                }
                strataStats.put(key, new StratumStat(pool.size(), nH, weight));
            }
        }

        batch.sampleSize = units.size();
        batch.strataJson = toJson(strataStats);
        SamplingBatch saved = batchRepo.save(batch);
        units.forEach(u -> u.batchId = saved.id);
        unitRepo.saveAll(units);
        return saved;
    }

    /**
     * 审核样本补录：把框内未抽中的快照追加到批次。
     * 补录样本是非概率样本，{@code supplemental=true} 且权重记 0，
     * 质量估计时与概率样本分开报告，不允许混入加权总体估计。
     */
    @Transactional
    public SamplingUnit supplement(UUID batchId, UUID candidateSnapshotId, String operatorId) {
        SamplingBatch batch = getBatch(batchId);
        if (batch.status != SamplingBatch.Status.OPEN) {
            throw new ConflictException("批次已关闭，不能补录样本");
        }
        CandidateSnapshot snap = snapshotService.get(candidateSnapshotId);
        if (snap.frameStatus != CandidateSnapshot.FrameStatus.ACTIVE) {
            throw new ConflictException("候选快照已退出抽样框（状态 " + snap.frameStatus
                    + "），不能补录；复核流程请走高级裁决");
        }
        if (unitRepo.existsByBatchIdAndCandidateSnapshotId(batchId, candidateSnapshotId)) {
            throw new ConflictException("该候选快照已在本批次样本中，不能重复补录");
        }
        SamplingUnit unit = buildUnit(batch, snap, snap.stratumKey, 0d, 0, 0, true);
        unit.batchId = batch.id;
        SamplingUnit saved = unitRepo.save(unit);
        batch.sampleSize = (int) unitRepo.countByBatchId(batch.id);
        batchRepo.save(batch);
        return saved;
    }

    @Transactional
    public SamplingBatch closeBatch(UUID batchId, String operatorId) {
        SamplingBatch batch = getBatch(batchId);
        batch.status = SamplingBatch.Status.CLOSED;
        batch.closedAt = Instant.now();
        return batchRepo.save(batch);
    }

    @Transactional(readOnly = true)
    public SamplingBatch getBatch(UUID id) {
        return batchRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("抽样批次不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<SamplingBatch> listBatches() {
        return batchRepo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<SamplingUnit> unitsOf(UUID batchId) {
        getBatch(batchId);
        return unitRepo.findByBatchIdOrderByCreatedAtAsc(batchId);
    }

    @Transactional(readOnly = true)
    public SamplingUnit getUnit(UUID unitId) {
        return unitRepo.findById(unitId)
                .orElseThrow(() -> new NotFoundException("抽样单元不存在: " + unitId));
    }

    // ---- 内部方法 ----

    /** 不确定度层级排序秩：HIGH 优先于 MEDIUM、LOW。 */
    private static int tierRank(String tier) {
        return switch (tier) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }

    /**
     * 最大余数法分配（不会超过 target）：
     * 先按比例取整（floor），再把保底名额（每层至少 1，高不确定度层优先）与
     * 余量按小数部分大小依次补到层容量上限 N_h。
     */
    private Map<String, Integer> allocate(Map<String, List<CandidateSnapshot>> strata, int target) {
        int totalN = strata.values().stream().mapToInt(List::size).sum();
        Map<String, Integer> allocation = new LinkedHashMap<>();
        Map<String, Double> exact = new LinkedHashMap<>();
        for (Map.Entry<String, List<CandidateSnapshot>> e : strata.entrySet()) {
            double ideal = (double) e.getValue().size() * target / totalN;
            exact.put(e.getKey(), ideal);
            allocation.put(e.getKey(), Math.min(e.getValue().size(), (int) Math.floor(ideal)));
        }

        // 保底：有余量时让零配额层至少 1 个，高不确定度层、理想配额大者优先
        List<String> byPriority = strata.keySet().stream()
                .sorted(Comparator.comparingInt((String k) -> tierRank(strata.get(k).get(0).uncertaintyTier))
                        .thenComparing(k -> -exact.get(k)))
                .toList();
        int remaining = target - allocation.values().stream().mapToInt(Integer::intValue).sum();
        for (String key : byPriority) {
            if (remaining <= 0) {
                break;
            }
            if (allocation.get(key) == 0 && strata.get(key).size() > 0) {
                allocation.put(key, 1);
                remaining--;
            }
        }

        // 余量：按小数部分从大到小补，且不超过层容量
        List<String> byFraction = exact.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
        while (remaining > 0) {
            boolean progressed = false;
            for (String key : byFraction) {
                if (remaining <= 0) {
                    break;
                }
                if (allocation.get(key) < strata.get(key).size()) {
                    allocation.put(key, allocation.get(key) + 1);
                    remaining--;
                    progressed = true;
                }
            }
            if (!progressed) {
                break; // 所有层均已取满（target ≤ N 时不会发生）
            }
        }
        return allocation;
    }

    private SamplingUnit buildUnit(SamplingBatch batch, CandidateSnapshot snap, String stratumKey,
                                   double weight, int frameCount, int sampleCount, boolean supplemental) {
        SamplingUnit unit = new SamplingUnit();
        unit.batchId = batch.id;
        unit.candidateSnapshotId = snap.id;
        unit.candidateId = snap.candidateId;
        unit.stratumKey = stratumKey;
        unit.inclusionWeight = weight;
        unit.stratumFrameCount = frameCount;
        unit.stratumSampleCount = sampleCount;
        unit.supplemental = supplemental;
        return unit;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 分层统计（序列化进批次 strataJson）。 */
    public record StratumStat(int frameCount, int sampledCount, double inclusionWeight) {
    }
}
