#!/usr/bin/env bash
# 匹配质量校准与抽样审计验收回放：
# 新院区同名高发、家庭共用手机号、低分真实同人、审核样本补录、分层抽样权重、
# 审核冲突复核、来源更正保留抽样框、候选撤销复核、阈值只作用后续候选、运营用户隔离。
# 本脚本自造 AUDIT- 前缀数据且不做合并/拆分（图谱版本保持不变），可与 verify.sh 任意顺序运行。
set -euo pipefail

BASE=${BASE_URL:-http://127.0.0.1:8080}
RUN=${RUN:-$(date +%s)}

pass=0; fail=0
ok()   { pass=$((pass+1)); echo "  ✔ $1"; }
bad()  { fail=$((fail+1)); echo "  ✘ $1"; }
check(){ if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望=$3 实际=$2）"; fi; }
check_ne(){ if [ "$2" != "$3" ]; then ok "$1"; else bad "$1（不应等于 $3）"; fi; }

get_h(){ # get_h <role> <path>
  curl -s -H "X-Operator-Role: $1" "$BASE$2";
}
post_h(){ # post_h <role> <path> <json> [operator]
  curl -s -X POST "$BASE$2" -H 'Content-Type: application/json' \
       -H "X-Operator-Role: $1" -H "X-Operator-Id: ${4:-system}" -d "$3";
}
code_h(){ # code_h <role> <method> <path> [json]
  local role=$1 method=$2 path=$3 json=${4:-}
  if [ "$method" = GET ]; then
    curl -s -o /tmp/audit-body.json -w "%{http_code}" -H "X-Operator-Role: $role" "$BASE$path"
  else
    curl -s -o /tmp/audit-body.json -w "%{http_code}" -X POST "$BASE$path" \
         -H 'Content-Type: application/json' -H "X-Operator-Role: $role" -d "$json"
  fi
}

ingest(){ # ingest <sourceSystem> <patientId> <name> <phone> <idCard>
  post_h ADMIN /api/source-identities \
    "{\"sourceSystem\":\"$1\",\"sourcePatientId\":\"$2\",\"fullName\":\"$3\",\"phone\":\"$4\",\"idCardNo\":\"$5\",\"gender\":\"M\",\"birthDate\":\"1990-01-01\"}" op_audit >/dev/null
}

echo "=========================================================="
echo " 匹配质量校准与抽样审计验收  BASE=$BASE  RUN=$RUN"
echo "=========================================================="

# ---- 自造候选：家庭共用手机号（HIGH 不确定度）×2、中等证据 ×2、强证据 ×2 ----
for i in 1 2; do
  ingest REG_T "AUDIT-$RUN-F$i-a" "家长$RUN-$i" "188$RUN$i" ""
  ingest REG_T "AUDIT-$RUN-F$i-b" "家长$RUN-${i}子" "188$RUN$i" ""
  ingest REG_T "AUDIT-$RUN-M$i-a" "中等$RUN-$i" "177$RUN$i" ""
  ingest REG_T "AUDIT-$RUN-M$i-b" "中等$RUN-$i" "177$RUN$i" ""
  ingest REG_T "AUDIT-$RUN-L$i-a" "强证$RUN-$i" "166$RUN$i" "IDC-$RUN-$i"
  ingest REG_T "AUDIT-$RUN-L$i-b" "强证$RUN-$i" "165$RUN$i" "IDC-$RUN-$i"
done

echo
echo "【场景1】候选生成快照固化当时证据与风险特征"
SNAP_COUNT=$(get_h REVIEWER /api/audit/candidate-snapshots | jq '[.[] | select(.evidenceJson | contains("AUDIT-'"$RUN"'"))] | length')
check "本脚本 6 个候选对均生成候选快照" "$SNAP_COUNT" "6"
FAM_SNAP=$(get_h REVIEWER /api/audit/candidate-snapshots \
  | jq -r '[.[] | select(.evidenceJson | contains("AUDIT-'"$RUN"-F1-a'"))][0].id')
FAM_RISK=$(get_h REVIEWER /api/audit/candidate-snapshots | jq -r --arg id "$FAM_SNAP" '.[] | select(.id==$id) | .riskFeaturesJson' | jq -r .familySharedPhone)
check "家庭共用手机号风险标记命中" "$FAM_RISK" "true"
FAM_TIER=$(get_h REVIEWER /api/audit/candidate-snapshots | jq -r --arg id "$FAM_SNAP" '.[] | select(.id==$id) | .uncertaintyTier')
check "家庭共用号码候选不确定度 HIGH" "$FAM_TIER" "HIGH"

echo
echo "【场景2】角色隔离：运营用户不可读个案，可读聚合"
check "OPS 访问候选快照列表被拒（403）" "$(code_h OPS GET /api/audit/candidate-snapshots)" "403"
check "OPS 访问审核工作台被拒（403）" "$(code_h OPS GET /api/audit/worklist)" "403"
check "OPS 编排抽样被拒（403）" "$(code_h OPS POST /api/audit/batches '{"strategy":"SRS","sampleSize":1}')" "403"
check "OPS 发布阈值被拒（403）" "$(code_h OPS POST /api/audit/thresholds '{"matchThreshold":0.5}')" "403"
check "REVIEWER 可访问审核工作台（200）" "$(code_h REVIEWER GET /api/audit/worklist)" "200"

echo
echo "【场景3】SRS 随机抽样 + 加权精确率/置信区间/候选覆盖"
BATCH1=$(post_h ADMIN /api/audit/batches \
  "{\"strategy\":\"SRS\",\"sampleSize\":100,\"seed\":$RUN,\"note\":\"审计回放 SRS 普查批次\"}" op_audit | jq -r .id)
FRAME_N=$(get_h REVIEWER /api/audit/batches/$BATCH1 | jq -r .frameSize)
UNIT_N=$(get_h REVIEWER /api/audit/batches/$BATCH1/units | jq 'length')
check "普查批次样本量等于抽样框 N" "$UNIT_N" "$FRAME_N"
WEIGHT_OK=$(get_h REVIEWER /api/audit/batches/$BATCH1/units | jq --argjson n "$UNIT_N" --argjson N "$FRAME_N" \
  '[.[] | select((.inclusionWeight - ($N/$n)) | fabs > 1e-9)] | length')
check "SRS 等权 N/n 固化到每个样本" "$WEIGHT_OK" "0"

# 取本脚本的 4 个样本：3 确认 + 1 拒绝（等权 → 精确率 0.75）
mapfile -t OWN_UNITS < <(jq -nr --slurpfile snaps <(get_h REVIEWER /api/audit/candidate-snapshots) --slurpfile units <(get_h REVIEWER /api/audit/batches/$BATCH1/units) \
  '$snaps[0] | map(select(.evidenceJson | contains("AUDIT-'"$RUN"'")) | .id) as $snapIds
   | $units[0] | map(select(.candidateSnapshotId as $sid | $snapIds | index($sid)) | select(.reviewStatus=="PENDING")) | .[0:4] | .[].id')
check "筛出本脚本 4 个待审样本" "${#OWN_UNITS[@]}" "4"
post_h REVIEWER "/api/audit/units/${OWN_UNITS[0]}/annotations" '{"label":"REJECTED","note":"家庭共用号码，非同一人"}' rv_li >/dev/null
for idx in 1 2 3; do
  post_h REVIEWER "/api/audit/units/${OWN_UNITS[$idx]}/annotations" '{"label":"CONFIRMED","note":"当时证据支持同一就诊人"}' "rv_$idx" >/dev/null
done
REPORT=$(get_h OPS /api/audit/reports/$BATCH1)
check "运营可读聚合报告且加权精确率=0.75" "$(echo "$REPORT" | jq -r .overall.precision.point)" "0.75"
check "报告给出精确率置信区间下界" "$(echo "$REPORT" | jq -r '.overall.precision.ciLow != null')" "true"
check "报告给出精确率置信区间上界" "$(echo "$REPORT" | jq -r '.overall.precision.ciHigh != null')" "true"
check "报告含未审核范围说明" "$(echo "$REPORT" | jq -r '.overall.unreviewed.note | length > 0')" "true"
check "报告含院区维度分段" "$(echo "$REPORT" | jq -r '.byCampus | length > 0')" "true"
check "报告含证据组合分段" "$(echo "$REPORT" | jq -r '.byEvidenceCombination | length > 0')" "true"
check "报告含分数区间分段" "$(echo "$REPORT" | jq -r '.byScoreBand | length > 0')" "true"
check "报告不含个案标识 candidateId" "$(echo "$REPORT" | jq -r 'has("candidateId")')" "false"
check "报告不含个案证据 evidenceJson" "$(echo "$REPORT" | jq -r 'has("evidenceJson")')" "false"

echo
echo "【场景4】分层抽样：权重 N_h/n_h 与加权总量恒等"
BATCH2=$(post_h ADMIN /api/audit/batches \
  "{\"strategy\":\"STRATIFIED\",\"sampleSize\":5,\"seed\":$RUN,\"note\":\"审计回放分层批次\"}" op_audit)
BATCH2_ID=$(echo "$BATCH2" | jq -r .id)
STRATA_FORMULA=$(get_h REVIEWER /api/audit/batches/$BATCH2_ID/units | jq \
  '[.[] | select((.inclusionWeight - (.stratumFrameCount/.stratumSampleCount)) | fabs > 1e-9)] | length')
check "每个分层样本权重=N_h/n_h" "$STRATA_FORMULA" "0"
WEIGHTED_SUM=$(get_h REVIEWER /api/audit/batches/$BATCH2_ID/units | jq '[.[].inclusionWeight] | add')
REPRESENTED=$(get_h REVIEWER /api/audit/batches/$BATCH2_ID/units \
  | jq -r 'group_by(.stratumKey) | map(.[0].stratumFrameCount) | add')
check "加权总量=被代表层框容量和" "$WEIGHTED_SUM" "$REPRESENTED"
check "报告策略标记为 STRATIFIED" "$(get_h OPS /api/audit/reports/$BATCH2_ID | jq -r .strategy)" "STRATIFIED"
check "批次分层元数据记录各层 N_h/n_h 权重" \
  "$(get_h REVIEWER /api/audit/batches/$BATCH2_ID | jq -r '.strataJson | fromjson | to_entries | map(select(.value.inclusionWeight > 0)) | length > 0')" "true"

echo
echo "【场景5】审核样本补录：非概率样本单列、不入总体加权"
SAMPLED_SNAPS=$(get_h REVIEWER /api/audit/batches/$BATCH2_ID/units | jq -r '.[].candidateSnapshotId' | jq -R . | jq -s .)
OUTSIDE_SNAP=$(get_h REVIEWER /api/audit/candidate-snapshots \
  | jq -r --argjson sampled "$SAMPLED_SNAPS" '[.[] | select(.frameStatus=="ACTIVE" and (.id as $id | $sampled | index($id) | not))][0].id')
SUPP=$(post_h REVIEWER /api/audit/batches/$BATCH2_ID/supplement "{\"candidateSnapshotId\":\"$OUTSIDE_SNAP\"}" rv_li)
check "补录样本标记 supplemental=true" "$(echo "$SUPP" | jq -r .supplemental)" "true"
check "补录样本权重为 0（不入总体估计）" "$(echo "$SUPP" | jq -r .inclusionWeight)" "0"
SUPP_UNIT=$(echo "$SUPP" | jq -r .id)
post_h REVIEWER "/api/audit/units/$SUPP_UNIT/annotations" '{"label":"REJECTED","note":"补录个案拒绝"}' rv_9 >/dev/null
check "质量报告单列补录样本数" "$(get_h OPS /api/audit/reports/$BATCH2_ID | jq -r .supplementalSampleCount)" "1"
check "补录拒绝计入补录汇总" "$(get_h OPS /api/audit/reports/$BATCH2_ID | jq -r .supplemental.rejected)" "1"

echo
echo "【场景6】审核员意见冲突 → 高级复核裁决"
CONFLICT_UNIT=$(get_h REVIEWER /api/audit/batches/$BATCH2_ID/units | jq -r '[.[] | select(.reviewStatus=="PENDING")][0].id')
post_h REVIEWER "/api/audit/units/$CONFLICT_UNIT/annotations" '{"label":"CONFIRMED","note":"审核员甲确认"}' rv_a >/dev/null
post_h REVIEWER "/api/audit/units/$CONFLICT_UNIT/annotations" '{"label":"REJECTED","note":"审核员乙拒绝"}' rv_b >/dev/null
check "意见冲突后样本转 ESCALATED_CONFLICT" \
  "$(get_h REVIEWER /api/audit/worklist | jq -r --arg id "$CONFLICT_UNIT" '.[] | select(.id==$id) | .reviewStatus')" "ESCALATED_CONFLICT"
check "两条冲突标注均保留" "$(get_h REVIEWER /api/audit/units/$CONFLICT_UNIT | jq -r '.annotations | length')" "2"
check "REVIEWER 无权高级裁决（403）" \
  "$(code_h REVIEWER POST "/api/audit/units/$CONFLICT_UNIT/adjudicate" '{"outcome":"REJECTED","note":"越权"}')" "403"
post_h ADMIN "/api/audit/units/$CONFLICT_UNIT/adjudicate" '{"outcome":"REJECTED","note":"按抽样时固化证据裁决拒绝"}' op_admin >/dev/null
check "ADMIN 裁决后样本关闭冻结" \
  "$(get_h REVIEWER /api/audit/batches/$BATCH2_ID/units | jq -r --arg id "$CONFLICT_UNIT" '.[] | select(.id==$id) | .reviewStatus')" "CLOSED"

echo
echo "【场景7】来源更正保留抽样框，固化证据不倒灌"
ingest REG_T "AUDIT-$RUN-C-a" "王芳$RUN" "147$RUN" ""
ingest REG_C "AUDIT-$RUN-C-b" "王芳芳$RUN" "147$RUN" ""
# 更正前快照仍在框（ACTIVE），先锁定旧快照 ID
CORRECT_SNAP=$(get_h REVIEWER /api/audit/candidate-snapshots \
  | jq -r '[.[] | select((.evidenceJson | contains("AUDIT-'"$RUN"-C-b'")) and .frameStatus=="ACTIVE")][0].id')
BATCH3=$(post_h ADMIN /api/audit/batches \
  "{\"strategy\":\"SRS\",\"sampleSize\":100,\"seed\":$RUN,\"note\":\"更正前普查\"}" op_audit | jq -r .id)
CORRECT_UNIT=$(get_h REVIEWER /api/audit/batches/$BATCH3/units \
  | jq -r --arg snap "$CORRECT_SNAP" '.[] | select(.candidateSnapshotId==$snap) | .id')
# 通过来源身份列表取 B 侧身份 ID
B_IDENTITY=$(curl -s "$BASE/api/source-identities" | jq -r --arg p "AUDIT-$RUN-C-b" '.[] | select(.sourcePatientId==$p) | .id')
post_h ADMIN "/api/source-identities/$B_IDENTITY/corrections" \
  '{"field":"idCardNo","newValue":"IDC-AFTER-'"$RUN"'","note":"抽样后补录证件号"}' op_audit >/dev/null
check "更正后样本转 ESCALATED_CORRECTED（抽样框保留）" \
  "$(get_h REVIEWER /api/audit/batches/$BATCH3/units | jq -r --arg id "$CORRECT_UNIT" '.[] | select(.id==$id) | .reviewStatus')" "ESCALATED_CORRECTED"
# 旧快照两侧证件号均为空白（null 或 ""），日后补录的证件号不得倒灌
check "旧快照固化证据不含日后证件号" \
  "$(get_h REVIEWER /api/audit/candidate-snapshots | jq -r --arg id "$CORRECT_SNAP" '.[] | select(.id==$id) | (.evidenceJson | fromjson) | (((.sideA.idCardNo // "") == "") and ((.sideB.idCardNo // "") == ""))')" "true"
post_h ADMIN "/api/audit/units/$CORRECT_UNIT/adjudicate" \
  '{"outcome":"CONFIRMED","note":"仅依抽样时姓名相似+手机+生日+性别确认同人"}' op_admin >/dev/null
check "按当时证据复核后样本关闭" \
  "$(get_h REVIEWER /api/audit/batches/$BATCH3/units | jq -r --arg id "$CORRECT_UNIT" '.[] | select(.id==$id) | .reviewStatus')" "CLOSED"

echo
echo "【场景8】候选抽样后被撤销：保留抽样框转复核"
WITHDRAW_SNAP=$(get_h REVIEWER /api/audit/candidate-snapshots \
  | jq -r '[.[] | select(.evidenceJson | contains("AUDIT-'"$RUN"-F2'"))][0].id')
WITHDRAW_UNIT=$(get_h REVIEWER /api/audit/batches/$BATCH3/units \
  | jq -r --arg snap "$WITHDRAW_SNAP" '.[] | select(.candidateSnapshotId==$snap) | .id')
WITHDRAW_CAND=$(get_h REVIEWER /api/audit/candidate-snapshots | jq -r --arg id "$WITHDRAW_SNAP" '.[] | select(.id==$id) | .candidateId')
curl -s -X POST "$BASE/api/candidates/$WITHDRAW_CAND/reject" -H 'Content-Type: application/json' \
  -H "X-Operator-Id: rv_li" -d '{"reason":"人工拒绝撤销"}' >/dev/null
check "候选撤销后样本转 ESCALATED_WITHDRAWN" \
  "$(get_h REVIEWER /api/audit/batches/$BATCH3/units | jq -r --arg id "$WITHDRAW_UNIT" '.[] | select(.id==$id) | .reviewStatus')" "ESCALATED_WITHDRAWN"
check "原候选快照仍保留（不删除）" \
  "$(get_h REVIEWER /api/audit/candidate-snapshots | jq -r --arg id "$WITHDRAW_SNAP" '[.[] | select(.id==$id)] | length')" "1"

echo
echo "【场景9】阈值发布只作用后续候选，不反写存量"
post_h ADMIN /api/audit/thresholds '{"matchThreshold":0.9,"rationale":"审计回放：提高阈值抑制同名误配"}' op_admin >/dev/null
ingest REG_T "AUDIT-$RUN-T1-a" "阈测$RUN-1" "144$RUN" ""
ingest REG_T "AUDIT-$RUN-T1-b" "阈测$RUN-1" "144$RUN" ""
T1_SNAP=$(get_h REVIEWER /api/audit/candidate-snapshots | jq -r '[.[] | select(.evidenceJson | contains("AUDIT-'"$RUN"-T1'"))] | length')
check "新阈值 0.9 下 0.60 分组合不再生成候选" "$T1_SNAP" "0"
post_h ADMIN /api/audit/thresholds '{"matchThreshold":0.3,"rationale":"审计回放：恢复阈值，仅作用后续候选"}' op_admin >/dev/null
ingest REG_T "AUDIT-$RUN-T2-a" "阈测$RUN-2" "143$RUN" ""
ingest REG_T "AUDIT-$RUN-T2-b" "阈测$RUN-2" "143$RUN" ""
T2_SNAP=$(get_h REVIEWER /api/audit/candidate-snapshots | jq -r '[.[] | select(.evidenceJson | contains("AUDIT-'"$RUN"-T2'"))][0]')
check "阈值恢复后同样证据组合生成候选并记录阈值版本" "$(echo "$T2_SNAP" | jq -r '.thresholdVersion != null')" "true"
check "阈值发布历史可查" "$(get_h OPS /api/audit/thresholds | jq 'length >= 2')" "true"

echo
echo "=========================================================="
echo " 审计验收回放完成：通过 $pass 项，失败 $fail 项"
echo "=========================================================="
[ "$fail" -eq 0 ]
