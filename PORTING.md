# 移植契约（Rust actix-web -> Java Spring Boot）

本仓库是 `/Volumes/PS2000/project/just-a-work`（Rust：actix-web 4 + sqlx 0.9 + PostgreSQL）的
**逐行为对齐**的 Java 移植（Spring Boot 3.5 + Spring JDBC + springdoc，`--release 21`，JDK 27 运行）。

> **最高原则：行为对齐，不是"重写一个类似的系统"。** 路径、HTTP 状态码、JSON 字段名（camelCase）、
> 错误 `code`/`message` 文案、校验上限、角色门禁、SQL 语义与排序、事务边界都必须与 Rust 版一致。
> 每个模块动手前 **先完整读对应的 Rust 文件**（handler + repository + 相关 migration 的 COMMENT）。

## 1. 已完成的地基（不要修改这些文件）

| 关注点 | 位置 | 说明 |
| --- | --- | --- |
| 统一错误 | `error/ApiException.java`、`error/ApiErrorHandler.java`、`error/ErrorResponse.java` | 抛 `ApiException.*`，全局转 `{code, message}` JSON；5xx 自动脱敏；唯一约束自动 409 |
| 鉴权 | `auth/AuthService.java`、`auth/AuthUser.java`、`auth/AuthUserArgumentResolver.java` | 控制器方法直接声明参数 `AuthUser auth`（等价 Rust 的 `AuthenticatedUser` 提取器） |
| 用户与角色门禁 | `repo/UserRepo.java` | `get/getOrNotFound/create/update/delete/list/listByRole`、`requireRecruiter/requireSeeker/requireAdmin/requireReviewer`、`recruitersOfCompany` |
| 认证数据 | `repo/AuthRepo.java` | `findByPhone/registerSeeker/registerRecruiter/issueToken/findUserByToken/revokeToken/ensureAdmin/createReviewer/updatePassword/revokeAllTokens`；`AuthRepo.StoredUser`、`AuthRepo.AdminBootstrap` |
| 安全原语 | `security/Security.java` | `hashPassword/verifyPassword/newSessionToken/isValidCnPhone/sha256Hex` |
| 限流 | `limiter/LoginLimiter.java` | `blocked/recordFailure/clear`（键 `手机号\|IP`） |
| 分页 | `model/pagination/Paginate.java` | `Paginate.paginate(page, pageSize)` -> `Page(page, pageSize, offset)`（page>=1，1<=pageSize<=100，默认 20） |
| 行映射 | `db/Rows.java` | `Rows.of(X.class)`：`DataClassRowMapper`，按 `snake_case 列名 -> camelCase 记录组件名` 自动映射；SQL 别名必须是 snake_case |
| 实时推送 | `ws/RealtimeHub.java`、`ws/WsEvents.java`、`ws/RealtimeNotifier.java` | `RealtimeNotifier.deliverMessage(Message)`、`notifyInterview(InterviewView)`、`notifyApplication(ApplicationView, List<UUID>)`、`notifyConversationRead(convId, reader, other)` |
| 模型 | `model/**`（已有文件） | 请求/响应记录，字段名与 Rust serde camelCase 完全一致，**只读，不要改** |
| 配置 | `config/AppProps.java` | `corsOrigins/adminPhone/...`；`@Configuration`、`@Service`、`@Repository`、`@RestController` 均为 Spring Bean |

依赖获取：`JdbcTemplate`（可注入）；SQL 一律用 `?` 占位符（Rust 的 `$1` -> `?`）。
事务：在 repository 的 public 方法上加 `@Transactional`（Spring JDBC 自动参与同一事务）。

## 2. 硬性规则

1. **JSON**：记录组件名即 JSON 字段名（camelCase）。布尔字段若组件名是 `isXxx`，模型里已加
   `@JsonProperty("isXxx")`；**不要新增**未经确认的字段名。`null` 字段默认输出（Rust `Option::None`
   也是 `null`），只有 `ConversationSummary.lastMessage` 标了 `@JsonInclude(NON_NULL)`（Rust 的 `skip_serializing_if`）。
2. **时间**：统一 `java.time.OffsetDateTime`（UTC），Jackson 输出形如 `2026-10-15T19:08:05.834034Z`，与 Rust chrono 一致。
3. **UUID**：`java.util.UUID`。整数：Rust `i64` -> `long/Long`；Rust `i32` -> `int/Integer`；
   PostgreSQL `count(*)` 是 **bigint -> 必须用 `long`**；`numeric`/`f64` -> `double/Double`。
   若 SQL 表达式返回类型与记录字段不符，用 `::int` / `::float8` / `::bigint` 显式转换。
4. **错误文案逐字对齐**：例如 `AppError::forbidden("仅招聘者账号（且已绑定企业）可执行该操作")`
   -> `throw ApiException.forbidden("仅招聘者账号（且已绑定企业）可执行该操作")`。
   Rust `AppError::not_found(format!("job \`{id}\`"))` -> `ApiException.notFoundResource("job `" + id + "`")`。
   注意 Rust `AppError::not_found(x)` 的文案是 `"{x} not found"`。
5. **校验口径逐字对齐**：长度上限（按 `chars().count()`，Java 用 `codePointCount`）、数值区间、
   枚举取值、错误信息文案。校验失败 -> `ApiException.badRequest(...)`；只有 Rust 用 403/404/409 的地方才用对应方法。
6. **权限**：招聘者只能操作本企业资源（比对 `companyId`）；求职者只能操作自己的资源；
   私有资源对越权者一律 **404**（防探测）；角色门禁必须调用 `UserRepo.require*`，不要自己重写。
7. **SQL**：只写参数绑定的字面量 SQL，绝不拼接用户输入；保持 Rust 的排序/过滤语义（尤其投诉等级排序、
   认领锁条件 UPDATE、`FOR UPDATE`/`ON CONFLICT` 行为）。
8. **状态码**：与 Rust `HttpResponse::Ok/Created/NoContent` 一一对应（`ResponseEntity.status(...)`）。
9. **不要**修改 `pom.xml`、`application.yml`、`model/**` 已有文件、上表"已完成地基"里的任何文件；
   需要新的请求体/查询参数记录时，在自己的 `model/<domain>/` 下**新建**文件。
10. 每个控制器加 `@RestController` + `@RequestMapping("/api/v1/...")` + `@Tag(name = "...", description = "...")`
    （tag 名与 Rust `openapi.rs` 一致），方法加 `@Operation(summary = ...)`。

## 3. 编译自检（必须做，不要跑 mvn）

```bash
cd /Volumes/PS2000/project/meow-star-careers-java
export JAVA_HOME=/usr/local/Cellar/openjdk/27/libexec/openjdk.jdk/Contents/Home
mkdir -p /tmp/selfcheck-out
"$JAVA_HOME/bin/javac" -encoding UTF-8 -parameters --release 21 -nowarn \
  -cp "$(cat .m2repo/classpath.txt):target/classes" \
  -d /tmp/selfcheck-out <你写的所有 .java 文件>
```

`target/classes` 是地基的最新编译产物（含模型与 `RealtimeNotifier`）。
如果报错指向**别的 agent 负责的文件**，属于并行开发中的正常现象，只需保证自己的文件无错。
**不要执行 `mvn`**（并行编译会互相覆盖 `target/`），集成编译由主流程统一执行。

## 4. Rust -> Java 命名对照

| Rust | Java |
| --- | --- |
| `handlers::jobs::list_jobs` | `web/JobsController#listJobs` |
| `repositories::job::list_for_seeker` | `repo/JobRepo#listForSeeker` |
| `web::Query<JobQuery>` | 方法参数 `JobQuery query`（Spring 自动绑定 query string） |
| `web::Path<Uuid>` | `@PathVariable UUID id` |
| `web::Json<T>` | `@RequestBody T payload` |
| `state.pool` | 注入 `JdbcTemplate jdbc` |
| `auth: AuthenticatedUser` | 方法参数 `AuthUser auth`（`auth.userId()` / `auth.token()`） |

返回体：直接返回记录对象（Spring 序列化为 JSON）；需要 201/204 时用 `ResponseEntity`。
`Ok(HttpResponse::Ok().json(x))` -> 返回 `x`；`Created` -> `ResponseEntity.status(HttpStatus.CREATED).body(x)`；
`NoContent` -> `ResponseEntity.noContent().build()`。

## 5. 路由总表（与 Rust `src/app.rs` + 各 handler `configure` 完全一致）

鉴权端点已在 `web/AuthController.java` 实现；系统端点见 `web/HealthController.java`。

### users（`src/handlers/users.rs`、`src/repositories/user.rs`）
- `GET /api/v1/users`（仅 admin）、`POST /api/v1/users`（仅 admin）
- `GET /api/v1/users/{id}`、`PUT /api/v1/users/{id}`（本人或 admin，查他人 404）、
  `DELETE /api/v1/users/{id}`（仅 admin）

### companies（`src/handlers/companies.rs`）
- `GET /api/v1/companies/mine`、`GET /api/v1/companies/{id}`

### jobs（`src/handlers/jobs.rs`、`src/repositories/job.rs`）
- `GET /api/v1/jobs`、`POST /api/v1/jobs`、`GET /api/v1/jobs/my`、`GET|PUT|DELETE /api/v1/jobs/{id}`
- `POST /api/v1/jobs/{id}/apply`、`POST /api/v1/jobs/{id}/active`
- `POST /api/v1/jobs/{id}/save`、`POST /api/v1/jobs/{id}/unsave`、`GET /api/v1/saved-jobs`
  （`/saved-jobs` 挂在 `/api/v1` 下）

### applications（`src/handlers/applications.rs`、`src/repositories/application.rs`）
- `GET /api/v1/applications`、`GET /api/v1/applications/{id}`、`POST /api/v1/applications/{id}/status`

### resumes（`src/handlers/resumes.rs`）
- `POST|GET /api/v1/resumes`、`GET /api/v1/resumes/search`、`GET|PUT|DELETE /api/v1/resumes/{id}`

### chat（`src/handlers/chat.rs`、`src/repositories/chat.rs`、`src/ws.rs`）
- `GET|POST /api/v1/conversations`、`GET /api/v1/conversations/{id}`
- `GET|POST /api/v1/conversations/{id}/messages`、`POST /api/v1/conversations/{id}/read`

### interviews（`src/handlers/interviews.rs`、`src/repositories/interview.rs`）
- `GET|POST /api/v1/interviews`、`GET /api/v1/interviews/{id}`
- `POST /api/v1/interviews/{id}/start`、`/finish`、`/cancel`

### complaints（`src/handlers/complaints.rs`、`src/repositories/complaint.rs`）
- `POST /api/v1/companies/{id}/complaints`（求职者，须与该企业有过实际沟通）
- `GET /api/v1/complaints/mine`、`GET /api/v1/complaints`
- `POST /api/v1/complaints/{id}/claim`、`/release`（`?force=`）、`/review`
- `GET /api/v1/complaint-rules`

### reviewers（`src/handlers/reviewers.rs`，仅 admin）
- `GET|POST /api/v1/reviewers`、`POST /api/v1/reviewers/{id}/active`、
  `POST /api/v1/reviewers/{id}/password`、`DELETE /api/v1/reviewers/{id}`

### push（`src/handlers/push.rs`、`src/repositories/push.rs`）
- `POST /api/v1/push-tokens`、`POST /api/v1/push-tokens/remove`

### stats（`src/handlers/stats.rs`、`src/repositories/stats.rs`）
- `GET /api/v1/stats/review`、`GET /api/v1/stats/companies`、`GET /api/v1/stats/seekers`

### 实时通道（主流程实现，agent 不要碰）
- `GET /ws?token=`（WebSocket）：`send` / `ping` / `signal` 上行，
  `message` / `ack` / `pong` / `error` / `interview-signal` / `interview-updated` /
  `application-updated` / `conversation-read` 下行。

## 6. 实时通道对 chat/interview 仓储的接口契约（agent C 必须按此签名实现）

```java
// repo/ChatRepo.java
public Optional<ConversationRow> findConversationForMember(UUID conversationId, UUID userId);
public void requireMember(ConversationRow conversation, UUID userId);      // 非成员抛 ApiException.forbidden
public Message insertMessage(ConversationRow conversation, UUID senderId, String body);
public Optional<ConversationRow> findOrCreateBetween(UUID userA, UUID userB);  // 供会话列表/发起复用

// repo/InterviewRepo.java
public Optional<UUID> peerOf(UUID interviewId, UUID userId);               // 非参与者 -> Optional.empty()
```
WebSocket 层（主流程）会这样调用：
`findConversationForMember` 为空 -> `ApiException.forbidden("conversation 不存在或不属于当前用户")`；
然后 `requireMember`、`insertMessage`、`RealtimeNotifier.deliverMessage(message)`。
REST 发消息（`POST /conversations/{id}/messages`）必须走**同样的**这三步 + `deliverMessage`。

## 7. 数据库

`migrations/*.sql`（19 个，含 `COMMENT ON` 与 `complaint_rule_*` 函数）已被主流程原样移植并在启动时执行；
数据库记录写入 sqlx 的 `_sqlx_migrations` 表，可与 Rust 版交替启动。
**投诉定级的阈值只在 `0018_complaint_rule_single_source.sql` 定义一次**：排序、响应字段
（`complaintLevel` / `complaintBasis` / `complaintRatePercent` / `companyStaffSize`）与
`GET /complaint-rules` 必须同源调用这些 SQL 函数，不要在任何 Java 代码里硬编码阈值。

## 8. 移植踩坑与已知差异（维护备忘）

1. **`DataClassRowMapper` 的列名匹配规则**（`db/Rows.java`）：只认「组件名全小写」或「仅在大写字母前插下划线」
   两种列名。组件名里含数字时（`reviewedLast7d` / `complaintsLast30d` / `newLast7d` / `newLast30d`）
   SQL 别名必须写成 `reviewed_last7d` 这种形式 —— 照抄 Rust 的 `reviewed_last_7d` 会在运行期
   `findColumn` 抛 SQLException（500）。对外 JSON 字段名不受影响。
2. **长度口径**：Rust 的 `.len()` 是 UTF-8 字节数，`chars().count()` 是字符数。移植时逐处对照：
   消息 body、简历 email 用字节数；name / evidence / title 等用 `codePointCount`。
3. **Web 容器**：使用 Undertow 并打开 `UndertowOptions.ALLOW_UNESCAPED_CHARACTERS_IN_URL`
   （见 `config/UndertowConfig.java`）。actix 与 Tomcat 都会对未转义的非 ASCII URL 直接 400，
   打开后 Java 版是**超集**：Rust 能用的请求 Java 全部可用，另外还接受未编码写法。
4. **解析失败文案**：`?limit=abc`、非法 JSON、非法 UUID 路径参数等，Rust 与 Java 的状态码与 `code` 一致，
   但 message 是 `ApiErrorHandler` 的固定文案（Rust 是 serde/actix 的具体报错）。属框架层差异，客户端只看 `code`。
5. **本卷宗文件系统限制**：PS2000 卷不支持 `write`/`edit` 工具的原子 rename（ENOTSUP），
   落盘请用 shell heredoc 或 `python3` 写文件；`read`/`grep`/`glob` 正常。
6. **脚本坑**：`$VAR` 若紧跟全角字符（如「（」），bash 会把多字节字符并入变量名导致 unbound variable，
   必须写 `${VAR}`；迁移 SQL 里也不要新增 `X7d` 这类带数字的别名（见第 1 条）。

## 9. 验证清单（可复现）

```bash
./scripts/build.sh                                   # 打包
./scripts/run.sh                                     # 启动（8080）
./scripts/e2e.sh                                     # 230 项 REST + 14 项 WebSocket
BASE=http://127.0.0.1:8081 ./scripts/e2e.sh          # 指到别的实例
./scripts/mvn.sh -DskipTests=false test              # JUnit 回归（含 Rust 哈希互操作）

# 与 Rust 版对照（可选）：同一套用例跑 Rust 二进制
DATABASE_URL=postgres://postgres:password@127.0.0.1:5432/appdb_fresh \
SERVER_HOST=127.0.0.1 SERVER_PORT=8082 \
ADMIN_PHONE=13700000000 ADMIN_PASSWORD=admin-secret-123 \
  /path/to/just-a-work/target/debug/meow-star-careers &
BASE=http://127.0.0.1:8082 ./scripts/e2e.sh
```
