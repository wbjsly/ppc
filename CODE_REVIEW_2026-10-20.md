# ERP 代码审查报告 — 性能与安全隐患

> 审查范围：`backend/`（Spring Boot 2.7 + MyBatis-Plus）、`frontend/`（Vue3 + Element Plus）
> 审查日期：2026-10-20
> 结论：数据层与序列化面很干净；主要问题集中在**硬编码密钥/凭证、CORS、日志泄露、登录防护缺失、JWT 吊销缺失、MyBatis 全量 SQL 日志、连接池与多实例锁、签名比较非定长**。

---

## 🔴 高危（建议必须修复）

### 1. JWT 签名密钥硬编码进源码仓库
- 位置：`backend/src/main/resources/application.yml:25`
  `app.jwt.secret: ErpSecretKey2026ForJwtTokenGenerationAndValidation0123456789`
- 风险：密钥随代码入库，任何人拿到仓库即可**伪造任意用户/角色的 JWT**，`SecurityConfig` 的整套鉴权形同虚设。JWT 有效期 24h（`expiration: 86400000`），且**无吊销/刷新机制**（见问题 8），影响被进一步放大。
- 建议：密钥移至环境变量/密钥管理（Vault/KMS），长度 ≥ 32 字节随机值；启用轮换；并定期清理历史提交（或视为已泄露，立即轮换）。

### 2. 数据库凭证硬编码（root/root123）入库
- 位置：`backend/src/main/resources/application-mysql.yml:6-7`
  `username: root` / `password: root123`
- 风险：生产库若使用同一份配置，数据库 root 权限直接暴露。同时该文件 `driver`/`url` 表明这是可直接连库的配置。
- 建议：凭证外置（环境变量或配置中心），且**禁止使用 root 作为应用账号**，按需授权最小权限用户。

### 3. MySQL 连接关闭 SSL 且允许公钥回传
- 位置：`application-mysql.yml:5`
  `useSSL=false&allowPublicKeyRetrieval=true`
- 风险：应用↔数据库明文传输 + 允许公钥回传，内网嗅探/中间人可截获凭证与业务数据；`allowPublicKeyRetrieval=true` 还有降级攻击面。
- 建议：生产启用 `useSSL=true&requireSSL=true`，关闭 `allowPublicKeyRetrieval`；仅本地开发允许关闭。

---

## 🟠 中危

### 4. CORS 配置：允许任意源 + 携带凭证
- 位置：`backend/src/main/java/com/erp/config/WebMvcConfig.java:29-30`
  `config.setAllowCredentials(true); config.addAllowedOriginPattern("*");`
- 风险：`Access-Control-Allow-Credentials: true` 配合通配源，任意站点可发起带凭证的跨域请求并读取响应。虽然本系统鉴权走 `Authorization` 头（非 Cookie），浏览器不会自动附带，实际 CSRF 风险有限，但属于安全扫描红线，且一旦 token 落入 Cookie 即成漏洞。
- 建议：明确白名单源（前端域名），收紧 `allowedMethods`/`allowedHeaders`，非必要不开启 credentials。

### 5. Swagger/Knife4j 文档端点匿名开放
- 位置：`SecurityConfig.java:46`
  `doc.html`、`/v3/api-docs/**`、`/swagger-resources/**` 全部 `permitAll()`
- 风险：生产环境任何人可读取**完整 API 契约**（所有路径、参数、结构），极大方便攻击者测绘与越权尝试。
- 建议：生产 profile 下禁用 Knife4j/SpringDoc，或将文档端点限定 ADMIN + 内网 IP。

### 6. MyBatis 全量 SQL 日志（StdOutImpl）
- 位置：`application.yml:21`
  `log-impl: org.apache.ibatis.logging.stdout.StdOutImpl`
- 风险：① **性能**：每条 SQL 都打到 stdout，高并发下 I/O 拖累显著；② **数据泄露**：SQL 含参数值，PII/金额/口令相关字段会进入日志（结合问题 2 的明文连接更危险）。这是同时命中性能与安全的双重问题。
- 建议：生产改用 `SLF4J` 且日志级别控制在 `INFO/WARN`，SQL 明细仅在 `DEBUG` + 特定 Mapper 开启；切勿在 stdout 全量打印。

### 7. 登录无频率限制 / 账户锁定 + 用户名枚举
- 位置：`service/impl/AuthServiceImpl.java:41-46`
  `throw new ServiceException(401, "用户不存在: " + username)` 与 "用户名或密码错误" 区分返回。
- 风险：① 无失败次数限制、无验证码、无锁账户 → 可被**暴力破解/撞库**；② 明确区分"用户不存在"与"密码错误"，**泄露账号是否存在**；错误信息里还拼接了用户名。
- 建议：增加登录失败计数 + 渐进延迟/锁定（如 5 次锁定 15 分钟）；统一错误文案为"用户名或密码错误"（不区分）；对用户名字段做长度/字符白名单。

### 8. JWT 角色与禁用态不在请求时实时校验
- 位置：`security/JwtAuthenticationFilter.java`（仅解析 token claims 作为权威）；`AuthServiceImpl.login` 检查 `status`（仅登录时）。
- 风险：token 内嵌角色与有效期 24h，**管理员随时撤销/改角色/禁用某用户，其旧 token 在 24h 内仍有效**；无黑名单/撤销端点。
- 建议：短期 token + 刷新机制；或对高敏操作做"token 版本/签发时间 vs 用户状态"的二次校验（如每次请求用 userId 拉取最新状态/角色，或维护 jti 吊销表）。至少对"禁用用户"做实时拦截。

### 9. 开放网关签名比较非定长（时序攻击）
- 位置：
  - `OpenApiGatewayServiceImpl.java:165` `if (!expected.equalsIgnoreCase(sign.trim()))`
  - `OpenApiGatewayServiceImpl.java:475` `equalsIgnoreCase(credential.getSecretHash())`（Basic 口令哈希）
- 风险：`String.equalsIgnoreCase` 是短路比较，时序差可被用于逐字节猜测 HMAC/口令哈希。虽然受 10 分钟时间戳窗口限制，但属密码学最佳实践违规。
- 建议：改用定长比较，如 `MessageDigest.isEqual(expected.getBytes(ISO_8859_1), sign.getBytes(ISO_8859_1))`（注意编码一致，十六进制串用单字节编码即可）。

### 10. 多实例部署下 `synchronized` 单实例锁失效
- 位置：`KittingServiceImpl.java:104/298`、`DispatchServiceImpl.java:225/399`、`SysParamServiceImpl.java:44`、`*SweepScheduler` 的 `synchronized doSweep()`
- 风险：注释已自述"单实例 Docker"假设。一旦水平扩成多实例，这些**进程内互斥全部失效**：齐套重算、排程打包、参数缓存失效、定时扫表会出现跨实例并发互踩/重复处理。
- 建议：用数据库行锁/`SELECT ... FOR UPDATE`、或分布式锁（如 Redis/DB 乐观锁 version），或明确约束为单实例部署并在运维层兜底。

### 11. Druid 连接池偏小 + 长等待
- 位置：`application-mysql.yml:9-12` `max-active: 20`、`max-wait: 60000`（60s）
- 风险：系统有大量 `@Transactional` 服务 + 定时任务 + 开放网关高频 DB 操作，20 连接在高并发下易耗尽；耗尽后请求最多阻塞 60s 才报"获取连接超时"，表现为大面积超时而非快速失败。
- 建议：按压测结果上调 `max-active`（如 50~100），并配合连接泄漏检测（`removeAbandoned`）；为长事务/批量任务单独评估。

---

## 🟡 低危 / 改进建议

### 12. 前端 token 存 localStorage（放大 XSS 影响）
- 位置：`frontend/src/utils/request.js:13`、 `store/user.js:14`
- 风险：当前前端**无 `v-html`/`innerHTML`/`eval`/`new Function`**（XSS 面很小，已确认），但 token 一旦因任何 XSS 落入攻击者脚本即可被读取利用。
- 建议：优先用 **httpOnly Cookie + SameSite** 承载会话（需后端配合改 CORS/cookie），或至少对 DOM 渲染保持默认转义、对富文本走白名单消毒。

### 13. 部分接口返回无分页上限的 `R<List>`
- 位置：`WarehouseController.list`、`ReworkController.list/sources`、`BiExtractController.batch`、`SdReturnController.returnables` 等（返回 `R<List<...>>`）。
- 风险：参考表还好；但若表增长或过滤不足，会一次性把大量行拉进内存再序列化，存在内存与响应体积风险（DoS 面）。
- 建议：列表类接口统一走分页（`current`/`size`），并对未分页的"全量"接口加硬性行数上限（如 1000）或游标。

### 14. JwtAuthenticationFilter 重复解析 token
- 位置：`JwtAuthenticationFilter.java:29` 先 `validateToken`，再 `getClaims`（内部又 parse 一次）。
- 风险：每个请求多一次 JWT 解析开销（含 HMAC 验签）。单请求影响极小，但属无谓重复。
- 建议：`validateToken` 可改为返回 `Claims` 或合并为一次 parse，失败再判无效。

### 15. 无效/误导的 MyBatis mapper 位置配置
- 位置：`application.yml:11` `mapper-locations: classpath*:mapper/**/*.xml`
- 说明：仓库内**不存在任何 XML mapper**（SQL 全在 `@Select` 注解与 Wrapper），该配置无害但会让人误以为有 XML；确认无 XML 后可删除以减少启动扫描与误解。

---

## ✅ 已确认安全良好的部分（避免误报）

- **SQL 注入**：Java DAO 与 resources 下 XML 均**未使用 `${}` 字符串拼接**；`@Select`/`@Update` 全部用 `#{}` 参数化，动态条件走 MyBatis-Plus `Wrapper`。无注入面。
- **命令注入 / 反序列化**：无 `Runtime.exec`/`ProcessBuilder`、无 `ObjectInputStream`、无 Jackson `enableDefaultTyping`/`activateDefaultTyping` → 无反序列化 gadget 链风险。
- **密码存储**：`BCryptPasswordEncoder` 哈希存储并在登录时 `matches` 校验（`AuthServiceImpl.java:44`），无明文/弱哈希。
- **开放网关**：具备 HMAC-SHA256 签名、10 分钟时间戳防重放、API Key 轮换、令牌桶限流、熔断状态机、审计 fail-closed（`audit-fail-closed: true`）——设计扎实。
- **供应商门户隔离**：`PortalGuardInterceptor` 一处拦截 ROLE_SUPPLIER 越权访问内部端点 + 行级 supplierId 过滤，双层隔离到位。
- **前端 XSS 面**：无 `v-html`/`innerHTML`/`dangerouslySetInnerHTML`/`eval`/`new Function`。

---

## 修复优先级建议

| 优先级 | 项 | 工作量 |
|--------|----|--------|
| P0 | #1 密钥外置+轮换、#2 DB 凭证外置+降权、#3 启用 SSL | 小（配置+部署） |
| P1 | #6 关闭 StdOutImpl、#7 登录防护+枚举修复、#8 JWT 吊销/实时校验、#9 定长签名比较 | 中 |
| P1 | #4 CORS 白名单、#5 生产关文档 | 小 |
| P2 | #10 多实例锁改造（若计划扩实例）、#11 连接池调优 | 中 |
| P3 | #12 token 存储、#13 分页上限、#14 解析合并、#15 清理配置 | 小~中 |

> 注：本机无 JDK，以上结论均来自**静态代码与配置审查**，未运行后端测试。建议将 P0/P1 项纳入 CI 的密钥扫描（如 gitleaks）与依赖/配置审计。
