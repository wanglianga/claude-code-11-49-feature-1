package com.clinic.mpi.service;

import com.clinic.mpi.domain.CandidateSnapshot;
import com.clinic.mpi.domain.ReviewAnnotation;
import com.clinic.mpi.domain.ReviewEscalation;
import com.clinic.mpi.domain.SamplingBatch;
import com.clinic.mpi.domain.SamplingUnit;
import com.clinic.mpi.repo.CandidateSnapshotRepo;
import com.clinic.mpi.repo.ReviewAnnotationRepo;
import com.clinic.mpi.repo.ReviewEscalationRepo;
import com.clinic.mpi.repo.SamplingBatchRepo;
import com.clinic.mpi.repo.SamplingUnitRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 质量估计模块：基于抽样批次的审核标签产出加权质量报告。
 *
 * <p>估计原则：</p>
 * <ul>
 *   <li>概率样本按入框权重 {@code N_h/n_h} 还原总体——分层抽样刻意多抽的边界样本
 *       不能被当成全体比例；补录样本（非概率）单独报告，不进入总体估计；</li>
 *   <li>精确率 = 加权确认 / 加权（确认+拒绝），暂不能判定不计入分母而进入待定率；</li>
 *   <li>待定率 = 加权待定 / 加权已审；候选覆盖 = 已审加权质量 / 抽样框总量 N；</li>
 *   <li>SRS 用 Wilson Score 区间；分层抽样用层内方差 + 有限总体修正的 Wald 区间，
 *       精确率（域估计）采用有效样本量 Wilson 近似，区间方法在报告中注明；</li>
 *   <li>报告只含聚合量，不输出任何个案明细，并单列未审核范围与各来源不确定性。</li>
 * </ul>
 */
@Service
public class QualityService {

    private final SamplingUnitRepo unitRepo;
    private final SamplingBatchRepo batchRepo;
    private final CandidateSnapshotRepo snapshotRepo;
    private final ReviewAnnotationRepo annotationRepo;
    private final ReviewEscalationRepo escalationRepo;

    public QualityService(SamplingUnitRepo unitRepo, SamplingBatchRepo batchRepo,
                          CandidateSnapshotRepo snapshotRepo, ReviewAnnotationRepo annotationRepo,
                          ReviewEscalationRepo escalationRepo) {
        this.unitRepo = unitRepo;
        this.batchRepo = batchRepo;
        this.snapshotRepo = snapshotRepo;
        this.annotationRepo = annotationRepo;
        this.escalationRepo = escalationRepo;
    }

    /** 批次质量报告（聚合，无个案明细）。 */
    @Transactional(readOnly = true)
    public QualityReport report(UUID batchId) {
        SamplingBatch batch = batchRepo.findById(batchId)
                .orElseThrow(() -> new NotFoundException("抽样批次不存在: " + batchId));
        List<SamplingUnit> units = unitRepo.findByBatchIdOrderByCreatedAtAsc(batchId);
        Map<UUID, CandidateSnapshot> snaps = new LinkedHashMap<>();
        snapshotRepo.findAllById(units.stream().map(u -> u.candidateSnapshotId).toList())
                .forEach(s -> snaps.put(s.id, s));

        List<LabeledUnit> probability = new ArrayList<>();
        List<LabeledUnit> supplemental = new ArrayList<>();
        int unreviewedCount = 0;
        for (SamplingUnit unit : units) {
            CandidateSnapshot snap = snaps.get(unit.candidateSnapshotId);
            String label = resolveLabel(unit);
            if (label == null) {
                unreviewedCount++;
            }
            LabeledUnit lu = new LabeledUnit(unit, snap, label);
            if (unit.supplemental) {
                supplemental.add(lu);
            } else {
                probability.add(lu);
            }
        }

        Overall overall = buildOverall(batch, probability, unreviewedCount);
        List<SegmentMetric> byCampus = buildSegments(probability, Segmentation.CAMPUS);
        List<SegmentMetric> byEvidence = buildSegments(probability, Segmentation.EVIDENCE);
        List<SegmentMetric> byScoreBand = buildSegments(probability, Segmentation.SCORE_BAND);
        List<SegmentMetric> byUncertainty = buildSegments(probability, Segmentation.UNCERTAINTY);
        SupplementalSummary supp = summarizeSupplemental(supplemental);

        return new QualityReport(batch.id, batch.code, batch.strategy.name(), batch.frameSize,
                batch.sampleSize, probability.size(), supplemental.size(), unreviewedCount,
                overall, byCampus, byEvidence, byScoreBand, byUncertainty, supp,
                "精确率=加权确认/(加权确认+加权拒绝)；待定率=加权待定/加权已审；"
                        + "候选覆盖=已审加权质量/抽样框N；补录样本为非概率样本，单列且不入总体估计");
    }

    // ---- 总体指标 ----

    private Overall buildOverall(SamplingBatch batch, List<LabeledUnit> probability, int unreviewedCount) {
        List<LabeledUnit> reviewed = probability.stream().filter(u -> u.label != null).toList();
        double wConf = sumWeight(reviewed, ReviewAnnotation.Label.CONFIRMED.name());
        double wRej = sumWeight(reviewed, ReviewAnnotation.Label.REJECTED.name());
        double wInc = sumWeight(reviewed, ReviewAnnotation.Label.INCONCLUSIVE.name());
        double wReviewed = reviewed.stream().mapToDouble(u -> u.unit.inclusionWeight).sum();

        Metric precision = precisionMetric(batch, reviewed, wConf, wRej);
        Metric inconclusive = rateMetric(batch, reviewed, wInc, wReviewed);
        double coveragePoint = batch.frameSize == 0 ? 0 : wReviewed / batch.frameSize;
        Metric coverage = new Metric(coveragePoint, null, null,
                "已审加权质量/抽样框N（点估计）", wReviewed, batch.frameSize);

        double unreviewedWeightedShare = batch.frameSize == 0 ? 0
                : Math.max(0, (batch.frameSize - wReviewed) / batch.frameSize);
        UnreviewedSummary unreviewed = new UnreviewedSummary(
                unreviewedCount, unreviewedWeightedShare,
                "未审核范围不计入精确率/待定率，报告结论仅覆盖已审加权质量对应的总体部分");
        return new Overall(precision, inconclusive, coverage, unreviewed,
                reviewed.size(), wConf, wRej, wInc);
    }

    private Metric precisionMetric(SamplingBatch batch, List<LabeledUnit> reviewed,
                                   double wConf, double wRej) {
        double denom = wConf + wRej;
        if (denom == 0) {
            return new Metric(null, null, null, "无可裁决（确认/拒绝）样本，精确率不可估计", 0, 0);
        }
        double point = wConf / denom;
        List<LabeledUnit> decided = reviewed.stream()
                .filter(u -> ReviewAnnotation.Label.CONFIRMED.name().equals(u.label)
                        || ReviewAnnotation.Label.REJECTED.name().equals(u.label))
                .toList();
        if (batch.strategy == SamplingBatch.Strategy.SRS) {
            int n = decided.size();
            int x = (int) decided.stream()
                    .filter(u -> ReviewAnnotation.Label.CONFIRMED.name().equals(u.label)).count();
            double[] ci = wilson(x, n);
            return new Metric(point, ci[0], ci[1], "Wilson Score 95% 区间（SRS）", x, n);
        }
        double nEff = effectiveN(decided);
        double[] ci = wilson(point * nEff, nEff);
        return new Metric(point, ci[0], ci[1],
                "有效样本量 Wilson 近似 95% 区间（分层域估计，n_eff=" + round3(nEff) + "）",
                wConf, denom);
    }

    private Metric rateMetric(SamplingBatch batch, List<LabeledUnit> reviewed,
                              double wEvent, double wReviewed) {
        if (wReviewed == 0) {
            return new Metric(null, null, null, "无已审样本，待定率不可估计", 0, 0);
        }
        double point = wEvent / wReviewed;
        if (batch.strategy == SamplingBatch.Strategy.SRS) {
            int n = reviewed.size();
            int x = (int) reviewed.stream()
                    .filter(u -> ReviewAnnotation.Label.INCONCLUSIVE.name().equals(u.label)).count();
            double[] ci = wilson(x, n);
            return new Metric(point, ci[0], ci[1], "Wilson Score 95% 区间（SRS）", x, n);
        }
        double[] ci = stratifiedWald(batch, reviewed, ReviewAnnotation.Label.INCONCLUSIVE.name());
        return new Metric(point, ci[0], ci[1],
                "分层方差+有限总体修正 Wald 95% 区间", wEvent, wReviewed);
    }

    /**
     * 分层估计区间：V = Σ_h W_h² (1-f_h) s_h²/n_h，
     * 总体层权 W_h=N_h/N（N=批次抽样框），抽样比 f_h=n_h/N_h，
     * s_h² 为层内 0/1 指示的样本方差。
     */
    private double[] stratifiedWald(SamplingBatch batch, List<LabeledUnit> reviewed, String eventLabel) {
        Map<String, List<LabeledUnit>> byStratum = new LinkedHashMap<>();
        reviewed.forEach(u -> byStratum
                .computeIfAbsent(u.unit.stratumKey, k -> new ArrayList<>()).add(u));
        double variance = 0;
        for (List<LabeledUnit> stratumUnits : byStratum.values()) {
            int nh = stratumUnits.size();
            int nhFrame = stratumUnits.get(0).unit.stratumFrameCount;
            if (nhFrame == 0 || batch.frameSize == 0) {
                continue; // 补录/无框信息层不入方差
            }
            double wh = (double) nhFrame / batch.frameSize;
            double fh = (double) nh / nhFrame;
            double pH = stratumUnits.stream()
                    .filter(u -> eventLabel.equals(u.label)).count() / (double) nh;
            double s2 = nh <= 1 ? 0 : (double) nh / (nh - 1) * pH * (1 - pH);
            variance += wh * wh * (1 - fh) * s2 / nh;
        }
        double point = sumWeight(reviewed, eventLabel)
                / reviewed.stream().mapToDouble(u -> u.unit.inclusionWeight).sum();
        double margin = 1.96 * Math.sqrt(Math.max(0, variance));
        return new double[] {clamp(point - margin), clamp(point + margin)};
    }

    // ---- 分段指标（院区 / 分数区间 / 不确定度） ----

    private enum Segmentation {CAMPUS, EVIDENCE, SCORE_BAND, UNCERTAINTY}

    private List<SegmentMetric> buildSegments(List<LabeledUnit> probability, Segmentation mode) {
        Map<String, List<LabeledUnit>> groups = new LinkedHashMap<>();
        for (LabeledUnit lu : probability) {
            for (String key : segmentKeys(lu.snap, mode)) {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(lu);
            }
        }
        List<SegmentMetric> result = new ArrayList<>();
        for (Map.Entry<String, List<LabeledUnit>> e : groups.entrySet()) {
            List<LabeledUnit> reviewed = e.getValue().stream().filter(u -> u.label != null).toList();
            double wConf = sumWeight(reviewed, ReviewAnnotation.Label.CONFIRMED.name());
            double wRej = sumWeight(reviewed, ReviewAnnotation.Label.REJECTED.name());
            double wInc = sumWeight(reviewed, ReviewAnnotation.Label.INCONCLUSIVE.name());
            double wReviewed = reviewed.stream().mapToDouble(u -> u.unit.inclusionWeight).sum();
            double wTotal = e.getValue().stream().mapToDouble(u -> u.unit.inclusionWeight).sum();
            Metric precision = (wConf + wRej) == 0 ? null
                    : new Metric(wConf / (wConf + wRej), null, null,
                            "段内样本量小，仅给点估计", wConf, wConf + wRej);
            Metric inconclusive = wReviewed == 0 ? null
                    : new Metric(wInc / wReviewed, null, null, "段内点估计", wInc, wReviewed);
            double unreviewedShare = wTotal == 0 ? 0 : (wTotal - wReviewed) / wTotal;
            result.add(new SegmentMetric(e.getKey(), e.getValue().size(), reviewed.size(),
                    unreviewedShare, precision, inconclusive));
        }
        result.sort((a, b) -> a.segment().compareTo(b.segment()));
        return result;
    }

    private List<String> segmentKeys(CandidateSnapshot snap, Segmentation mode) {
        return switch (mode) {
            case CAMPUS -> List.of(snap.campusA, snap.campusB);
            case EVIDENCE -> List.of(snap.evidenceKey);
            case SCORE_BAND -> List.of(snap.scoreBand);
            case UNCERTAINTY -> List.of(snap.uncertaintyTier);
        };
    }

    private SupplementalSummary summarizeSupplemental(List<LabeledUnit> supplemental) {
        long confirmed = supplemental.stream()
                .filter(u -> ReviewAnnotation.Label.CONFIRMED.name().equals(u.label)).count();
        long rejected = supplemental.stream()
                .filter(u -> ReviewAnnotation.Label.REJECTED.name().equals(u.label)).count();
        long inconclusive = supplemental.stream()
                .filter(u -> ReviewAnnotation.Label.INCONCLUSIVE.name().equals(u.label)).count();
        long unreviewed = supplemental.stream().filter(u -> u.label == null).count();
        return new SupplementalSummary(supplemental.size(), confirmed, rejected, inconclusive,
                unreviewed, "补录为非概率样本，只做个案参考，不参与加权总体估计与置信区间");
    }

    // ---- 标签裁决 ----

    /**
     * 样本当前有效标签：
     * CLOSED 取最近一次复核裁决；LABELED 取一致标注；PENDING/复核中返回 null（未审核）。
     */
    private String resolveLabel(SamplingUnit unit) {
        if (unit.reviewStatus == SamplingUnit.ReviewStatus.CLOSED) {
            return escalationRepo.findBySamplingUnitIdOrderByCreatedAtAsc(unit.id).stream()
                    .filter(e -> e.outcome != ReviewEscalation.Outcome.PENDING)
                    .reduce((a, b) -> b)
                    .map(e -> e.outcome.name())
                    .orElse(null);
        }
        if (unit.reviewStatus == SamplingUnit.ReviewStatus.LABELED) {
            List<ReviewAnnotation> annotations =
                    annotationRepo.findBySamplingUnitIdOrderByCreatedAtAsc(unit.id);
            long distinct = annotations.stream().map(a -> a.label).distinct().count();
            return distinct == 1 ? annotations.get(0).label.name() : null;
        }
        return null;
    }

    private double sumWeight(List<LabeledUnit> units, String label) {
        return units.stream().filter(u -> label.equals(u.label))
                .mapToDouble(u -> u.unit.inclusionWeight).sum();
    }

    /** Kish 有效样本量：(Σw)²/Σw²。 */
    private double effectiveN(List<LabeledUnit> units) {
        double sumW = units.stream().mapToDouble(u -> u.unit.inclusionWeight).sum();
        double sumW2 = units.stream()
                .mapToDouble(u -> u.unit.inclusionWeight * u.unit.inclusionWeight).sum();
        return sumW2 == 0 ? 0 : (sumW * sumW) / sumW2;
    }

    /** Wilson Score 95% 区间。 */
    private double[] wilson(double x, double n) {
        if (n <= 0) {
            return new double[] {0, 0};
        }
        double phat = x / n;
        double z = 1.96;
        double z2 = z * z;
        double denom = 1 + z2 / n;
        double center = (phat + z2 / (2 * n)) / denom;
        double margin = z * Math.sqrt((phat * (1 - phat) + z2 / (4 * n)) / n) / denom;
        return new double[] {clamp(center - margin), clamp(center + margin)};
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    // ---- 报告 DTO（仅聚合量，无个案明细） ----

    private record LabeledUnit(SamplingUnit unit, CandidateSnapshot snap, String label) {
    }

    public record Metric(Double point, Double ciLow, Double ciHigh, String method,
                         double eventMass, double basisMass) {
    }

    public record UnreviewedSummary(int count, double weightedShare, String note) {
    }

    public record Overall(Metric precision, Metric inconclusiveRate, Metric candidateCoverage,
                          UnreviewedSummary unreviewed, int reviewedSampleCount,
                          double weightedConfirmed, double weightedRejected, double weightedInconclusive) {
    }

    public record SegmentMetric(String segment, int sampleCount, int reviewedCount,
                                double unreviewedShare, Metric precision, Metric inconclusiveRate) {
    }

    public record SupplementalSummary(long total, long confirmed, long rejected,
                                      long inconclusive, long unreviewed, String note) {
    }

    public record QualityReport(UUID batchId, String batchCode, String strategy, int frameSize,
                                int sampleSize, int probabilitySampleCount, int supplementalSampleCount,
                                int unreviewedSampleCount, Overall overall,
                                List<SegmentMetric> byCampus, List<SegmentMetric> byEvidenceCombination,
                                List<SegmentMetric> byScoreBand, List<SegmentMetric> byUncertainty,
                                SupplementalSummary supplemental, String estimationBasis) {
    }
}
