# 企业资源计划（ERP）系统

---

## 1. 项目目标

- **统一主数据（MDM）**：法人主体、组织、物料、客户、供应商、汇率、税码的唯一权威源，含版本快照与变更审计。
- **核心域覆盖**：计划、采购、生产、销售、库存、质量、资产、财务、税务、HR 等业务域一体化。
- **实时决策支持**：可扩展的集成能力与报表分析。
- **多组织核算**：多法人主体、多币种、多税率；支持中/英多语言（预留扩展）。
- **配置化治理**：关键流程走工作流引擎；所有阈值类规则参数化（规则引擎），禁止硬编码（规格 §4.0.3）。

## 2. 业务域总览

按 [`08-erp-menu.md`](docs/design/08-erp-menu.md) 的基准清单，系统规划 18 个一级菜单域（菜单命名遵循"四字优先"规则）：

| # | 业务域 | 简称 | # | 业务域 | 简称 |
| --- | --- | --- | --- | --- | --- |
| 1 | 基础数据 | MDM | 10 | 人力资源 | HR |
| 2 | 采购管理 | Procurement | 11 | 客户关系 | CRM |
| 3 | 销售管理 | S&D | 12 | 项目研发 | RD |
| 4 | 库存管理 | WMS | 13 | 外部协同 | SCM |
| 5 | 生产管理 | MRP | 14 | 移动物联 | IoT |
| 6 | 质量管理 | QMS | 15 | 报表分析 | BI |
| 7 | 资产管理 | EAM | 16 | 集成门户 | Portal |
| 8 | 财务管理 | Finance | 17 | 系统管理 | Admin |
| 9 | 税务管理 | Tax | 18 | 本地适配 / 运维管理 | i18n / Ops |

**当前实现进度**：

- ✅ **基础数据（MDM）**：组织（法人主体 / 成本中心 / 利润中心 / 组织单元）、物料（含分类维护、变更审计）、客户（准入 / 信用额度 / 跨域共享）、供方（准入 / 合并去重）、汇率（维护 / 批量 / 历史）、税码（维护 / 政策 / 批量导入）——前后端全栈代码与迁移脚本已落库。
- ✅ **采购管理**：请购（自动 / 手工 / 紧急 / 审批）、询价比价、紧急采购状态机——后端状态机与前端视图已落码。
- ✅ **系统管理**：认证（JWT）、菜单、角色。
- ⏳ 其余业务域按 `openspec/changes/` 中的变更规格迭代推进。

## 3. 技术栈

### 3.1 后端（`backend/`）

| 类别 | 技术 | 版本 |
| --- | --- | --- |
| 核心框架 | Spring Boot（Java 17，单 JAR 打包） | 2.7.18 |
| ORM | MyBatis-Plus（`BaseMapper` + `LambdaQueryWrapper`，分页 / 乐观锁拦截器） | 3.5.5 |
| 数据库 | MySQL 8.0（InnoDB + `utf8mb4`）、Druid 连接池 | 8.0 |
| 安全 | Spring Security + jjwt（无状态 JWT） | 0.11.5 |
| API 文档 | Knife4j（OpenAPI 3），`/doc.html` | 4.3.0 |
| 工具库 | Lombok / Hutool | — |

### 3.2 前端（`frontend/`）

| 类别 | 技术 | 版本 |
| --- | --- | --- |
| 核心框架 | Vue 3（Composition API + `<script setup>`） | 3.4.21 |
| 构建工具 | Vite（dev 端口 4185，`/api` 代理至后端 8090） | 5.2.0 |
| UI 组件库 | Element Plus + 图标 | 2.6.1 |
| 路由 / 状态 | Vue Router（扁平子路由 + 全局守卫）/ Pinia（user / tab / menu / dict） | 4.3.0 / 2.1.7 |
| HTTP / 图表 | Axios（统一封装 `utils/request.js`）/ ECharts | 1.6.7 / 6.0.0 |

### 3.3 基础设施（`deploy/`）

`deploy/docker-compose.yml` 编排基础设施容器，开发与生产同构：

| 服务 | 镜像 | 端口 | 说明 |
| --- | --- | --- | --- |
| mysql | `mysql:8.0` | 3306→3306 | 库 `erp`；`mysql-data` 卷持久化；字符集 `utf8mb4` |

> 规范文档规划的完整基础设施（Redis / RabbitMQ / MinIO / Nginx）随对应业务域落地时逐步纳入编排。

## 4. 工程结构

```
erp/
├── backend/            Spring Boot 后端（com.erp）
│   ├── src/main/java/com/erp/
│   │   ├── controller/       HTTP 接入层（参数接收 → 调用 Service 接口）
│   │   ├── service/          Service 接口层（业务能力契约）+ impl/ 实现层
│   │   ├── dao/              MyBatis-Plus Mapper
│   │   ├── entity/           表映射实体（BaseEntity：UUID 主键 / 审计字段 / 逻辑删除 / 乐观锁）
│   │   ├── procurement/      采购域状态机
│   │   ├── security/         JwtTokenProvider / JwtAuthenticationFilter / SecurityConfig
│   │   ├── config/           MyBatis-Plus、Knife4j、CORS 等配置
│   │   ├── common/           R<T> 统一响应、GlobalExceptionHandler、ServiceException
│   │   ├── bootstrap/        DbBootstrap 数据库迁移引导
│   │   └── ops/ / schedule/  Outbox 事件发布、定时任务
│   └── src/main/resources/
│       ├── db/mysql/         迁移脚本（NNN-模块-描述.sql，按文件名排序执行）
│       └── application*.yml  配置（默认 profile：mysql）
├── frontend/           Vue 3 前端
│   └── src/
│       ├── views/            页面（mdm / proc / system / dashboard / login）
│       ├── api/              API 模块（getXxxApi / createXxxApi 命名）
│       ├── components/       布局与公共组件
│       ├── router/           路由 + 全局守卫（权限 / 页签 / 字典预热）
│       ├── store/            Pinia store
│       └── utils/request.js  Axios 封装（token 注入、弹错单点化）
├── deploy/             Docker Compose 编排
├── docs/design/        设计文档（规格 / 架构规范 / 菜单清单）
└── openspec/           规格驱动开发变更集（proposal / design / tasks / spec）
```

## 5. 开发规范要点

完整规范见 [`01-architecture-style.md`](docs/design/01-architecture-style.md)，核心约定：

**后端分层（强制）**

```
Controller → Service 接口 → ServiceImpl → DAO → Entity
```

- Controller 构造注入 **Service 接口**，禁止注入实现类、禁止直接触达 DAO；
- `ServiceImpl` 承担事务边界（`@Transactional`）与流程编排，`LambdaQueryWrapper` 等持久化细节不外泄；
- 统一响应 `R<T>`，业务异常走 `ServiceException` + `GlobalExceptionHandler`。

**数据库迁移**

- 启动时 `DbBootstrap` 扫描 `classpath:db/mysql/*.sql` 按文件名排序执行，`erp_ops_bootstrap_marker` 表记录已执行脚本；
- 编号规则 `NNN-模块-描述.sql`，新迁移追加末尾编号；DDL 统一 InnoDB + `utf8mb4`。

**表命名规范**

- 全部表以 `erp_` 开头：`{业务域前缀}_{表名}`，全小写下划线分隔（如 `erp_mdm_item`、`erp_procurement_rfq`）；
- 业务域前缀对照：`mdm` / `procurement` / `sd` / `wms` / `mrp` / `qms` / `eam` / `finance` / `tax` / `hr` / `crm` / `rd` / `pm` / `scm` / `iot` / `bi` / `portal` / `admin` / `i18n` / `ops`。

**前端约定**

- 弹错单点化：错误提示只在 `request.js` 拦截器发生，页面 `catch` 分支静默恢复；
- 列表页写 `onActivated(loadData)` 回刷；删除 / 状态推进必弹 `ElMessageBox.confirm`；
- 状态 Tag 颜色优先取 `dictStore.getTagType()`，勿硬编码；
- 色板沿用 Element Plus 默认（主色 `#409EFF`），阈值业务色遵循规范（预算执行率 <0.8 绿 / <0.95 橙 / ≥0.95 红）。

## 6. 快速开始

**环境要求**：JDK 17+、Maven、Node.js 18+、Docker。

```bash
# 1. 启动数据库（库 erp，root/root123，开发默认）
docker compose -f deploy/docker-compose.yml up -d

# 2. 启动后端（端口 8090，自动执行 db/mysql/ 迁移脚本）
cd backend && mvn spring-boot:run

# 3. 启动前端（端口 4185，/api 代理至 8090）
cd frontend && npm install && npm run dev
```

| 服务 | 地址 |
| --- | --- |
| 前端 | http://localhost:4185 |
| 后端 API | http://localhost:8090/api |
| 接口文档 | http://localhost:8090/doc.html |
| MySQL | localhost:3306（库 `erp`） |

> 数据库账号密码（`root/root123`）为本地开发默认值，来源于 `application-mysql.yml` 与 `deploy/docker-compose.yml`，投入使用前请通过环境变量覆盖。

## 7. 设计文档导航

| 文档 | 内容 |
| --- | --- |
| [`00-erp-spec.md`](docs/design/00-erp-spec.md) | 需求规格说明书 v0.2.3：业务域功能需求（§4）、非功能与平台支撑（§5）、数据 / 接口 / 验收 / 风险（§6–10）；含全局卡控（C-0-xx）、配置参数表（§4.0.3）与卡控级别定义（L1–L5） |
| [`01-architecture-style.md`](docs/design/01-architecture-style.md) | 技术与设计规范：技术栈、前后端分层、色板字阶、交互逻辑、测试体系、新页面开发检查清单 |
| [`08-erp-menu.md`](docs/design/08-erp-menu.md) | 层次化功能菜单清单：18 个一级域、四字命名规则、与规格章节对照（附录一 / 二） |

## 8. 参与贡献

1. Fork 本仓库，基于 `dev` 分支新建 `feat/xxx` 分支
2. 按 `openspec/` 流程先补变更规格（proposal / design / tasks），再落码
3. 遵循第 5 节开发规范；新迁移脚本追加末尾编号
4. 提交代码并新建 Pull Request
