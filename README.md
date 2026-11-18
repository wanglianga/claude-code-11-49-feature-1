# 就诊人主索引（MPI）合并拆分、快照稳定与匹配质量校准服务

面向连锁诊所的就诊人主索引服务。不同院区的挂号、检验、结算系统各自生成就诊人编号，
本服务负责：**来源身份接入 →候选匹配 → 人工决议（合并/拆分）→ 时态关系图 → 业务快照 → 影响清单**，
保证已确认的结算、检验报告和经营报表"当时引用了谁"永远可复现；并在冻结候选匹配与人工决策之上，
新增**匹配质量校准与抽样审计数据服务**：候选生成快照固化当时证据与风险特征，经随机/分层抽样与
授权人工核验产出加权精确率、待定率、候选覆盖与置信区间，回答"分数升高是模型变准还是新院区同名更多"。

## 核心设计

| 决策 | 实现 |
| --- | --- |
| 身份历史模型 | **时态关系图**：`mpi_link`（身份→主体）带生效图谱版本区间 `[validFrom, validTo)`，按业务时点解析身份 |
| 快照稳定 | 预约/结算/检验/分析启动时**发布快照固定图谱版本**，并把该版本的归属映射物化到 `snapshot_mapping`，读取快、可复核、永不回写 |
| 审核隔离 | 候选对、候选快照都是"尚在审核的身份推断"，下游只能读 `PUBLISHED` 快照（候选快照读取返回 409） |
| 并发决议 | 决议携带 `expectedGraphVersion`，事务内对全局图谱行加写锁：**先提交者锁定该对及相邻关系，后提交者收 409 必须重新检查**，不会形成一人并入两个互斥主体 |
| 身份更正 | 来源证件更正只更新证据字段并重跑匹配；**已发布快照不回写**，影响经新决议→影响分析→**候选快照**供业务确认 |
| 关账保护 | 已关账结算凭证**绝不改写**，身份拆分只生成 `ADJUSTMENT_LIST` 调整清单供财务线下处理；检验报告、预约提醒按各自业务规则领取新快照 |
| 候选生成快照 | 候选对生成即固化**当时可见证据**、证据组合、来源院区、分数区间与风险特征（姓名变更/家庭共用手机号/同名高发/跨院区），人工核验只评价当时证据，日后证件号不倒灌 |
| 抽样编排 | SRS 按分数随机抽样（简单、监测整体）；STRATIFIED 按不确定度/高影响来源分层优先抽样，样本固化权重 `N_h/n_h`，估计总体必须加权，过采样边界样本不当全体比例 |
| 审核标注 | 确认/拒绝/暂不能判定三标签，暂不能判定进待定率而非掩盖为精确；冲突、候选撤销、来源更正均**保留原抽样框**并转明确复核状态 |
| 质量报告 | 加权精确率、待定率、候选覆盖与置信区间（SRS 用 Wilson，分层用层方差+有限总体修正），**只出聚合量**，单列未审核范围、补录样本与各来源不确定性，运营用户看不到个案 |
| 阈值发布 | 阈值模块独立持有状态，新阈值**只作用后续候选**，不重算存量候选、不反写已发布业务快照，历史版本永久保留 |

技术栈：Java 17+（本地用 JDK 25 验证）、Spring Boot 3.5、Spring Data JPA、PostgreSQL、springdoc-openapi（Swagger UI）。

## 一键启动（Docker Compose）

```bash
docker compose up --build
```

启动后：应用 http://localhost:8080 ，Swagger UI http://localhost:8080/swagger-ui.html 。
Compose 包含 PostgreSQL 18 容器，首次启动自动建表并播种样例数据。
重置数据：`docker compose down -v && docker compose up --build`。

## 本地开发

前置：JDK 17+（本仓库在 JDK 25 下 验过）、Maven 3.9+、PostgreSQL（默认连接 `127.0.0.1:5432`，库/用户/密码 `ccdev`，可用环境变量覆盖）。

```bash
# 启动（自动建表 + 播种样例数据）
mvn spring-boot:run

# 自定义数据库连接
DB_HOST=127.0.0.1 DB_PORT=5432 DB_NAME=ccdev DB_USER=ccdev DB_PASS=ccdev mvn spring-boot:run
```

## 测试账号（操作员与角色）

业务接口通过请求头 `X-Operator-Id` 标识操作员（缺省 `system`）：

| 账号 | 用途 |
| --- | --- |
| `op_zhang` | 主索引操作员 1（合并/拆分/确认快照） |
| `op_li` | 主索引操作员 2（并发决议回放中的后提交者） |

审计接口（`/api/audit/**`）另需请求头 `X-Operator-Role`，缺省按最小权限 `OPS` 处理：

| 角色 | 权限 |
| --- | --- |
| `ADMIN` | 抽样编排、阈值发布、高级复核裁决（op_admin） |
| `REVIEWER` | 审核工作台、确认/拒绝/待定标注、审核样本补录（rv_a/rv_b/rv_li） |
| `OPS` | 普通运营用户：**只能读聚合质量报告与批次/阈值元数据**，候选快照、工作台、样本等个案明细一律 403 |

## 验证方式

### 1. 集成测试（20 个用例，真实 PostgreSQL）

```bash
# 准备测试库（首次）
psql -h 127.0.0.1 -U ccdev -d ccdev -c "CREATE DATABASE ccdev_test;"
# 运行（使用独立库 ccdev_test，create-drop，不影响开发数据）
mvn test
```

覆盖：接入与评分、合并版本递增与时点解析、过期版本 409 后重检成功、**双线程并发决议恰好一个成功**、
误并拆分恢复、证件更正不回写快照、关账只出调整清单、候选快照确认按业务规则生效、家庭共用号码拒绝合并；
以及校准审计 11 个用例：候选快照证据/风险固化、新院区同名高发、姓名变更风险、SRS 等权与加权精确率/置信区间、
分层 `N_h/n_h` 权重与加权总量恒等、审核冲突复核、来源更正保留抽样框且旧证据不倒灌、
候选撤销转复核、补录样本单列、阈值只作用后续候选、运营用户个案隔离。

### 2. 验收回放脚本（需服务已启动）

```bash
./scripts/verify.sh        # 主任务回放：45 项断言，要求全新播种数据（图谱版本 0）
./scripts/verify-audit.sh  # 校准审计回放：40 项断言，自造 AUDIT- 前缀数据、不做合并拆分，可与前者任意顺序运行
```

- `verify.sh`：多院重复挂号合并 → **误并拆分 + 关账后调整** → **来源证件更正** → **并发决议**（含家庭共用号码拒绝），
  最后校验关系链按版本可复现。
- `verify-audit.sh`：候选快照风险特征 → 角色隔离 → SRS 加权报告 → 分层权重 → 样本补录 →
  冲突裁决 → 来源更正保留抽样框/证据不倒灌 → 候选撤销复核 → 阈值只作用后续候选。全部通过时退出码为 0。

### 3. 手工回放（curl 示例）

```bash
# 查看待处理候选对（评分+证据）
curl -s 'http://127.0.0.1:8080/api/candidates?status=PENDING' | jq

# 取当前图谱版本 → 提交合并决议（先提交者锁定）
V=$(curl -s http://127.0.0.1:8080/api/graph/version | jq -r .graphVersion)
curl -s -X POST http://127.0.0.1:8080/api/resolutions/merge \
  -H 'Content-Type: application/json' -H 'X-Operator-Id: op_zhang' \
  -d "{\"candidateId\":\"<候选对ID>\",\"expectedGraphVersion\":$V,\"reason\":\"两院重复挂号\"}" | jq

# 后提交者持同一旧版本提交 → 409，响应携带 currentGraphVersion
# 查看影响清单与候选快照，业务确认领取
curl -s 'http://127.0.0.1:8080/api/impacts' | jq
curl -s -X POST http://127.0.0.1:8080/api/snapshots/<候选快照ID>/confirm -H 'X-Operator-Id: op_zhang'

# 按业务时点解析身份归属（历史可复现）
curl -s 'http://127.0.0.1:8080/api/resolve?sourceSystem=LAB_A&sourcePatientId=L-A-001&atVersion=0' | jq
```

### 重置样例数据（本地）

```bash
psql -h 127.0.0.1 -U ccdev -d ccdev -c "drop schema public cascade; create schema public;"
# 重启应用后会自动重新播种
```

## 样例数据（启动自动播种）

| 场景 | 数据 | 预期处理 |
| --- | --- | --- |
| 多院重复挂号 | 张三 `REG_A/P-A-001` × `REG_B/P-B-001`（证件号一致，评分 1.0） | 合并 |
| 误并素材 | 两个同名陈晨 `REG_A/P-A-002` × `REG_B/P-B-002`（仅姓名相同，低于候选阈值） | 人工误并 → 拆分回放 |
| 家庭共用号码 | 李四 × 李四中（共用手机号，评分 0.4） | 人工拒绝合并，快照标 `familySharedPhone` 高风险 |
| 证件更正 | 赵六 `LAB_A/L-A-001` 证件号末位录错（`...110` 应为 `...111`） | 更正 → 新候选 → 合并 |
| 新院区同名高发（校准） | 三个张伟 `REG_A/P-A-005`、`REG_C/P-C-001`、`REG_C/P-C-002`（新院区 C，同号同名 0.60 分对） | 快照标 `highNameCollision`，分层抽样优先 |
| 低分真实同人（校准） | 王芳 × 王芳芳 `REG_A/P-A-006` × `REG_C/P-C-006`（曾用名、无证件号，0.50 分候选） | 按当时证据可确认，验证低分区不能漏审 |

下游记录：陈晨B 预约（BOOKED）、李四中预约（BOOKED）、陈晨A 结算单（**已关账**，2026-10，¥880）、
赵六结算单（未关账，2026-11，¥1200）、陈晨A 血常规（未签发）、赵六 尿常规（未签发）。
候选生成快照随候选对自动产生，启动后共 7 个待处理候选对、7 份候选生成快照。

## API 概览（完整见 Swagger UI）

| 分组 | 接口 |
| --- | --- |
| 来源身份 | `POST/GET /api/source-identities`、`GET /api/source-identities/{id}`、`POST /api/source-identities/{id}/corrections` |
| 候选匹配 | `GET /api/candidates`、`GET /api/candidates/{id}`、`POST /api/candidates/{id}/reject` |
| 人工决议 | `POST /api/resolutions/merge`、`POST /api/resolutions/split`、`GET /api/resolutions[/{id}]` |
| 时态关系图 | `GET /api/graph/version`、`GET /api/graph/masters[/{id}]`、`GET /api/resolve?atVersion=` |
| 业务快照 | `POST /api/snapshots`、`GET /api/snapshots[/{id}]`、`GET /{id}/mappings`、`GET /{id}/resolve`、`POST /{id}/confirm`、`POST /{id}/reject` |
| 影响清单 | `GET /api/impacts`、`POST /api/impacts/{id}/ack` |
| 下游模拟 | `POST/GET /api/biz/appointments`、`POST /{id}/remind`、`POST/GET /api/biz/settlements`、`POST /{id}/close`、`POST/GET /api/biz/lab-reports`、`POST /{id}/finalize` |
| 校准-候选快照（REVIEWER/ADMIN） | `GET /api/audit/candidate-snapshots[/{id}]` |
| 校准-抽样编排（ADMIN 编排，REVIEWER 补录） | `POST /api/audit/batches`、`GET /api/audit/batches[/{id}]`、`GET /{id}/units`、`POST /{id}/supplement`、`POST /{id}/close` |
| 校准-审核标注（REVIEWER/ADMIN） | `GET /api/audit/worklist`、`GET /api/audit/units/{id}`、`POST /api/audit/units/{id}/annotations`、`POST /api/audit/units/{id}/adjudicate`（仅 ADMIN） |
| 校准-质量报告（各角色，仅聚合） | `GET /api/audit/reports/{batchId}` |
| 校准-阈值发布（ADMIN） | `POST /api/audit/thresholds`、`GET /api/audit/thresholds` |

### 校准审计 curl 示例

```bash
# ADMIN 创建分层抽样批次（按不确定度/高影响来源优先，样本带 N_h/n_h 权重）
curl -s -X POST http://127.0.0.1:8080/api/audit/batches \
  -H 'Content-Type: application/json' -H 'X-Operator-Role: ADMIN' -H 'X-Operator-Id: op_admin' \
  -d '{"strategy":"STRATIFIED","sampleSize":10,"seed":42,"note":"新院区接入后首轮校准"}' | jq

# REVIEWER 在工作台取样本并标注（只评价候选生成时固化证据）
curl -s -X POST http://127.0.0.1:8080/api/audit/units/<样本ID>/annotations \
  -H 'Content-Type: application/json' -H 'X-Operator-Role: REVIEWER' -H 'X-Operator-Id: rv_a' \
  -d '{"label":"CONFIRMED","note":"当时姓名+手机+生日+性别一致，确认同一就诊人"}'

# OPS 普通运营用户读聚合报告（无个案明细），读个案返回 403
curl -s -H 'X-Operator-Role: OPS' http://127.0.0.1:8080/api/audit/reports/<批次ID> | jq
curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Operator-Role: OPS' \
  http://127.0.0.1:8080/api/audit/candidate-snapshots   # 403

# ADMIN 发布新阈值：只作用之后生成的候选，存量候选与已发布快照不反写
curl -s -X POST http://127.0.0.1:8080/api/audit/thresholds \
  -H 'Content-Type: application/json' -H 'X-Operator-Role: ADMIN' -H 'X-Operator-Id: op_admin' \
  -d '{"matchThreshold":0.55,"rationale":"校准显示 0.4~0.55 区间误配率偏高，收紧阈值"}'
```

## 目录结构

```
src/main/java/com/clinic/mpi/
├── domain/      # 19 个实体：主索引 13 个 + 校准 6 个
│                #   候选生成快照、抽样批次、抽样单元、审核标注、复核裁决、阈值发布
├── repo/        # Spring Data JPA 仓库（含悲观写锁查询）
├── service/     # Match/Resolution/Snapshot/Impact/Biz/Identity/Graph
│                #   + CandidateSnapshot/Sampling/Review/Quality/ThresholdRelease 校准五模块
├── web/         # 8 个 REST 控制器（含 AuditController 角色隔离）+ 全局异常处理（403/409）
└── seed/        # 样例数据播种（幂等）
scripts/verify.sh        # 主任务验收回放（45 项断言）
scripts/verify-audit.sh  # 校准审计验收回放（39 项断言）
```

## 校准模块如何回应治理组的问题

- **分数升高是模型变准还是同名更多？** 质量报告按院区、证据组合、分数区间、不确定度分层给出
  加权精确率与置信区间；新院区 C 的同名高发通过 `highNameCollision` 风险特征与高影响来源标记单独可见，
  不会与整体精确率混为一谈。
- **漏合并/误合并如何扭曲复诊统计？** 待定率（暂不能判定）、候选覆盖率与未审核加权占比明确列出
  "可验证表现"与"未审核范围"，报告结论只覆盖已审加权质量对应的总体部分，不以少量人工确认替代总体证据。
- **审核会不会被日后数据污染？** 候选生成时证据字段值整体固化进快照；来源更正、候选撤销只触发
  复核状态流转，审核与裁决均不读取当前字段，日后证件号无法倒灌。
- **分层多抽的边界样本会不会带偏结论？** 每个样本固化 `N_h/n_h` 入框权重，报告全部按权重还原；
  补录的非概率样本权重为 0 且单列，不进入总体估计与置信区间。

## 验证结论（实际执行记录）

以下验证均在本仓库交付前实际执行通过：

- `mvn test`：20 个集成测试全部通过（真实 PostgreSQL 18，独立测试库 `ccdev_test`；MpiFlowTest 9 个 + AuditCalibrationTest 11 个）。
- `./scripts/verify.sh`：45 项主任务验收断言全部通过（本地启动 jar + 全新播种数据，图谱版本从 0 开始）。
- `./scripts/verify-audit.sh`：40 项校准审计断言全部通过（同实例续跑，自造数据不改变图谱版本）。
- Swagger UI（`/swagger-ui.html` 重定向至 `/swagger-ui/index.html`，HTTP 200）与 OpenAPI 文档
  （`/v3/api-docs`，含 13 个 `/api/audit` 路径，共 41 个路径）已实际访问确认可用。
