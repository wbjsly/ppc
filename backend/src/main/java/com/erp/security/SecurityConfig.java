package com.erp.security;

import com.erp.service.intf.OpenApiGatewayService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import javax.servlet.http.HttpServletResponse;

/**
 * 权限规则集中定义（01 文档 2.4）：
 * /api/auth/**、doc.html 匿名；menus 写操作 hasRole("ADMIN")；法人主体写操作 hasRole("ADMIN")；其余 /api/** 需认证。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final OpenApiAuthFilter openApiAuthFilter;

    public SecurityConfig(JwtTokenProvider jwtTokenProvider, OpenApiGatewayService openApiGatewayService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.openApiAuthFilter = new OpenApiAuthFilter(openApiGatewayService);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf().disable()
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()
            .authorizeRequests()
                .antMatchers("/api/auth/**").permitAll()
                .antMatchers("/doc.html", "/webjars/**", "/v3/api-docs/**", "/swagger-resources/**").permitAll()
                .antMatchers(HttpMethod.GET, "/api/system/menus/user").authenticated()
                .antMatchers(HttpMethod.POST, "/api/system/menus/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/system/menus/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/system/menus/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/legal-entities/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/legal-entities/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/cost-centers/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/cost-centers/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/profit-centers/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/profit-centers/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/org-units/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/org-units/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/items/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/items/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/mdm/items/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/item-categories/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/item-categories/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/customer-groups/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/customer-groups/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/customer-views/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/customer-views/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/credit-limits/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/credit-limits/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/price-agreements/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/price-agreements/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/suppliers/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/suppliers/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/mdm/suppliers/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/supplier-merges/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/supplier-merges/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/exchange-rates/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/exchange-rates/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/mdm/exchange-rates/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/exchange-rate-batch/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/tax-codes/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/tax-codes/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/mdm/tax-codes/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/tax-code-batch/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/tax-policies/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/tax-policies/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/mdm/tax-policies/**").hasRole("ADMIN")
                // 质量协同（add-quality-collaboration）：六角色写权限。必须先于下方 /api/proc/** 的 ADMIN 规则，
                // 否则 POST /api/proc/returns/** 会把仓库/质量角色先拦成 403。
                // 细粒度（如双签节点必须由 ROLE_REQUIRED 角色签）在 ApprovalServiceImpl 内二次校验。
                // 抽样执行记录（rework-sampling-inspection）：质检员/质量工程师/质量经理可写
                .antMatchers(HttpMethod.POST, "/api/qms/lots/*/sampling-records")
                .hasAnyRole("ADMIN", "INSPECTOR", "QUALITY_ENG", "QUALITY_MGR")
                .antMatchers(HttpMethod.GET, "/api/qms/lots/history").hasAnyRole("ADMIN", "INSPECTOR",
                        "QUALITY_ENG", "QUALITY_MGR", "QUALITY_DIRECTOR")
                .antMatchers(HttpMethod.POST, "/api/qms/lots/**").hasAnyRole("ADMIN", "INSPECTOR", "QUALITY_ENG")
                .antMatchers(HttpMethod.PUT, "/api/qms/lots/**").hasAnyRole("ADMIN", "INSPECTOR", "QUALITY_ENG")
                .antMatchers(HttpMethod.POST, "/api/qms/standards/**").hasAnyRole("ADMIN", "QUALITY_ENG")
                .antMatchers(HttpMethod.PUT, "/api/qms/standards/**").hasAnyRole("ADMIN", "QUALITY_ENG")
                // 客户质量协议（rework-inspection-tasks）：质量工程师维护 + 质量经理生效/停用
                .antMatchers(HttpMethod.POST, "/api/qms/agreements/**").hasAnyRole("ADMIN", "QUALITY_ENG",
                        "QUALITY_MGR")
                .antMatchers(HttpMethod.POST, "/api/qms/exempts/**").hasAnyRole("ADMIN", "QUALITY_ENG")
                // 6.5.1 隔离确认（rework-ncr-identification-disposition）：质检员贴标作业，
                // 须先于下方 ncrs 通用写规则放行 INSPECTOR（细粒度在服务层 requireRole 二次校验）
                .antMatchers(HttpMethod.POST, "/api/qms/ncrs/*/isolate")
                .hasAnyRole("ADMIN", "INSPECTOR", "QUALITY_ENG", "QUALITY_MGR")
                .antMatchers(HttpMethod.POST, "/api/qms/ncrs/**").hasAnyRole("ADMIN", "QUALITY_ENG", "QUALITY_MGR")
                .antMatchers(HttpMethod.PUT, "/api/qms/ncrs/**").hasAnyRole("ADMIN", "QUALITY_ENG", "QUALITY_MGR")
                // 让步接收：申请/提交（ENG/MGR）+ 核销放行（质检员/仓库）；双签签署走 approvals 规则
                .antMatchers(HttpMethod.POST, "/api/qms/concessions/quality-gate").hasAnyRole("ADMIN",
                        "WAREHOUSE", "INSPECTOR", "QUALITY_ENG", "QUALITY_MGR")
                .antMatchers(HttpMethod.POST, "/api/qms/concessions/**").hasAnyRole("ADMIN", "QUALITY_ENG",
                        "QUALITY_MGR", "INSPECTOR", "WAREHOUSE")
                .antMatchers(HttpMethod.POST, "/api/qms/capa/**").hasAnyRole("ADMIN", "QUALITY_ENG", "QUALITY_MGR")
                .antMatchers(HttpMethod.PUT, "/api/qms/capa/**").hasAnyRole("ADMIN", "QUALITY_ENG", "QUALITY_MGR")
                .antMatchers(HttpMethod.POST, "/api/qms/copq/**").hasAnyRole("ADMIN", "QUALITY_ENG", "QUALITY_MGR")
                .antMatchers(HttpMethod.PUT, "/api/qms/copq/**").hasAnyRole("ADMIN", "QUALITY_ENG", "QUALITY_MGR")
                .antMatchers(HttpMethod.POST, "/api/qms/scar/**").hasAnyRole("ADMIN", "SQE", "QUALITY_MGR")
                .antMatchers(HttpMethod.PUT, "/api/qms/scar/**").hasAnyRole("ADMIN", "SQE", "QUALITY_MGR")
                .antMatchers(HttpMethod.POST, "/api/qms/gauges/**").hasAnyRole("ADMIN", "QUALITY_ENG")
                .antMatchers(HttpMethod.PUT, "/api/qms/gauges/**").hasAnyRole("ADMIN", "QUALITY_ENG")
                .antMatchers(HttpMethod.POST, "/api/qms/spc/**").hasAnyRole("ADMIN", "INSPECTOR", "QUALITY_ENG")
                // 审批底座：待办/签署入口按角色放行，节点角色不符由服务层 403
                // （付款/预付款分级审批新增 ROLE_FINANCE_MGR / ROLE_GM 签署节点，add-payment-management）
                // （商机 5 阶段审批新增 ROLE_SALES_MGR；SO 审批/特殊价格会签预留 SALES_DIRECTOR/CREDIT_ADMIN，add-sales-lead-to-cash）
                // （BOM 发布签署新增 ROLE_PROCESS_MGR，add-bom-management）
                .antMatchers(HttpMethod.POST, "/api/qms/approvals/**").hasAnyRole("ADMIN", "INSPECTOR", "QUALITY_ENG", "QUALITY_MGR",
                        "TECH_OWNER", "QUALITY_DIRECTOR", "SQE", "WAREHOUSE", "PM", "RECEIVER",
                        "FINANCE_MGR", "GM", "SALES_MGR", "SALES_DIRECTOR", "CREDIT_ADMIN", "PROCESS_MGR",
                        // 工单审批签署（add-work-order-management：MoApprove 节点 = 计划主管，各变更向名单追加先例）
                        "PLAN_MGR",
                        // 红冲/作废审批签署（add-red-invoice-void：税务专员主管=FINANCE_MGR 已在列，此处补财务经理）
                        "FINANCE", "FIN_MGR",
                        // HR 审批签署（add-employee-archive：入职开通/锁定特批/转正/调岗/离职节点）
                        "HR", "HR_MGR",
                        // 项目基线/预算变更审批签署（add-project-initiation：PmBaseline=PmBudgetChange 节点角色）
                        "PMO",
                        // 超预算解冻审批签署（add-cost-collection-freeze：BR-4.8-22 第三级分管领导）
                        "EXEC")
                // 2.6.1 质量退货：具体路径在前（出库过账 WAREHOUSE），兜底创建/编辑 ADMIN
                .antMatchers(HttpMethod.POST, "/api/proc/returns/*/postings").hasAnyRole("ADMIN", "WAREHOUSE")
                // 非质量退货创建（2.6.2，spec other-return）：采购侧发起，收窄为 ADMIN/PM（须先于下方粗规则）
                .antMatchers(HttpMethod.POST, "/api/proc/returns").hasAnyRole("ADMIN", "PM")
                // 带出与台账查询：采购/仓库/质量侧均可读
                .antMatchers(HttpMethod.GET, "/api/proc/returns/po-returnables",
                        "/api/proc/returns/red-vouchers").hasAnyRole("ADMIN", "PM", "WAREHOUSE",
                        "QUALITY_ENG", "QUALITY_MGR")
                // 质量退货提交由质量侧发起（NCR 自动带出单）；创建/编辑兜底 ADMIN
                .antMatchers(HttpMethod.POST, "/api/proc/returns/**").hasAnyRole("ADMIN", "QUALITY_ENG", "QUALITY_MGR")
                .antMatchers(HttpMethod.PUT, "/api/proc/returns/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/proc/returns/**").hasRole("ADMIN")
                // 评标评分（add-tender-bidding 4.1）：放行评委，必须先于 /api/proc/** 的 ADMIN 规则，
                // 否则顺序在前的 POST /api/proc/** 会先把评委拦成 403
                .antMatchers(HttpMethod.POST, "/api/proc/tenders/*/scores").hasAnyRole("ADMIN", "BID_JUDGE")
                // 收货管理（add-goods-receipt D7）：同样必须先于 /api/proc/** 的 ADMIN 规则。
                // 顺序要点：具体路径在前——否则 POST /grs/** 的 RECEIVER 规则会吞掉过账/放行的 WAREHOUSE 门禁
                .antMatchers(HttpMethod.POST, "/api/proc/grs/*/postings").hasAnyRole("ADMIN", "WAREHOUSE")
                // 入库确认（inbound-workbench FR-4.4-1-7）：过账同权限，先于通用 RECEIVER 规则
                .antMatchers(HttpMethod.POST, "/api/proc/grs/*/confirm").hasAnyRole("ADMIN", "WAREHOUSE")
                .antMatchers(HttpMethod.POST, "/api/proc/grs/**").hasAnyRole("ADMIN", "RECEIVER")
                .antMatchers(HttpMethod.POST, "/api/proc/gr-differences/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/proc/gr-adjustments/**").hasAnyRole("ADMIN", "PM")
                // 寄售采购 VMI（add-consignment-procurement D11）：须先于 /api/proc/** 的 ADMIN 规则。
                // 协议维护/结算确认/处置确认/水位确认 = ADMIN+PM；读 = ADMIN+PM+WAREHOUSE
                .antMatchers(HttpMethod.GET, "/api/proc/vmi/**").hasAnyRole("ADMIN", "PM", "WAREHOUSE")
                .antMatchers(HttpMethod.POST, "/api/proc/vmi/**").hasAnyRole("ADMIN", "PM")
                .antMatchers(HttpMethod.PUT, "/api/proc/vmi/**").hasAnyRole("ADMIN", "PM")
                // 门户账号管理（add-supplier-portal-collaboration D2）：读 ADMIN+PM（创建/启停走下方 POST/PUT ADMIN 兜底）
                .antMatchers(HttpMethod.GET, "/api/proc/portal-accounts/**").hasAnyRole("ADMIN", "PM")
                // 推送台账（spec portal-event-push）：内部读 ADMIN+PM+WAREHOUSE
                .antMatchers(HttpMethod.GET, "/api/proc/portal-events",
                        "/api/proc/portal-events/**").hasAnyRole("ADMIN", "PM", "WAREHOUSE")
                // PO 协同（spec po-collaboration）：解锁仅 ADMIN，其余动作 ADMIN+PM，读三角色（先于下方 POST/PUT ADMIN 兜底）
                .antMatchers(HttpMethod.POST, "/api/proc/po-coops/unlocks").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/proc/po-coops/**").hasAnyRole("ADMIN", "PM")
                .antMatchers(HttpMethod.GET, "/api/proc/po-coops",
                        "/api/proc/po-coops/**").hasAnyRole("ADMIN", "PM", "WAREHOUSE")
                // ASN（spec asn-collaboration）：读三角色，代录创建 ADMIN+PM（门户创建走 /api/portal/asns）
                .antMatchers(HttpMethod.POST, "/api/proc/asns").hasAnyRole("ADMIN", "PM")
                .antMatchers(HttpMethod.GET, "/api/proc/asns",
                        "/api/proc/asns/**").hasAnyRole("ADMIN", "PM", "WAREHOUSE")
                .antMatchers(HttpMethod.POST, "/api/proc/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/proc/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/proc/**").hasRole("ADMIN")
                // 财务集成（add-accrual-three-way-match D11）：细规则在前——
                // 冲回 / 合并迁移财务确认 / 跨期手工过账仅 ADMIN，其余读写 ADMIN+PM
                .antMatchers(HttpMethod.POST, "/api/fin/accruals/migrations/*/confirm").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/fin/accruals/*/reverse").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/fin/invoices/matches/*/manual-post").hasRole("ADMIN")
                // 付款管理（add-payment-management design D9）：排期/待付款标记/执行付款/预付执行仅 ADMIN
                .antMatchers(HttpMethod.POST, "/api/fin/payments/*/schedule",
                        "/api/fin/payments/*/wait-funds",
                        "/api/fin/payments/*/execute",
                        "/api/fin/prepayments/*/execute").hasRole("ADMIN")
                // 销售开票与应收核销（add-sales-lead-to-cash tasks 10.9）：
                // FINANCE_MGR 是 3.8 三页的业务角色，须先于下方 /api/fin/** 的 ADMIN+PM 兜底放行
                .antMatchers(HttpMethod.GET, "/api/fin/invoice-applies/**",
                        "/api/fin/ar/**").hasAnyRole("ADMIN", "FINANCE_MGR")
                .antMatchers(HttpMethod.POST, "/api/fin/invoice-applies/**",
                        "/api/fin/ar/**").hasAnyRole("ADMIN", "FINANCE_MGR")
                .antMatchers(HttpMethod.PUT, "/api/fin/invoice-applies/**",
                        "/api/fin/ar/**").hasAnyRole("ADMIN", "FINANCE_MGR")
                // 计税管理（add-tax-calculation，spec tax-calculation）：税务专员/财务主管业务角色，
                // 须先于下方 /api/fin/** 的 ADMIN+PM 兜底放行（M9-1 菜单 PERM 三角色同口径）
                .antMatchers("/api/fin/tax-calc/**").hasAnyRole("ADMIN", "FINANCE", "FINANCE_MGR")
                // 发票管理（add-invoice-management，spec invoice-management）：M9-2 四组接口，
                // 与 M9-1 菜单 PERM 三角色同口径，先于 /api/fin/** 兜底
                .antMatchers("/api/fin/input-invoices/**",
                        "/api/fin/digital-invoices/**",
                        "/api/fin/invoice-deliveries/**",
                        "/api/fin/sales-invoice-ledger/**").hasAnyRole("ADMIN", "FINANCE", "FINANCE_MGR")
                // 红冲作废（add-red-invoice-void，spec red-invoice-void）：M9-4 三组接口，
                // 菜单 PERM 三角色（ROLE_FINANCE/FIN_MGR/ADMIN），先于 /api/fin/** 兜底
                .antMatchers("/api/fin/red-flush-applies/**",
                        "/api/fin/invoice-void-applies/**",
                        "/api/fin/input-tax-transfers/**").hasAnyRole("ADMIN", "FINANCE", "FIN_MGR")
                // 税务风控（add-tax-risk-control，spec tax-preference-register/tax-risk-monitoring/transfer-pricing）：
                // M9-6 三组接口，菜单 PERM 三角色（ROLE_FINANCE/FIN_MGR/ADMIN），先于 /api/fin/** 兜底
                .antMatchers("/api/fin/tax-preferences/**",
                        "/api/fin/tax-risks/**",
                        "/api/fin/tp/**").hasAnyRole("ADMIN", "FINANCE", "FIN_MGR")
                // 员工档案与入转调离（add-employee-archive，spec employee-archive/employee-transfer）：
                // M10-1/M10-7，菜单 PERM 三角色（ROLE_HR/HR_MGR/ADMIN），新前缀 /api/hr/** 先于兜底
                // 加班 GM 特批（add-overtime-followup BR-4.7-04）：审批/视图/催办清单须放行 ROLE_GM，
                // 先于下方 /api/hr/** 三角色兜底（GM_SPECIAL 单服务层再校验 ROLE_GM）
                .antMatchers(HttpMethod.POST, "/api/hr/overtime/applies/*/approve",
                        "/api/hr/overtime/applies/*/approval-view").hasAnyRole("ADMIN", "HR", "HR_MGR", "GM")
                .antMatchers(HttpMethod.GET, "/api/hr/overtime/applies/*/approval-view",
                        "/api/hr/overtime/approval-reminders").hasAnyRole("ADMIN", "HR", "HR_MGR", "GM")
                // 薪酬域（add-payroll-management）：社保政策/个税/PayrollRun/发放对账须放行财务角色，
                // 先于下方 /api/hr/** 三角色兜底（角色门控与 GM 特批同教训）
                .antMatchers("/api/hr/payroll/**", "/api/hr/performance/**")
                    .hasAnyRole("ADMIN", "HR", "HR_MGR", "FINANCE", "FIN_MGR")
                // 培训认证域（add-training-certification，spec training-plan-execution/
                // qualification-certification/skill-matrix）：费用/凭证只读放行财务，
                // 计划管理/审核等写操作服务层再校验 HR（同 payroll 前缀先例），先于兜底
                .antMatchers("/api/hr/train/**", "/api/hr/certs/**", "/api/hr/skill-matrix/**")
                    .hasAnyRole("ADMIN", "HR", "HR_MGR", "FINANCE", "FIN_MGR")
                .antMatchers("/api/hr/**").hasAnyRole("ADMIN", "HR", "HR_MGR")
                // 税务申报（add-tax-filing，spec tax-filing/export-tax-refund）：M9-5 接口族，
                // 菜单 PERM 三角色同口径，先于 /api/fin/** 兜底
                .antMatchers("/api/fin/tax-returns/**",
                        "/api/fin/tax-refunds/**",
                        "/api/fin/export-refunds/**").hasAnyRole("ADMIN", "FINANCE", "FIN_MGR")
                // 审批签署角色追加（红冲/作废节点 = 财务主管 + 财务经理，add-red-invoice-void）
                // 凭证审核/过账/驳回/作废（DRAFT 三态，制单≠审核 C-4.6-07）：财务主管职责
                .antMatchers(HttpMethod.POST, "/api/fin/manual-vouchers/*/audit",
                        "/api/fin/manual-vouchers/*/post",
                        "/api/fin/manual-vouchers/*/reject",
                        "/api/fin/manual-vouchers/*/void").hasAnyRole("ADMIN", "FINANCE_MGR")
                .antMatchers(HttpMethod.GET, "/api/fin/**").hasAnyRole("ADMIN", "PM")
                .antMatchers(HttpMethod.POST, "/api/fin/**").hasAnyRole("ADMIN", "PM")
                .antMatchers(HttpMethod.PUT, "/api/fin/**").hasAnyRole("ADMIN", "PM")
                .antMatchers(HttpMethod.DELETE, "/api/fin/**").hasRole("ADMIN")
                // 领料出库（add-consignment-procurement D11）：创建/过账/作废 = ADMIN/WAREHOUSE，读 + PM
                // 试产挪用双人确认发起（add-trial-production-management 3.4）：计划侧发起（服务层再限
                // PLANNER/PLAN_MGR/ADMIN，仓管过 HTTP 后落业务 403——spec trial-execution 权限矩阵）
                .antMatchers(HttpMethod.POST, "/api/inv/issues/*/borrow-approval")
                        .hasAnyRole("ADMIN", "WAREHOUSE", "PLANNER", "PLAN_MGR")
                .antMatchers(HttpMethod.POST, "/api/inv/issues/**").hasAnyRole("ADMIN", "WAREHOUSE")
                .antMatchers(HttpMethod.GET, "/api/inv/issues/**").hasAnyRole("ADMIN", "WAREHOUSE", "PM")
                // 仓位分配（add-bin-assignment，spec bin-assignment）：写限 ADMIN/WAREHOUSE，查询认证
                .antMatchers(HttpMethod.POST, "/api/inv/bin-assignment/**").hasAnyRole("ADMIN", "WAREHOUSE")
                .antMatchers(HttpMethod.PUT, "/api/inv/bin-assignment/**").hasAnyRole("ADMIN", "WAREHOUSE")
                .antMatchers(HttpMethod.DELETE, "/api/inv/bin-assignment/**").hasAnyRole("ADMIN", "WAREHOUSE")
                // 调拨单（add-outbound-workbench，spec transfer-order）：写限 ADMIN/WAREHOUSE，查询认证
                .antMatchers(HttpMethod.POST, "/api/inv/transfers/**").hasAnyRole("ADMIN", "WAREHOUSE")
                .antMatchers(HttpMethod.PUT, "/api/inv/transfers/**").hasAnyRole("ADMIN", "WAREHOUSE")
                // 报废出库单（add-outbound-workbench，spec scrap-order）：写限 ADMIN/WAREHOUSE，查询认证
                .antMatchers(HttpMethod.POST, "/api/inv/scrap-orders/**").hasAnyRole("ADMIN", "WAREHOUSE")
                .antMatchers(HttpMethod.PUT, "/api/inv/scrap-orders/**").hasAnyRole("ADMIN", "WAREHOUSE")
                // 出库作业台（add-outbound-workbench，spec outbound-workbench）：动作限 ADMIN/WAREHOUSE，队列查询认证
                .antMatchers(HttpMethod.POST, "/api/inv/outbound/**").hasAnyRole("ADMIN", "WAREHOUSE")
                // 出库策略（add-outbound-strategy，spec outbound-strategy）：4.6.3 推荐确认写口限 ADMIN/WAREHOUSE，
                // 4.6.1 试算/偏离与 4.6.2 预警为查询（/api/** 兜底 authenticated）
                .antMatchers(HttpMethod.POST, "/api/inv/pick-recommend/**").hasAnyRole("ADMIN", "WAREHOUSE")
                // 拣货复核（add-picking-review，spec picking-review）：任务/扫码/复核/差异写口限 ADMIN/WAREHOUSE
                .antMatchers(HttpMethod.POST, "/api/inv/pick-tasks/**").hasAnyRole("ADMIN", "WAREHOUSE")
                .antMatchers(HttpMethod.POST, "/api/inv/pick-diffs/**").hasAnyRole("ADMIN", "WAREHOUSE")
                .antMatchers(HttpMethod.POST, "/api/inv/pick-scans/**").hasAnyRole("ADMIN", "WAREHOUSE")
                .antMatchers(HttpMethod.POST, "/api/inv/pick-reviews/**").hasAnyRole("ADMIN", "WAREHOUSE")
                // 波次管理（add-wave-management，spec wave-management）：写口限 ADMIN/WAREHOUSE
                .antMatchers(HttpMethod.POST, "/api/inv/waves/**").hasAnyRole("ADMIN", "WAREHOUSE")
                .antMatchers(HttpMethod.PUT, "/api/inv/waves/**").hasAnyRole("ADMIN", "WAREHOUSE")
                .antMatchers(HttpMethod.POST, "/api/inv/routes/**").hasAnyRole("ADMIN", "WAREHOUSE")
                .antMatchers(HttpMethod.PUT, "/api/inv/routes/**").hasAnyRole("ADMIN", "WAREHOUSE")
                // 供应商门户（add-supplier-portal-collaboration D2）：门户 API 仅 ROLE_SUPPLIER，
                // 且必须先于 /api/** 兜底；反向（门户账号打内部端点）由 PortalGuardInterceptor 403
                // 销售发货（add-sales-lead-to-cash，spec sales-shipment tasks 9.10）
                .antMatchers(HttpMethod.POST, "/api/sd/shipments/**")
                .hasAnyRole("ADMIN", "WAREHOUSE", "SALES")

                // ATP 承诺与分批交付（add-sales-lead-to-cash，spec sales-atp-reservation tasks 8.9）
                .antMatchers(HttpMethod.POST, "/api/sd/atp/**")
                .hasAnyRole("ADMIN", "SALES", "WAREHOUSE")

                // 销售订单（add-sales-lead-to-cash，spec sales-order tasks 7.11）
                .antMatchers(HttpMethod.POST, "/api/sd/so/**")
                .hasAnyRole("ADMIN", "SALES", "SALES_MGR", "SALES_DIRECTOR")
                .antMatchers(HttpMethod.PUT, "/api/sd/so/**")
                .hasAnyRole("ADMIN", "SALES", "SALES_MGR", "SALES_DIRECTOR")

                // 返利结算（add-sales-lead-to-cash，spec sales-rebate tasks 11.9）
                .antMatchers(HttpMethod.GET, "/api/sd/rebate/**")
                .hasAnyRole("ADMIN", "SALES_MGR", "SALES_DIRECTOR", "FINANCE_MGR")
                .antMatchers(HttpMethod.POST, "/api/sd/rebate/**")
                .hasAnyRole("ADMIN", "SALES_MGR", "SALES_DIRECTOR", "FINANCE_MGR")
                .antMatchers(HttpMethod.DELETE, "/api/sd/rebate/**")
                .hasAnyRole("ADMIN", "SALES_MGR", "SALES_DIRECTOR", "FINANCE_MGR")

                // 销售退货（add-sales-lead-to-cash，spec sales-return tasks 12.8）
                .antMatchers(HttpMethod.GET, "/api/sd/returns/**", "/api/sd/returns")
                .hasAnyRole("ADMIN", "SALES", "SALES_MGR", "FINANCE_MGR", "WAREHOUSE")
                .antMatchers(HttpMethod.POST, "/api/sd/returns/**", "/api/sd/returns")
                .hasAnyRole("ADMIN", "SALES", "SALES_MGR", "FINANCE_MGR", "WAREHOUSE")

                // 销售框架协议（add-sales-lead-to-cash，spec sales-framework-agreement tasks 13.7）
                .antMatchers(HttpMethod.GET, "/api/sd/frameworks/**", "/api/sd/frameworks")
                .hasAnyRole("ADMIN", "SALES", "SALES_MGR", "SALES_DIRECTOR", "WAREHOUSE")
                .antMatchers(HttpMethod.POST, "/api/sd/frameworks/**", "/api/sd/frameworks")
                .hasAnyRole("ADMIN", "SALES", "SALES_MGR", "SALES_DIRECTOR", "WAREHOUSE")

                // 销售合同（add-sales-lead-to-cash，spec sales-contract tasks 14.7）
                .antMatchers(HttpMethod.GET, "/api/crm/contracts/**", "/api/crm/contracts")
                .hasAnyRole("ADMIN", "SALES", "SALES_MGR", "FINANCE_MGR")
                .antMatchers(HttpMethod.POST, "/api/crm/contracts/**", "/api/crm/contracts")
                .hasAnyRole("ADMIN", "SALES", "SALES_MGR", "FINANCE_MGR")

                // 里程碑进度只读（add-milestone-management）：财务/总监只读看板与列表，
                // 须置于下方 /api/pm/** 兜底前（GM/FINANCE 前缀教训）
                .antMatchers(HttpMethod.GET, "/api/pm/milestones", "/api/pm/milestones/progress")
                .hasAnyRole("ADMIN", "PMO", "PM", "FINANCE", "FIN_MGR")
                // 工时归集/偏差只读（add-timesheet-management）：财务读成本与预警，
                // 同须置于 /api/pm/** 兜底前
                .antMatchers(HttpMethod.GET, "/api/pm/hours/**")
                .hasAnyRole("ADMIN", "PMO", "PM", "FINANCE", "FIN_MGR")
                // 预算管控（add-cost-collection-freeze）：读=归集/分析线（PM/PMO/财务/财务经理），
                // 冻结/超预算管理端写与读=PMO/FINANCE/EXEC（不含普通 PM，四叶差异化权限）
                .antMatchers(HttpMethod.GET, "/api/pm/cost/**")
                .hasAnyRole("ADMIN", "PMO", "PM", "FINANCE", "FIN_MGR", "EXEC")
                .antMatchers(HttpMethod.POST, "/api/pm/cost/over-budgets")
                // 项目经理提交《超预算审批申请》（FR-4.8-5-7）；冻结清单页不含 PM 但提交入口放行
                .hasAnyRole("ADMIN", "PMO", "PM", "FINANCE", "EXEC")
                .antMatchers(HttpMethod.POST, "/api/pm/cost/variance/scan")
                .hasAnyRole("ADMIN", "PMO", "FINANCE")
                .antMatchers(HttpMethod.POST, "/api/pm/cost/material-issues/**",
                        "/api/pm/cost/subcontracts/**", "/api/pm/cost/travels/**",
                        "/api/pm/cost/allocs/**", "/api/pm/cost/alerts/**")
                .hasAnyRole("ADMIN", "PMO", "PM", "FINANCE", "FIN_MGR", "RD_LEAD")
                // 项目立项（add-project-initiation）：PMO/PM/ADMIN 全域；模板与产能维护
                // 写端点另在方法级 @PreAuthorize 限 PMO/ADMIN；PM 数据范围在服务层过滤
                // 知识产权（add-ip-management）：四叶含法务/研发负责人/财务视角，
                // 单据级角色校验在服务层；同须置于 /api/pm/** 兜底前
                // 研发物料试制（add-rnd-material）：消耗确认/退料/成本，含研发负责人，
                // 单据级角色校验在服务层；同须置于 /api/pm/** 兜底前
                .antMatchers("/api/pm/trial/**")
                .hasAnyRole("ADMIN", "PMO", "PM", "RD_LEAD", "FINANCE", "FIN_MGR")
                .antMatchers("/api/pm/ip/**")
                .hasAnyRole("ADMIN", "PMO", "PM", "LEGAL", "RD_LEAD", "FIN_MGR")
                // 服务工单（add-service-order）：客服/服务工程师/服务主管/销售经理，
                // 单据级角色校验在服务层；同须置于 /api/pm/** 兜底前
                .antMatchers("/api/pm/service-orders/**")
                .hasAnyRole("ADMIN", "PMO", "CS", "SERVICE_ENG", "SERVICE_MGR", "SALES_MGR")
                .antMatchers("/api/pm/**")
                .hasAnyRole("ADMIN", "PMO", "PM")

                // 服务工单客户确认免密链接（add-service-order design D6）：
                // token 一次性+过期校验在服务层，匿名放行仅此两个动作
                .antMatchers("/api/public/service-orders/**").permitAll()

                .antMatchers("/api/portal/**").hasRole("SUPPLIER")
                // 采购分析（add-procurement-analysis design D10）：菜单同口径四角色，
                // 门户记分卡在 /api/portal/** 之上不冲突；行级法人过滤在查询层（design D6）
                .antMatchers("/api/bi/**", "/api/scm/scorecard/**")
                    .hasAnyRole("PM", "FINANCE_MGR", "GM", "ADMIN")
                // 开放调用入口（add-interface-integration design D1）：由 OpenApiAuthFilter 全量短路——
                // API Key + HMAC + 时间戳 + 限流 + 熔断 + 审计校验通过后才会带上 ROLE_OPEN_API，
                // 校验失败在 filter 内直接写 400/401/429/503 并 return，绝不会走到这行放行
                .antMatchers("/api/open/**").authenticated()
                // 接口对接 2.8.3（design D5）：读写 ADMIN + INTF_OPS，四类高危动作在服务层二次校验 ADMIN
                .antMatchers("/api/intf/**").hasAnyRole("ADMIN", "INTF_OPS")
                .antMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            .and()
            .exceptionHandling().authenticationEntryPoint((request, response, authException) -> {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已过期\"}");
            })
            .and()
            .addFilterBefore(openApiAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new JwtAuthenticationFilter(jwtTokenProvider), OpenApiAuthFilter.class);
        return http.build();
    }
}
