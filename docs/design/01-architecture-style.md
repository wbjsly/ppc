# 技术与设计规范（完整版）

> 本文是一份覆盖 **技术栈 → 后端分层 → 前端分层 → 设计规范 → 交互逻辑 → 测试 → 已知问题** 的完整参考。
>
> 适用场景：新功能开发、新页面开发、风格对齐、代码审查参照、新人上手。
>
> **基础设施 Docker 化**：MySQL、MinIO、Redis、RabbitMQ、Nginx 一律以 Docker 容器部署（`deploy/docker-compose.yml` 统一编排，见 1.3）。
>
> **后端分层**：Controller → Service 接口 → ServiceImpl → DAO → Entity（必须包含 Service 接口层，见 2.1）。
>
> 目录结构：`backend/`（Spring Boot 后端）、`frontend/`（Vue 3 前端）、`deploy/`（Docker Compose 编排与 Nginx 配置）、`docs/`（文档）。

---

# 第一部分 工程总览与技术栈

## 1.1 后端技术栈

| 类别 | 技术 | 版本 | 说明 |
|------|------|------|------|
| 核心框架 | Spring Boot | 2.7.18 | Java 17，单 JAR 打包 |
| ORM | MyBatis-Plus | 3.5.5 | `BaseMapper` + `LambdaQueryWrapper`，分页/乐观锁拦截器 |
| 数据库 | MySQL | 8.0（建议 8.0.46） | Docker 容器 `localhost:3306`；InnoDB + `utf8mb4`，库名 `wh_pm`；Druid 连接池（max-active=20）；驱动 `mysql-connector-java` 8.0.33 |
| 工作流 | Flowable | 6.8.0 | 审批流程引擎，BPMN 2.0，`act_*` 表与业务表同库 |
| 安全 | Spring Security + jjwt | 0.11.5 | 无状态 JWT 认证，Token 有效期 24h |
| 缓存 | Spring Data Redis | — | Docker 容器 `localhost:6389`（容器内 6379），用于认证 token 黑名单 |
| 消息队列 | RabbitMQ（Spring AMQP） | 3.13-management | Docker 容器 `localhost:5672`（AMQP）/ `localhost:15672`（管理台）；异步事件：成本事件、审批通知、集成事件，详见 2.8 |
| 对象存储 | MinIO | 8.5.7 | Docker 容器 `localhost:9010`，交付物附件上传 |
| API 文档 | Knife4j (OpenAPI 3) | 4.3.0 | `/doc.html` |
| 工具库 | Hutool / Lombok | 5.8.25 / — | 通用工具 / 代码简化 |
| 测试 | JUnit 5 + MockMvc + JaCoCo | — | `@SpringBootTest` 集成测试，覆盖率报告 |

**MySQL 版本选型理由（8.0 系列，建议 8.0.46）：**

- Spring Boot 2.7.18 默认托管 `mysql-connector-java` 8.0.x，与 MyBatis-Plus 3.5.5、Flowable 6.8.0、Druid 1.2.20 的 MySQL 兼容矩阵一致，无需额外版本调和；
- 相比已 EOL 的 MySQL 5.7，8.0 提供默认 `utf8mb4`、窗口函数与 CTE（预实对比/成本分析 SQL 可用）、在线 DDL；
- 相比 8.4（LTS），8.0 与本栈（Boot 2.7 + 老版 Connector）的兼容性验证更充分，风险更低。
- 统一字符集 `utf8mb4`、排序规则 `utf8mb4_0900_ai_ci`；表引擎一律 InnoDB。

## 1.2 前端技术栈

| 类别 | 技术 | 版本 | 说明 |
|------|------|------|------|
| 核心框架 | Vue 3 | 3.4.21 | Composition API + `<script setup>` |
| 构建工具 | Vite | 5.2.0 | dev 端口 4185，`/api` 代理到后端 8090 |
| UI 组件库 | Element Plus | 2.6.1 | 配 `@element-plus/icons-vue` 图标 |
| 路由 | Vue Router | 4.3.0 | 扁平子路由 + 全局守卫 |
| 状态管理 | Pinia | 2.1.7 | 4 个 store（user/tab/menu/dict） |
| HTTP | Axios | 1.6.7 | 统一封装 `utils/request.js` |
| 图表 | ECharts | 6.0.0 | 项目详情页使用 |
| 自动导入 | （已移除）unplugin-auto-import / unplugin-vue-components | — | 原按需解析与全量注册双轨并存，已统一为全量注册（见 5.9，问题 #9 修复） |
| 单元测试 | Vitest + @vue/test-utils + happy-dom | 4.x | 58 个测试文件，v8 覆盖率 |
| E2E 测试 | Playwright | 1.60 | 仅 chromium，12 个业务域 spec |

## 1.3 工程化与基础设施

- **Monorepo 脚本**（根 `package.json`）：`npm run dev` 用 `concurrently` 同时前后端启动；`dev:fe` / `dev:be` 单独启动；`build` 构建前端到 `wh-frontend/dist/`；`install:all` 安装依赖。
- **端口约定**：后端 8090 / 前端 4185 / MySQL 3306 / Redis 6389 / MinIO 9010 / RabbitMQ 5672（管理台 15672）/ Nginx 80。Vite 将 `/api` 代理到 `http://localhost:8090`。
- **容器化部署（Docker）**：`deploy/docker-compose.yml` 统一编排以下容器，开发与生产同构，`docker compose up -d` 一键拉起全部基础设施：

  | 服务 | 镜像 | 端口映射 | 说明 |
  |------|------|----------|------|
  | mysql | `mysql:8.0` | 3306→3306 | 库 `wh_pm`；`mysql-data` 卷持久化；字符集 `utf8mb4` |
  | redis | `redis:7-alpine` | 6389→6379 | 认证 token 黑名单；设密码，仅应用可访问 |
  | minio | `minio/minio` | 9010→9000、9011→9001 | `minio-data` 卷；9011 为控制台 |
  | rabbitmq | `rabbitmq:3.13-management` | 5672→5672、15672→15672 | `rabbitmq-data` 卷；15672 为管理台；账号密码走环境变量 |
  | nginx | `nginx:alpine` | 80→80 | 挂载 `deploy/nginx.conf` 与 `frontend/dist`；静态资源 + `/api` 反向代理 |
  | backend | 自构建（`eclipse-temurin:11-jre` + Spring Boot JAR） | 8090→8090 | `depends_on` + healthcheck 等待 mysql/redis/minio/rabbitmq 就绪后启动 |

- **网络与地址约定**：容器间互访走 compose 网络服务名（`mysql:3306`、`redis:6379`、`minio:9000`、`rabbitmq:5672`、`backend:8090`）；宿主机进程（开发期 Spring Boot / Vite）走上表映射端口。Spring 按 profile 切地址：`application-docker.yml`（容器服务名）/ `application-mysql.yml`（localhost 映射端口，开发默认）。
- **部署**：生产由 Nginx 容器托管 `frontend/dist` 静态资源并反向代理 `/api` → `backend:8090`（`deploy/nginx.conf`），不再依赖宿主机 Nginx。
- **数据库迁移**：`DbBootstrap`（原 `SqliteBootstrap`，`InitializingBean + ApplicationRunner`）启动时扫描 `classpath:db/mysql/*.sql` 按文件名排序执行，`erp_ops_bootstrap_marker` 表记录已执行脚本；脚本统一以 InnoDB + `utf8mb4` 建表；编号规则 `NNN-模块-描述.sql`（001-003 基础、009 Flowable schema、010+ 按 PM 知识域、022+ 功能增量、099 种子数据），新迁移追加末尾编号。
- **流程定义**：BPMN 文件位于 `wh-backend/src/main/resources/bpmn/`。
- **参考文档**：`docs/busi/` 业务需求、`docs/tech/` 开发规范（`devspec-code.md`、`devspec-db.md`）、`docs/roles/` 角色指南、`openspec/specs/` 功能规格。

## 1.4 数据库表命名规范（按业务域前缀）

所有表以 `erp_` 开头，第二段为业务域前缀，第三段为表名语义，全小写下划线分隔：`{业务域前缀}_{表名}`。例如系统管理域的用户表为 `erp_admin_user`。

| 业务域 | 原始前缀 | 实际表前缀 |
|--------|----------|------------|
| 基础数据 | erp-mdm | `erp_mdm_` |
| 采购管理 | erp-procurement | `erp_procurement_` |
| 销售管理 | erp-s&d | `erp_sd_` |
| 库存管理 | erp-wms | `erp_wms_` |
| 生产管理 | erp-mrp | `erp_mrp_` |
| 质量管理 | erp-qms | `erp_qms_` |
| 资产管理 | erp-eam | `erp_eam_` |
| 财务管理 | erp-finance | `erp_finance_` |
| 税务管理 | erp-tax | `erp_tax_` |
| 人力资源 | erp-hr | `erp_hr_` |
| 客户关系 | erp-crm | `erp_crm_` |
| 项目研发（研发） | erp-rd | `erp_rd_` |
| 项目研发（项目） | erp-pm | `erp_pm_` |
| 外部协同 | erp-scm | `erp_scm_` |
| 移动物联 | erp-iot | `erp_iot_` |
| 报表分析 | erp-bi | `erp_bi_` |
| 集成门户 | erp-portal | `erp_portal_` |
| 系统管理 | erp-admin | `erp_admin_` |
| 本地适配 | erp-i18n | `erp_i18n_` |
| 运维管理 | erp-ops | `erp_ops_` |

**说明**：原始规范中的连字符与 `&` 是 MySQL 标识符雷区（连字符在原生 SQL 与 MyBatis-Plus 生成的 SQL 中必须反引号包裹，`&` 为保留字符），经确认统一改用下划线；`erp-s&d` 取 `erp_sd`。表内索引名（`UK_*` / `IDX_*`）不在前缀规范范围内。

**已落库表**：`erp_admin_user` / `erp_admin_role` / `erp_admin_menu` / `erp_admin_user_role`（系统管理域）、`erp_ops_bootstrap_marker`（运维管理域，迁移脚本执行记录）。

---

# 第二部分 后端代码层级逻辑

## 2.1 分层总览

```
┌───────────────────────────────────────────────────────────────────┐
│  Controller   (com.wh.controller)        参数接收、调用 Service 接口 │
│      ↓                                                            │
│  Service 接口  (com.wh.service)           业务能力契约（面向接口编程） │
│      ↓                                                            │
│  ServiceImpl  (com.wh.service.impl)      @Service，核心业务逻辑      │
│      ↓                                                            │
│  DAO   (com.wh.dao)                      MyBatis-Plus Mapper       │
│      ↓                                                            │
│  Entity   (com.wh.entity)                数据表映射                │
└───────────────────────────────────────────────────────────────────┘
```

**关键约定：必须有 Service 接口层。**

- **命名**：每个业务模块一对 `XxxService`（接口）+ `XxxServiceImpl`（实现类，`@Service`），接口方法与用例一一对应。
- **依赖方向**：Controller 构造注入 **Service 接口**，禁止注入实现类；`ServiceImpl` 实现接口并注入 DAO，承担事务边界（`@Transactional`）与流程编排；单测可面向接口打桩。
- **跨层规则**：Controller 不得直接触达 DAO；接口只暴露业务语义方法，`LambdaQueryWrapper` 等持久化细节封装在实现内；Service 之间可互相注入，但禁止循环依赖。
- 原 `bo/` 包职责整体迁移为 `service/`（接口）+ `service/impl/`（实现），Controller 由调用 BO 改为调用 Service 接口。

## 2.2 包结构与职责（`com.wh`，约 247 个 Java 文件）

| 包 | 文件数 | 职责 |
|----|-------|------|
| `controller/` | 16 | HTTP 接入层，仅做参数接收与 Service 接口调用；`pm/` 10 个 + `system/` 5 个 + `AuthController` |
| `service/` | 39 | **Service 接口层**（`service/pm` 33 + `service/system` 5 + `SequenceService`），声明业务能力契约 |
| `service/impl/` | 39 | **Service 实现层**（原 `bo/` 迁移）：CRUD + 审批编排 + 业务规则 + `@Transactional` 事务边界，含 `SequenceServiceImpl` |
| `dao/` | 25 | MyBatis-Plus `BaseMapper` 接口（`dao/pm` 15、`dao/system` 9、`dao/sequence` 1） |
| `entity/` | 30 | 表映射实体（`entity/pm` 15、`entity/system` 11、`entity/approval` 2 + `BaseEntity` 等） |
| `vo/` | 8 | 返回视图对象，聚合/脱敏后的出参 |
| `common/` | 3 | `R<T>` 统一响应、`GlobalExceptionHandler`、`ServiceException` |
| `config/` | 10 | 数据源、Flowable、MyBatis-Plus、Knife4j、MinIO、RabbitMQ（`MqConfig`）、CORS 等配置类 |
| `security/` | 3 | `JwtTokenProvider`、`JwtAuthenticationFilter`、`SecurityConfig` |
| `approval/` | 7 | Flowable 审批回调注册表 + 各业务回调实现 |
| `bootstrap/` | 1 | `DbBootstrap`（原 `SqliteBootstrap`）数据库迁移引导 |
| `mq/` | 3 | RabbitMQ 生产者/消费者：报文转换、幂等去重、死信处理，业务逻辑转调 Service 接口（见 2.8） |
| `cost/` + `task/` | 4 | 成本事件经 RabbitMQ 发布（见 2.8）+ `CostWarningScheduledTask` 定时预警（cron `0 0 6 * * ?`，三级阈值 80%/95%/100%，触发后发 MQ 通知） |
| `util/` / `constant/` | 2 | `SecurityUtils` 当前用户工具、`DictTypes` 字典常量 |

主类 `WhApplication.java`（`@MapperScan("com.wh.dao")`）。

## 2.3 典型调用链（以 Charter 项目立项为例）

```java
// 1. Controller —— controller/pm/WhPmCharterController.java
@RestController @RequestMapping("/api/pm/charters")
public class WhPmCharterController {
    private final WhPmCharterService charterService;   // 构造注入接口，不注入实现类
    public R<IPage<WhPmCharter>> list(@RequestParam int pageNum, @RequestParam int pageSize, ...)
        { return R.ok(charterService.pageList(...)); }
}

// 2. Service 接口 —— service/pm/WhPmCharterService.java
public interface WhPmCharterService {
    IPage<WhPmCharter> pageList(...);   // 查询条件封装在实现内，不外泄 Wrapper
    void submit(String id);             // 提交审批用例
}

// 3. Service 实现 —— service/impl/WhPmCharterServiceImpl.java
@Slf4j @Service
public class WhPmCharterServiceImpl implements WhPmCharterService {
    // 构造注入 DAO、SysUserService、SequenceService、Flowable RuntimeService/TaskService
    @Override public IPage<WhPmCharter> pageList(...)  // LambdaQueryWrapper 组装查询条件
    @Override @Transactional
    public void submit(String id)                      // runtimeService.startProcessInstanceByKey(...)
}

// 4. DAO —— dao/pm/WhPmCharterDao.java
@Mapper public interface WhPmCharterDao extends BaseMapper<WhPmCharter> { ... }

// 5. Entity —— entity/pm/WhPmCharter.java
public class WhPmCharter extends BaseEntity { ... }
```

WBS 模块同构：`WhPmWbsElementController`（`/api/pm/wbs`，18 个端点，含 `submitModify` / `approveModify` / `importWbs`）→ `WhPmWbsElementService` 接口 → `WhPmWbsElementServiceImpl`（14 处 `@Transactional` 覆盖 create/update/delete/suspend/start/test/complete/cancel 等状态流转）→ `WhPmWbsElementDao` → `WhPmWbsElement`（`@TableName("pm_wbs_element")`）。

## 2.4 横切组件

| 组件 | 路径 | 职责 |
|------|------|------|
| `R<T>` | `common/R.java` | 统一响应 `{code, message, data, timestamp}`；`R.ok(data)` / `R.fail(msg)` |
| `GlobalExceptionHandler` | `common/GlobalExceptionHandler.java` | `@RestControllerAdvice`：`ServiceException` → 自定义 code；`MethodArgumentNotValidException` → 400；兜底 `Exception` → 500 |
| `ServiceException` | `common/ServiceException.java` | 业务异常，携带 int code（默认 500） |
| `BaseEntity` | `entity/BaseEntity.java` | UUID 主键（`ASSIGN_UUID`）；`createBy/createDate/updateBy/updateDate` 自动填充；`delFlag` 逻辑删除（`@TableLogic`）；`verNo` 乐观锁（`@Version`） |
| `MyBatisPlusConfig` | `config/MyBatisPlusConfig.java` | 注册 `PaginationInnerInterceptor(DbType.MYSQL, maxLimit=1000)` + `OptimisticLockerInnerInterceptor` + `MetaObjectHandler`（审计字段填充，用户取自 `SecurityUtils.getCurrentUserId()`） |
| `JwtAuthenticationFilter` | `security/JwtAuthenticationFilter.java` | 解析 `Authorization: Bearer`，roles claim → `SimpleGrantedAuthority` 写入 SecurityContext |
| `SecurityConfig` | `security/SecurityConfig.java` | 权限规则集中定义：`/api/auth/**`、doc.html 匿名；dict/menus 写操作 `hasRole("ADMIN")`；其余 `/api/**` 需认证 |

## 2.5 配置层（`config` 包）

- `BusinessDataSourceConfig` — `@Bean("dataSource")`，Druid + MySQL：`jdbc:mysql://localhost:3306/wh_pm?useUnicode=true&characterEncoding=utf8mb4&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true`；`max-active=20`，写并发由 InnoDB 行锁与默认事务隔离级别处理
- `FlowableConfig` — 导出 `ProcessEngine/RuntimeService/TaskService` 等 Bean；`databaseSchemaUpdate=false`（`act_*` 表结构由迁移脚本统一管理）、关闭 async executor/IDM；MySQL 下不再需要 SQLite 时代的自定义 TypeHandler
- `MqConfig` — Spring AMQP：`CachingConnectionFactory`（开发 `localhost:5672` / 容器 `rabbitmq:5672`）、声明交换机/队列/死信队列、生产者 confirm、消费者手动 ack，约定详见 2.8
- `MyBatisPlusConfig`、`Knife4jConfig`、`MinioConfig`、`WebMvcConfig`（CORS + 静态资源）、`UuidIdGenerator`

`application.yml` 关键项：`server.port=8090`、profiles `mysql,docker,dev`、`flowable.process-definition-location-prefix=classpath:bpmn/`、`mybatis-plus.global-config`（`id-type=assign_uuid`、`logic-delete-field=delFlag`）、`spring.rabbitmq.*`（host/port/username/password，随 profile 切换）、`app.jwt.*`、`app.minio.*`、`app.redis.*`、`app.db.bootstrap.*`（原 `app.sqlite.bootstrap.*`：`enabled` 启/禁用、`skip-scripts` 跳过列表）、`cost.warning.*`。明细配置：`application-mysql.yml`（宿主机映射端口，开发默认，原 `application-sqlite.yml`）、`application-docker.yml`（compose 服务名，容器内运行）。

## 2.6 Flowable 审批工作流（回调注册表模式）

```
BPMN endEvent
  → ${flowableProcessEndListener}          (FlowableProcessEndListener, ExecutionListener)
      → 读取流程变量 flowCode / bizId / approvalResult / rejectReason
          → ApprovalCallbackRegistry.getCallback(flowCode)
              → ApprovalCompletedCallback.onApproved(bizId, params) / onRejected(bizId, reason)
```

- **`ApprovalCompletedCallback` 接口**：`getFlowCode()` / `onApproved(bizId, params)` / `onRejected(bizId, reason)`
- **`ApprovalCallbackRegistry`**：`@Component`，构造器注入 `Map<String, ApprovalCompletedCallback>`（Spring 自动收集所有实现），按 flowCode 建索引
- **已注册回调**：`CharterApprovalCallback`（PM_CHARTER_APPROVAL）、`BudgetApprovalCallback`（PM_BUDGET_APPROVAL）、`DeliverableApprovalCallback`（PM_DELIVERABLE_APPROVAL）、`PmWbsModifyApprovalCallback`（PM_WBS_MODIFY_APPROVAL）
- **BPMN 流程**（`resources/bpmn/` 4 个 `.bpmn20.xml`）：提交确认 → 发起人审批 → 排他网关 → 通过/驳回 end
- 审批实体：`WhApprovalInstance` / `WhApprovalRecord`（DDL 见 `db/mysql/003-approval-engine.sql`）

## 2.7 Controller 模块划分

| 分组 | 前缀 | 模块 |
|------|------|------|
| 认证 | `/api/auth` | login / logout / info |
| 业务 `controller/pm/` | `/api/pm/*` | charters、wbs、budgets、deliverables、work-hours、actual-costs、cost-warnings、modules、products（+ work-calendar 走 `/api/system/`） |
| 系统 `controller/system/` | `/api/system/*` | users、roles、menus、dict、cost-quota |

## 2.8 RabbitMQ 消息队列

| 项 | 约定 |
|----|------|
| 客户端 | Spring AMQP（`spring-boot-starter-amqp`）；开发连 `localhost:5672`，容器内连 `rabbitmq:5672`；管理台 `localhost:15672` |
| 交换机/队列 | 直接事件走 `erp.direct`，广播通知走 `erp.topic`；队列命名 `erp.<域>.<对象>.<动作>`；死信队列统一 `erp.dlx.<原队列名>` |
| 生产者 | 只在事务提交后发送：`@TransactionalEventListener(phase = AFTER_COMMIT)`，避免回滚产生脏消息；开启 publisher confirm + return，发送失败进重试 |
| 消费者 | 落在 `mq/` 包（`@RabbitListener`）；手动 ack；以 `bizId + 事件类型` 为业务键做幂等去重；失败重试 3 次（退避 5s/30s/2min）后进死信队列，由定时任务告警兜底；消费者只做报文转换与幂等，业务逻辑转调 Service 接口 |
| 场景 | ① 成本事件（成本归集/预警消费）② 审批结果通知 ③ 集成同步事件出站投递（对应菜单「事件订阅」「报文网关」）④ 成本三级阈值（80%/95%/100%）触发的通知 |

---

# 第三部分 前端代码层级逻辑

## 3.1 目录结构

```
wh-frontend/src/
├── main.js              入口：app.use(ElementPlus)、注册图标、挂载 router/pinia
├── App.vue              根组件：v-if 控制是否渲染 MainLayout（登录页除外）
├── views/               页面（38 个 .vue），按业务域分模块
│   ├── pm/              charter(3) wbs(5) budget(6) deliverable(3) work-hours(2) product(1)
│   ├── system/          user(4) dict menu calendar cost-quota
│   ├── dashboard/  login/
├── components/          公共组件（共 4 个）
│   ├── layout/MainLayout.vue、layout/SidebarMenu.vue
│   ├── MonthCalendar.vue、WorkHourDialog.vue
├── api/                 API 模块（14 个）
│   ├── auth.js  pm/（8 模块）  system/（5 模块）
├── router/index.js      路由定义 + 全局守卫（一体）
├── store/               Pinia：user.js  tab.js  menu.js  dict.js
├── styles/              element-overrides.css（全局唯一共享覆写，见 4.1/4.5，问题 #6 修复）
├── utils/request.js     Axios 封装（拦截器）
└── __tests__/           Vitest 单测，镜像 src 结构（58 个文件）
```

特例：`views/pm/budget/upgrade/` 是页面内子组件目录（6 个 Section 组件 + `format.js` + `useBudgetUpgrade.js` 组合式 hook）。

## 3.2 分层调用链

```
View (views/**.vue)
  → 组件交互（props/emits/ref 调用子组件）
  → API 模块 (api/**/*.js)          命名 getXxxApi / submitXxxApi
  → utils/request.js                Axios 实例：baseURL '/api'、token、错误处理
  → Vite dev proxy → 后端 :8090
  → Pinia store (store/*.js)        跨页面状态：用户/标签页/菜单/字典
```

## 3.3 路由设计（`router/index.js`）

- **扁平子路由**：`/login` 独立；`/` 为父路由（`redirect: '/dashboard'`），所有业务页作为扁平 children 挂在其下。
- **布局在视图层而非路由层**：没有路由级 Layout 组件——`App.vue` 用 `<MainLayout v-if="showLayout" />`（`route.path !== '/login'`）包裹，MainLayout 内部才有 `<router-view>`。
- **meta 约定**：`title`（tab/文档标题）、`group`（菜单分组）、`perm`（如 `'ROLE_PM,ROLE_SPONSOR'`）、`hidden: true`（详情/表单页不进标签栏）、`closable: false`（Dashboard 常驻不可关闭）。
- **典型路由四件套**：`/pm/charter`（index 列表）、`/detail/:id`、`/form`、`/form/:id`，全部懒加载 `() => import('@/views/...')`。
- **全局守卫 `beforeEach`**（动态 `import('@/store/...')` 避免循环依赖），顺序：
  1. 设置 `document.title`
  2. 无 token → `next('/login?redirect=...')`
  3. `userInfo` 为空 → `await userStore.getUserInfo()`
  4. **权限拦截**：`meta.perm` 存在时按逗号拆分与 `userInfo.roles` 求交集，无交集且非 `ROLE_ADMIN` → `ElMessage.warning('无权访问该页面')` + `next('/dashboard')`（修复问题 #2，直接输 URL 也无法进入受限页）
  5. `dictStore.loadAll()`（防重入 `loaded/loading`，fire-and-forget）
  6. `menuStore.fetchMenus()`
  7. `tabStore.addTab(to)`
- **权限说明（三层防线）**：路由守卫按 `meta.perm` 拦截直达 URL；`SidebarMenu.vue` 的 `visibleItems` 过滤菜单可见性（按 `userStore.userInfo.roles` 匹配 `item.perm`，`ROLE_ADMIN` 全通）；后端 `SecurityConfig` 接口级兜底。`meta.perm` 是前端唯一权限声明，新路由漏填即视为无限制——列为 Code Review 必查项。

## 3.4 Pinia Store 职责

| Store | 风格 | 状态 | 说明 |
|-------|------|------|------|
| `user.js` | options | `token` / `userInfo` / `permissions` | actions：`login/getUserInfo/logout`；仅 token 手动持久化到 localStorage（无插件级持久化） |
| `tab.js` | — | `tabs[]` / `activeTab` | `addTab`（hidden 路由只切 activeTab 不建 tab）、`removeTab/closeLeft/closeRight/closeOther/closeAll`；Dashboard 常驻 |
| `menu.js` | setup | `menuItems` / `loaded` / `loading` | `fetchMenus()` 拉取 `/api/system/menus/user` 动态菜单；`reset()` |
| `dict.js` | — | `dictMap`（两级 `typeCode → itemCode → {label, tagType, itemValue}`） | getters：`getDictItems / getLabel / getTagType`（柯里化）；`loadAll()` 首跳守卫时懒加载、幂等，失败降级返回原 code |

## 3.5 API 层（`utils/request.js`）

- `axios.create({ baseURL: '/api', timeout: 15000 })`，dev 环境 Vite 代理到 `http://localhost:8090`。
- **请求拦截**：从 localStorage 取 token → `Authorization: Bearer <token>`。
- **响应拦截**：
  - `res.code !== 200` → `ElMessage.error(message)`
  - `code === 401` 或 HTTP 401/403 → 清 token + `router.push('/login')`
  - 其他 HTTP 错误 → "系统错误"；无响应 → "网络异常"
  - 成功 → **直接返回 `res`**（页面统一取 `res.data`）
  - **弹错单点化（问题 #4 修复）**：以上错误提示只在拦截器发生一次；页面 `catch` 分支禁止再调 `ElMessage.error`，只做静默恢复（重置 loading、回滚表单状态）
- **模块写法**（`api/pm/charter.js`）：

```js
export function getCharterListApi(params) { return request.get('/pm/charters', { params }) }
export function createCharterApi(data)    { return request.post('/pm/charters', data) }
export function submitCharterApi(id)      { return request.post(`/pm/charters/${id}/submit`) }
```

命名统一 `getXxxApi / createXxxApi / submitXxxApi`，RESTful 路径 + 动作后缀（`/submit`、`/approve`、`/reject`）。

## 3.6 页面（views）组织模式

按业务域分模块，模块内固定三种页面：

| 页面类型 | 文件 | 结构模式 |
|---------|------|---------|
| 列表页 | `index.vue` | `el-card` → header 放查询区（`queryParams` reactive + `el-select/el-input` + 查询/重置）→ `el-table`（`v-loading`、操作列 `fixed="right"` 按 `row.status` 条件渲染）→ `el-pagination`；`onMounted` + `onActivated` 双触发加载 |
| 表单页 | `form.vue` | 新增/编辑同一文件，靠 `route.params.id` 区分；`el-steps` 分步 + `v-show` 切换；`el-form :rules` + `formRef.validate()`；成功后 `ElMessage.success` + `router.push` 回列表 |
| 详情页 | `detail.vue` | `el-tabs` 分区（如 Charter：项目信息/预算/成本/工时），各 tab 懒加载数据 |

个别模块附加 `history.vue`（版本历史）、`approval.vue`（审批）、`upgrade.vue`（预算版本升级）。

---

# 第四部分 前端设计规范

## 4.1 总体定位

**「Element Plus 默认主题 + 深色侧栏 + 灰底白卡」的典型中后台风格。**

| 项 | 结论 |
|---|---|
| 主题定制 | **无**。全项目 0 个 CSS 自定义属性、0 处主题变量覆写 |
| 全局样式 | `main.js` 引入 `element-plus/dist/index.css` + `src/styles/element-overrides.css`（唯一共享覆写文件，收敛重复 `:deep` 规则，问题 #6 修复） |
| 样式存放 | 各组件 scoped CSS 写死十六进制值；跨组件复用只进两个共享文件：`styles/element-overrides.css`（全局覆写）、`budget/upgrade/section-common.css`（分区范式） |
| `:deep` 覆写 | 修复后全项目 8 处、4 个文件 + 共享覆写文件 1 个（仅用于压 EP 内边距/背景、语义标红） |
| 暗色模式 | 无（深色仅出现在固定侧栏区域） |
| 响应式 | 无 `@media` 断点；靠侧栏折叠、表格固定列、flex 换行、图表 resize |
| 动画 | 仅 5 处 `transition`，0 处 `@keyframes`，0 处渐变 |

## 4.2 色板（设计 Token 实况）

### 语义色（Element Plus 默认四色 + 信息灰）

| 语义 | 色值 | 用途 |
|---|---|---|
| 主色 | `#409EFF` | 主按钮、链接、激活态、强调数字、左蓝条 |
| 成功 | `#67C23A` | 完成/通过/正常、绿底浅色 `#f0f9eb` |
| 警告 | `#E6A23C` | 进行中/草稿/超阈值、橙底浅色 `#fdf6ec` |
| 危险 | `#F56C6C` | 逾期/驳回/超支/删除、红底浅色 `#fef0f0` |
| 信息 | `#909399` | 未开始/禁用/占位、次要说明文字 |
| 主色浅底 | `#ECF5FF` | 今天高亮、激活页签底色 |

### 中性色

| 用途 | 色值 |
|---|---|
| 侧栏底色 | `#304156`（菜单区）、`#263445`（Logo 头/搜索框） |
| 侧栏文字 | `#BFCBD9`，激活 `#409EFF` |
| 页面底色 | `#F0F2F5` |
| 卡片/顶栏底 | `#FFFFFF` |
| 区块头/表头/浅灰块 | `#F5F7FA`；更浅 `#FAFAFA`；非当月 `#F9F9F9` |
| 网格边框 | `#EBEEF5`；分隔 `#E4E7ED`；虚线 `#DCDFE6`；顶栏底边 `#E8E8E8` |
| 文字三级 | 主 `#303133` / 次 `#606266` / 辅助 `#909399` |
| 占位/禁用 | `#C0C4CC`；散见 `#999` `#666` `#333` |

### 业务逻辑色（阈值驱动，必须遵循）

| 场景 | 规则 | 出处 |
|---|---|---|
| 预算执行率 | `<0.8` 绿 · `<0.95` 橙 · `≥0.95` 红 | `pm/budget/comparison.vue:164`、`pm/charter/detail.vue:389` |
| 预算余额 | `<0` 红；投入比例 `>1` 红、`>0.9` 橙 | `pm/budget/index.vue:136-146` |
| 差异额（红涨绿跌） | `>0` `#F56C6C` · `<0` `#67C23A` · `=0` `#303133` | `pm/budget/upgrade/format.js:10` |
| 零定额 | 输入框内容红 + 加粗 | `budget/form.vue:685`、`LaborSection.vue:100` |
| 实际成本 | 恒 `#E6A23C` 加粗 | 预算升级族各组件 |
| 工时条状态 | 草稿 `#fdf6ec/#e6a23c`、通过 `#f0f9eb/#67c23a`、驳回 `#fef0f0/#f56c6c`，带 3px 左边框 | `MonthCalendar.vue:386-400` |

### 图表色（ECharts，仅 `pm/charter/detail.vue` 使用）

- 成本 7 色：人工 `#409eff` · 采购 `#67c23a` · 差旅 `#e6a23c` · 商务 `#f56c6c` · 招待 `#909399` · 活动 `#b37feb` · 其他 `#6b7b8d`
- 工时状态 3 色：草稿 `#909399` · 已提交 `#e6a23c` · 已审批 `#67c23a`
- 未使用 ECharts 默认调色盘，未注册主题

## 4.3 字体与间距

**字体**
- `font-family`：全项目 0 处自定义，沿用 Element Plus 默认（含微软雅黑）
- 字重：`font-weight: bold` 为唯一加粗方式（63 处），`600` 仅 1 处

**字阶**

| 字号 | 语义 |
|---|---|
| 32px bold | 仪表盘统计大数（`stat-value`） |
| 28px bold | 工时概况数值 |
| 24 / 20px bold | 汇总数字（`summary-value`、KPI 卡数值） |
| 16px bold | **卡片页头标题**、侧栏 Logo |
| 15px bold | 区块标题（`section-title`、dashboard 卡头） |
| 14px | 正文/表单默认 |
| 13px | 次要说明、小节标题（`section-subtitle`）、标签 |
| 12px | 表格辅助、徽章、图表 label |

**间距**
- 基数：**4 / 8 / 12 / 16 / 24**；16px 为区块与页边距基准，8px 为元素间距基准
- 内容区 `padding: 16px`；栅格 `:gutter="16"`（主流）/ `20`
- 常用 `margin-bottom`：8 / 16 / 12 / 24；卡片 header 内操作区 `gap: 8px`

## 4.4 布局骨架

**结构**（`src/components/layout/MainLayout.vue`）：

```
el-container (100vh)
├─ el-aside          220px ↔ 折叠 64px · #304156 · transition width .3s
├─ el-header 40px    白底 · border-bottom 1px #e8e8e8 · card 型多页签 + 用户下拉
└─ el-main           padding:16px · background:#f0f2f5 · overflow-y:auto
   └─ router-view + keep-alive
```

**标准页面模板**（约 20 个页面遵循）：

```html
<div class="xxx-page">
  <el-card>
    <template #header>
      <div class="card-header">  <!-- flex; space-between; center -->
        <span style="font-weight:bold;font-size:16px;">标题</span>
        <div class="header-actions">  <!-- gap:8px -->
          筛选 select/input + 查询 + 重置 + 图标新增按钮
        </div>
      </div>
    </template>
    <el-table stripe row-key="id" v-loading="loading" />
    <el-pagination style="margin-top:16px; justify-content:flex-end" />
  </el-card>
</div>
```

## 4.5 组件风格

### 自有公共组件（`src/components/`，共 4 个）

| 组件 | 职责 | 风格与交互要点 |
|---|---|---|
| `layout/MainLayout.vue` | 全站外壳 | 页签 `type="card"`、去下划线（`:deep`）；**右键页签菜单**（关闭/左/右/其他/全部，`position:fixed; z-index:9999`，`0 2px 12px rgba(0,0,0,.15)` 阴影）；`keep-alive :include` 按页签白名单缓存（见 5.4，问题 #3 修复） |
| `layout/SidebarMenu.vue` | 深色侧栏 | 菜单三色属性 `#304156/#bfcbd9/#409EFF`；Logo 头 `#263445` 高 50px；菜单实时搜索（深底搜索框 + 蓝色聚焦环）；角色过滤菜单可见性 |
| `MonthCalendar.vue` | 工时月历 | 自绘 flex 网格（非 el-calendar）；日格 `min-height:100px`；日期三色底（工作日 `#f0f9eb`/周末 `#fef0f0`/节假日 `#fdf6ec`，今天 `#ecf5ff`）；工时条状态色 + 3px 左边框、hover `opacity:.85`；第 8 列周汇总（80px，`#fafafa`）；格右上悬浮 `+`；点非当月格跳月 |
| `WorkHourDialog.vue` | 工时录入弹窗 | 500px；4 种 footer 形态（新建/草稿编辑/驳回编辑/只读）；`el-input-number` 0.5~24 步进 0.5 默认 8；`defineExpose({open})` 命令式打开 |

### Element Plus 使用规范

| 组件 | 工程惯例 |
|---|---|
| `el-table` | `row-key` + `v-loading` + `stripe` 主流（17 处）；`border` 仅预实对比 2 处；操作列 `fixed="right"`；金额列 `align="right"` + `font-variant-numeric: tabular-nums`；**无排序、无列筛选** |
| `el-button` | 无 round、EP 默认 4px 圆角；`primary` 74 处；**`link` 67 处**（表格行内主流）；新增按钮惯用纯图标 + tooltip；`:loading` 仅提交类（22 处） |
| `el-tag` | 几乎全 `size="small"`；颜色由后端字典 `dictStore.getTagType()` 下发（`store/dict.js:32`），本地仅兜底映射 |
| `el-form` | `label-width` 三档：弹窗 80px / 中 100px / 页面级 120px；双列 `el-row :gutter="16"` + `el-col :span="12"` |
| `el-dialog` | 宽度按信息量 360~640px；footer 恒为「取消 + primary 确定(`:loading`)」；**无 `destroy-on-close`**，靠打开时重置数据 |
| `el-card` | 页面主卡默认阴影 + `#header` 插槽；`shadow="hover"` 仅 dashboard（5 处）；`shadow="never"` 仅 KPI 小卡（8 处） |
| `el-descriptions` | 详情页标准：统一 `border`，`:column="2"`（详情）/ `4`（高密度 tab） |
| `el-pagination` | `layout="total, sizes, prev, pager, next"` + `margin-top:16px; justify-content:flex-end`（无 jumper） |
| `el-select` | 筛选类固定宽 100~240px + `clearable filterable`；多选统一 `multiple collapse-tags collapse-tags-tooltip` |
| `el-tooltip` | 53 处，几乎清一色 `placement="top"` 包图标按钮补文字 |
| `el-divider` | 8 处，全部作分节标题（预算科目/合同信息/预实对比） |
| `el-alert` | 3 处，均 `show-icon :closable="false"`，页面内静态提示 |
| `el-steps` | 仅 charter 向导 1 组 |
| 面包屑 | **完全未使用**（0 处） |

### 业务内嵌组件

**预算升级族**（`src/views/pm/budget/upgrade/`，全站最成体系）：

- `section-common.css` —— **分区范式共享 CSS**（全站两个共享样式文件之一，另一个为 `styles/element-overrides.css`），被 4 个 section `@import`：
  - `.category-section`：`border:1px solid #ebeef5; border-radius:6px; margin-bottom:24px`
  - `.section-header`：`padding:12px 16px; background:#f5f7fa`
  - `.section-body`：flex，左栏 `flex:1` + `border-right:1px dashed #dcdfe6`，右栏 `flex:2` → **1:2 虚线分栏「调整前 | 调整后」**
  - `.section-subtitle`：`13px bold #606266; border-left:3px solid #409eff`（左蓝条小标题）
- `UpgradeInfoBar`：4 格只读信息条（`span=6`），label `13px #606266`
- `BudgetWaterfallPanel`：纯 flex 手写瀑布表（非 ECharts），`#f5f7fa` 底，金额 `tabular-nums`，实际成本列 `#e6a23c` 加粗
- `LaborSection / ProcurementSection / OtherSection`：左只读表 + 可编辑表；`OtherSection` 用 `A + B + …` 公式化横排（18px 粗体 `+` 运算符）
- `BudgetSummarySection`：`总预算 = 直接 + 管理储备` 汇总，value `14px bold`，运算符 `16px bold #909399`
- `format.js`：`diffColor` 红涨绿跌、`formatMoney` 用 `toLocaleString('zh-CN')`
- `useBudgetUpgrade.js`：页面级 composable（30+ 状态）；脏数据保护改为复用公共 `useUnsavedGuard` composable（见 5.6，问题 #5 修复）

**其它内嵌片段**：

| 片段 | 出处 | 要点 |
|---|---|---|
| 项目手风琴条 | `wbs/index.vue:20`、`product/index.vue:21`、`deliverable/index.vue:20` | `#f5f7fa` 灰条 + `cursor:pointer` + 箭头 `rotate(90deg)` 0.2s + 单开模式，展开才加载子表 |
| 附件 Popover | `deliverable/index.vue:49-76` | `trigger="hover" :show-after="200"`，唯一非 scoped style 块（`!important` 压 EP 链接色） |
| 导入结果三色 | `wbs/ImportDialog.vue` | `el-upload drag` + `el-statistic` 成功 `#67C23A` ✓ / 降级 `#E6A23C` ⚠ / 失败 `#F56C6C` ✗ |
| KPI 四联卡 | `budget/comparison.vue:18`、`charter/detail.vue:78` | `shadow="never"` ×4，value `24px bold`，比率三档色 |
| 批量操作条 | `work-hours/approval.vue:36-40` | 选中才出现，`#f5f7fa` 灰底 + `gap:12px`（全站唯一浮动批量条） |
| 预算算式列 | `budget/form.vue:44-59` | 20px 空列表头塞 `*` `=` 运算符，`16px bold #909399` |

### `:deep` 覆写全量清单（修复后 8 处 / 4 文件，另 2 处收敛至 `styles/element-overrides.css`）

| 文件 | 覆写 | 目的 |
|---|---|---|
| `MainLayout.vue:192,195` | `el-tabs__header margin-bottom:0`、`nav-wrap::after height:0` | 去页签下划线 |
| `SidebarMenu.vue:186,189,198` | 搜索框深底 + 蓝聚焦环；子菜单 `padding-left:50px` | 侧栏适配 |
| `styles/element-overrides.css`（原 `budget/form.vue:685`、`LaborSection.vue:100` 两处） | `.zero-rate .el-input__inner` 红 + 加粗 | 语义标红统一为共享规则（问题 #6 修复），原两处 `:deep` 已删除 |
| `dashboard/index.vue:230,257` | `el-card__body padding:20px`、`el-icon margin-right:8px` | 内边距/间距 |
| `login/index.vue:71` | `el-input__wrapper` 透明背景 | 输入框融入卡片 |

规律：**只压内边距/背景、语义标红，从不覆写主题色变量**。

## 4.6 页面分类风格（28 路由 → 10 类）

| 类型 | 代表页面 | 风格特征 |
|---|---|---|
| 登录页 | `login/index.vue` | 唯一脱离 Layout；flex 居中 + 400px 独立卡 + 透明输入框 + `size="large"` 满宽按钮 |
| 仪表盘 | `dashboard/index.vue` | **唯一无主卡**；`shadow="hover"` 散卡按栅格铺满；32px 蓝色大数；hover 上浮 2px；48px 快捷大按钮；无图表 |
| 平铺列表 | `pm/charter/index.vue`、`system/user/index.vue` | 单卡 + header 内联筛选 + stripe 表 + 右对齐分页；**多数无 `<style>` 块**（纯 EP + inline） |
| 手风琴列表 | `pm/wbs/index.vue`、`pm/product/index.vue`、`pm/deliverable/index.vue` | 灰条项目头 + 箭头旋转 + 展开才加载子表；操作按钮仅展开时出现；wbs 操作列 260px 全站最宽 |
| 向导表单 | `pm/charter/form.vue` | 全站唯一 `el-steps` 4 步；`v-show` 分屏保输入；末步 `el-descriptions` 预览；无 `<style>` |
| 分区算式表单 | `pm/budget/form.vue` | 主卡内嵌 3 张 category-card（**全站唯一卡片二层嵌套**）；表格内可编辑 + `*`/`=` 运算符列；底部居中汇总 |
| 详情页 | `pm/charter/detail.vue` 等 4 型 | Tab 聚合型（6 tab 懒加载 + ECharts）/ 分区只读型（手写 label-value）/ 状态机型（按 status 显隐按钮行）/ 极简型（单 descriptions） |
| 日历/工时 | `pm/work-hours/index.vue`、`system/calendar/index.vue` | 完全脱离 el-table；纯 CSS 网格；三色日期底；工时条状态色 + 3px 左边框；12 宫格年历 |
| 预算升级 | `pm/budget/upgrade.vue` + 子组件 | 全站最长纵向链；共享 CSS 范式（`section-common.css`）；1:2 虚线对照分栏；红涨绿跌徽标；信息密度最高 |
| 系统管理 | `system/{user,menu,dict,cost-quota}` | 组内差异最大：user 纯默认 / menu 树表 + dialog 内 alert / dict 左右双栏主从 / cost-quota 抽屉 + 三层弹窗 |

### 隐藏的「两派」分界线

按有无 `<style>` 块可清晰切分，这是比"类型"更底层的差异：

- **默认派**（无 scoped 样式，纯 EP + inline）：`charter/{index,form}`、`wbs/{index,detail,form,history}`、`product/index`、`system/user/*`、`system/menu`
- **定制派**（有 scoped 样式）：`login`、`dashboard`、`budget/*`（含 upgrade 全套）、`deliverable/*`、`work-hours/*`、`charter/detail`、`system/{dict,cost-quota,calendar}`

### 颜色密度梯度（低 → 高）

1. 零自定义色（默认派整组）
2. 列表阈值着色（budget 余额/比例、charter/detail 比率）
3. 中等：dashboard 彩色大数、日历三色底
4. 最高：budget 系（蓝合计 + 绿实际成本 + 红零定额）、upgrade（红涨绿跌）、charter/detail（7 色图表）

---

# 第五部分 前端交互设计与逻辑

## 5.1 交互模式总览

```
MainLayout (组件容器)
 ├── SidebarMenu          v-model:collapsed 双向绑定
 ├── TabBar (el-tabs)     tabStore 驱动，keep-alive 缓存
 └── <router-view>        页面视图
      ├── 列表页 index.vue
      │    ├── 查询表单 / 表格 / 分页          页面内联
      │    ├── 内联 el-dialog                  visible + mode 状态驱动
      │    └── 独立子组件 (ImportDialog)        ref + defineExpose({open})
      ├── MonthCalendar   props 单向下传 + emits 上抛
      └── WorkHourDialog  ref.open() 打开，emit('save') 通知刷新
```

**跨组件通信只有三条路：props+emit、`defineExpose({open})`、Pinia。全站无事件总线、无 provide/inject。**

## 5.2 父子组件通信模式

**模式一：props 单向下传 + emits 上抛（数据展示型组件）**

```vue
<!-- work-hours/index.vue → MonthCalendar -->
<MonthCalendar
  :year="year" :month="month" :work-logs="workLogs" :work-days="workDays"
  @add="handleAdd" @edit="handleEdit" @delete="handleDelete"
  @resubmit="handleResubmit" @month-change="handleMonthChange"
/>
```
父层 handler 只负责改状态/刷新数据，子组件不关心数据来源。

**模式二：`ref` + `defineExpose({ open })`（弹窗型组件）**

```vue
<!-- 父：wbs/index.vue → ImportDialog -->
<ImportDialog ref="importRef" @refresh="loadData" />
<script setup>
const importRef = ref()
function openImport() { importRef.value.open() }
</script>

<!-- 子：ImportDialog.vue -->
<script setup>
const emit = defineEmits(['refresh'])
const visible = ref(false)
function open() { visible.value = true }
defineExpose({ open })
// 导入成功 → emit('refresh') → 父刷新树/列表
</script>
```
用于 `WorkHourDialog`、`ImportDialog` 等独立弹窗：props 传 `date-str` / `edit-entry` 等上下文，父主动调 `open()` 打开，子完成后 `emit('save'/'refresh')`，父只负责刷新，不关心弹窗内部细节。

**模式三：v-model 双向绑定（受控状态）**

```vue
<!-- MainLayout → SidebarMenu -->
<SidebarMenu v-model:collapsed="collapsed" />
<!-- BudgetSummarySection 的储备金 -->
<BudgetSummarySection v-model:reserve="reserve" />
```

**模式四：组合式大页面拆分（复杂表单）**

`budget/upgrade.vue` 是最复杂的页面，拆为 `LaborSection` / `ProcurementSection` / `OtherSection` / `BudgetSummarySection` / `UpgradeInfoBar` 子组件：
- 子组件通过 `@add / @remove / @calc` 细粒度事件把操作回传父层
- 汇总组件用 `v-model` 双向绑定管理储备金
- 业务算法（预实对比计算等）抽到组合式 hook `useBudgetUpgrade.js`，页面与子组件共享

## 5.3 弹窗（Dialog）两种实现

| 模式 | 适用场景 | 实现 |
|------|---------|------|
| **页面内联 `el-dialog`** | 简单审批框、字典双弹窗 | `reactive({ visible, mode, ... })` 或独立 `xxxDialogVisible` ref；`handleAdd/handleEdit` 复用同一弹窗，`mode` 区分新增/编辑 |
| **独立 dialog 组件** | 逻辑较复杂、可复用 | 独立 `.vue` 文件 + `ref` + `defineExpose({ open })`，如上模式二 |

关闭行为全默认（点遮罩可关、实例不销毁）；**关闭即刷新列表**是惯例；ImportDialog 是特例（成功后留在弹窗展示三色计数 + `emit('refresh')`）；嵌套弹窗仅 cost-quota，内层 `append-to-body`。

## 5.4 keep-alive × 标签页联动

```vue
<!-- MainLayout.vue -->
<router-view v-slot="{ Component }">
  <keep-alive :include="cachedViews">
    <component :is="Component" />
  </keep-alive>
</router-view>
<!-- cachedViews：由 tabStore.tabs 派生的路由 name 白名单 -->

<el-tabs v-model="tabStore.activeTab" type="card" closable @tab-remove="tabStore.removeTab">
  <!-- 右键上下文菜单：关闭左/右/其他/全部 -->
</el-tabs>
```

多页签体系（页签即工作台）：

| 环节 | 机制 | 出处 |
|---|---|---|
| 登记 | 路由守卫 `beforeEach` 里 `tabStore.addTab(to)`，页面自己不管页签 | `router/index.js:248-252` |
| hidden 路由 | detail/form/history/profile **不建页签、只改 activeTab**（打开详情时页签栏无选中项） | `store/tab.js:11-34` |
| 激活 | `@tab-click → router.push(name)`，**路由是唯一事实源** | `MainLayout.vue:141-143` |
| 关闭 | 优先选右邻页、否则左邻；`closable:false` 的主页不可关 | `store/tab.js:41-46` |
| 右键菜单 | 关闭/关闭左/右/其他/全部；点任意处关闭；全部关闭后回 dashboard | `MainLayout.vue:52-58,135-169` |
| 缓存 | `keep-alive :include="cachedViews"` **按页签白名单缓存**；关闭页签即移出白名单并销毁实例，解决状态陈旧与内存驻留（问题 #3 修复） | `MainLayout.vue:42-46`、`store/tab.js` |

因为缓存后 `onMounted` 不再触发，**18 个 view 用 `onActivated` 决定是否重新拉数据**（详见 5.8）。登出时 `tabStore.$patch` 重置 tabs。

## 5.5 字典（Dict）数据注入

```vue
<script setup>
const dictStore = useDictStore()
</script>

<!-- 查询区/表单下拉 -->
<el-select v-model="queryParams.status">
  <el-option v-for="item in dictStore.getDictItems('CHARTER_STATUS')"
             :label="item.label" :value="item.itemValue" />
</el-select>

<!-- 表格状态标签 -->
<el-tag :type="dictStore.getTagType('CHARTER_STATUS', row.status)">
  {{ dictStore.getLabel('CHARTER_STATUS', row.status) }}
</el-tag>
```

- 加载时机：路由守卫首跳时 `dictStore.loadAll()`（防重入，dict/menu 并行、失败静默），数据缓存在 `dictMap`
- 字典由后端 `/api/system/dict` 提供，管理端维护；**是全站 Tag 颜色与下拉选项的唯一来源**
- 遗留：少量页面仍硬编码 label map（如 `categoryLabel`）

## 5.6 页面内交互风格（组件级）

### 加载态
- **只有 `v-loading`（26 处），无骨架屏、无全屏 Loading**
- 挂载位置：列表 → `el-table`；详情 → 整张 `el-card`；局部 → 包一层 div
- 按钮 `:loading` 命名：`submitting / submitLoading / uploading / generating`，**只有提交类带 loading**；删除靠 `await confirm + await api` 串行防抖
- 详情页 tab 懒加载双状态（`tabLoading` + `loadedTabs`），只加载一次、切回不刷新，`charter/detail.vue:310-324`

### 反馈机制
- **确认框铁律**（35 处）：

  ```js
  await ElMessageBox.confirm('确认删除该任务节点？…', '提示', { type: 'warning' })
  // title 几乎恒为 '提示'；type 固定 'warning'；confirmButtonText 多用默认
  ```

- **成功提示**（77 处）：动宾短语——创建成功 / 更新成功 / 删除成功 / 提交成功 / 审批通过；批量带数字 `成功通过 N 条，跳过 M 条`
- **错误提示**（68 处）：「对象 + 失败」——加载数据失败 / 保存失败；弹错只在 `utils/request.js` 拦截器发生（`code!==200`、401、网络异常），页面 `catch` **一律不再提示**（问题 #4 修复，仅静默回滚 loading/表单状态）
- **警告**（6 处）：前置条件未满足的补位提示，如「请输入驳回原因」
- `ElNotification` **0 处**；`el-alert` 仅作页面静态提示
- 审批类按钮的 success/danger 色彩区分只发生在 `el-dialog` footer，不发生在 MessageBox

### 表单交互
- 校验触发：**文本 `blur`、选择/日期 `change`**（稳定惯例）
- 提交统一模式（10+ 页面）：

  ```js
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  ```

- 跨字段联动：`watch(startDate) → validateField('endDate')` + `:disabledDate` 禁早于开始日
- 向导前进规则：仅第 0 步整表 validate，通过才 `currentStep++`；后退永远无条件；步骤体用 `v-show` 保输入
- **脏数据保护统一 composable `useUnsavedGuard(isDirty)`**（`onBeforeRouteLeave` + `beforeunload`）：所有含输入的表单页必须挂载（原仅 budget/upgrade 一处，问题 #5 修复），`useBudgetUpgrade` 改为复用该 composable

### 表格交互
- `stripe` + `row-key` + 操作列 `fixed="right"` 是标配
- **无 sortable、无列筛选、无右键、无双击行**；过滤一律在卡片头「select/input + 查询 + 重置」三件套，回车可触发查询
- 展开行仅 budget（`@row-click` 联动 + 单开模式）；树表用 `:tree-props`；**多选全站仅工时审批 1 处**（选中浮出批量条）
- 单选高亮行仅 dict 左右联动 1 处
- **操作列铁律：破坏性/状态推进必弹确认，导航类不弹**；按钮按 `row.status` 条件显隐（状态机）
- 图标按钮一律 `<el-tooltip content="查看" placement="top">`

### 悬停与弹窗
- tooltip 单一场景：给纯图标按钮补文字（53 处）
- 卡片 hover 上浮仅 dashboard `translateY(-2px)`
- 自定义 hover 变色：右键菜单项、折叠按钮、用户名、日历条目 `opacity:.85`、删除图标变红
- 弹窗打开两模式：visible ref + 事件重置（最常见）/ `defineExpose({open})` 命令式
- **关闭即刷新列表**是惯例；ImportDialog 是特例（成功后留在弹窗展示三色计数 + `emit('refresh')`）

### 特殊交互
- 导出/下载统一「Blob + 临时 `<a>` + click + revoke」模板（4 处几乎逐字相同）；成功不弹提示，失败弹 `导出失败`
- 拖拽仅 `el-upload drag`；复制、打印 0 处
- 日历：点非当月格跳月、点日期弹 360px 编辑、格 hover `#ecf5ff`；**编辑即保存、保存即提示**，不设假「保存」按钮（问题 #7 修复）
- 权限驱动显隐：`v-if="isPm"` / `canApprove` 等，各页重复实现（未抽公共 store）

## 5.7 页面间交互风格

**核心链路：路由跳转 → 路由守卫（校验 + 登记页签）→ keep-alive 缓存 → onActivated 决定刷新。**

### 路由跳转
- **只有 `push`（0 处 replace）**；`router.back()` 专门表示返回/取消
- 参数分工：
  - 详情/编辑 id → **path params**（`/pm/charter/detail/:id`）
  - 上下文/回刷 → **query**：`?projectId=` 锁定项目、`?budgetId=` 切版本、`?refresh=Date.now()` 定向回刷、`?year=&month=`、`?redirect=`
  - **无 props 传参、无 store 传"待编辑对象"**，一律回源重拉
- 保存成功回列表用**固定路径 push**（非 back）；取消/返回按钮才用 `$router.back()`

### 返回机制
- 无面包屑；导航三件套 = 左侧菜单 + 顶部页签 + 卡片 header 右侧「返回」按钮
- 返回按钮统一模板：header 右侧 flex（左 16px bold 标题 | 右 `返回` 按钮）
- **统一约定（问题 #8 修复）**：取消/返回一律 `$router.back()`（无历史记录时兜底 `replace` 到模块列表页）；保存成功一律固定路径 `push` 回列表；删除 deliverable/detail、user/detail、approval 等处固定 push 返回的旧实现

### 激活与状态保持
18 处 `onActivated` 决定行为：
- **列表页** → 每次激活回源刷新（charter/budget/user/product/menu/dashboard/work-hours/cost-quota）
- **表单页** → 激活重初始化前先查 `isDirty`（由 `useUnsavedGuard` 提供）：脏则跳过重载保留输入，净则回源重载（原 `useBudgetUpgrade` 已如此，其余表单页统一补齐，问题 #5 修复）
- **详情页** → `onActivated(loadDetail)`
- **无需刷新** → 不写（history/approval/calendar）

**同一组件换 id 的陈旧修复**：`watch(route.params.id)` + `route.path.startsWith('/xxx')` 前缀守卫防误触发（8 个页面）。

### 登录与跨页状态
- 登录守卫：无 token → `/login?redirect=原路径`；401/403 由拦截器二次守卫清 token 跳登录
- 全局数据（dict/menu）在守卫里懒加载预热，失败静默

### 列表刷新四惯例
1. **`onActivated(loadData)` 回刷**（最普遍）——详情/表单返回时自动刷新
2. **页内操作后立即 `loadData()` / 重载展开项**
3. **`refresh` query 定向回刷**（保留展开状态）：`push('/pm/wbs?refresh=Date.now()')` → `onActivated` 里判断后仅重载当前项目
4. **query 筛选闭环**（问题 #1 修复）：列表页在 `onMounted`/`onActivated` 读取 `route.query`（如 `status`、`projectId`）写入 `queryParams` 并触发查询；看板统计卡、预警中心等跳转统一带 query，跳转即过滤

个别用 `tableKey.value++` 强制重建表格（user、budget）。

## 5.8 图表（ECharts）使用

仅 `views/pm/charter/detail.vue` 使用：
```js
import * as echarts from 'echarts'
const chartRef = ref(); let chartInstance = null
async function renderChart() {
  await nextTick()
  if (chartInstance) chartInstance.dispose()   // 重绘前销毁
  chartInstance = echarts.init(chartRef.value)
  chartInstance.setOption({ ... })
}
```
数据加载后才 init；未封装公共图表组件；调色板见 4.2「图表色」；单测中 `vi.mock('echarts')`。

## 5.9 构建期自动导入（`vite.config.js`）

```js
resolve: { alias: { '@': path.resolve(__dirname, 'src') } },
server: { port: 4185, proxy: { '/api': 'http://localhost:8090' } }
```

**统一为全量注册（问题 #9 修复）**：移除 `unplugin-auto-import` / `unplugin-vue-components` 两个按需插件，只保留 `main.js` 的 `app.use(ElementPlus, { locale: zhCn })` + 完整 CSS，消除「按需引入但实际全局注册」的双轨配置（中后台场景包体积可接受；若日后确需瘦身，整体切回按需并删除全量注册，二者不得并存）。样式统一 `<style scoped>` + `:deep()` 穿透。

---

# 第六部分 测试体系

| 层级 | 工具 | 组织 |
|------|------|------|
| 后端单元/集成 | JUnit 5 + MockMvc + JaCoCo | `wh-backend/src/test/java`（56 文件）：根包 17 个 Service 集成测试（`WhPmCharterServiceTest`、`WorkflowBranchTest` 等，面向 Service 接口打桩）；`controller/pm`(16) + `controller/system`(5) + `AuthControllerTest` 用 MockMvc；子包覆盖 approval(6)、config(4)、security(2)、common、bootstrap、task、mq 等；`fixtures/AuthHelper` 生成各角色 token、`fixtures/TestFixtures` 造数据 |
| 前端单元 | Vitest + @vue/test-utils + happy-dom | `src/__tests__/` 镜像 src 结构（58 文件，含 `setup.js`、`helpers.js`）；v8 覆盖率（排除 `__tests__` 与 `main.js`）；echarts/axios 均有 mock |
| E2E | Playwright | `e2e/` 12 个 spec 按业务域划分（auth charter wbs budget work-hours system…+ `dashboard-permissions.spec.js`），baseURL `localhost:4185`，仅 chromium，失败截图 + trace |

命令：
```bash
cd wh-backend  && mvn test                          # 后端
cd wh-frontend && npm test                          # 前端单测
cd wh-frontend && npm run test:coverage             # 含覆盖率
npx playwright test --config=wh-frontend/e2e         # E2E
```

---

业务域表结构

| 业务域   | 表结构前缀  |      |      |      |
| -------- | ----------- | ---- | ---- | ---- |
| 基础数据 | mdm         |      |      |      |
| 采购管理 | procurement |      |      |      |
| 销售管理 | s&d         |      |      |      |
| 库存管理 | wms         |      |      |      |
| 生产管理 | mrp         |      |      |      |
| 质量管理 | qms         |      |      |      |
| 资产管理 | eam         |      |      |      |
| 财务管理 | finance     |      |      |      |
| 税务管理 | tax         |      |      |      |
| 人力资源 | hr          |      |      |      |
| 客户关系 | crm         |      |      |      |
| 项目研发 | rd          |      |      |      |
| 项目研发 | pm          |      |      |      |
| 外部协同 | scm         |      |      |      |
| 移动物联 | iot         |      |      |      |
| 报表分析 | bi          |      |      |      |
| 集成门户 | portal      |      |      |      |
| 系统管理 | admin       |      |      |      |
| 本地适配 | i18n        |      |      |      |
| 运维管理 | ops         |      |      |      |

# 附录 新页面开发检查清单（最小复刻）

1. 引入 `element-plus/dist/index.css`，**不改主题色**（用默认 `#409EFF`）
2. 套标准骨架：`MainLayout` 下单 `<el-card>`，header 左 `16px bold` 标题、右筛选/操作（`gap:8px`）
3. 表格用 `stripe` + `row-key` + `v-loading`，操作列 `fixed="right"` + link 图标 + tooltip
4. 分页 `layout="total, sizes, prev, pager, next"`、`margin-top:16px` 右对齐
5. 色板只用：主 `#409eff` / 成功 `#67c23a` / 警告 `#e6a23c` / 危险 `#f56c6c` / 信息 `#909399`；浅底 `#f0f9eb/#fdf6ec/#fef0f0/#ecf5ff`；区块头 `#f5f7fa`；边框 `#ebeef5`；文字 `#303133/#606266/#909399`
6. 字阶 12/13/14/15/16 + 大数 20/24/28/32，强调一律 `bold`；间距用 4/8/12/16/24
7. 删除/状态推进 → `ElMessageBox.confirm('…？', '提示', {type:'warning'})` → 成功动宾短语 → 立即 `loadData()`
8. 表单校验：文本 `blur`、选择 `change`；提交 `validate().catch(()=>false)`
9. 返回用 `$router.back()` 放卡片 header 右侧；保存成功固定 `push` 回列表
10. 列表页写 `onActivated(loadData)`；详情/表单页写 `watch(route.params.id)` + 路径前缀守卫
11. 复杂分区页照抄 `pm/budget/upgrade/section-common.css` 范式（灰头 + 6px 圆角框 + 左蓝条小标题 + 虚线分栏）
12. 状态 Tag 颜色优先取 `dictStore.getTagType()`，勿硬编码
13. 新接口按 `getXxxApi/createXxxApi` 命名加 `api/pm/` 或 `api/system/` 模块；后端新页面/新表遵循第二部分分层（Controller → Service 接口 → ServiceImpl → DAO → Entity，Controller 只依赖 Service 接口）+ 末尾编号迁移脚本（MySQL DDL：InnoDB + `utf8mb4`，列名与 `@TableField` 大写风格一致）
