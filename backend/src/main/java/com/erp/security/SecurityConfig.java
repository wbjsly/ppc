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
    private final com.erp.dao.system.SysUserDao sysUserDao;

    public SecurityConfig(JwtTokenProvider jwtTokenProvider, OpenApiGatewayService openApiGatewayService,
                          com.erp.dao.system.SysUserDao sysUserDao) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.openApiAuthFilter = new OpenApiAuthFilter(openApiGatewayService);
        this.sysUserDao = sysUserDao;
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
                // 账号运维操作台（add-account-lifecycle-console，spec account-lifecycle R6）：
                // 全端点限 ADMIN（重置密码/角色调整/强停/归档为高危动作，归档另有二次确认）
                .antMatchers("/api/system/accounts/**").hasRole("ADMIN")
                // 角色权限矩阵（add-role-permission-console，spec role-permission-management）：
                // 矩阵读写、角色写、例外申请单限 ADMIN；GET /api/system/roles 保持 authenticated
                // （既有角色下拉消费方不因本轮收紧）
                .antMatchers(HttpMethod.POST, "/api/system/roles/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/system/roles/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/system/roles/**").hasRole("ADMIN")
                .antMatchers("/api/system/perm-matrix/**").hasRole("ADMIN")
                .antMatchers("/api/system/position-baselines/**").hasRole("ADMIN")
                // 权限审批（add-permission-approval-flow，spec permission-approval）：
                // 申请单/预检互斥/台账/窗口 限 ADMIN，置于域兜底之前
                .antMatchers("/api/system/perm-requests/**").hasRole("ADMIN")
                .antMatchers("/api/system/sod-rules/**").hasRole("ADMIN")
                .antMatchers("/api/system/grant-ledger/**").hasRole("ADMIN")
                .antMatchers("/api/system/change-windows/**").hasRole("ADMIN")
                // 紧急权限（add-break-glass-console，spec break-glass-management）限 ADMIN
                .antMatchers("/api/system/break-glass/**").hasRole("ADMIN")
                // 权限复核（add-access-review-console，spec access-review）限 ADMIN
                .antMatchers("/api/system/uar/**").hasRole("ADMIN")
                // 审计日志（add-audit-log-console，spec audit-log-management）：
                // 检索/脱敏规则限 ADMIN；取证双签端点 authenticated（签署人持 UAR_AUDITOR/DPO
                // 角色不必是 ADMIN，角色校验在 Service 内，沿 16.3 D3 节点级授权先例）
                .antMatchers("/api/system/audit-logs/**").hasRole("ADMIN")
                .antMatchers("/api/system/mask-rules/**").hasRole("ADMIN")
                .antMatchers("/api/system/forensic/requests/*/sign-audit").authenticated()
                .antMatchers("/api/system/forensic/requests/*/sign-dpo").authenticated()
                .antMatchers("/api/system/forensic/**").hasRole("ADMIN")
                // 越权检测（add-authz-detection-console，spec authz-detection）限 ADMIN
                .antMatchers("/api/system/authz-incidents/**").hasRole("ADMIN")
                // 服务报告（add-sla-report-console，spec sla-report-management）限 ADMIN
                .antMatchers("/api/system/sla-reports/**").hasRole("ADMIN")
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
                // 审批底座签署入口（add-permission-approval-flow design D3 修订）：
                // 原按角色 allowlist 放行（历轮逐增已 20+ 角色）无法表达"按人节点"
                // （直属主管等无角色签署人），改为仅要求登录；真正的授权在引擎
                // pass() 节点级执行（角色节点比角色 / 按人节点比 SIGNER / 禁自签 /
                // ACTIVE 与乐观锁），业务层 403 语义不变
                .antMatchers(HttpMethod.POST, "/api/qms/approvals/**").authenticated()
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
                .antMatchers(HttpMethod.POST, "/api/proc/po-coops/tickets/*/resolve").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/proc/po-coops/**").hasAnyRole("ADMIN", "PM")
                .antMatchers(HttpMethod.GET, "/api/proc/po-coops",
                        "/api/proc/po-coops/**").hasAnyRole("ADMIN", "PM", "WAREHOUSE")
                // ASN（spec asn-collaboration）：读三角色，代录创建 ADMIN+PM（门户创建走 /api/portal/asns）
                .antMatchers(HttpMethod.POST, "/api/proc/asns").hasAnyRole("ADMIN", "PM")
                .antMatchers(HttpMethod.GET, "/api/proc/asns",
                        "/api/proc/asns/**").hasAnyRole("ADMIN", "PM", "WAREHOUSE")
                // 伙伴入驻（add-partner-onboarding design D6，先于 /api/proc/** 兜底）：
                // 申请单写 PROCUREMENT/ADMIN；通道配置写 INTF_OPS/ADMIN；读随各自动作
                .antMatchers(HttpMethod.POST, "/api/proc/partner-applies",
                        "/api/proc/partner-applies/**").hasAnyRole("ADMIN", "PURCHASER")
                .antMatchers(HttpMethod.PUT, "/api/proc/partner-applies/**")
                    .hasAnyRole("ADMIN", "PURCHASER")
                .antMatchers(HttpMethod.GET, "/api/proc/partner-applies",
                        "/api/proc/partner-applies/**").hasAnyRole("ADMIN", "PURCHASER")
                .antMatchers("/api/proc/partner-channels/**")
                    .hasAnyRole("ADMIN", "INTF_OPS")
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
                // 供应商对账双签（add-reconciliation-console，spec supplier-statement-reconciliation R2）：
                // FINANCE_MGR 须读差异清单并签第二节点——GET 与 confirm 放行三角色；
                // 登记 POST 与 match 不放行（保持下方兜底 ADMIN,PM 口径），先于 /api/fin/** 兜底
                .antMatchers(HttpMethod.GET, "/api/fin/statements",
                        "/api/fin/statements/*").hasAnyRole("ADMIN", "PM", "FINANCE_MGR")
                .antMatchers(HttpMethod.POST, "/api/fin/statements/*/confirm")
                        .hasAnyRole("ADMIN", "PM", "FINANCE_MGR")
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
                .antMatchers("/api/pm/closure/**")
                .hasAnyRole("ADMIN", "PMO", "PM", "FINANCE", "FIN_MGR")
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

                // 伙伴激活免密链接（add-partner-onboarding design D4）：
                // token 一次性+过期+复杂度校验在服务层，匿名放行仅此两个动作
                .antMatchers("/api/public/partner-activate/**").permitAll()

                .antMatchers("/api/portal/**").hasRole("SUPPLIER")
                // CPFR 协同预测与补货（add-cpfr-forecast-replenishment，spec cpfr-collaboration R8）：
                // 查询四角色（SCMDIR/PMGR/PM/ADMIN）；协议启用/停用/参数变更限 SCMDIR（服务层同口径）；
                // 计划确认/调整/转化 = SCMDIR,PM,ADMIN（PMGR 是审批签署方，走审批台不走业务写口）
                .antMatchers(HttpMethod.GET, "/api/scm/cpfr/**")
                        .hasAnyRole("ADMIN", "SCMDIR", "PMGR", "PM")
                .antMatchers(HttpMethod.POST, "/api/scm/cpfr/agreements")
                        .hasAnyRole("ADMIN", "SCMDIR")
                .antMatchers(HttpMethod.POST, "/api/scm/cpfr/agreements/*/enable",
                        "/api/scm/cpfr/agreements/*/disable")
                        .hasAnyRole("ADMIN", "SCMDIR")
                .antMatchers("/api/scm/cpfr/**")
                        .hasAnyRole("ADMIN", "SCMDIR", "PM")
                // 采购分析（add-procurement-analysis design D10）：菜单同口径四角色，
                // 门户记分卡在 /api/portal/** 之上不冲突；行级法人过滤在查询层（design D6）
                .antMatchers("/api/bi/**", "/api/scm/scorecard/**")
                    .hasAnyRole("PM", "FINANCE_MGR", "GM", "ADMIN")
                // 开放调用入口（add-interface-integration design D1）：由 OpenApiAuthFilter 全量短路——
                // API Key + HMAC + 时间戳 + 限流 + 熔断 + 审计校验通过后才会带上 ROLE_OPEN_API，
                // 校验失败在 filter 内直接写 400/401/429/503 并 return，绝不会走到这行放行
                .antMatchers("/api/open/**").authenticated()
                // 接口对接 2.8.3（design D5）：读写 ADMIN + INTF_OPS，四类高危动作在服务层二次校验 ADMIN
                // 事件定义（add-event-subscription D6）：INTF_ARCH 可写（服务层再校验），细规则置于兜底前
                .antMatchers("/api/intf/event-types/**").hasAnyRole("ADMIN", "INTF_OPS", "INTF_ARCH")
                // SLA 月报（add-service-monitoring-console，spec service-monitoring-console）：
                // ARCH 须查询/生成/审核报告（审核角色=集成架构师），细规则置兜底前；
                // POST 精确到 generate 与 review——归档/发布保持兜底口径（服务层 requireAdmin 二次校验）
                .antMatchers(HttpMethod.GET, "/api/intf/sla/reports/**")
                        .hasAnyRole("ADMIN", "INTF_OPS", "INTF_ARCH")
                .antMatchers(HttpMethod.POST, "/api/intf/sla/reports/generate",
                        "/api/intf/sla/reports/*/review")
                        .hasAnyRole("ADMIN", "INTF_OPS", "INTF_ARCH")
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
            .addFilterAfter(new JwtAuthenticationFilter(jwtTokenProvider, sysUserDao), OpenApiAuthFilter.class);
        return http.build();
    }
}
