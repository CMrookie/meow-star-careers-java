# meow-star-careers · 招聘求职系统服务端（Java 版）

**meow-star-careers** 是模块化的 **Spring Boot 3.5 + PostgreSQL** 招聘求职平台后端：注册登录（求职者/招聘者/审核账号）、
职位发布与搜索、简历管理、在线投递与状态流转、职位收藏、投诉（举报）与审核，另含一对一实时私聊与线上面试信令。

本仓库是 [just-a-work（Rust / actix-web 版）](https://github.com/CMrookie) 的 **逐行为对齐的 Java 移植**：
接口路径、JSON 字段、错误 `code`/`message`、状态码、角色门禁、投诉定级规则与排序契约、WebSocket 报文协议全部一致，
并且 **与 Rust 版共用同一个数据库**（迁移记录写入 sqlx 的 `_sqlx_migrations` 表，两者可交替启动）。

- 框架：Spring Boot 3.5（Java 21 目标字节码，实测运行于 JDK 27）
- Web 容器：Undertow（`allow-unescaped-characters-in-url`，见「已知差异」）
- 数据访问：Spring JDBC（`JdbcTemplate` + 参数绑定），连接池 HikariCP
- 数据库：PostgreSQL，启动时自动执行 `migrations/*.sql`（19 个迁移，含 `COMMENT ON` 中文注释）
- **API 文档自动生成**：springdoc-openapi -> `/api-docs/openapi.json`，交互式 UI 在 `/swagger-ui/`（含 bearerAuth）
- **模块化分层**：`config` / `db` / `auth` / `security` / `limiter` / `ws` / `model` / `repo` / `web` / `error`
- **统一错误处理**：单一 `ApiException`，所有错误输出一致 JSON（`code` + `message`）并集中记录日志；5xx 对客户端脱敏
- **日志**：每个请求生成/透传 `X-Request-Id`，结构化请求日志（方法、路径、状态、耗时）
- **角色模型**：单 `users` 表 + `role`（seeker/recruiter/admin/reviewer），招聘者关联 `companies`；
  注册时招聘者同事务创建企业；**admin 与 reviewer 都不可自助注册** —— admin 由 `ADMIN_PHONE`/`ADMIN_PASSWORD` 幂等引导，
  reviewer 由 admin 通过 `/reviewers` 创建管理
- **搜索**：PostgreSQL ILIKE/范围过滤 + 分页（零额外服务）
- **实时通道**：Spring WebSocket（原生协议，无 STOMP）实现 `/ws`，REST 与 WS 共用同一套「入库 + 推送」链路

## 目录结构

```
├── pom.xml
├── .env.example                 # 环境变量样例（默认连本机 pgdb 的 appdb）
├── PORTING.md                   # 移植契约（Rust -> Java 的规则与接口约定）
├── migrations/                  # 0001-0019（与 Rust 版逐字节相同，含 COMMENT ON 与定级规则函数）
├── scripts/
│   ├── mvn.sh / build.sh / run.sh   # 仓库内置 Maven 包装脚本（依赖落在 .m2repo/，无需系统装 maven）
│   ├── e2e.sh                       # 端到端验收（REST 全流程，黑盒 curl）
│   └── ws_e2e.py                    # WebSocket 协议验收（纯标准库客户端）
└── src/main/java/com/meowstar/careers/
    ├── CareersApplication.java  # 入口（.env 载入 -> 迁移 -> 管理员引导 -> 启动）
    ├── config/     # AppProps / AppConfig / DbConfig / WebConfig(CORS) / OpenApiConfig / 日志与体积过滤
    ├── db/         # Migrator（sqlx 兼容迁移执行器）/ Rows（记录行映射）/ AdminBootstrapper
    ├── error/      # ApiException / ApiErrorHandler / ErrorResponse
    ├── security/   # argon2id / 令牌 / sha256 / 手机号校验
    ├── auth/       # AuthService / AuthUser 参数解析器
    ├── limiter/    # 登录失败限流（手机号|IP）
    ├── ws/         # RealtimeHub / WsHandler / WsEvents / RealtimeNotifier
    ├── model/      # user/auth/company/job/resume/application/chat/interview/complaint/stats
    ├── repo/       # 数据访问层
    └── web/        # REST 控制器
```

## 快速开始

依赖：JDK 21+（本机为 27）、正在运行的 PostgreSQL 容器 **pgdb**（宿主 5432，postgres/password）。

```bash
# 1) 项目库（可重复执行；与 Rust 版共用，若已存在则跳过）
docker exec pgdb psql -U postgres -h 127.0.0.1 -c "CREATE DATABASE appdb OWNER postgres;" 2>/dev/null || true

# 2) 配置
cp .env.example .env       # 默认 DATABASE_URL 指向 pgdb 的 appdb；可配置 ADMIN_PHONE/ADMIN_PASSWORD 引导管理员

# 3) 构建并启动（启动时自动执行迁移；.env 自动载入）
./scripts/build.sh
./scripts/run.sh
```

访问：<http://127.0.0.1:8080/swagger-ui/> ｜ 规范：<http://127.0.0.1:8080/api-docs/openapi.json>

首次构建会从 Maven Central 下载依赖到 `.m2repo/`（约 1-2 分钟）；Maven 本体在 `.tools/` 内（脚本会提示安装命令）。

## 角色与账号

> 账号体系：**手机号 + 密码**（中国大陆 11 位手机号，无需短信验证码）。`users.phone` 唯一。

- 求职者 `seeker`（默认）：浏览/搜索职位、收藏、维护简历、投递与撤回；
- 招聘者 `recruiter` + `company`：发布/上下架/编辑职位，查看并推进投递状态，检索公开简历；
- 平台管理 `admin`：管理账号（创建/启停/改密/删除审核账号）与用户管理，作为超级角色也可代审投诉。
  **只能由服务端引导创建**（`ADMIN_PHONE` + `ADMIN_PASSWORD`，可选 `ADMIN_NAME`）：
  手机号不存在 -> 新建 admin；已是 admin -> 保持原密码不动；已被其它角色占用 -> **服务启动失败**（绝不提权）。
- 审核专用账号 `reviewer`：只做举报审核，不可自助注册，由 admin 通过 `POST /api/v1/reviewers` 创建；
  多个审核账号可同时在线并行审核，同一账号也支持多端同时在线。

```jsonc
// 求职者
{ "phone": "13800138000", "name": "Alice", "password": "secret123" }
// 招聘者（staffSize = 员工人数，投诉定级 v2 的分母，可选）
{ "phone": "13900000001", "name": "HR", "password": "secret123",
  "role": "recruiter",
  "company": { "name": "公司名", "industry": "互联网", "location": "北京", "staffSize": 2000 } }
```

## REST 接口一览（除注明外均需 `Authorization: Bearer <token>`）

### 认证
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | 注册（seeker / recruiter+company）→ 201 `{token, user}` |
| POST | `/api/v1/auth/login` / `/logout` / GET `/auth/me` | 登录 200 / 登出 204 / 当前用户 |

### 用户与私聊
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | `/api/v1/users` | 用户列表 / 创建（**仅平台管理员**） |
| GET/PUT | `/api/v1/users/{id}` | 查询 / 修改（**本人或平台管理员**；查他人一律 404） |
| DELETE | `/api/v1/users/{id}` | 删除用户（**仅平台管理员**，级联清理其数据） |
| POST/GET | `/api/v1/conversations(/{id})`、`/messages`、`/read` | 一对一私聊（REST） |
| GET | `/ws?token=` | WebSocket 实时通道（消息自动入库并推送双方） |
| POST | `/api/v1/push-tokens`、`/push-tokens/remove` | 设备推送令牌注册/注销（204） |

### 企业
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/companies/mine` | 我的企业（招聘者） |
| GET | `/api/v1/companies/{id}` | 企业公开信息（含 `complaintsCount` 与 `staffSize`） |

### 投诉（举报）与审核
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/companies/{id}/complaints` | 发起投诉（求职者；**须与该企业有过实际沟通**，证据 20-5000 字）→ 201 |
| GET | `/api/v1/complaints/mine` | 我发起的投诉 |
| GET | `/api/v1/complaints` | 审核账号/管理员=全部（`?status=pending/approved/rejected`）、招聘者=本企业、求职者=本人 |
| POST | `/api/v1/complaints/{id}/claim` | **认领即锁定**：同一条只允许一个持锁人；重复调用=续约（租约 10 分钟） |
| POST | `/api/v1/complaints/{id}/release` | 释放认领锁：本人释放；`?force=true` 仅管理员；已过期锁任何人可清理 |
| GET | `/api/v1/complaint-rules` | 定级规则（单一来源）：`version`/`levels`/`minStaffSizeForRate`/`rateThresholds`/`countThresholds` |
| POST | `/api/v1/complaints/{id}/review` | **审核账号或管理员**审核 `{approved, note?}`；重复审核 409；被他人持锁 409；留痕 `reviewedBy`/`reviewedByName` |

### 审核账号管理（仅平台管理员）
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | `/api/v1/reviewers` | 列表 200 / 创建 201 |
| POST | `/api/v1/reviewers/{id}/active` | 启用/禁用 `{isActive}`；禁用**立即踢下线** |
| POST | `/api/v1/reviewers/{id}/password` | 重置密码 204；旧会话与旧密码同时失效 |
| DELETE | `/api/v1/reviewers/{id}` | 删除审核账号 204 |

### 平台统计
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/stats/review` | 待审队列概览 + 审核账号工作量。**管理员=全部审核账号，审核账号=只有自己** |
| GET | `/api/v1/stats/companies` | 用人单位优劣（`?limit=` 默认 200）；**最差在前**（仅 admin） |
| GET | `/api/v1/stats/seekers` | 求职用户分析（仅 admin） |

> **审核只记录及时性**：`/stats/review` 返回「提交→审结」总时长、「认领→审结」处理时长、及时率（及时线 24h）与队列积压；
> **刻意不返回通过/驳回数量与结论分布**。`/stats/companies` 与 `/stats/seekers` 只返回**聚合值**。

### 职位
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/jobs` | 搜索：`keyword` `location` `jobType` `salaryMin` `salaryMax` `page` `pageSize` |
| GET | `/api/v1/jobs/{id}` | 职位详情（在招；本企业招聘者可看已下架） |
| POST | `/api/v1/jobs` | 发布职位（招聘者）→ 201 |
| GET | `/api/v1/jobs/my` | 我的（本企业）职位 |
| PUT/DELETE | `/api/v1/jobs/{id}` | 编辑 200 / 删除 204 |
| POST | `/api/v1/jobs/{id}/active` | 上架/下架 `{isActive}` |
| POST | `/api/v1/jobs/{id}/save` `/unsave` | 收藏 / 取消收藏（204） |
| GET | `/api/v1/saved-jobs` | 我的收藏 |

### 投递 / 简历 / 线上面试
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/jobs/{id}/apply` | 投递（`resumeId?` `coverLetter?`），一人一岗一次 → 201 |
| GET | `/api/v1/applications` | 求职者=我的投递（`jobId`/`status` 过滤）；招聘者=本企业收件箱 |
| GET | `/api/v1/applications/{id}` | 投递详情（含求职者联系方式），仅双方可见 |
| POST | `/api/v1/applications/{id}/status` | 求职者撤回 `withdrawn`；招聘者推进 `viewed`→`interviewing`→`offered`/`rejected` |
| POST/GET | `/api/v1/resumes` | 新建 201 / 我的简历 |
| GET | `/api/v1/resumes/search?keyword=` | 检索**公开**简历（仅招聘者） |
| GET/PUT/DELETE | `/api/v1/resumes/{id}` | 详情 / 更新 / 删除（仅本人，越权 404） |
| POST | `/api/v1/interviews` | 发起 `{applicationId, scheduledAt?}` → 201（scheduledAt 空=即时） |
| GET | `/api/v1/interviews(/{id})` | 我参与的面试 / 详情 |
| POST | `/api/v1/interviews/{id}/start` `/finish` `/cancel` | 状态流转 invited→in_progress→finished / cancelled |

### 系统
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/`、`/healthz` | 服务信息、健康检查（含 DB 探活，不可用时 503） |
| GET | `/api-docs/openapi.json`、`/swagger-ui/` | OpenAPI 规范与交互式文档 |

## 职位列表排序契约：一律按用人单位投诉等级「从优到劣」（定级 v2）

`GET /jobs`、`GET /jobs/my`、`GET /saved-jobs` 三个列表**都在服务端排序**，返回顺序即最终展示顺序：

```
ORDER BY complaint_level_rank(c.complaints_count, c.staff_size) ASC,   -- 等级 v2：有规模按每百人投诉率
         (complaint_rate_percent(..., c.staff_size) IS NULL) ASC,      -- 同等级内「有规模折算」的优先
         COALESCE(complaint_rate_percent(...), c.complaints_count) ASC,-- 再按率（无规模按次数）小者优先
         <时间> DESC,                                                   -- 搜索/本企业按发布；收藏按收藏时间
         j.id DESC                                                      -- 末位兜底，保证分页稳定
```

| 等级 | 每百人投诉率（规模 ≥50 人） | 次数兜底（未申报规模 / <50 人） | 颜色（App 展示） |
| --- | --- | --- | --- |
| 优秀 excellent | 0（分子为 0，两种口径都判优秀） | 0 次 | 绿 |
| 轻微 minor | ≤ **0.5%** | 1 – 2 次 | 蓝 |
| 预警 alert | ≤ **1.5%** | 3 – 5 次 | 橙黄 |
| 警告 warning | ≤ **3.0%** | 6 – 9 次 | 橙红 |
| 严重 severe | **> 3.0%** | 10 次及以上 | 红 |

- 分子 `companies.complaints_count` 只计**管理员审核通过**的投诉；分母 `companies.staff_size` 是企业申报人数（可为空）。
- 小样本退回次数：规模 < 50 人时改用次数定级，并在响应里用 `complaintBasis=count` 标明口径。
- **规则单一来源**：阈值只在 `migrations/0018_complaint_rule_single_source.sql` 的 `complaint_rule_*` 函数里定义一次，
  排序用 `complaint_level_rank`，响应字段用 `complaint_level`/`complaint_basis`/`complaint_rate_percent`，
  规则接口 `GET /complaint-rules` 原样返回同一批数字；**Java 代码里没有任何硬编码阈值**。
- 响应字段：`companyStaffSize`（未申报为 `null`）、`complaintLevel`、`complaintBasis`、`complaintRatePercent`。

## WebSocket 私聊协议

`ws://127.0.0.1:8080/ws?token=<登录token>`

```json
{"type": "send", "conversationId": "<会话id>", "body": "约个面试时间？"}  // 上行
{"type": "message", "conversationId": "...", "message": { ... }}         // 下行（推给双方）
{"type": "ack", "conversationId": "..."}
{"type": "signal", "interviewId": "<面试id>", "payload": {"kind":"joined"}}        // 上行：面试信令
{"type": "interview-signal", "interviewId": "...", "from": "<userId>", "payload": {...}} // 下行：转发给对端
{"type": "interview-updated", "interview": { ... }}                              // 下行：面试状态变化推送给双方
{"type": "application-updated", "application": { ... }}                          // 下行：投递状态变化
{"type": "conversation-read", "conversationId": "...", "userId": "..."}          // 下行：已读同步
{"type": "error", "code": "...", "message": "..."}
```

`ping` 上行会收到 `pong`；非法报文/未知类型/缺字段都会收到 `error` 报文。

## 验证

```bash
./scripts/build.sh                       # 编译打包
./scripts/run.sh                         # 启动（另一个终端）
./scripts/e2e.sh                         # 端到端验收（REST 全流程 + 文档/CORS/体积上限 + WebSocket 协议）
BASE=http://127.0.0.1:8081 ./scripts/e2e.sh   # 也可指向别的实例（WebSocket 端口会自动跟随 BASE）
./scripts/mvn.sh -DskipTests=false test  # 单元/回归测试（含与 Rust 哈希互操作）

# e2e 每个用例都用当次随机手机号/公司名（公司A-<随机> 等），不会与既有数据冲突；
# 跑完想清理测试数据（只按这些固定名称模式精确删除，不动其它数据）：
docker exec -i pgdb psql -U postgres -d appdb -v ON_ERROR_STOP=1 < scripts/cleanup-e2e-data.sql
```

### 实测结果（本次交付）

| 验证 | 结果 |
| --- | --- |
| `scripts/e2e.sh` 对 **Java 版**（8080 / appdb） | **230/230 通过**（另含 WebSocket 14/14） |
| `scripts/e2e.sh` 对 **Rust 版**（同一套用例，同一份数据库） | **230/230 通过**（WebSocket 14/14） |
| 空库迁移（Java 首次执行 19 个迁移） | 19/19 成功，`_sqlx_migrations` 记录 checksum（sha384）与 sqlx 一致 |
| Rust 版启动 Java 迁移过的库 | 迁移校验通过（跳过已应用）、管理员账号识别为已存在 —— **两版可交替启动** |
| 密码哈希互操作 | Rust 写入的 argon2id 哈希可被 Java 校验（错误密码被拒）；Java 写入的哈希可被 Rust 识别 |
| JUnit 回归（`SecurityInteropTest`） | 7/7 通过 |

其中「同一套用例同时跑通 Java 与 Rust」是最强的对齐证据：接口路径、状态码、错误 `code`/`message`、
权限门禁、JSON 字段、投诉定级与跨页排序在两端表现一致；唯一差异见下节。

`scripts/e2e.sh` 覆盖：系统端点、认证与角色门禁（含 401/403/404/409/429/400 边界）、管理员引导幂等、
用户管理、审核账号全生命周期（禁用即踢下线、改密撤销会话）、职位 CRUD/搜索/上下架/收藏、
简历隐私、投递与状态流转、私聊 REST、线上面试状态机、投诉前置条件与认领锁（他人 409、
非管理员 force 403、管理员强制解锁、重复审核 409）、**投诉定级 v2 双口径与跨页全局排序**、
三类平台统计、推送令牌、CORS 白名单与请求体上限、WebSocket 协议（含信令转发与非法报文）。

## 与 Rust 版的已知差异

对齐目标是「逐行为一致」，实测只发现一处**超集行为**与少量解析细节差异，均不影响客户端：

| 差异 | Rust 版 | Java 版 | 影响 |
| --- | --- | --- | --- |
| URL 中未转义的非 ASCII 字符（如 `?location=北京`） | HTTP 400（actix 解析层拒绝） | HTTP 200（Undertow 放开） | Java 更宽松：**Rust 能用的请求 Java 全部可用**，Java 额外接受未编码写法（README 里的 curl 示例在 Java 版可直接跑） |
| 解析类失败（`?limit=abc`、非法 JSON、非法 UUID 路径参数） | 4xx + serde/actix 的具体文案 | 同状态码与 `code` + 统一错误体文案 | 客户端只看 `code`，不受影响 |

## 与 Rust 版的互通

- `migrations/*.sql` 与 Rust 仓库**逐字节相同**；迁移执行器把记录写入 sqlx 的 `_sqlx_migrations`
  （version = 文件名数字前缀，checksum = 文件内容 sha384），因此两版可对同一个库**交替启动**：
  Java 启动时会跳过 Rust 已应用的迁移，反之亦然（checksum 不一致时会拒绝启动并提示重置本地库）。
- 密码哈希双向兼容：argon2id（v19 / m=19456 KiB / t=2 / p=1，PHC 字符串），Rust 写入的密码可被 Java 校验。
- 令牌只存 sha256 摘要，两版共用 `auth_tokens` 表。

## 设计要点

### 新增业务模块
1. `migrations/000N_xxx.sql` 写迁移（启动自动执行，注意 sqlx checksum：已应用的迁移改动后需重置本地库）；
2. `model/xxx/` 定义请求/响应记录（字段名即 JSON 名，camelCase）；
3. `repo/xxx/` 写 SQL（`JdbcTemplate` + `?` 参数绑定，禁止拼接用户输入），行映射用 `Rows.of(X.class)`；
4. `web/XxxController.java` 写控制器，受保护接口声明参数 `AuthUser auth`；
5. 需要文档时加 `@Tag` / `@Operation`。

### 权限约定
- 角色门禁统一走 `UserRepo.requireRecruiter / requireSeeker / requireAdmin / requireReviewer`；
- 招聘者只能操作**本企业**职位/投递；求职者只能撤回自己的投递；
- 私有简历、他人账号对其他角色一律 404（不泄露存在性）；
- `/reviewers` 全套与 `/users` 列表/创建/删除仅 admin；`/stats/companies`、`/stats/seekers` 仅 admin；
- 账号被禁用后既有令牌立即失效（401），重新登录 403；重置审核账号密码同样撤销其全部会话；
- 并行审理由**认领锁**保证：`claim` 用条件 UPDATE（空闲 OR 自己 OR 已过期）抢占；`review` 要求未被他人有效锁定。

### 安全设计
- SQL 注入：全部参数绑定；认证：argon2id + 令牌摘要 + 撤销/过期；登录失败按「手机号|IP」5 次/15 分钟冻结 → 429；
- 输入上限：密码 8-128、职位描述 10000、消息/求职信 2000、keyword 200 等；请求体上限 1 MiB；
- CORS：默认拒绝跨域，配置 `CORS_ALLOWED_ORIGINS` 白名单才放行；
- 错误脱敏：5xx 一律不向客户端泄露内部细节，原始错误仅进日志。

## 生产注意
- 远程（SSL）PostgreSQL 请在 JDBC URL 上追加 `?sslmode=require`；
- 令牌每请求查库，量大时可换 JWT 或加缓存；搜索量级上来后可加 GIN 全文索引/专用引擎。

---

## 相关仓库

- **Rust 服务端（本仓库的上游契约来源）**：`just-a-work`
- **后台管理系统（Vue3 + TypeScript）**：[GitHub](https://github.com/CMrookie/meow-star-careers-admin) ｜ [Gitee](https://gitee.com/rookie_c/meow-star-careers-admin)
- **移动端 App（Flutter）**：[GitHub](https://github.com/CMrookie/meow_star_careers_app) ｜ [Gitee](https://gitee.com/rookie_c/meow_star_careers_app)

> 三端共用一套接口契约与角色模型（seeker / recruiter / reviewer / admin），由 OpenAPI 定义。
