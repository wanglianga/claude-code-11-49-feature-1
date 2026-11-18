# 就诊人主索引（MPI）合并拆分与快照稳定服务

面向连锁诊所的就诊人主索引服务。不同院区的挂号、检验、结算系统各自生成就诊人编号，
本服务负责：**来源身份接入 →候选匹配 → 人工决议（合并/拆分）→ 时态关系图 → 业务快照 → 影响清单**，
保证已确认的结算、检验报告和经营报表"当时引用了谁"永远可复现。

## 核心设计

| 决策 | 实现 |
| --- | --- |
| 身份历史模型 | **时态关系图**：`mpi_link`（身份→主体）带生效图谱版本区间 `[validFrom, validTo)`，按业务时点解析身份 |
| 快照稳定 | 预约/结算/检验/分析启动时**发布快照固定图谱版本**，并把该版本的归属映射物化到 `snapshot_mapping`，读取快、可复核、永不回写 |
| 审核隔离 | 候选对、候选快照都是"尚在审核的身份推断"，下游只能读 `PUBLISHED` 快照（候选快照读取返回 409） |
| 并发决议 | 决议携带 `expectedGraphVersion`，事务内对全局图谱行加写锁：**先提交者锁定该对及相邻关系，后提交者收 409 必须重新检查**，不会形成一人并入两个互斥主体 |
| 身份更正 | 来源证件更正只更新证据字段并重跑匹配；**已发布快照不回写**，影响经新决议→影响分析→**候选快照**供业务确认 |
| 关账保护 | 已关账结算凭证**绝不改写**，身份拆分只生成 `ADJUSTMENT_LIST` 调整清单供财务线下处理；检验报告、预约提醒按各自业务规则领取新快照 |

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

## 测试账号（操作员）

接口通过请求头 `X-Operator-Id` 标识操作员（缺省 `system`）：

| 账号 | 用途 |
| --- | --- |
| `op_zhang` | 主索引操作员 1（合并/拆分/确认快照） |
| `op_li` | 主索引操作员 2（并发决议回放中的后提交者） |

## 验证方式

### 1. 集成测试（9 个用例，真实 PostgreSQL）

```bash
# 准备测试库（首次）
psql -h 127.0.0.1 -U ccdev -d ccdev -c "CREATE DATABASE ccdev_test;"
# 运行（使用独立库 ccdev_test，create-drop，不影响开发数据）
mvn test
```

覆盖：接入与评分、合并版本递增与时点解析、过期版本 409 后重检成功、**双线程并发决议恰好一个成功**、
误并拆分恢复、证件更正不回写快照、关账只出调整清单、候选快照确认按业务规则生效、家庭共用号码拒绝合并。

### 2. 验收回放脚本（45 项断言，需服务已启动且为全新播种数据）

```bash
./scripts/verify.sh            # 默认 BASE_URL=http://127.0.0.1:8080
```

回放四大验收场景：多院重复挂号合并 → **误并拆分 + 关账后调整** → **来源证件更正** → **并发决议**（含家庭共用号码拒绝），
最后校验关系链按版本可复现。全部通过时退出码为 0。

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
| 家庭共用号码 | 李四 × 李四中（共用手机号，评分 0.4） | 人工拒绝合并 |
| 证件更正 | 赵六 `LAB_A/L-A-001` 证件号末位录错（`...110` 应为 `...111`） | 更正 → 新候选 → 合并 |

下游记录：陈晨B 预约（BOOKED）、李四中预约（BOOKED）、陈晨A 结算单（**已关账**，2026-10，¥880）、
赵六结算单（未关账，2026-11，¥1200）、陈晨A 血常规（未签发）、赵六 尿常规（未签发）。

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

## 目录结构

```
src/main/java/com/clinic/mpi/
├── domain/      # 13 个实体：来源身份、候选对、决议、图谱、时态链接、快照、影响项、下游记录
├── repo/        # Spring Data JPA 仓库（含悲观写锁查询）
├── service/     # Match/Resolution/Snapshot/Impact/Biz/Identity/Graph 服务
├── web/         # 7 个 REST 控制器 + 全局异常处理（409 版本冲突）
└── seed/        # 样例数据播种（幂等）
scripts/verify.sh  # 验收回放脚本（45 项断言）
```

## 验证结论（实际执行记录）

以下验证均在本仓库交付前实际执行通过：

- `mvn test`：9 个集成测试全部通过（真实 PostgreSQL 18，独立测试库 `ccdev_test`）。
- `./scripts/verify.sh`：45 项验收断言全部通过（本地启动 `mvn spring-boot:run` + 全新播种数据）。
- Swagger UI（`/swagger-ui.html`）与 OpenAPI 文档（`/v3/api-docs`，28 个路径）已实际访问确认可用。
