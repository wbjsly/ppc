import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录' }
  },
  {
    // 供应商门户（spec supplier-portal-account：ROLE_SUPPLIER 独立路由，不进内部主菜单）
    path: '/portal',
    name: 'SupplierPortal',
    component: () => import('@/views/portal/supplier/index.vue'),
    meta: { title: '供应商门户', perm: 'ROLE_SUPPLIER' }
  },
  {
    path: '/',
    name: 'Layout',
    redirect: '/dashboard',
    children: [
      {
        path: '/dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '工作台', closable: false }
      },
      {
        // 通知中心（add-kitting-check design D4：布局级铃铛入口，不进 099 菜单种子）
        path: '/notices',
        name: 'NoticeCenter',
        component: () => import('@/views/system/notices/index.vue'),
        meta: { title: '通知中心', group: '系统管理' }
      },
      {
        // 我的离职（add-transfer-followup FR-4.7-4-11：员工自助入口，认证即可不进菜单种子）
        path: '/my/exit',
        name: 'MyExit',
        component: () => import('@/views/my/exit.vue'),
        meta: { title: '我的离职申请', group: '我的' }
      },
      {
        // 我的工时（add-timesheet-management：员工每日填报，0.5h 步长/24h/补录分档）
        path: '/my/timesheet',
        name: 'MyTimesheet',
        component: () => import('@/views/my/timesheet.vue'),
        meta: { title: '我的工时', group: '我的' }
      },
      {
        // 10.8.1 外派管理（add-employee-secondment：主体转移，生效写回法人主体+社保双单）
        path: '/m/10.8.1',
        name: 'DispatchManage',
        component: () => import('@/views/hr/secondment/secondment-manage.vue'),
        meta: { title: '外派管理', group: '人力资源', secType: 'DISPATCH',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.8.2 借调管理（add-employee-secondment：主档零改动+分摊凭证+返岗延期）
        path: '/m/10.8.2',
        name: 'SecondmentManage',
        component: () => import('@/views/hr/secondment/secondment-manage.vue'),
        meta: { title: '借调管理', group: '人力资源', secType: 'SECOND',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.9.1 培训计划（add-training-certification：编制即发布+任务展开+结果登记+费用凭证）
        path: '/m/10.9.1',
        name: 'TrainPlanManage',
        component: () => import('@/views/hr/training/plan-list.vue'),
        meta: { title: '培训计划', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 10.9.2 资格认证（add-training-certification：类别字典+上传审核+台账）
        path: '/m/10.9.2',
        name: 'CertManage',
        component: () => import('@/views/hr/training/cert-manage.vue'),
        meta: { title: '资格认证', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 10.9.3 到期预警（add-training-certification：三清单+带病排班处置）
        path: '/m/10.9.3',
        name: 'CertAlert',
        component: () => import('@/views/hr/training/cert-alert.vue'),
        meta: { title: '到期预警', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 我的培训任务（add-training-certification：自助确认，认证即可不进菜单种子）
        path: '/my/train',
        name: 'MyTrain',
        component: () => import('@/views/my/train-tasks.vue'),
        meta: { title: '我的培训', group: '我的' }
      },
      {
        // 我的证书（add-training-certification：上传提交+到期倒计时）
        path: '/my/certs',
        name: 'MyCerts',
        component: () => import('@/views/my/my-certs.vue'),
        meta: { title: '我的证书', group: '我的' }
      },
      {
        path: '/system/menu',
        name: 'MenuManagement',
        component: () => import('@/views/system/menu/index.vue'),
        meta: { title: '菜单管理', group: '系统管理', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.1.1 入职开通（add-account-lifecycle-console：一页面四 Tab，meta.tab 定位）
        path: '/m/16.1.1',
        name: 'AccountOnboard',
        component: () => import('@/views/system/account/index.vue'),
        meta: { title: '入职开通', group: '系统管理', tab: 'onboard', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.1.2 在岗变更
        path: '/m/16.1.2',
        name: 'AccountTransfer',
        component: () => import('@/views/system/account/index.vue'),
        meta: { title: '在岗变更', group: '系统管理', tab: 'transfer', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.1.3 离职回收
        path: '/m/16.1.3',
        name: 'AccountExit',
        component: () => import('@/views/system/account/index.vue'),
        meta: { title: '离职回收', group: '系统管理', tab: 'exit', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.1.4 账号归档（只读；归档不可恢复 C-0-05）
        path: '/m/16.1.4',
        name: 'AccountArchive',
        component: () => import('@/views/system/account/index.vue'),
        meta: { title: '账号归档', group: '系统管理', tab: 'archive', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.2.1 角色管理（add-role-permission-console：一页面三 Tab，meta.tab 定位）
        path: '/m/16.2.1',
        name: 'RoleManagement',
        component: () => import('@/views/system/permission/index.vue'),
        meta: { title: '角色管理', group: '系统管理', tab: 'roles', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.2.2 权限矩阵
        path: '/m/16.2.2',
        name: 'PermissionMatrix',
        component: () => import('@/views/system/permission/index.vue'),
        meta: { title: '权限矩阵', group: '系统管理', tab: 'matrix', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.2.3 岗位基线
        path: '/m/16.2.3',
        name: 'PositionBaseline',
        component: () => import('@/views/system/permission/index.vue'),
        meta: { title: '岗位基线', group: '系统管理', tab: 'baseline', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.3.1 权限申请（add-permission-approval-flow：一页面三 Tab，meta.tab 定位）
        path: '/m/16.3.1',
        name: 'PermRequest',
        component: () => import('@/views/system/permission-request/index.vue'),
        meta: { title: '权限申请', group: '系统管理', tab: 'requests', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.3.2 职责预检
        path: '/m/16.3.2',
        name: 'SodPrecheck',
        component: () => import('@/views/system/permission-request/index.vue'),
        meta: { title: '职责预检', group: '系统管理', tab: 'precheck', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.3.3 到期回收
        path: '/m/16.3.3',
        name: 'GrantReclaim',
        component: () => import('@/views/system/permission-request/index.vue'),
        meta: { title: '到期回收', group: '系统管理', tab: 'reclaim', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.4.1 窗口维护（add-change-window-console：周周期列表 + 在窗状态条）
        path: '/m/16.4.1',
        name: 'ChangeWindowMaintain',
        component: () => import('@/views/system/change-window/index.vue'),
        meta: { title: '窗口维护', group: '系统管理', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.4.2 升级审批（permission-request 页窗口外升级 Tab）
        path: '/m/16.4.2',
        name: 'ChangeWindowEscalation',
        component: () => import('@/views/system/permission-request/index.vue'),
        meta: { title: '升级审批', group: '系统管理', tab: 'outwindow', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.4.3 双人复核（permission-request 页复核台账 Tab，只读证据面）
        path: '/m/16.4.3',
        name: 'ChangeWindowDualReview',
        component: () => import('@/views/system/permission-request/index.vue'),
        meta: { title: '双人复核', group: '系统管理', tab: 'review', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.5.1 紧急申请（add-break-glass-console：一页面三 Tab，meta.tab 定位）
        path: '/m/16.5.1',
        name: 'BreakGlassRequest',
        component: () => import('@/views/system/break-glass/index.vue'),
        meta: { title: '紧急申请', group: '系统管理', tab: 'requests', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.5.2 双人审批
        path: '/m/16.5.2',
        name: 'BreakGlassApproval',
        component: () => import('@/views/system/break-glass/index.vue'),
        meta: { title: '双人审批', group: '系统管理', tab: 'approvals', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.5.3 事后审计
        path: '/m/16.5.3',
        name: 'BreakGlassAudit',
        component: () => import('@/views/system/break-glass/index.vue'),
        meta: { title: '事后审计', group: '系统管理', tab: 'audits', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.6.1 季度任务（add-access-review-console：一页面四 Tab，meta.tab 定位）
        path: '/m/16.6.1',
        name: 'UarTasks',
        component: () => import('@/views/system/access-review/index.vue'),
        meta: { title: '季度任务', group: '系统管理', tab: 'tasks', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.6.2 快照比对
        path: '/m/16.6.2',
        name: 'UarSnapshots',
        component: () => import('@/views/system/access-review/index.vue'),
        meta: { title: '快照比对', group: '系统管理', tab: 'snapshots', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.6.3 回收执行
        path: '/m/16.6.3',
        name: 'UarReclaim',
        component: () => import('@/views/system/access-review/index.vue'),
        meta: { title: '回收执行', group: '系统管理', tab: 'reclaim', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.6.4 复核报告
        path: '/m/16.6.4',
        name: 'UarReports',
        component: () => import('@/views/system/access-review/index.vue'),
        meta: { title: '复核报告', group: '系统管理', tab: 'reports', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.7.1 日志检索（audit-log 页检索 Tab，含防篡改尝试记录）
        path: '/m/16.7.1',
        name: 'AuditLogSearch',
        component: () => import('@/views/system/audit-log/index.vue'),
        meta: { title: '日志检索', group: '系统管理', tab: 'search', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.7.2 取证导出（调阅申请 + 双签 + 封条包 + 分片下载）
        path: '/m/16.7.2',
        name: 'AuditForensic',
        component: () => import('@/views/system/audit-log/index.vue'),
        meta: { title: '取证导出', group: '系统管理', tab: 'forensic', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.7.3 脱敏策略（正则规则 CRUD，即刻生效）
        path: '/m/16.7.3',
        name: 'AuditMaskRules',
        component: () => import('@/views/system/audit-log/index.vue'),
        meta: { title: '脱敏策略', group: '系统管理', tab: 'mask', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.8.1 拒绝告警（403 风暴触发 + 处置三动作）
        path: '/m/16.8.1',
        name: 'AuthzAlerts',
        component: () => import('@/views/system/authz-detection/index.vue'),
        meta: { title: '拒绝告警', group: '系统管理', tab: 'alerts', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.8.2 临时降权（降权名单 + 到期/手动恢复）
        path: '/m/16.8.2',
        name: 'AuthzRestrictions',
        component: () => import('@/views/system/authz-detection/index.vue'),
        meta: { title: '临时降权', group: '系统管理', tab: 'restrictions', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.9.1 月度报告（五类指标推导 + 按月生成 + 指标详情）
        path: '/m/16.9.1',
        name: 'SlaMonthlyReports',
        component: () => import('@/views/system/sla-report/index.vue'),
        meta: { title: '月度报告', group: '系统管理', tab: 'reports', perm: 'ROLE_ADMIN' }
      },
      {
        // 16.9.2 归档发布（版本锁定 + BR-5.6-16 发布门禁 + CSV 下载）
        path: '/m/16.9.2',
        name: 'SlaArchivePublish',
        component: () => import('@/views/system/sla-report/index.vue'),
        meta: { title: '归档发布', group: '系统管理', tab: 'publish', perm: 'ROLE_ADMIN' }
      },
      {
        // 修改密码（add-account-lifecycle-console：首次登录/重置后强制；守卫放行此路由）
        path: '/my/change-password',
        name: 'ChangePassword',
        component: () => import('@/views/my/change-password.vue'),
        meta: { title: '修改密码', group: '我的' }
      },      {
        path: '/m/1.1.1',
        name: 'LegalEntity',
        component: () => import('@/views/mdm/legal-entity/index.vue'),
        meta: { title: '法人主体', group: '基础数据' }
      },
      {
        path: '/m/1.1.2',
        name: 'CostCenter',
        component: () => import('@/views/mdm/cost-center/index.vue'),
        meta: { title: '成本中心', group: '基础数据' }
      },
      {
        path: '/m/1.1.3',
        name: 'ProfitCenter',
        component: () => import('@/views/mdm/profit-center/index.vue'),
        meta: { title: '利润中心', group: '基础数据' }
      },
      {
        path: '/m/1.1.4',
        name: 'OrgUnit',
        component: () => import('@/views/mdm/org-unit/index.vue'),
        meta: { title: '组织单元', group: '基础数据' }
      },
      {
        path: '/m/1.3.1',
        name: 'CustomerAdmission',
        component: () => import('@/views/mdm/customer/index.vue'),
        meta: { title: '客户准入', group: '基础数据' }
      },
      {
        path: '/m/1.3.2',
        name: 'CreditLimit',
        component: () => import('@/views/mdm/credit-limit/index.vue'),
        meta: { title: '信用额度', group: '基础数据' }
      },
      {
        path: '/m/1.3.3',
        name: 'CrossDomainSharing',
        component: () => import('@/views/mdm/cross-domain-sharing/index.vue'),
        meta: { title: '跨域共享', group: '基础数据' }
      },
      {
        path: '/m/1.5.1',
        name: 'ExchangeRate',
        component: () => import('@/views/mdm/exchange-rate/index.vue'),
        meta: { title: '汇率维护', group: '基础数据' }
      },
      {
        path: '/m/1.5.2',
        name: 'ExchangeRateBatch',
        component: () => import('@/views/mdm/exchange-rate-batch/index.vue'),
        meta: { title: '批量更新', group: '基础数据' }
      },
      {
        path: '/m/1.5.3',
        name: 'ExchangeRateHistory',
        component: () => import('@/views/mdm/exchange-rate-history/index.vue'),
        meta: { title: '历史汇率', group: '基础数据' }
      },
      {
        path: '/m/2.1.1',
        name: 'AutoRequisition',
        component: () => import('@/views/proc/auto-requisition/index.vue'),
        meta: { title: '自动请购', group: '采购管理' }
      },
      {
        path: '/m/2.1.2',
        name: 'ManualRequisition',
        component: () => import('@/views/proc/manual-requisition/index.vue'),
        meta: { title: '手工请购', group: '采购管理' }
      },
      {
        path: '/m/2.1.3',
        name: 'EmergencyProcurement',
        component: () => import('@/views/proc/emergency-procurement/index.vue'),
        meta: { title: '紧急采购', group: '采购管理' }
      },
      {
        path: '/m/2.2.1',
        name: 'RfqComparison',
        component: () => import('@/views/proc/rfq-comparison/index.vue'),
        meta: { title: '询价比价', group: '采购管理' }
      },
      {
        path: '/m/2.2.2',
        name: 'TenderBidding',
        component: () => import('@/views/proc/tender/index.vue'),
        meta: { title: '招标竞价', group: '采购管理' }
      },
      {
        path: '/m/2.2.3',
        name: 'FrameworkAgreement',
        component: () => import('@/views/proc/framework-agreement/index.vue'),
        meta: { title: '框架协议', group: '采购管理' }
      },
      {
        path: '/m/2.2.4',
        name: 'PriceMatrix',
        component: () => import('@/views/proc/price-matrix/index.vue'),
        meta: { title: '比价矩阵', group: '采购管理' }
      },
      {
        path: '/m/2.1.4',
        name: 'RequisitionApproval',
        component: () => import('@/views/proc/requisition-approval/index.vue'),
        meta: { title: '请购审批', group: '采购管理' }
      },
      {
        path: '/m/2.3.1',
        name: 'PoCreate',
        component: () => import('@/views/proc/purchase-order/index.vue'),
        meta: { title: '订单创建', group: '采购管理', tab: 'orders' }
      },
      {
        path: '/m/2.3.2',
        name: 'PoApproval',
        component: () => import('@/views/proc/purchase-order/index.vue'),
        meta: { title: '订单审批', group: '采购管理', tab: 'approval' }
      },
      {
        path: '/m/2.3.3',
        name: 'PoChange',
        component: () => import('@/views/proc/purchase-order/index.vue'),
        meta: { title: '订单变更', group: '采购管理', tab: 'change' }
      },
      {
        path: '/m/2.3.4',
        name: 'PoVersion',
        component: () => import('@/views/proc/purchase-order/index.vue'),
        meta: { title: '版本管理', group: '采购管理', tab: 'versions' }
      },
      {
        path: '/m/2.3.5',
        name: 'PriceControl',
        component: () => import('@/views/proc/price-control/index.vue'),
        meta: { title: '价控校验', group: '采购管理' }
      },
      {
        path: '/m/2.4.1',
        name: 'GoodsReceipt',
        component: () => import('@/views/proc/goods-receipt/index.vue'),
        meta: { title: '到货登记', group: '采购管理', tab: 'register' }
      },
      {
        path: '/m/2.4.2',
        name: 'QcHold',
        component: () => import('@/views/proc/goods-receipt/index.vue'),
        meta: { title: '待检管理', group: '采购管理', tab: 'qc' }
      },
      {
        path: '/m/2.4.3',
        name: 'ReceiptDifference',
        component: () => import('@/views/proc/goods-receipt/index.vue'),
        meta: { title: '容差校验', group: '采购管理', tab: 'difference' }
      },
      {
        path: '/m/2.4.4',
        name: 'ReceiptPosting',
        component: () => import('@/views/proc/goods-receipt/index.vue'),
        meta: { title: '入库过账', group: '采购管理', tab: 'posting' }
      },
      // 质量审批待办（通用审批底座聚合入口，无菜单项，由各质量页跳转）
      {
        path: '/m/qms-approval',
        name: 'QmsApproval',
        component: () => import('@/views/qms/approval/index.vue'),
        meta: { title: '质量审批待办', group: '质量管理', hidden: true }
      },
      // 6.1 标准管理（rework-standard-management：两级式，列表共用 + 独立待办 + 详情工作台）
      {
        path: '/m/6.1.1',
        name: 'StandardApply',
        component: () => import('@/views/qms/standard/StandardList.vue'),
        meta: { title: '标准申请', group: '质量管理', view: 'apply' }
      },
      {
        path: '/m/6.1.2',
        name: 'StandardApproval',
        component: () => import('@/views/qms/standard/StandardApproval.vue'),
        meta: { title: '标准审批', group: '质量管理' }
      },
      {
        path: '/m/6.1.3',
        name: 'StandardPublish',
        component: () => import('@/views/qms/standard/StandardList.vue'),
        meta: { title: '版本发布', group: '质量管理', view: 'publish' }
      },
      {
        path: '/m/6.1.4',
        name: 'SamplingPlan',
        component: () => import('@/views/qms/standard/StandardList.vue'),
        meta: { title: '抽样方案', group: '质量管理', view: 'sampling' }
      },
      {
        path: '/m/6.1.5',
        name: 'StandardRetire',
        component: () => import('@/views/qms/standard/StandardList.vue'),
        meta: { title: '标准退役', group: '质量管理', view: 'retire' }
      },
      // 6.1 标准详情工作台（不占菜单码，守卫按 6.1.1 叶授权）
      {
        path: '/m/6.1.standard/:id',
        name: 'StandardDetail',
        component: () => import('@/views/qms/standard/StandardDetail.vue'),
        meta: { title: '标准详情', group: '质量管理', hidden: true }
      },
      // 2.5 质量协同 + 6.2/6.3/6.4 检验执行（rework-inspection-tasks 两级式：TaskBoard/TaskDetail）
      {
        path: '/m/2.5.1',
        name: 'IncomingInspection',
        component: () => import('@/views/qms/inspection/TaskBoard.vue'),
        meta: { title: '来料检验', group: '采购管理', lotType: 'IQC' }
      },
      {
        path: '/m/2.6.1',
        name: 'QualityReturn',
        component: () => import('@/views/proc/quality-return/index.vue'),
        meta: { title: '质量退货', group: '采购管理' }
      },
      // 2.6.2 其他退货（add-other-return，非质量退货独立入口）
      {
        path: '/m/2.6.2',
        name: 'OtherReturn',
        component: () => import('@/views/proc/other-return/index.vue'),
        meta: { title: '其他退货', group: '采购管理' }
      },
      // 2.6.3 红字凭证台账（add-other-return，只读）
      {
        path: '/m/2.6.3',
        name: 'RedVoucher',
        component: () => import('@/views/proc/red-voucher/index.vue'),
        meta: { title: '红字凭证', group: '采购管理' }
      },
      // 2.7.1 应付暂估（add-accrual-three-way-match，台账 + 手工冲回）
      {
        path: '/m/2.7.1',
        name: 'Accrual',
        component: () => import('@/views/proc/accrual/index.vue'),
        meta: { title: '应付暂估', group: '采购管理' }
      },
      // 2.7.2 三方匹配（add-accrual-three-way-match，发票登记 + 匹配 + 异常对账单）
      {
        path: '/m/2.7.2',
        name: 'ThreeWayMatch',
        component: () => import('@/views/proc/three-way-match/index.vue'),
        meta: { title: '三方匹配', group: '采购管理' }
      },
      // 2.7.3 付款管理（add-payment-management，单页五 Tab：对账/申请/排期执行/核销/预付）
      {
        path: '/m/2.7.3',
        name: 'PaymentManagement',
        component: () => import('@/views/proc/payment/index.vue'),
        meta: { title: '付款管理', group: '采购管理' }
      },
      {
        // 2.8.1 寄售采购（add-consignment-procurement，五 Tab：协议/库存/领用/结算/告警）
        path: '/m/2.8.1',
        name: 'Consignment',
        component: () => import('@/views/proc/consignment/index.vue'),
        meta: { title: '寄售采购', group: '采购管理' }
      },
      {
        // 2.8.2 门户协同（add-supplier-portal-collaboration，单页四 Tab：PO/ASN/VMI/对账）
        path: '/m/2.8.2',
        name: 'PortalCollaboration',
        component: () => import('@/views/proc/portal-collaboration/index.vue'),
        meta: { title: '门户协同', group: '采购管理' }
      },
      {
        // 2.8.3 接口对接（add-interface-integration，单页五 Tab：总览/事件与报文/接入治理/凭证与防护/SLA）
        path: '/m/2.8.3',
        name: 'InterfaceIntegration',
        component: () => import('@/views/proc/interface-integration/index.vue'),
        meta: { title: '接口对接', group: '采购管理', perm: 'ROLE_INTF_OPS,ROLE_ADMIN' }
      },
      {
        // 2.9.1 成本分析（add-procurement-analysis，spec procurement-cost-analysis 三 Tab）
        path: '/m/2.9.1',
        name: 'CostAnalysis',
        component: () => import('@/views/proc/cost-analysis/index.vue'),
        meta: { title: '成本分析', group: '采购管理', perm: 'ROLE_PM,ROLE_FINANCE_MGR,ROLE_GM,ROLE_ADMIN' }
      },
      {
        // 2.9.2 绩效评估（spec supplier-scorecard，4.9 流程七）
        path: '/m/2.9.2',
        name: 'SupplierScorecard',
        component: () => import('@/views/proc/scorecard/index.vue'),
        meta: { title: '绩效评估', group: '采购管理', perm: 'ROLE_PM,ROLE_FINANCE_MGR,ROLE_GM,ROLE_ADMIN' }
      },
      {
        // 12.9.1 评分模型（add-supply-performance-menu：SCM 域入口，初始定位模型配置 Tab）
        path: '/m/12.9.1',
        name: 'ScmScorecardModels',
        component: () => import('@/views/proc/scorecard/index.vue'),
        meta: { title: '评分模型', group: '外部协同', tab: 'models',
          perm: 'ROLE_SCMDIR,ROLE_PMGR,ROLE_PM,ROLE_FINANCE_MGR,ROLE_GM,ROLE_ADMIN' }
      },
      {
        // 12.9.2 记分卡片（add-supply-performance-menu：默认记分卡 Tab，整改同页可达）
        path: '/m/12.9.2',
        name: 'ScmScorecardCards',
        component: () => import('@/views/proc/scorecard/index.vue'),
        meta: { title: '记分卡片', group: '外部协同',
          perm: 'ROLE_SCMDIR,ROLE_PMGR,ROLE_PM,ROLE_FINANCE_MGR,ROLE_GM,ROLE_ADMIN' }
      },
      {
        // 2.9.3 价格监测（spec price-monitoring 监测工作台）
        path: '/m/2.9.3',
        name: 'PriceMonitoring',
        component: () => import('@/views/proc/price-monitoring/index.vue'),
        meta: { title: '价格监测', group: '采购管理', perm: 'ROLE_PM,ROLE_FINANCE_MGR,ROLE_GM,ROLE_ADMIN' }
      },
      {
        // 4.1.1 仓库档案（add-sales-lead-to-cash，spec warehouse-master）
        path: '/m/4.1.1',
        name: 'WarehouseMaster',
        component: () => import('@/views/inv/warehouse/index.vue'),
        meta: { title: '仓库档案', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.1.2 仓位规划（add-warehouse-zone-management，spec warehouse-zone-planning）
        path: '/m/4.1.2',
        name: 'WarehouseZone',
        component: () => import('@/views/inv/zone/index.vue'),
        meta: { title: '仓位规划', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.1.3 属性配置（add-warehouse-zone-management，spec warehouse-attribute-config）
        path: '/m/4.1.3',
        name: 'WarehouseAttributeConfig',
        component: () => import('@/views/inv/attribute-config/index.vue'),
        meta: { title: '属性配置', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.2.1 批号管理（add-batch-serial-master，spec batch-master）
        path: '/m/4.2.1',
        name: 'BatchMaster',
        component: () => import('@/views/inv/batch/index.vue'),
        meta: { title: '批号管理', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.2.2 序列管理（add-batch-serial-master，spec serial-master）
        path: '/m/4.2.2',
        name: 'SerialMaster',
        component: () => import('@/views/inv/serial/index.vue'),
        meta: { title: '序列管理', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.3.1 可用库存（add-stock-snapshot-three-state，spec stock-snapshot）
        path: '/m/4.3.1',
        name: 'StockSnapshotAvailable',
        component: () => import('@/views/inv/stock-snapshot/index.vue'),
        meta: { title: '可用库存', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.3.2 冻结库存（add-stock-snapshot-three-state，spec freeze-management）
        path: '/m/4.3.2',
        name: 'FreezeStock',
        component: () => import('@/views/inv/freeze/index.vue'),
        meta: { title: '冻结库存', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.3.3 在制库存（同组件另一 Tab，spec stock-snapshot D8）
        path: '/m/4.3.3',
        name: 'StockSnapshotWip',
        component: () => import('@/views/inv/stock-snapshot/index.vue'),
        meta: { title: '在制库存', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.4.1 采购入库（add-stock-posting-engine，spec inbound-workbench，type=PURCHASE_IN）
        path: '/m/4.4.1',
        name: 'InboundPurchase',
        component: () => import('@/views/inv/inbound-workbench/index.vue'),
        meta: { title: '采购入库', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.4.2 完工入库（同组件 type=WIP_IN，空态骨架）
        path: '/m/4.4.2',
        name: 'InboundWip',
        component: () => import('@/views/inv/inbound-workbench/index.vue'),
        meta: { title: '完工入库', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.4.3 退货入库（同组件 type=SALES_RETURN_IN，镜像+流水）
        path: '/m/4.4.3',
        name: 'InboundReturn',
        component: () => import('@/views/inv/inbound-workbench/index.vue'),
        meta: { title: '退货入库', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.4.4 调拨入库（同组件 type=TRANSFER_IN，空态骨架）
        path: '/m/4.4.4',
        name: 'InboundTransfer',
        component: () => import('@/views/inv/inbound-workbench/index.vue'),
        meta: { title: '调拨入库', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.4.5 仓位分配（add-bin-assignment，spec bin-assignment，双 Tab 工作台）
        path: '/m/4.4.5',
        name: 'BinAssignment',
        component: () => import('@/views/inv/bin-assignment/index.vue'),
        meta: { title: '仓位分配', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 类型配置页（spec stock-doc-type，ADMIN 写）
        path: '/inv/doc-type',
        name: 'DocTypeConfig',
        component: () => import('@/views/inv/doc-type/index.vue'),
        meta: { title: '出入库类型配置', group: '库存管理', perm: 'ROLE_ADMIN' }
      },
      {
        // 11.1.1 线索录入（add-sales-lead-to-cash，spec crm-lead-management）
        path: '/m/11.1.1',
        name: 'LeadEntry',
        component: () => import('@/views/crm/lead/index.vue'),
        meta: { title: '线索录入', group: '项目研发', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_ADMIN' }
      },
      {
        // 11.1.2 线索评分（add-sales-lead-to-cash，spec crm-lead-management）
        path: '/m/11.1.2',
        name: 'LeadScore',
        component: () => import('@/views/crm/lead-score/index.vue'),
        meta: { title: '线索评分', group: '项目研发', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_ADMIN' }
      },
      {
        // 3.1.1 商机录入（add-sales-lead-to-cash，spec opportunity-management）
        path: '/m/3.1.1',
        name: 'OpportunityEntry',
        component: () => import('@/views/crm/opportunity/index.vue'),
        meta: { title: '商机录入', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_ADMIN' }
      },
      {
        // 3.1.2 商机转化（add-sales-lead-to-cash，spec opportunity-management）
        path: '/m/3.1.2',
        name: 'OpportunityConvert',
        component: () => import('@/views/crm/opportunity-convert/index.vue'),
        meta: { title: '商机转化', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_ADMIN' }
      },
      {
        // 3.1.3 商机跟进（add-sales-lead-to-cash，spec opportunity-management）
        path: '/m/3.1.3',
        name: 'OpportunityFollowup',
        component: () => import('@/views/crm/opportunity-followup/index.vue'),
        meta: { title: '商机跟进', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_ADMIN' }
      },
      {
        // 3.2.1 报价创建（add-sales-lead-to-cash，spec sales-quote）
        path: '/m/3.2.1',
        name: 'QuoteCreate',
        component: () => import('@/views/sales/quote-create/index.vue'),
        meta: { title: '报价创建', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_ADMIN' }
      },
      {
        // 3.2.2 毛利测算（add-sales-lead-to-cash，spec sales-quote）
        path: '/m/3.2.2',
        name: 'QuoteMargin',
        component: () => import('@/views/sales/quote-margin/index.vue'),
        meta: { title: '毛利测算', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_ADMIN' }
      },
      {
        // 3.2.3 报价转化（add-sales-lead-to-cash，spec sales-quote）
        path: '/m/3.2.3',
        name: 'QuoteConvert',
        component: () => import('@/views/sales/quote-convert/index.vue'),
        meta: { title: '报价转化', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_ADMIN' }
      },
      {
        // 11.2.1 报价创建（add-quote-approval-console：同 3.2.1 组件别名，group 项目研发）
        path: '/m/11.2.1',
        name: 'ProjectQuoteCreate',
        component: () => import('@/views/sales/quote-create/index.vue'),
        meta: { title: '报价创建', group: '项目研发', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_ADMIN' }
      },
      {
        // 11.2.2 报价审批（add-quote-approval-console，spec sales-quote 报价审批台）
        path: '/m/11.2.2',
        name: 'QuoteApproval',
        component: () => import('@/views/sales/quote-approval/index.vue'),
        meta: { title: '报价审批', group: '项目研发',
          perm: 'ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 11.3.1 立项申请（add-project-initiation，spec project-initiation）
        path: '/m/11.3.1',
        name: 'PmProjectList',
        component: () => import('@/views/pm/project/project-list.vue'),
        meta: { title: '立项申请', group: '项目研发', perm: 'ROLE_PMO,ROLE_PM,ROLE_ADMIN' }
      },
      {
        // 11.3.2 任务分解（add-project-initiation：WBS 树 + 模板 Tab + 资源 Tab）
        path: '/m/11.3.2',
        name: 'PmWbsEdit',
        component: () => import('@/views/pm/project/wbs-edit.vue'),
        meta: { title: '任务分解', group: '项目研发', perm: 'ROLE_PMO,ROLE_PM,ROLE_ADMIN' }
      },
      {
        // 11.3.3 预算基线（add-project-initiation：预算编制 + 审批 + 变更）
        path: '/m/11.3.3',
        name: 'PmBudgetBaseline',
        component: () => import('@/views/pm/project/budget-baseline.vue'),
        meta: { title: '预算基线', group: '项目研发',
          perm: 'ROLE_PMO,ROLE_PM,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 11.4.1 关键节点（add-milestone-management，spec milestone-management）
        path: '/m/11.4.1',
        name: 'PmMilestoneManage',
        component: () => import('@/views/pm/milestone/milestone-manage.vue'),
        meta: { title: '关键节点', group: '项目研发', perm: 'ROLE_PM,ROLE_PMO,ROLE_ADMIN' }
      },
      {
        // 11.4.2 交付评审（add-milestone-management：提交/三态评审/L2 审批队列）
        path: '/m/11.4.2',
        name: 'PmMilestoneReview',
        component: () => import('@/views/pm/milestone/milestone-review.vue'),
        meta: { title: '交付评审', group: '项目研发', perm: 'ROLE_PM,ROLE_PMO,ROLE_ADMIN' }
      },
      {
        // 11.4.3 进度更新（add-milestone-management：完成率看板+偏差分级+催办）
        path: '/m/11.4.3',
        name: 'PmProjectProgress',
        component: () => import('@/views/pm/milestone/project-progress.vue'),
        meta: { title: '进度更新', group: '项目研发',
          perm: 'ROLE_PM,ROLE_PMO,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 11.5.1 团队工时看板（add-timesheet-management：管理者视角，员工填报走 /my/timesheet）
        path: '/m/11.5.1',
        name: 'PmTeamHours',
        component: () => import('@/views/pm/timesheet/team-hours.vue'),
        meta: { title: '工时录入', group: '项目研发', perm: 'ROLE_PM,ROLE_PMO,ROLE_ADMIN' }
      },
      {
        // 11.5.2 主管审核（add-timesheet-management：任务负责人/项目经理单头单审）
        path: '/m/11.5.2',
        name: 'PmTimesheetReview',
        component: () => import('@/views/pm/timesheet/timesheet-review.vue'),
        meta: { title: '主管审核', group: '项目研发',
          perm: 'ROLE_PM,ROLE_PMO,ROLE_DEPT_HEAD,ROLE_ADMIN' }
      },
      {
        // 11.5.3 工时归集（add-timesheet-management：四级汇总+预估对账）
        path: '/m/11.5.3',
        name: 'PmHoursCollect',
        component: () => import('@/views/pm/timesheet/hours-collect.vue'),
        meta: { title: '工时归集', group: '项目研发',
          perm: 'ROLE_PM,ROLE_PMO,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 11.5.4 偏差分析（add-timesheet-management：黄/红分级+已处理收口）
        path: '/m/11.5.4',
        name: 'PmHoursVariance',
        component: () => import('@/views/pm/timesheet/hours-variance.vue'),
        meta: { title: '偏差分析', group: '项目研发',
          perm: 'ROLE_PM,ROLE_PMO,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 11.6.1 成本归集（add-cost-collection-freeze：五类台账+领料/外协/差旅/分摊）
        path: '/m/11.6.1',
        name: 'PmCostCollect',
        component: () => import('@/views/pm/cost/cost-collect.vue'),
        meta: { title: '成本归集', group: '项目研发',
          perm: 'ROLE_PM,ROLE_PMO,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 11.6.2 偏差监控（add-cost-collection-freeze：双阈值 PV 线性+五分类）
        path: '/m/11.6.2',
        name: 'PmCostVariance',
        component: () => import('@/views/pm/cost/cost-variance.vue'),
        meta: { title: '偏差监控', group: '项目研发',
          perm: 'ROLE_PM,ROLE_PMO,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 11.6.3 超支冻结（add-cost-collection-freeze：冻结清单+超预算三级审批；不含普通 PM）
        path: '/m/11.6.3',
        name: 'PmCostFreeze',
        component: () => import('@/views/pm/cost/cost-freeze.vue'),
        meta: { title: '超支冻结', group: '项目研发',
          perm: 'ROLE_PMO,ROLE_FINANCE,ROLE_EXEC,ROLE_ADMIN' }
      },
      {
        // 11.7.1 变更申请（add-project-change：五类变更+影响分析+三级路由；倒签 L1）
        path: '/m/11.7.1',
        name: 'PmProjectChangeApply',
        component: () => import('@/views/pm/project-change/project-change-apply.vue'),
        meta: { title: '变更申请', group: '项目研发',
          perm: 'ROLE_PM,ROLE_PMO,ROLE_ADMIN' }
      },
      {
        // 11.7.2 变更审批（add-project-change：三态跟踪+基线版本历史+合同联动；不含普通 PM）
        path: '/m/11.7.2',
        name: 'PmProjectChangeApprove',
        component: () => import('@/views/pm/project-change/project-change-approve.vue'),
        meta: { title: '变更审批', group: '项目研发',
          perm: 'ROLE_PMO,ROLE_FINANCE,ROLE_EXEC,ROLE_ADMIN' }
      },
      {
        // 11.8.1 成果登记（add-ip-management：登记+工时提取+确认/异议；研发负责人把关）
        path: '/m/11.8.1',
        name: 'PmAchievementRegister',
        component: () => import('@/views/pm/ip/achievement-register.vue'),
        meta: { title: '成果登记', group: '项目研发',
          perm: 'ROLE_PM,ROLE_RD_LEAD,ROLE_PMO,ROLE_ADMIN' }
      },
      {
        // 11.8.2 权属判定（add-ip-management：协议维护+判定记录；法务维护协议）
        path: '/m/11.8.2',
        name: 'PmOwnershipJudge',
        component: () => import('@/views/pm/ip/ownership-judge.vue'),
        meta: { title: '权属判定', group: '项目研发',
          perm: 'ROLE_RD_LEAD,ROLE_LEGAL,ROLE_PMO,ROLE_ADMIN' }
      },
      {
        // 11.8.3 冲突仲裁（add-ip-management：双入口+主张+意见+裁决；法务线）
        path: '/m/11.8.3',
        name: 'PmIpArbitration',
        component: () => import('@/views/pm/ip/arbitration.vue'),
        meta: { title: '冲突仲裁', group: '项目研发',
          perm: 'ROLE_LEGAL,ROLE_PMO,ROLE_ADMIN' }
      },
      {
        // 11.8.4 资产建档（add-ip-management：档案台账+申请状态+历史；含财务视角）
        path: '/m/11.8.4',
        name: 'PmIpAsset',
        component: () => import('@/views/pm/ip/ip-asset.vue'),
        meta: { title: '资产建档', group: '项目研发',
          perm: 'ROLE_RD_LEAD,ROLE_LEGAL,ROLE_PMO,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 11.9.1 领用管理（add-rnd-material：试制领料+消耗确认+样品台账+退料）
        path: '/m/11.9.1',
        name: 'PmTrialManage',
        component: () => import('@/views/pm/trial/trial-manage.vue'),
        meta: { title: '领用管理', group: '项目研发',
          perm: 'ROLE_PM,ROLE_RD_LEAD,ROLE_PMO,ROLE_ADMIN' }
      },
      {
        // 11.9.2 试制成本（add-rnd-material：TRIAL 任务聚合+预算比对；含财务视角）
        path: '/m/11.9.2',
        name: 'PmTrialCost',
        component: () => import('@/views/pm/trial/trial-cost.vue'),
        meta: { title: '试制成本', group: '项目研发',
          perm: 'ROLE_PM,ROLE_RD_LEAD,ROLE_PMO,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 11.10.1 投诉受理（add-service-order：登记/定级/派单；客服+服务主管+销售经理）
        path: '/m/11.10.1',
        name: 'PmComplaintAccept',
        component: () => import('@/views/pm/service/complaint-accept.vue'),
        meta: { title: '投诉受理', group: '项目研发',
          perm: 'ROLE_CS,ROLE_SERVICE_MGR,ROLE_SALES_MGR,ROLE_ADMIN' }
      },
      {
        // 11.10.2 工单闭环（add-service-order：处理/确认/回访/改进任务；含服务工程师）
        path: '/m/11.10.2',
        name: 'PmOrderClose',
        component: () => import('@/views/pm/service/order-close.vue'),
        meta: { title: '工单闭环', group: '项目研发',
          perm: 'ROLE_CS,ROLE_SERVICE_ENG,ROLE_SERVICE_MGR,ROLE_SALES_MGR,ROLE_ADMIN' }
      },
      {
        // 11.12.1 结项检查（add-project-closure：发起/检查单/遗留；PM 发起，PMO 审批查看）
        path: '/m/11.12.1',
        name: 'PmClosureCheck',
        component: () => import('@/views/pm/closure/closure-check.vue'),
        meta: { title: '结项检查', group: '项目研发',
          perm: 'ROLE_PMO,ROLE_PM,ROLE_ADMIN' }
      },
      {
        // 11.12.2 成本结算（财务确认签字，FINANCE/FIN_MGR 可操作）
        path: '/m/11.12.2',
        name: 'PmClosureSettle',
        component: () => import('@/views/pm/closure/closure-settle.vue'),
        meta: { title: '成本结算', group: '项目研发',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_PMO,ROLE_ADMIN' }
      },
      {
        // 11.12.3 文档归档（③⑥凭证 + 总结报告上传）
        path: '/m/11.12.3',
        name: 'PmClosureArchive',
        component: () => import('@/views/pm/closure/closure-archive.vue'),
        meta: { title: '文档归档', group: '项目研发',
          perm: 'ROLE_PMO,ROLE_PM,ROLE_ADMIN' }
      },
      {
        // 11.12.4 资源释放（归档快照只读，仅 PMO/ADMIN）
        path: '/m/11.12.4',
        name: 'PmResRelease',
        component: () => import('@/views/pm/closure/res-release.vue'),
        meta: { title: '资源释放', group: '项目研发',
          perm: 'ROLE_PMO,ROLE_ADMIN' }
      },
      {
        // 12.1.1 伙伴注册（add-partner-onboarding：引入申请+证照附件+激活链接）
        path: '/m/12.1.1',
        name: 'PartnerApply',
        component: () => import('@/views/scm/partner-onboarding/apply-list.vue'),
        meta: { title: '伙伴注册', group: '外部协同',
          perm: 'ROLE_PURCHASER,ROLE_ADMIN' }
      },
      {
        // 12.1.2 资质审核（add-partner-onboarding：动态节点链待办+驳回+时限说明）
        path: '/m/12.1.2',
        name: 'PartnerReview',
        component: () => import('@/views/scm/partner-onboarding/review.vue'),
        meta: { title: '资质审核', group: '外部协同',
          perm: 'ROLE_PURCHASER,ROLE_QUALITY_MGR,ROLE_COMPLIANCE,ROLE_ADMIN' }
      },
      {
        // 12.1.3 通道开通（add-partner-onboarding：配置唯一入口，SANDBOX 默认走会签升 PROD）
        path: '/m/12.1.3',
        name: 'PartnerChannel',
        component: () => import('@/views/scm/partner-onboarding/channel-config.vue'),
        meta: { title: '通道开通', group: '外部协同',
          perm: 'ROLE_INTF_OPS,ROLE_ADMIN' }
      },
      {
        // 12.2.1 事件定义（add-event-subscription：注册表/Schema/版本，集成架构师维护）
        path: '/m/12.2.1',
        name: 'EventType',
        component: () => import('@/views/proc/event-subscription/event-type.vue'),
        meta: { title: '事件定义', group: '外部协同',
          perm: 'ROLE_INTF_ARCH,ROLE_ADMIN' }
      },
      {
        // 12.2.2 订阅配置（add-event-subscription：伙伴×类型，两级回落零断流）
        path: '/m/12.2.2',
        name: 'EventSubscription',
        component: () => import('@/views/proc/event-subscription/subscription.vue'),
        meta: { title: '订阅配置', group: '外部协同',
          perm: 'ROLE_INTF_OPS,ROLE_ADMIN' }
      },
      {
        // 12.2.3 消费监控（add-event-subscription：类型聚合+台账+重放/工单收口）
        path: '/m/12.2.3',
        name: 'EventConsumeMonitor',
        component: () => import('@/views/proc/event-subscription/consume-monitor.vue'),
        meta: { title: '消费监控', group: '外部协同',
          perm: 'ROLE_INTF_OPS,ROLE_ADMIN' }
      },
      {
        // 12.2.4 告警处理（add-event-subscription：三级告警确认+死信/暂存区块）
        path: '/m/12.2.4',
        name: 'EventAlertHandle',
        component: () => import('@/views/proc/event-subscription/alert-handle.vue'),
        meta: { title: '告警处理', group: '外部协同',
          perm: 'ROLE_INTF_OPS,ROLE_ADMIN' }
      },
      {
        // 12.3.1 认证鉴权（add-interface-call-console：拒绝原因分析+凭证只读，方案 B 独立建页）
        path: '/m/12.3.1',
        name: 'AuthRejection',
        component: () => import('@/views/proc/interface-call-console/auth-rejection.vue'),
        meta: { title: '认证鉴权', group: '外部协同',
          perm: 'ROLE_INTF_OPS,ROLE_ADMIN' }
      },
      {
        // 12.3.2 限流控制（状态总览+降档历史+429 趋势，参数只读跳 12.1.3）
        path: '/m/12.3.2',
        name: 'RateControl',
        component: () => import('@/views/proc/interface-call-console/rate-control.vue'),
        meta: { title: '限流控制', group: '外部协同',
          perm: 'ROLE_INTF_OPS,ROLE_ADMIN' }
      },
      {
        // 12.3.3 调用审计（Trace 下钻+聚合+明细增强+导出）
        path: '/m/12.3.3',
        name: 'AuditTrace',
        component: () => import('@/views/proc/interface-call-console/audit-trace.vue'),
        meta: { title: '调用审计', group: '外部协同',
          perm: 'ROLE_INTF_OPS,ROLE_ADMIN' }
      },
      {
        // 12.4.1 报文接收（add-edi-message-console：台账+三入口+MDN）
        path: '/m/12.4.1',
        name: 'EdiReceive',
        component: () => import('@/views/proc/edi-message-console/receive.vue'),
        meta: { title: '报文接收', group: '外部协同',
          perm: 'ROLE_INTF_OPS,ROLE_ADMIN' }
      },
      {
        // 12.4.2 格式校验（四级校验详情+重试轨迹+工单联动）
        path: '/m/12.4.2',
        name: 'EdiValidation',
        component: () => import('@/views/proc/edi-message-console/validation.vue'),
        meta: { title: '格式校验', group: '外部协同',
          perm: 'ROLE_INTF_OPS,ROLE_ADMIN' }
      },
      {
        // 12.4.3 映射入库（映射规则维护 admin-only+入库结果+回执视图）
        path: '/m/12.4.3',
        name: 'EdiMapping',
        component: () => import('@/views/proc/edi-message-console/mapping.vue'),
        meta: { title: '映射入库', group: '外部协同',
          perm: 'ROLE_INTF_OPS,ROLE_ADMIN' }
      },
      {
        // 12.5.1 订单下达（add-po-collaboration-console：推送列表+推送台账）
        path: '/m/12.5.1',
        name: 'PoPush',
        component: () => import('@/views/proc/po-collaboration-console/push.vue'),
        meta: { title: '订单下达', group: '外部协同',
          perm: 'ROLE_PM,ROLE_ADMIN,ROLE_WAREHOUSE' }
      },
      {
        // 12.5.2 交期确认（确认四分支+倒计时+时间线+协同异常工单）
        path: '/m/12.5.2',
        name: 'PoConfirm',
        component: () => import('@/views/proc/po-collaboration-console/confirm.vue'),
        meta: { title: '交期确认', group: '外部协同',
          perm: 'ROLE_PM,ROLE_ADMIN,ROLE_WAREHOUSE' }
      },
      {
        // 12.5.3 寄售协同（补链：复用 2.8.1 寄售采购页，同一页两域入口不复制）
        path: '/m/12.5.3',
        name: 'ConsignmentLink',
        component: () => import('@/views/proc/consignment/index.vue'),
        meta: { title: '寄售协同', group: '外部协同',
          perm: 'ROLE_PM,ROLE_ADMIN' }
      },
      {
        // 12.6.1 对账匹配（add-reconciliation-console：登记+系统匹配+差异并排）
        path: '/m/12.6.1',
        name: 'ReconMatch',
        component: () => import('@/views/proc/reconciliation-console/match.vue'),
        meta: { title: '对账匹配', group: '外部协同',
          perm: 'ROLE_PM,ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 12.6.2 差异处理（add-reconciliation-console：差异清单+双签解冻+冻结回显）
        path: '/m/12.6.2',
        name: 'ReconException',
        component: () => import('@/views/proc/reconciliation-console/exception.vue'),
        meta: { title: '差异处理', group: '外部协同',
          perm: 'ROLE_PM,ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 12.7.1 指标采集（add-service-monitoring-console：四项指标+伙伴维度筛选）
        path: '/m/12.7.1',
        name: 'SlaMetrics',
        component: () => import('@/views/proc/service-monitoring/metrics.vue'),
        meta: { title: '指标采集', group: '外部协同',
          perm: 'ROLE_INTF_OPS,ROLE_ADMIN' }
      },
      {
        // 12.7.2 告警升级（add-service-monitoring-console：升级链只读视图）
        path: '/m/12.7.2',
        name: 'SlaAlerts',
        component: () => import('@/views/proc/service-monitoring/alerts.vue'),
        meta: { title: '告警升级', group: '外部协同',
          perm: 'ROLE_INTF_OPS,ROLE_ADMIN' }
      },
      {
        // 12.7.3 月度报告（add-service-monitoring-console：月报四链+章节预览）
        path: '/m/12.7.3',
        name: 'SlaReport',
        component: () => import('@/views/proc/service-monitoring/report.vue'),
        meta: { title: '月度报告', group: '外部协同',
          perm: 'ROLE_INTF_OPS,ROLE_INTF_ARCH,ROLE_ADMIN' }
      },
      {
        // 12.8.1 协同预测（add-cpfr-forecast-replenishment：协议/预测/反馈/例外四 Tab）
        path: '/m/12.8.1',
        name: 'CpfrForecast',
        component: () => import('@/views/scm/forecast-replenishment/forecast.vue'),
        meta: { title: '协同预测', group: '外部协同',
          perm: 'ROLE_SCMDIR,ROLE_PMGR,ROLE_PM,ROLE_ADMIN' }
      },
      {
        // 12.8.2 补货计划（add-cpfr-forecast-replenishment：联合计划/冻结期调整/准确率）
        path: '/m/12.8.2',
        name: 'CpfrPlan',
        component: () => import('@/views/scm/forecast-replenishment/plan.vue'),
        meta: { title: '补货计划', group: '外部协同',
          perm: 'ROLE_SCMDIR,ROLE_PMGR,ROLE_PM,ROLE_ADMIN' }
      },
      {
        // 3.3.1 信用检查（add-sales-lead-to-cash，spec customer-credit-control）
        path: '/m/3.3.1',
        name: 'CreditCheck',
        component: () => import('@/views/sales/credit-check/index.vue'),
        meta: { title: '信用检查', group: '销售管理', perm: 'ROLE_CREDIT_ADMIN,ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 3.3.2 信用冻结看板（add-sales-lead-to-cash，spec customer-credit-control）
        path: '/m/3.3.2',
        name: 'CreditFreeze',
        component: () => import('@/views/sales/credit-freeze/index.vue'),
        meta: { title: '信用冻结', group: '销售管理', perm: 'ROLE_CREDIT_ADMIN,ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 3.3.3 预收处理（add-sales-lead-to-cash，spec customer-credit-control）
        path: '/m/3.3.3',
        name: 'Prepayment',
        component: () => import('@/views/sales/prepayment/index.vue'),
        meta: { title: '预收处理', group: '销售管理', perm: 'ROLE_CREDIT_ADMIN,ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 3.7.1 部分发货（add-sales-lead-to-cash，spec sales-shipment）
        path: '/m/3.7.1',
        name: 'ShipmentPartial',
        component: () => import('@/views/sales/shipment-partial/index.vue'),
        meta: { title: '部分发货', group: '销售管理', perm: 'ROLE_SALES_MGR,ROLE_WAREHOUSE,ROLE_SALES,ROLE_ADMIN' }
      },
      {
        // 3.7.2 合并发货（add-sales-lead-to-cash，spec sales-shipment）
        path: '/m/3.7.2',
        name: 'ShipmentMerge',
        component: () => import('@/views/sales/shipment-merge/index.vue'),
        meta: { title: '合并发货', group: '销售管理', perm: 'ROLE_SALES_MGR,ROLE_WAREHOUSE,ROLE_SALES,ROLE_ADMIN' }
      },
      {
        // 3.7.3 分批发货（add-sales-lead-to-cash，spec sales-shipment）
        path: '/m/3.7.3',
        name: 'ShipmentBatch',
        component: () => import('@/views/sales/shipment-batch/index.vue'),
        meta: { title: '分批发货', group: '销售管理', perm: 'ROLE_SALES_MGR,ROLE_WAREHOUSE,ROLE_SALES,ROLE_ADMIN' }
      },
      {
        // 3.7.4 发货确认（add-sales-lead-to-cash，spec sales-shipment）
        path: '/m/3.7.4',
        name: 'ShipmentConfirm',
        component: () => import('@/views/sales/shipment-confirm/index.vue'),
        meta: { title: '发货确认', group: '销售管理', perm: 'ROLE_SALES_MGR,ROLE_WAREHOUSE,ROLE_SALES,ROLE_ADMIN' }
      },
      {
        // 3.8.1 开票触发（add-sales-lead-to-cash，spec sales-invoicing-receivable）
        path: '/m/3.8.1',
        name: 'InvoiceTrigger',
        component: () => import('@/views/sales/invoice-trigger/index.vue'),
        meta: { title: '开票触发', group: '销售管理', perm: 'ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 3.8.2 自动核销（add-sales-lead-to-cash，spec sales-invoicing-receivable）
        path: '/m/3.8.2',
        name: 'ArWriteoff',
        component: () => import('@/views/sales/ar-writeoff/index.vue'),
        meta: { title: '自动核销', group: '销售管理', perm: 'ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 3.8.3 应收管理（add-sales-lead-to-cash，spec sales-invoicing-receivable）
        path: '/m/3.8.3',
        name: 'ArManage',
        component: () => import('@/views/sales/ar-manage/index.vue'),
        meta: { title: '应收管理', group: '销售管理', perm: 'ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 3.9.1 返利计算（add-sales-lead-to-cash，spec sales-rebate，含三配置 Tab）
        path: '/m/3.9.1',
        name: 'RebateCalc',
        component: () => import('@/views/sales/rebate-calc/index.vue'),
        meta: { title: '返利计算', group: '销售管理', perm: 'ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 3.9.2 返利审批（add-sales-lead-to-cash，spec sales-rebate）
        path: '/m/3.9.2',
        name: 'RebateApproval',
        component: () => import('@/views/sales/rebate-approval/index.vue'),
        meta: { title: '返利审批', group: '销售管理', perm: 'ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 3.9.3 返利兑现（add-sales-lead-to-cash，spec sales-rebate）
        path: '/m/3.9.3',
        name: 'RebateCash',
        component: () => import('@/views/sales/rebate-cash/index.vue'),
        meta: { title: '返利兑现', group: '销售管理', perm: 'ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 3.10.1 退货申请（add-sales-lead-to-cash，spec sales-return）
        path: '/m/3.10.1',
        name: 'ReturnRequest',
        component: () => import('@/views/sales/return-request/index.vue'),
        meta: { title: '退货申请', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_FINANCE_MGR,ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 3.10.2 退货判定（add-sales-lead-to-cash，spec sales-return）
        path: '/m/3.10.2',
        name: 'ReturnJudge',
        component: () => import('@/views/sales/return-judge/index.vue'),
        meta: { title: '退货判定', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_FINANCE_MGR,ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 3.10.3 退款换货（add-sales-lead-to-cash，spec sales-return）
        path: '/m/3.10.3',
        name: 'ReturnSettle',
        component: () => import('@/views/sales/return-settle/index.vue'),
        meta: { title: '退款换货', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_FINANCE_MGR,ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 3.11.1 框架订单（add-sales-lead-to-cash，spec sales-framework-agreement）
        path: '/m/3.11.1',
        name: 'FrameworkAgreement',
        component: () => import('@/views/sales/framework-agreement/index.vue'),
        meta: { title: '框架订单', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 3.11.2 分批发货执行视图（add-sales-lead-to-cash，spec sales-framework-agreement）
        path: '/m/3.11.2',
        name: 'FrameworkExecution',
        component: () => import('@/views/sales/framework-execution/index.vue'),
        meta: { title: '分批发货', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 11.11.1 合同签订（add-sales-lead-to-cash，spec sales-contract）
        path: '/m/11.11.1',
        name: 'ContractSign',
        component: () => import('@/views/crm/contract-sign/index.vue'),
        meta: { title: '合同签订', group: '项目研发', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 11.11.2 收款计划（add-sales-lead-to-cash，spec sales-contract）
        path: '/m/11.11.2',
        name: 'ContractPlan',
        component: () => import('@/views/crm/contract-plan/index.vue'),
        meta: { title: '收款计划', group: '项目研发', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 11.11.3 合同变更（add-sales-lead-to-cash，spec sales-contract）
        path: '/m/11.11.3',
        name: 'ContractChange',
        component: () => import('@/views/crm/contract-change/index.vue'),
        meta: { title: '合同变更', group: '项目研发', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 3.4.1 承诺试算（add-sales-lead-to-cash，spec sales-atp-reservation）
        path: '/m/3.4.1',
        name: 'AtpTrial',
        component: () => import('@/views/sales/atp/index.vue'),
        meta: { title: '承诺试算', group: '销售管理', perm: 'ROLE_SALES_MGR,ROLE_SALES,ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 3.4.2 分批交付（add-sales-lead-to-cash，spec sales-atp-reservation）
        path: '/m/3.4.2',
        name: 'BatchDelivery',
        component: () => import('@/views/sales/batch-delivery/index.vue'),
        meta: { title: '分批交付', group: '销售管理', perm: 'ROLE_SALES_MGR,ROLE_SALES,ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 3.5.1 订单创建（add-sales-lead-to-cash，spec sales-order）
        path: '/m/3.5.1',
        name: 'SoCreate',
        component: () => import('@/views/sales/so-create/index.vue'),
        meta: { title: '订单创建', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_ADMIN' }
      },
      {
        // 3.5.2 订单审批（add-sales-lead-to-cash，spec sales-order）
        path: '/m/3.5.2',
        name: 'SoApproval',
        component: () => import('@/views/sales/so-approval/index.vue'),
        meta: { title: '订单审批', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_ADMIN' }
      },
      {
        // 3.5.3 订单变更（add-sales-lead-to-cash，spec sales-order）
        path: '/m/3.5.3',
        name: 'SoChange',
        component: () => import('@/views/sales/so-change/index.vue'),
        meta: { title: '订单变更', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_ADMIN' }
      },
      {
        // 3.5.4 订单关闭（add-sales-lead-to-cash，spec sales-order）
        path: '/m/3.5.4',
        name: 'SoClose',
        component: () => import('@/views/sales/so-close/index.vue'),
        meta: { title: '订单关闭', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_ADMIN' }
      },
      {
        // 3.6.1 价格矩阵（add-sales-lead-to-cash，spec sales-pricing-discount）
        path: '/m/3.6.1',
        name: 'PriceMatrix',
        component: () => import('@/views/sales/price-matrix/index.vue'),
        meta: { title: '价格矩阵', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_ADMIN' }
      },
      {
        // 3.6.2 折扣矩阵（add-sales-lead-to-cash，spec sales-pricing-discount）
        path: '/m/3.6.2',
        name: 'DiscountMatrix',
        component: () => import('@/views/sales/discount-matrix/index.vue'),
        meta: { title: '折扣矩阵', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_ADMIN' }
      },
      {
        // 3.6.3 专属折扣（add-sales-lead-to-cash，spec sales-pricing-discount）
        path: '/m/3.6.3',
        name: 'ExclusiveDiscount',
        component: () => import('@/views/sales/exclusive-discount/index.vue'),
        meta: { title: '专属折扣', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_ADMIN' }
      },
      {
        // 3.6.4 取价记录（add-sales-lead-to-cash，spec sales-pricing-discount）
        path: '/m/3.6.4',
        name: 'PriceAudit',
        component: () => import('@/views/sales/price-audit/index.vue'),
        meta: { title: '取价记录', group: '销售管理', perm: 'ROLE_SALES,ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_ADMIN' }
      },
      {
        // 4.5.1 销售出库（add-outbound-workbench，spec outbound-workbench，四路由共用作业台）
        path: '/m/4.5.1',
        name: 'OutboundSales',
        component: () => import('@/views/inv/outbound-workbench/index.vue'),
        meta: { title: '销售出库', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.5.2 领料出库（作业台队列；创建领料单入口外链 /inv/material-issue——偏差 D6）
        path: '/m/4.5.2',
        name: 'OutboundMaterialIssue',
        component: () => import('@/views/inv/outbound-workbench/index.vue'),
        meta: { title: '领料出库', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 领料单创建/管理页（移出菜单路由，作业台外链可达）
        path: '/inv/material-issue',
        name: 'MaterialIssue',
        component: () => import('@/views/inv/material-issue/index.vue'),
        meta: { title: '领料单', group: '库存管理' }
      },
      {
        // 4.5.3 调拨出库（作业台出库段过账）
        path: '/m/4.5.3',
        name: 'OutboundTransfer',
        component: () => import('@/views/inv/outbound-workbench/index.vue'),
        meta: { title: '调拨出库', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.5.4 报废出库（作业台队列；新建报废单入口外链 /inv/scrap-order）
        path: '/m/4.5.4',
        name: 'OutboundScrap',
        component: () => import('@/views/inv/outbound-workbench/index.vue'),
        meta: { title: '报废出库', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 报废单管理（创建/会签/过账/核销，spec scrap-order）
        path: '/inv/scrap-order',
        name: 'ScrapOrder',
        component: () => import('@/views/inv/scrap-order/index.vue'),
        meta: { title: '报废单', group: '库存管理' }
      },
      {
        // 4.13.1 批次追溯（add-trace-recall：三索引发起 + 五类流向 + 冻结/拦截/召回登记）
        path: '/m/4.13.1',
        name: 'TraceAnalyze',
        component: () => import('@/views/inv/trace-analyze/index.vue'),
        meta: {
          title: '批次追溯', group: '库存管理',
          perm: 'ROLE_QUALITY_ENG,ROLE_QUALITY_MGR,ROLE_WAREHOUSE,ROLE_ADMIN'
        }
      },
      {
        // 4.13.2 召回处理（add-trace-recall：受限区入库 + 报废处置 + 结案报告）
        path: '/m/4.13.2',
        name: 'TraceRecall',
        component: () => import('@/views/inv/trace-recall/index.vue'),
        meta: {
          title: '召回处理', group: '库存管理',
          perm: 'ROLE_QUALITY_ENG,ROLE_QUALITY_MGR,ROLE_WAREHOUSE,ROLE_ADMIN'
        }
      },
      {
        // 4.13.1 批次追溯（add-trace-recall：三索引发起 + 五类流向 + 冻结/拦截/召回登记）
        path: '/m/4.13.1',
        name: 'TraceAnalyze',
        component: () => import('@/views/inv/trace-analyze/index.vue'),
        meta: {
          title: '批次追溯', group: '库存管理',
          perm: 'ROLE_QUALITY_ENG,ROLE_QUALITY_MGR,ROLE_WAREHOUSE,ROLE_ADMIN'
        }
      },
      {
        // 4.13.2 召回处理（add-trace-recall：受限区入库 + 报废处置 + 结案报告）
        path: '/m/4.13.2',
        name: 'TraceRecall',
        component: () => import('@/views/inv/trace-recall/index.vue'),
        meta: {
          title: '召回处理', group: '库存管理',
          perm: 'ROLE_QUALITY_ENG,ROLE_QUALITY_MGR,ROLE_WAREHOUSE,ROLE_ADMIN'
        }
      },
      {
        // 4.14.1 实时查询（add-inventory-reports：位行明细 + 库龄列）
        path: '/m/4.14.1',
        name: 'ReportRealtime',
        component: () => import('@/views/inv/report-realtime/index.vue'),
        meta: { title: '实时查询', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.14.2 周转分析（add-inventory-reports：M-WMS-001，财务口径多两财务角色）
        path: '/m/4.14.2',
        name: 'ReportTurnover',
        component: () => import('@/views/inv/report-turnover/index.vue'),
        meta: {
          title: '周转分析', group: '库存管理',
          perm: 'ROLE_WAREHOUSE,ROLE_ADMIN,ROLE_FINANCE,ROLE_FINANCE_MGR'
        }
      },
      {
        // 4.14.3 库龄分析（add-inventory-reports：分桶 + 呆滞清单 + 报废联动）
        path: '/m/4.14.3',
        name: 'ReportAging',
        component: () => import('@/views/inv/report-aging/index.vue'),
        meta: { title: '库龄分析', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.14.1 实时查询（add-inventory-reports：位行明细 + 库龄列）
        path: '/m/4.14.1',
        name: 'ReportRealtime',
        component: () => import('@/views/inv/report-realtime/index.vue'),
        meta: { title: '实时查询', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.14.2 周转分析（add-inventory-reports：M-WMS-001，财务口径多两财务角色）
        path: '/m/4.14.2',
        name: 'ReportTurnover',
        component: () => import('@/views/inv/report-turnover/index.vue'),
        meta: {
          title: '周转分析', group: '库存管理',
          perm: 'ROLE_WAREHOUSE,ROLE_ADMIN,ROLE_FINANCE,ROLE_FINANCE_MGR'
        }
      },
      {
        // 4.14.3 库龄分析（add-inventory-reports：分桶 + 呆滞清单 + 报废联动）
        path: '/m/4.14.3',
        name: 'ReportAging',
        component: () => import('@/views/inv/report-aging/index.vue'),
        meta: { title: '库龄分析', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 5.1.1 清单创建（add-bom-management：新建/复制/草稿编辑 + 内嵌循环校验）
        path: '/m/5.1.1',
        name: 'BomCreate',
        component: () => import('@/views/mrp/bom/BomCreate.vue'),
        meta: { title: '清单创建', group: '生产管理', perm: 'ROLE_PROCESS_ENG,ROLE_PROCESS_MGR,ROLE_ADMIN' }
      },
      {
        // 5.1.2 清单变更（add-bom-management：仅已发布可发起 + 变更原因必填 + 版本历史留痕）
        path: '/m/5.1.2',
        name: 'BomChange',
        component: () => import('@/views/mrp/bom/BomChange.vue'),
        meta: { title: '清单变更', group: '生产管理', perm: 'ROLE_PROCESS_ENG,ROLE_PROCESS_MGR,ROLE_ADMIN' }
      },
      {
        // 5.1.3 版本发布（add-bom-management：提交挂 BomPublish 审批 + 主管签署 + 手动废止）
        path: '/m/5.1.3',
        name: 'BomPublish',
        component: () => import('@/views/mrp/bom/BomPublish.vue'),
        meta: { title: '版本发布', group: '生产管理', perm: 'ROLE_PROCESS_ENG,ROLE_PROCESS_MGR,ROLE_ADMIN' }
      },
      {
        // 5.1.4 循环校验（add-bom-management：按父项/全量扫描，环路径高亮）
        path: '/m/5.1.4',
        name: 'BomCycleCheck',
        component: () => import('@/views/mrp/bom/BomCycleCheck.vue'),
        meta: { title: '循环校验', group: '生产管理', perm: 'ROLE_PROCESS_ENG,ROLE_PROCESS_MGR,ROLE_ADMIN' }
      },
      {
        // 5.2.1 工序维护（add-routing-management：全局工序字典，编码建后不可改）
        path: '/m/5.2.1',
        name: 'OperationList',
        component: () => import('@/views/mrp/routing/OperationList.vue'),
        meta: { title: '工序维护', group: '生产管理', perm: 'ROLE_PROCESS_ENG,ROLE_PROCESS_MGR,ROLE_ADMIN' }
      },
      {
        // 5.2.2 工作中心（add-routing-management：资源台账 + 产能三要素 + 外协供应商）
        path: '/m/5.2.2',
        name: 'WorkCenterList',
        component: () => import('@/views/mrp/routing/WorkCenterList.vue'),
        meta: { title: '工作中心', group: '生产管理', perm: 'ROLE_PROCESS_ENG,ROLE_PROCESS_MGR,ROLE_ADMIN' }
      },
      {
        // 5.2.3 标准工时（add-routing-management：（工序×工作中心）定额矩阵 = 适配关系）
        path: '/m/5.2.3',
        name: 'StandardMatrix',
        component: () => import('@/views/mrp/routing/StandardMatrix.vue'),
        meta: { title: '标准工时', group: '生产管理', perm: 'ROLE_PROCESS_ENG,ROLE_PROCESS_MGR,ROLE_ADMIN' }
      },
      {
        // 5.2.4 路线装配（add-routing-management：产品→有序工序序列，版本化 + 审批发布）
        path: '/m/5.2.4',
        name: 'RouteAssembly',
        component: () => import('@/views/mrp/routing/RouteAssembly.vue'),
        meta: { title: '路线装配', group: '生产管理', perm: 'ROLE_PROCESS_ENG,ROLE_PROCESS_MGR,ROLE_ADMIN' }
      },
      {
        // 5.3.1 采购建议（add-mrp-demand-planning：正式 MRP 运行 → 审核 → 复用请购链路转正）
        path: '/m/5.3.1',
        name: 'PurchaseSuggestions',
        component: () => import('@/views/mrp/plan/PurchaseSuggestions.vue'),
        meta: { title: '采购建议', group: '生产管理', perm: 'ROLE_PLANNER,ROLE_ADMIN' }
      },
      {
        // 5.3.2 生产建议（add-mrp-demand-planning：确认后转 PMO 占位计划工单）
        path: '/m/5.3.2',
        name: 'ProductionSuggestions',
        component: () => import('@/views/mrp/plan/ProductionSuggestions.vue'),
        meta: { title: '生产建议', group: '生产管理', perm: 'ROLE_PLANNER,ROLE_ADMIN' }
      },
      {
        // 5.3.3 异常标记（add-mrp-demand-planning：EXCESS+OVERDUE 聚合与处置留痕）
        path: '/m/5.3.3',
        name: 'PlanExceptions',
        component: () => import('@/views/mrp/plan/Exceptions.vue'),
        meta: { title: '异常标记', group: '生产管理', perm: 'ROLE_PLANNER,ROLE_ADMIN' }
      },
      {
        // 5.4.1 工单创建（add-work-order-management：手工/PMO 双入口 + 双快照 + 预检）
        path: '/m/5.4.1',
        name: 'MoCreate',
        component: () => import('@/views/mrp/mo/MoCreate.vue'),
        meta: { title: '工单创建', group: '生产管理', perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN' }
      },
      {
        // 5.4.2 工单审批（Planned→提交→Pending→主管签核→Confirmed；驳回回 Planned）
        path: '/m/5.4.2',
        name: 'MoApprove',
        component: () => import('@/views/mrp/mo/MoApprove.vue'),
        meta: { title: '工单审批', group: '生产管理', perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN' }
      },
      {
        // 5.4.3 工单释放（Confirmed→Released + 缺料清单展示；缺料不阻断仅标记）
        path: '/m/5.4.3',
        name: 'MoRelease',
        component: () => import('@/views/mrp/mo/MoRelease.vue'),
        meta: { title: '工单释放', group: '生产管理', perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN' }
      },
      {
        // 5.4.4 工单变更（挂起/恢复/取消 + 拆分 C-4.5-15；合并只读提示）
        path: '/m/5.4.4',
        name: 'MoChange',
        component: () => import('@/views/mrp/mo/MoChange.vue'),
        meta: { title: '工单变更', group: '生产管理', perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN' }
      },
      {
        // 5.4.5 工单关闭（手动完工确认 + 关闭预检 + 关闭终态）
        path: '/m/5.4.5',
        name: 'MoClose',
        component: () => import('@/views/mrp/mo/MoClose.vue'),
        meta: { title: '工单关闭', group: '生产管理', perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN' }
      },
      {
        // 5.5.1 齐套校验（add-kitting-check：单工单工作台 + 勾选批量 + 替代候选提示）
        path: '/m/5.5.1',
        name: 'KittingCheck',
        component: () => import('@/views/mrp/kitting/KittingCheck.vue'),
        meta: { title: '齐套校验', group: '生产管理', perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 5.5.2 缺料预警（跨工单按物料聚合 + 缺料转采购建议出口）
        path: '/m/5.5.2',
        name: 'ShortageWarning',
        component: () => import('@/views/mrp/kitting/ShortageWarning.vue'),
        meta: { title: '缺料预警', group: '生产管理', perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 5.6.1 智能派工（待派列表 + 生成/确认/取消 + 缺料阻断弹窗 + 排程时段，spec dispatch-management）
        path: '/m/5.6.1',
        name: 'DispatchPending',
        component: () => import('@/views/mrp/dispatch/DispatchPending.vue'),
        meta: { title: '智能派工', group: '生产管理', perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_SHOP_FLOOR,ROLE_ADMIN' }
      },
      {
        // 5.6.2 负荷监控（WC×日期负荷 + 110% 标红 + 跳外协建议，spec capacity-load）
        path: '/m/5.6.2',
        name: 'CapacityMonitor',
        component: () => import('@/views/mrp/dispatch/CapacityMonitor.vue'),
        meta: { title: '负荷监控', group: '生产管理', perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_SHOP_FLOOR,ROLE_ADMIN' }
      },
      {
        // 5.6.3 外协建议（超载下钻 + 候选/协议价 + 改派审批 + 回联状态，spec outsourcing-dispatch）
        path: '/m/5.6.3',
        name: 'OutsourceSuggest',
        component: () => import('@/views/mrp/dispatch/OutsourceSuggest.vue'),
        meta: { title: '外协建议', group: '生产管理', perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN' }
      },
      {
        // 16.11.1 工作日历（ADMIN 维护 upsert + PLANNER 只读，spec capacity-load）
        path: '/m/16.11.1',
        name: 'WorkCalendar',
        component: () => import('@/views/system/calendar/WorkCalendar.vue'),
        meta: { title: '工作日历', group: '系统管理', perm: 'ROLE_ADMIN,ROLE_PLANNER' }
      },
      {
        // 16.11.2 班次模板（班次 CRUD + WC 绑定，被引用禁删 422）
        path: '/m/16.11.2',
        name: 'ShiftTemplate',
        component: () => import('@/views/system/calendar/ShiftTemplate.vue'),
        meta: { title: '班次模板', group: '系统管理', perm: 'ROLE_ADMIN,ROLE_PLANNER' }
      },
      {
        // 5.7.1 报工录入（SHOP_FLOOR 作业台：扫派工单→开工→投料→产出→工时→提交，spec shop-floor-reporting）
        path: '/m/5.7.1',
        name: 'ReportWorkbench',
        component: () => import('@/views/mrp/reporting/ReportWorkbench.vue'),
        meta: { title: '报工录入', group: '生产管理', perm: 'ROLE_SHOP_FLOOR,ROLE_ADMIN' }
      },
      {
        // 5.8.1 返工工单（计划侧：判定来源选入建单/返工列表/转在制状态，change add-rework-management）
        path: '/m/5.8.1',
        name: 'ReworkOrder',
        component: () => import('@/views/mrp/rework/ReworkOrder.vue'),
        meta: { title: '返工工单', group: '生产管理', perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN' }
      },
      {
        // 5.8.2 返修处理（质量侧：复检跟踪/二次返工审批/强制报废/待入库重试/成本明细，PLANNER 只读）
        path: '/m/5.8.2',
        name: 'ReworkProcess',
        component: () => import('@/views/mrp/rework/ReworkProcess.vue'),
        meta: { title: '返修处理', group: '生产管理', perm: 'ROLE_QUALITY_ENG,ROLE_QUALITY_MGR,ROLE_ADMIN,ROLE_PLANNER' }
      },
      {
        // 5.9.1 工程试产 EVT（三叶共用 TrialStage 组件按 stage 过滤，change add-trial-production-management）
        path: '/m/5.9.1',
        name: 'TrialEvt',
        component: () => import('@/views/mrp/trial/TrialStage.vue'),
        meta: { title: '工程试产', group: '生产管理', stage: 'EVT',
          perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_PROCESS_ENG,ROLE_PROCESS_MGR,ROLE_PM,ROLE_ADMIN' }
      },
      {
        // 5.9.2 设计试产 DVT（同组件 stage=DVT）
        path: '/m/5.9.2',
        name: 'TrialDvt',
        component: () => import('@/views/mrp/trial/TrialStage.vue'),
        meta: { title: '设计试产', group: '生产管理', stage: 'DVT',
          perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_PROCESS_ENG,ROLE_PROCESS_MGR,ROLE_PM,ROLE_ADMIN' }
      },
      {
        // 5.9.3 量产试产 PVT（同组件 stage=PVT；PVT 良率闸口与转量产主战场）
        path: '/m/5.9.3',
        name: 'TrialPvt',
        component: () => import('@/views/mrp/trial/TrialStage.vue'),
        meta: { title: '量产试产', group: '生产管理', stage: 'PVT',
          perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_PROCESS_ENG,ROLE_PROCESS_MGR,ROLE_PM,ROLE_ADMIN' }
      },
      {
        // 5.7.2 报工管理（记录查询 + 冲销发起 + 完工确认 + 良率预警/NCR 入口，偏差 D1 蓝图 4→2 叶）
        path: '/m/5.7.2',
        name: 'ReportAdmin',
        component: () => import('@/views/mrp/reporting/ReportAdmin.vue'),
        meta: { title: '报工管理', group: '生产管理', perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN' }
      },
      {
        // 5.10.1 外协订单（外协派工单聚合工作台 + 交期/备注/供料维护，spec outsourcing-execution）
        path: '/m/5.10.1',
        name: 'OutsourceOrder',
        component: () => import('@/views/mrp/outsourcing/OutsourceOrder.vue'),
        meta: { title: '外协订单', group: '生产管理',
          perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_PURCHASER,ROLE_ADMIN' }
      },
      {
        // 5.10.2 收货检验（外协 PO 来源检验批 + 派工上下文 + SCAR 联动，spec outsourcing-execution）
        path: '/m/5.10.2',
        name: 'OutsourceInspection',
        component: () => import('@/views/mrp/outsourcing/OutsourceInspection.vue'),
        meta: { title: '收货检验', group: '生产管理',
          perm: 'ROLE_INSPECTOR,ROLE_QUALITY_ENG,ROLE_QUALITY_MGR,ROLE_ADMIN' }
      },
      {
        // 5.10.3 外协发料（甲供发料单：创建/出库过账/定额冲减提示/余料退回，spec outsourcing-material-supply）
        path: '/m/5.10.3',
        name: 'OutsourceMaterial',
        component: () => import('@/views/mrp/outsourcing/OutsourceMaterial.vue'),
        meta: { title: '外协发料', group: '生产管理',
          perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_PURCHASER,ROLE_ADMIN' }
      },
      {
        // 5.10.4 外协对账（按 PO 汇总收货明细 → 审批 → 应付挂账，spec outsourcing-settlement D-OS-02）
        path: '/m/5.10.4',
        name: 'OutsourceSettlement',
        component: () => import('@/views/mrp/outsourcing/OutsourceSettlement.vue'),
        meta: { title: '外协对账', group: '生产管理',
          perm: 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_PURCHASER,ROLE_ADMIN' }
      },
      {
        // 5.11.1 直接材料（量产归集要素页，spec production-cost-aggregation；成本会计全权+计划线只读）
        path: '/m/5.11.1',
        name: 'CostMaterial',
        component: () => import('@/views/mrp/cost/CostElement.vue'),
        meta: { title: '直接材料', group: '生产管理', element: 'MATERIAL',
          perm: 'ROLE_COST_ACCOUNTANT,ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN' }
      },
      {
        // 5.11.2 直接人工（同页按 meta.element 分流）
        path: '/m/5.11.2',
        name: 'CostLabor',
        component: () => import('@/views/mrp/cost/CostElement.vue'),
        meta: { title: '直接人工', group: '生产管理', element: 'LABOR',
          perm: 'ROLE_COST_ACCOUNTANT,ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN' }
      },
      {
        // 5.11.3 制造费用（费用池录入 + 三规则分摊结果，FR-4.5-6-3）
        path: '/m/5.11.3',
        name: 'OverheadPool',
        component: () => import('@/views/mrp/cost/OverheadPool.vue'),
        meta: { title: '制造费用', group: '生产管理',
          perm: 'ROLE_COST_ACCOUNTANT,ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN' }
      },
      {
        // 5.11.4 完工凭证（标准入账+差异补平、DRAFT 待过账、月度转销，spec completion-cost-voucher）
        path: '/m/5.11.4',
        name: 'CostVoucher',
        component: () => import('@/views/mrp/cost/CostVoucher.vue'),
        meta: { title: '完工凭证', group: '生产管理',
          perm: 'ROLE_COST_ACCOUNTANT,ROLE_FINANCE,ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 4.12.1 仓间调拨（调拨单全生命周期 + 内部往来，spec transfer-order P1）
        path: '/m/4.12.1',
        name: 'TransferOrder',
        component: () => import('@/views/inv/transfer-order/index.vue'),
        meta: { title: '仓间调拨', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.12.2 在途跟踪（C-4.4-09 超期挂起）
        path: '/m/4.12.2',
        name: 'TransferIntransit',
        component: () => import('@/views/inv/transfer-intransit/index.vue'),
        meta: { title: '在途跟踪', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.6.1 先进先出（add-outbound-strategy：试算 + 偏离监控）
        path: '/m/4.6.1',
        name: 'FifoStrategy',
        component: () => import('@/views/inv/fifo-strategy/index.vue'),
        meta: { title: '先进先出', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.6.2 效期优先（add-outbound-strategy：预警清单 + 锁定标识）
        path: '/m/4.6.2',
        name: 'ExpiryPriority',
        component: () => import('@/views/inv/expiry-priority/index.vue'),
        meta: { title: '效期优先', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.6.3 拣货推荐（add-outbound-strategy：队列A → 推荐 → 确认回写）
        path: '/m/4.6.3',
        name: 'PickRecommend',
        component: () => import('@/views/inv/pick-recommend/index.vue'),
        meta: { title: '拣货推荐', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.7.1 拣货任务（add-picking-review：确认即生成 + 改派/作废）
        path: '/m/4.7.1',
        name: 'PickTask',
        component: () => import('@/views/inv/pick-task/index.vue'),
        meta: { title: '拣货任务', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.7.2 扫码确认（add-picking-review：行级三码校验 + 实拣确认）
        path: '/m/4.7.2',
        name: 'PickScan',
        component: () => import('@/views/inv/pick-scan/index.vue'),
        meta: { title: '扫码确认', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.7.3 出库复核（add-picking-review：三分支复核）
        path: '/m/4.7.3',
        name: 'PickReview',
        component: () => import('@/views/inv/pick-review/index.vue'),
        meta: { title: '出库复核', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.7.4 差异处理（add-picking-review：差异闭环解锁过账门闩）
        path: '/m/4.7.4',
        name: 'PickDiff',
        component: () => import('@/views/inv/pick-diff/index.vue'),
        meta: { title: '差异处理', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.8.1 波次拣货（add-wave-management：聚类生成 + 分配 + 改批审批 + 线路 Tab）
        path: '/m/4.8.1',
        name: 'WavePick',
        component: () => import('@/views/inv/wave-pick/index.vue'),
        meta: { title: '波次拣货', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.8.2 集货发运（add-wave-management：分播 → 装车 → 逐单发运）
        path: '/m/4.8.2',
        name: 'WaveShip',
        component: () => import('@/views/inv/wave-ship/index.vue'),
        meta: { title: '集货发运', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.9.1 质量冻结（add-freeze-management-menus：FreezePanel type 分流）
        path: '/m/4.9.1',
        name: 'FreezeQuality',
        component: () => import('@/views/inv/freeze-quality/index.vue'),
        meta: { title: '质量冻结', group: '库存管理', perm: 'ROLE_QUALITY_ENG,ROLE_QUALITY_MGR,ROLE_ADMIN' }
      },
      {
        // 4.9.2 财务冻结（add-freeze-management-menus：FreezePanel type 分流）
        path: '/m/4.9.2',
        name: 'FreezeFinance',
        component: () => import('@/views/inv/freeze-finance/index.vue'),
        meta: { title: '财务冻结', group: '库存管理', perm: 'ROLE_FINANCE,ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 4.9.3 解冻审批（add-freeze-management-menus：todo 过滤 Unfreeze + 页内签署）
        path: '/m/4.9.3',
        name: 'FreezeApprove',
        component: () => import('@/views/inv/freeze-approve/index.vue'),
        meta: { title: '解冻审批', group: '库存管理', perm: 'ROLE_QUALITY_MGR,ROLE_FINANCE_MGR,ROLE_ADMIN' }
      },
      {
        // 4.9.4 影响通知（add-freeze-management-menus：影响面快照三类清单 + 通知记录）
        path: '/m/4.9.4',
        name: 'FreezeImpact',
        component: () => import('@/views/inv/freeze-impact/index.vue'),
        meta: { title: '影响通知', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.10.1 效期预警（add-expiry-management-menus：每日报告留痕+实时清单）
        path: '/m/4.10.1',
        name: 'ExpiryWarning',
        component: () => import('@/views/inv/expiry-warning/index.vue'),
        meta: { title: '效期预警', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.10.2 临期锁定（add-expiry-management-menus：台账+历史+人工锁定）
        path: '/m/4.10.2',
        name: 'ExpiryLock',
        component: () => import('@/views/inv/expiry-lock/index.vue'),
        meta: { title: '临期锁定', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.11.1 周期盘点（add-count-management：手工选范围，生成即锁仓）
        path: '/m/4.11.1',
        name: 'CountCycle',
        component: () => import('@/views/inv/count-cycle/index.vue'),
        meta: { title: '周期盘点', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.11.2 全面盘点（add-count-management：一键全仓）
        path: '/m/4.11.2',
        name: 'CountFull',
        component: () => import('@/views/inv/count-full/index.vue'),
        meta: { title: '全面盘点', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.11.3 差异审批（add-count-management：CountDiff 页内审批 + COUNT 台账）
        path: '/m/4.11.3',
        name: 'CountApprove',
        component: () => import('@/views/inv/count-approve/index.vue'),
        meta: { title: '差异审批', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.11.1 周期盘点（add-count-management：手工选范围，生成即锁仓）
        path: '/m/4.11.1',
        name: 'CountCycle',
        component: () => import('@/views/inv/count-cycle/index.vue'),
        meta: { title: '周期盘点', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.11.2 全面盘点（add-count-management：一键全仓）
        path: '/m/4.11.2',
        name: 'CountFull',
        component: () => import('@/views/inv/count-full/index.vue'),
        meta: { title: '全面盘点', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.11.3 差异审批（add-count-management：CountDiff 页内审批 + COUNT 台账）
        path: '/m/4.11.3',
        name: 'CountApprove',
        component: () => import('@/views/inv/count-approve/index.vue'),
        meta: { title: '差异审批', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.10.3 质量评估（add-expiry-management-menus：三分支处置链）
        path: '/m/4.10.3',
        name: 'ExpiryEval',
        component: () => import('@/views/inv/expiry-eval/index.vue'),
        meta: { title: '质量评估', group: '库存管理', perm: 'ROLE_QUALITY_ENG,ROLE_QUALITY_MGR,ROLE_ADMIN' }
      },
      {
        // 4.7.1 拣货任务（add-picking-review：确认即生成 + 改派/作废）
        path: '/m/4.7.1',
        name: 'PickTask',
        component: () => import('@/views/inv/pick-task/index.vue'),
        meta: { title: '拣货任务', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.7.2 扫码确认（add-picking-review：行级三码校验 + 实拣确认）
        path: '/m/4.7.2',
        name: 'PickScan',
        component: () => import('@/views/inv/pick-scan/index.vue'),
        meta: { title: '扫码确认', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.7.3 出库复核（add-picking-review：三分支复核）
        path: '/m/4.7.3',
        name: 'PickReview',
        component: () => import('@/views/inv/pick-review/index.vue'),
        meta: { title: '出库复核', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        // 4.7.4 差异处理（add-picking-review：差异闭环解锁过账门闩）
        path: '/m/4.7.4',
        name: 'PickDiff',
        component: () => import('@/views/inv/pick-diff/index.vue'),
        meta: { title: '差异处理', group: '库存管理', perm: 'ROLE_WAREHOUSE,ROLE_ADMIN' }
      },
      {
        path: '/m/2.5.2',
        name: 'ConcessionPurchase',
        component: () => import('@/views/qms/concession/index.vue'),
        meta: { title: '让步接收', group: '采购管理' }
      },
      {
        path: '/m/6.4.2',
        name: 'ConcessionQuality',
        component: () => import('@/views/qms/concession/index.vue'),
        meta: { title: '让步接收', group: '质量管理' }
      },
      {
        path: '/m/6.2.1',
        name: 'TaskIqc',
        component: () => import('@/views/qms/inspection/TaskBoard.vue'),
        meta: { title: '来料检验', group: '质量管理', lotType: 'IQC' }
      },
      {
        path: '/m/6.2.2',
        name: 'TaskIpqc',
        component: () => import('@/views/qms/inspection/TaskBoard.vue'),
        meta: { title: '过程检验', group: '质量管理', lotType: 'IPQC' }
      },
      {
        path: '/m/6.2.3',
        name: 'TaskOqc',
        component: () => import('@/views/qms/inspection/TaskBoard.vue'),
        meta: { title: '出货检验', group: '质量管理', lotType: 'OQC' }
      },
      // 6.2 详情工作台（不占菜单码）+ 配置页（巡检/免检/严格度/客户协议）
      {
        path: '/m/6.2.lot/:id',
        name: 'TaskLotDetail',
        component: () => import('@/views/qms/inspection/TaskDetail.vue'),
        meta: { title: '检验批详情', group: '质量管理', hidden: true }
      },
      {
        path: '/m/6.2.config',
        name: 'TaskConfig',
        component: () => import('@/views/qms/inspection/TaskConfig.vue'),
        meta: { title: '检验任务配置', group: '质量管理', hidden: true }
      },
      {
        path: '/m/6.3.1',
        name: 'SamplingExecute',
        component: () => import('@/views/qms/inspection/TaskBoard.vue'),
        meta: { title: '抽样执行', group: '质量管理', samplingMode: true }
      },
      {
        path: '/m/6.3.2',
        name: 'InspectionRecord',
        component: () => import('@/views/qms/inspection/RecordList.vue'),
        meta: { title: '检验记录', group: '质量管理' }
      },
      {
        path: '/m/6.4.1',
        name: 'QualifiedRelease',
        component: () => import('@/views/qms/inspection/TaskBoard.vue'),
        meta: { title: '合格放行', group: '质量管理', releaseMode: true }
      },
      // 6.8 供应质量 SCAR / 6.9 器具校准 / 6.10 过程控制（add-quality-collaboration）
      {
        path: '/m/6.8.1',
        name: 'ScarClaim',
        component: () => import('@/views/qms/scar/index.vue'),
        meta: { title: '质量索赔', group: '质量管理' }
      },
      {
        path: '/m/6.8.2',
        name: 'ScarScore',
        component: () => import('@/views/qms/scar/index.vue'),
        meta: { title: '评分反馈', group: '质量管理' }
      },
      {
        path: '/m/6.9.1',
        name: 'GaugePlan',
        component: () => import('@/views/qms/gauge/index.vue'),
        meta: { title: '校准计划', group: '质量管理' }
      },
      {
        path: '/m/6.9.2',
        name: 'GaugeExecute',
        component: () => import('@/views/qms/gauge/index.vue'),
        meta: { title: '校准执行', group: '质量管理' }
      },
      {
        path: '/m/6.9.3',
        name: 'GaugeWarning',
        component: () => import('@/views/qms/gauge/index.vue'),
        meta: { title: '到期预警', group: '质量管理' }
      },
      {
        path: '/m/6.10.1',
        name: 'SpcMonitor',
        component: () => import('@/views/qms/spc/index.vue'),
        meta: { title: '特性监控', group: '质量管理' }
      },
      {
        path: '/m/6.10.2',
        name: 'SpcTrend',
        component: () => import('@/views/qms/spc/index.vue'),
        meta: { title: '趋势预警', group: '质量管理' }
      },
      // 6.6 根因分析 CAPA / 6.7 质量成本 COPQ（add-quality-collaboration）
      {
        path: '/m/6.6.1',
        name: 'CapaReport',
        component: () => import('@/views/qms/capa/index.vue'),
        meta: { title: '8D 报告', group: '质量管理', tab: 'report' }
      },
      {
        path: '/m/6.6.2',
        name: 'CapaAction',
        component: () => import('@/views/qms/capa/index.vue'),
        meta: { title: '措施执行', group: '质量管理', tab: 'actions' }
      },
      {
        path: '/m/6.6.3',
        name: 'CapaVerify',
        component: () => import('@/views/qms/capa/index.vue'),
        meta: { title: '效果验证', group: '质量管理', tab: 'verify' }
      },
      {
        path: '/m/6.7.1',
        name: 'CopqLedger',
        component: () => import('@/views/qms/copq/index.vue'),
        meta: { title: '成本归集', group: '质量管理', tab: 'ledger' }
      },
      {
        path: '/m/6.7.2',
        name: 'CopqAnalysis',
        component: () => import('@/views/qms/copq/index.vue'),
        meta: { title: '损失分析', group: '质量管理', tab: 'analysis' }
      },
      // 7.1 资产转资（change add-asset-capitalization，spec asset-capitalization）
      {
        path: '/m/7.1.1',
        name: 'EamPurchaseCap',
        component: () => import('@/views/eam/capitalization/index.vue'),
        meta: { title: '采购转资', group: '资产管理', mode: 'PURCHASE' }
      },
      {
        path: '/m/7.1.2',
        name: 'EamCipCap',
        component: () => import('@/views/eam/capitalization/index.vue'),
        meta: { title: '在建转资', group: '资产管理', mode: 'CIP' }
      },
      {
        path: '/m/7.1.3',
        name: 'EamAssetCard',
        component: () => import('@/views/eam/card/index.vue'),
        meta: { title: '卡片生成', group: '资产管理' }
      },
      {
        path: '/m/7.1.4',
        name: 'EamCategory',
        component: () => import('@/views/eam/category/index.vue'),
        meta: { title: '分类维护', group: '资产管理' }
      },
      {
        path: '/m/7.1.5',
        name: 'EamCipLedger',
        component: () => import('@/views/eam/cip/index.vue'),
        meta: { title: '在建台账', group: '资产管理' }
      },
      // 7.2 折旧管理（change add-asset-depreciation，spec asset-depreciation，同组件三路由）
      {
        path: '/m/7.2.1',
        name: 'EamDepreciationRun',
        component: () => import('@/views/eam/depreciation/index.vue'),
        meta: { title: '月度计提', group: '资产管理', tab: 'run' }
      },
      {
        path: '/m/7.2.2',
        name: 'EamDepreciationChange',
        component: () => import('@/views/eam/depreciation/index.vue'),
        meta: { title: '变动处理', group: '资产管理', tab: 'change' }
      },
      {
        path: '/m/7.2.3',
        name: 'EamDepreciationVoucher',
        component: () => import('@/views/eam/depreciation/index.vue'),
        meta: { title: '凭证生成', group: '资产管理', tab: 'voucher' }
      },
      // 7.3 资产运维（add-asset-maintenance：同组件三路由）
      {
        path: '/m/7.3.1',
        name: 'EamMaintPlan',
        component: () => import('@/views/eam/maintenance/index.vue'),
        meta: { title: '点检保养', group: '资产管理', tab: 'plan' }
      },
      {
        path: '/m/7.3.2',
        name: 'EamMaintRepair',
        component: () => import('@/views/eam/maintenance/index.vue'),
        meta: { title: '故障维修', group: '资产管理', tab: 'repair' }
      },
      {
        path: '/m/7.3.3',
        name: 'EamMaintSpares',
        component: () => import('@/views/eam/maintenance/index.vue'),
        meta: { title: '备件领用', group: '资产管理', tab: 'spares' }
      },
      // 7.3 资产运维（add-asset-maintenance：同组件三路由）
      {
        path: '/m/7.3.1',
        name: 'EamMaintPlan',
        component: () => import('@/views/eam/maintenance/index.vue'),
        meta: { title: '点检保养', group: '资产管理', tab: 'plan' }
      },
      {
        path: '/m/7.3.2',
        name: 'EamMaintRepair',
        component: () => import('@/views/eam/maintenance/index.vue'),
        meta: { title: '故障维修', group: '资产管理', tab: 'repair' }
      },
      {
        path: '/m/7.3.3',
        name: 'EamMaintSpares',
        component: () => import('@/views/eam/maintenance/index.vue'),
        meta: { title: '备件领用', group: '资产管理', tab: 'spares' }
      },
      // 7.4 调拨租赁（add-asset-transfer-lease：同组件三路由）
      {
        path: '/m/7.4.1',
        name: 'EamTransferDept',
        component: () => import('@/views/eam/transfer-lease/index.vue'),
        meta: { title: '部门调拨', group: '资产管理', tab: 'dept' }
      },
      {
        path: '/m/7.4.2',
        name: 'EamTransferCrossLe',
        component: () => import('@/views/eam/transfer-lease/index.vue'),
        meta: { title: '法人调拨', group: '资产管理', tab: 'cross' }
      },
      {
        path: '/m/7.4.3',
        name: 'EamLease',
        component: () => import('@/views/eam/transfer-lease/index.vue'),
        meta: { title: '租赁管理', group: '资产管理', tab: 'lease' }
      },
      // 7.5 资产盘点（add-asset-count：同组件两路由）
      {
        path: '/m/7.5.1',
        name: 'EamCountWorkbench',
        component: () => import('@/views/eam/asset-count/index.vue'),
        meta: { title: '年度盘点', group: '资产管理', tab: 'workbench' }
      },
      {
        path: '/m/7.5.2',
        name: 'EamCountDiff',
        component: () => import('@/views/eam/asset-count/index.vue'),
        meta: { title: '差异处理', group: '资产管理', tab: 'diff' }
      },
      // 7.6 报废处置（add-asset-disposal：同组件三路由）
      {
        path: '/m/7.6.1',
        name: 'EamDisposalScrap',
        component: () => import('@/views/eam/disposal/index.vue'),
        meta: { title: '报废申请', group: '资产管理', tab: 'scrap' }
      },
      {
        path: '/m/7.6.2',
        name: 'EamDisposalSale',
        component: () => import('@/views/eam/disposal/index.vue'),
        meta: { title: '资产出售', group: '资产管理', tab: 'sale' }
      },
      {
        path: '/m/7.6.3',
        name: 'EamDisposalPl',
        component: () => import('@/views/eam/disposal/index.vue'),
        meta: { title: '损益处理', group: '资产管理', tab: 'pl' }
      },
      // 7.7 技改大修（add-asset-rework）
      {
        path: '/m/7.7.1',
        name: 'EamRework',
        component: () => import('@/views/eam/rework/index.vue'),
        meta: { title: '资本确认', group: '资产管理', tab: 'rework' }
      },
      // 8.1 总账管理（add-gl-management：同组件五路由）
      {
        path: '/m/8.1.1',
        name: 'GlAccounts',
        component: () => import('@/views/fin/gl/index.vue'),
        meta: { title: '科目管理', group: '财务管理', tab: 'accounts' }
      },
      {
        path: '/m/8.1.2',
        name: 'GlEntry',
        component: () => import('@/views/fin/gl/index.vue'),
        meta: { title: '凭证录入', group: '财务管理', tab: 'entry' }
      },
      {
        path: '/m/8.1.3',
        name: 'GlAudit',
        component: () => import('@/views/fin/gl/index.vue'),
        meta: { title: '凭证审核', group: '财务管理', tab: 'audit' }
      },
      {
        path: '/m/8.1.4',
        name: 'GlPost',
        component: () => import('@/views/fin/gl/index.vue'),
        meta: { title: '凭证过账', group: '财务管理', tab: 'post' }
      },
      {
        path: '/m/8.1.5',
        name: 'GlClose',
        component: () => import('@/views/fin/gl/index.vue'),
        meta: { title: '期末结账', group: '财务管理', tab: 'close' }
      },
      // 8.3 应付管理（add-ap-management：同组件三路由）
      {
        path: '/m/8.3.1',
        name: 'ApConfirm',
        component: () => import('@/views/fin/ap/index.vue'),
        meta: { title: '应付确认', group: '财务管理', tab: 'confirm' }
      },
      {
        path: '/m/8.3.2',
        name: 'ApWriteoff',
        component: () => import('@/views/fin/ap/index.vue'),
        meta: { title: '应付核销', group: '财务管理', tab: 'writeoff' }
      },
      {
        path: '/m/8.3.3',
        name: 'ApPayment',
        component: () => import('@/views/fin/ap/index.vue'),
        meta: { title: '付款管理', group: '财务管理', tab: 'payment' }
      },
      // 8.3 应付管理（add-ap-management：同组件三路由）
      {
        path: '/m/8.3.1',
        name: 'ApConfirm',
        component: () => import('@/views/fin/ap/index.vue'),
        meta: { title: '应付确认', group: '财务管理', tab: 'confirm' }
      },
      {
        path: '/m/8.3.2',
        name: 'ApWriteoff',
        component: () => import('@/views/fin/ap/index.vue'),
        meta: { title: '应付核销', group: '财务管理', tab: 'writeoff' }
      },
      {
        path: '/m/8.3.3',
        name: 'ApPayment',
        component: () => import('@/views/fin/ap/index.vue'),
        meta: { title: '付款管理', group: '财务管理', tab: 'payment' }
      },
      // 8.4 收付管理（add-payment-collection：同组件三路由）
      {
        path: '/m/8.4.1',
        name: 'CashReceive',
        component: () => import('@/views/fin/collection/index.vue'),
        meta: { title: '收款执行', group: '财务管理', tab: 'receive' }
      },
      {
        path: '/m/8.4.2',
        name: 'CashPay',
        component: () => import('@/views/fin/collection/index.vue'),
        meta: { title: '付款执行', group: '财务管理', tab: 'pay' }
      },
      {
        path: '/m/8.4.3',
        name: 'BankReceiptMatch',
        component: () => import('@/views/fin/collection/index.vue'),
        meta: { title: '回单对账', group: '财务管理', tab: 'receipt' }
      },
      // 8.8 费用报销（add-expense-reimbursement：单页三 tab）
      {
        path: '/m/8.8.1',
        name: 'ReimburseApply',
        component: () => import('@/views/fin/expense-reimbursement/index.vue'),
        meta: { title: '报销申请', group: '财务管理', tab: 'reimburse' }
      },
      {
        path: '/m/8.8.2',
        name: 'EmployeeLoan',
        component: () => import('@/views/fin/expense-reimbursement/index.vue'),
        meta: { title: '借款管理', group: '财务管理', tab: 'loan' }
      },
      {
        path: '/m/8.8.3',
        name: 'LoanWriteoff',
        component: () => import('@/views/fin/expense-reimbursement/index.vue'),
        meta: { title: '借款冲销', group: '财务管理', tab: 'writeoff' }
      },
      // 8.9 预提待摊（add-expense-reimbursement：单页两 tab）
      {
        path: '/m/8.9.1',
        name: 'PrepaidManage',
        component: () => import('@/views/fin/prepaid-amortization/index.vue'),
        meta: { title: '预提管理', group: '财务管理', tab: 'prepaid' }
      },
      {
        path: '/m/8.9.2',
        name: 'DeferredManage',
        component: () => import('@/views/fin/prepaid-amortization/index.vue'),
        meta: { title: '待摊管理', group: '财务管理', tab: 'deferred' }
      },
      {
        // 8.10.1 成本归集（change add-cost-accounting：财务入口复用生产域 CostElement，
        // 元素归集/差异列/审阅即入口；perm 六角色与 M8-10 菜单 PERM 同口径）
        path: '/m/8.10.1',
        name: 'CostCollectFinance',
        component: () => import('@/views/mrp/cost/CostElement.vue'),
        meta: { title: '成本归集', group: '财务管理', element: 'MATERIAL',
          perm: 'ROLE_COST_ACCOUNTANT,ROLE_FINANCE,ROLE_FINANCE_MGR,ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 8.10.2 差异分析（同页差异列+审阅按钮即差异分析工作面，design D1 核实结论：无独立区块无需 tab 定位）
        path: '/m/8.10.2',
        name: 'CostVarianceFinance',
        component: () => import('@/views/mrp/cost/CostElement.vue'),
        meta: { title: '差异分析', group: '财务管理', element: 'MATERIAL',
          perm: 'ROLE_COST_ACCOUNTANT,ROLE_FINANCE,ROLE_FINANCE_MGR,ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 8.10.3 成本报表（change add-cost-accounting：期间报表主区块 + 多期趋势构成双 Tab）
        path: '/m/8.10.3',
        name: 'CostReportFinance',
        component: () => import('@/views/fin/cost-accounting/index.vue'),
        meta: { title: '成本报表', group: '财务管理',
          perm: 'ROLE_COST_ACCOUNTANT,ROLE_FINANCE,ROLE_FINANCE_MGR,ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.1.1 税码匹配 + 9.1.2 税额计算（change add-tax-calculation：同页双 tab 工作台，菜单驱动定 tab）
        path: '/m/9.1.1',
        name: 'TaxMatch',
        component: () => import('@/views/fin/tax-calculation/index.vue'),
        meta: { title: '税码匹配', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        path: '/m/9.1.2',
        name: 'TaxCalc',
        component: () => import('@/views/fin/tax-calculation/index.vue'),
        meta: { title: '税额计算', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.1.3 合规校验（全域扫描报告页）
        path: '/m/9.1.3',
        name: 'TaxCompliance',
        component: () => import('@/views/fin/tax-calculation/report.vue'),
        meta: { title: '合规校验', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.2.1 销项开票（change add-invoice-management：销项台账税务视角+限额提示；
        // 申请/开具链复用既有 sales/invoice-trigger 页面）
        path: '/m/9.2.1',
        name: 'SalesInvoiceLedger',
        component: () => import('@/views/fin/invoice-management/sales-ledger.vue'),
        meta: { title: '销项开票', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.2.2 进项收票（三单匹配→认证→批量进项凭证）
        path: '/m/9.2.2',
        name: 'InputInvoices',
        component: () => import('@/views/fin/invoice-management/input-invoices.vue'),
        meta: { title: '进项收票', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.2.3 数电发票（额度→请求队列→补开→勾稽）
        path: '/m/9.2.3',
        name: 'DigitalInvoices',
        component: () => import('@/views/fin/invoice-management/digital-invoices.vue'),
        meta: { title: '数电发票', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.2.4 发票交付（双路登记+重发+超期黄标）
        path: '/m/9.2.4',
        name: 'InvoiceDelivery',
        component: () => import('@/views/fin/invoice-management/delivery.vue'),
        meta: { title: '发票交付', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.3.1 真伪查验（change add-input-certification：接线 9.2.2 进项页——查验闸口工作面）
        path: '/m/9.3.1',
        name: 'InputInspect',
        component: () => import('@/views/fin/invoice-management/input-invoices.vue'),
        meta: { title: '真伪查验', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.3.2 认证抵扣（同页认证/凭证工作面）
        path: '/m/9.3.2',
        name: 'InputCertify',
        component: () => import('@/views/fin/invoice-management/input-invoices.vue'),
        meta: { title: '认证抵扣', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.4.1 红字冲销（change add-red-invoice-void：申请→审批→信息单→编号门禁→开红票）
        path: '/m/9.4.1',
        name: 'RedFlush',
        component: () => import('@/views/fin/red-invoice-void/red-flush.vue'),
        meta: { title: '红字冲销', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.4.2 发票作废（三条件 BR-4.14-24 + 审批执行）
        path: '/m/9.4.2',
        name: 'InvoiceVoid',
        component: () => import('@/views/fin/red-invoice-void/invoice-void.vue'),
        meta: { title: '发票作废', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.4.3 进项转出（直接/分摊两模式 + 转出凭证）
        path: '/m/9.4.3',
        name: 'InputTaxTransfer',
        component: () => import('@/views/fin/red-invoice-void/input-tax-transfer.vue'),
        meta: { title: '进项转出', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.5.1 增值申报（change add-tax-filing：汇总→勾稽→生成→三级审批→缴款 + 留抵退税入口）
        path: '/m/9.5.1',
        name: 'VatFiling',
        component: () => import('@/views/fin/tax-filing/vat-filing.vue'),
        meta: { title: '增值申报', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.5.2 所得申报（累计利润×税率+调整项，同审批缴款链）
        path: '/m/9.5.2',
        name: 'CitFiling',
        component: () => import('@/views/fin/tax-filing/cit-filing.vue'),
        meta: { title: '所得申报', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.5.3 出口退税（一票一档/单证/FOB 折算/状态跟踪/核销/分级预警）
        path: '/m/9.5.3',
        name: 'ExportRefund',
        component: () => import('@/views/fin/tax-filing/export-refund.vue'),
        meta: { title: '出口退税', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.5.4 申报归档（按期聚合申报表/栏次/回执/调整记录，≥10 年）
        path: '/m/9.5.4',
        name: 'TaxArchive',
        component: () => import('@/views/fin/tax-filing/archive.vue'),
        meta: { title: '申报归档', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.6.1 风险预警（8 类信号看板 + 证书 + 离线开票 + 税负率四 Tab）
        path: '/m/9.6.1',
        name: 'TaxRiskDashboard',
        component: () => import('@/views/fin/tax-risk-control/risk-dashboard.vue'),
        meta: { title: '风险预警', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.6.2 优惠管理（备案台账 + 到期预警/续期/冻结/终止，BR-4.14-05/06）
        path: '/m/9.6.2',
        name: 'TaxPreference',
        component: () => import('@/views/fin/tax-risk-control/tax-preference.vue'),
        meta: { title: '优惠管理', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 9.6.3 关联定价（关联方 + 三档文档台账 + 提醒 + 风险自评，S-4.14-08）
        path: '/m/9.6.3',
        name: 'TransferPricing',
        component: () => import('@/views/fin/tax-risk-control/transfer-pricing.vue'),
        meta: { title: '关联定价', group: '税务管理',
          perm: 'ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 10.1.1 建档登记（六步状态机，FR-4.7-1）
        path: '/m/10.1.1',
        name: 'ArchiveFiling',
        component: () => import('@/views/hr/employee-archive/archive-filing.vue'),
        meta: { title: '建档登记', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.1.2 信息维护（档案列表 + 变更日志 BR-4.7-07 + 试用期提醒）
        path: '/m/10.1.2',
        name: 'ArchiveList',
        component: () => import('@/views/hr/employee-archive/archive-list.vue'),
        meta: { title: '信息维护', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.7.1 入职管理（建档进度跟踪 + 试用期提醒）
        path: '/m/10.7.1',
        name: 'OnboardingTrack',
        component: () => import('@/views/hr/employee-transfer/onboarding-track.vue'),
        meta: { title: '入职管理', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.7.2 转正管理（评价/HR 审核/超期自动转正）
        path: '/m/10.7.2',
        name: 'ProbationManage',
        component: () => import('@/views/hr/employee-transfer/probation-manage.vue'),
        meta: { title: '转正管理', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.7.3 调岗管理（双人复核五要素比对 C-4.7-03）
        path: '/m/10.7.3',
        name: 'PostChange',
        component: () => import('@/views/hr/employee-transfer/post-change.vue'),
        meta: { title: '调岗管理', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.7.4 离职管理（四类交接 C-4.7-04 硬阻断 + 离场凭证）
        path: '/m/10.7.4',
        name: 'ExitManage',
        component: () => import('@/views/hr/employee-transfer/exit-manage.vue'),
        meta: { title: '离职管理', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.2.1 部门管理（树+主管/成本中心/合并/停用双阻断）
        path: '/m/10.2.1',
        name: 'DeptManage',
        component: () => import('@/views/hr/org-management/dept-manage.vue'),
        meta: { title: '部门管理', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.2.2 岗位管理（职级关联 + 说明书要点 + 带宽链）
        path: '/m/10.2.2',
        name: 'PositionManage',
        component: () => import('@/views/hr/org-management/position-manage.vue'),
        meta: { title: '岗位管理', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.2.3 汇报关系（防环/深度上限/变更留痕）
        path: '/m/10.2.3',
        name: 'ReportLine',
        component: () => import('@/views/hr/org-management/report-line.vue'),
        meta: { title: '汇报关系', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.2.4 职级管理（带宽挂职级，变更走调整单审批）
        path: '/m/10.2.4',
        name: 'GradeManage',
        component: () => import('@/views/hr/org-management/grade-manage.vue'),
        meta: { title: '职级管理', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.3.1 排班管理（班次库+到人到天日历+批量+复制上月）
        path: '/m/10.3.1',
        name: 'ScheduleManage',
        component: () => import('@/views/hr/attendance/schedule-manage.vue'),
        meta: { title: '排班管理', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.3.2 异常识别（今日打卡+CSV 导入+手动重跑+异常日报）
        path: '/m/10.3.2',
        name: 'ExceptionDetect',
        component: () => import('@/views/hr/attendance/exception-detect.vue'),
        meta: { title: '异常识别', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.3.3 考勤申诉（批量申诉 5 工作日期限+补卡申请）
        path: '/m/10.3.3',
        name: 'AppealManage',
        component: () => import('@/views/hr/attendance/appeal-manage.vue'),
        meta: { title: '考勤申诉', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.3.4 主管确认（批量表态+催办清单）
        path: '/m/10.3.4',
        name: 'MgrConfirm',
        component: () => import('@/views/hr/attendance/mgr-confirm.vue'),
        meta: { title: '主管确认', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.3.5 考勤终审（终审锁定+月度汇总+更正解锁+C-4.7-01 挂起标记）
        path: '/m/10.3.5',
        name: 'FinalizeManage',
        component: () => import('@/views/hr/attendance/finalize-manage.vue'),
        meta: { title: '考勤终审', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.4.1 加班申请（36h 拦截+补单+确认分流+试算器）
        path: '/m/10.4.1',
        name: 'OvertimeApply',
        component: () => import('@/views/hr/attendance/overtime-apply.vue'),
        meta: { title: '加班申请', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.4.2 调休额度（FEFO 批次+到期提醒+清零留痕）
        path: '/m/10.4.2',
        name: 'CompTime',
        component: () => import('@/views/hr/attendance/comp-time.vue'),
        meta: { title: '调休额度', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.4.3 加班工资（固化金额列表，21.75/8 基数）
        path: '/m/10.4.3',
        name: 'OvertimeWage',
        component: () => import('@/views/hr/attendance/overtime-wage.vue'),
        meta: { title: '加班工资', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.4.4 请假管理（余额池分组+请假单+假期类型规则）
        path: '/m/10.4.4',
        name: 'LeaveManage',
        component: () => import('@/views/hr/attendance/leave-manage.vue'),
        meta: { title: '请假管理', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.10.1 绩效方案（周期+等级系数映射，评分期内锁定）
        path: '/m/10.10.1',
        name: 'PerfPlan',
        component: () => import('@/views/hr/performance/plan-manage.vue'),
        meta: { title: '绩效方案', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.10.2 评分录入（分值→等级自动映射，DRAFT→SUBMITTED）
        path: '/m/10.10.2',
        name: 'PerfScoreEntry',
        component: () => import('@/views/hr/performance/score-entry.vue'),
        meta: { title: '评分录入', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.10.3 评分校准（自建状态机批量流转+等级调整留痕）
        path: '/m/10.10.3',
        name: 'PerfCalibrate',
        component: () => import('@/views/hr/performance/score-calibrate.vue'),
        meta: { title: '评分校准', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN' }
      },
      {
        // 10.10.4 绩效结果（确认快照，薪酬核算取数来源）
        path: '/m/10.10.4',
        name: 'PerfResult',
        component: () => import('@/views/hr/performance/result-view.vue'),
        meta: { title: '绩效结果', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 10.5.1 社保扣缴（属地政策表+封顶保底试算+7月基数提醒）
        path: '/m/10.5.1',
        name: 'SocialPolicy',
        component: () => import('@/views/hr/payroll/social-policy.vue'),
        meta: { title: '社保扣缴', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 10.5.2 个税计算（累计预扣法+税率表+专项附加+年终奖单独/并入）
        path: '/m/10.5.2',
        name: 'IitCalc',
        component: () => import('@/views/hr/payroll/iit-calc.vue'),
        meta: { title: '个税计算', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 10.5.3 薪资核算（数据准备/补扣项/核算引擎/负数波动标记）
        path: '/m/10.5.3',
        name: 'PayrollCalc',
        component: () => import('@/views/hr/payroll/payroll-calc.vue'),
        meta: { title: '薪资核算', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 10.5.4 核算审批（PayrollRun 锁定/薪酬更正 L2/工资条异议回复）
        path: '/m/10.5.4',
        name: 'PayrollApprove',
        component: () => import('@/views/hr/payroll/payroll-approve.vue'),
        meta: { title: '核算审批', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 10.6.1/10.6.2/10.6.3 薪酬发放三叶合一操作台（代发→付款登记→回单对账→差异→归档）
        path: '/m/10.6.1',
        name: 'PayDisbursement',
        component: () => import('@/views/hr/payroll/disbursement.vue'),
        meta: { title: '代发文件', group: '人力资源',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        path: '/m/10.6.2',
        name: 'PayExecution',
        component: () => import('@/views/hr/payroll/disbursement.vue'),
        meta: { title: '发放执行', group: '人力资源', tab: '1',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        path: '/m/10.6.3',
        name: 'PayReconcile',
        component: () => import('@/views/hr/payroll/disbursement.vue'),
        meta: { title: '回单对账', group: '人力资源', tab: '2',
          perm: 'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      {
        // 8.10.3 成本报表（change add-cost-accounting：期间报表主区块 + 多期趋势构成双 Tab）
        path: '/m/8.10.3',
        name: 'CostReportFinance',
        component: () => import('@/views/fin/cost-accounting/index.vue'),
        meta: { title: '成本报表', group: '财务管理',
          perm: 'ROLE_COST_ACCOUNTANT,ROLE_FINANCE,ROLE_FINANCE_MGR,ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN' }
      },
      // 8.7 银行对账（add-bank-reconciliation：8.7.1/2 接线回单页复用同一数据源；8.7.3/4 新组件双 tab）
      {
        path: '/m/8.7.1',
        name: 'BankFlowImport',
        component: () => import('@/views/fin/collection/index.vue'),
        meta: { title: '流水导入', group: '财务管理', tab: 'receipt' }
      },
      {
        path: '/m/8.7.2',
        name: 'BankAutoCompare',
        component: () => import('@/views/fin/collection/index.vue'),
        meta: { title: '自动比对', group: '财务管理', tab: 'receipt' }
      },
      {
        path: '/m/8.7.3',
        name: 'BankReconcileDiff',
        component: () => import('@/views/fin/bank-reconciliation/index.vue'),
        meta: { title: '差异处理', group: '财务管理', tab: 'diff' }
      },
      {
        path: '/m/8.7.4',
        name: 'BankBalanceAdjust',
        component: () => import('@/views/fin/bank-reconciliation/index.vue'),
        meta: { title: '余额调节', group: '财务管理', tab: 'reconcile' }
      },
      // 8.6 资产折旧（add-depreciation-monitoring：同组件双路由，财务只读监控）
      {
        path: '/m/8.6.1',
        name: 'DeprRunMonitor',
        component: () => import('@/views/fin/depreciation-monitoring/index.vue'),
        meta: { title: '折旧计提', group: '财务管理', tab: 'run' }
      },
      {
        path: '/m/8.6.2',
        name: 'DeprResidualAlert',
        component: () => import('@/views/fin/depreciation-monitoring/index.vue'),
        meta: { title: '残值预警', group: '财务管理', tab: 'alert' }
      },
      // 8.5 外币核算（add-fx-revaluation：8.5.1 接线 MDM 汇率页；8.5.2/3 同组件双路由）
      {
        path: '/m/8.5.1',
        name: 'FxRateMaintain',
        component: () => import('@/views/mdm/exchange-rate/index.vue'),
        meta: { title: '汇率维护', group: '财务管理' }
      },
      {
        path: '/m/8.5.2',
        name: 'FxRevaluation',
        component: () => import('@/views/fin/fx-revaluation/index.vue'),
        meta: { title: '期末重估', group: '财务管理', tab: 'reval' }
      },
      {
        path: '/m/8.5.3',
        name: 'FxGainLoss',
        component: () => import('@/views/fin/fx-revaluation/index.vue'),
        meta: { title: '汇兑损益', group: '财务管理', tab: 'gainloss' }
      },
      // 8.2 应收管理（add-ar-management：同组件三路由）
      {
        path: '/m/8.2.1',
        name: 'ArConfirm',
        component: () => import('@/views/fin/ar/index.vue'),
        meta: { title: '应收确认', group: '财务管理', tab: 'confirm' }
      },
      {
        path: '/m/8.2.2',
        name: 'ArAging',
        component: () => import('@/views/fin/ar/index.vue'),
        meta: { title: '账龄分析', group: '财务管理', tab: 'aging' }
      },
      {
        path: '/m/8.2.3',
        name: 'ArProvision',
        component: () => import('@/views/fin/ar/index.vue'),
        meta: { title: '坏账计提', group: '财务管理', tab: 'provision' }
      },
      // 8.2 应收管理（add-ar-management：同组件三路由）
      {
        path: '/m/8.2.1',
        name: 'ArConfirm',
        component: () => import('@/views/fin/ar/index.vue'),
        meta: { title: '应收确认', group: '财务管理', tab: 'confirm' }
      },
      {
        path: '/m/8.2.2',
        name: 'ArAging',
        component: () => import('@/views/fin/ar/index.vue'),
        meta: { title: '账龄分析', group: '财务管理', tab: 'aging' }
      },
      {
        path: '/m/8.2.3',
        name: 'ArProvision',
        component: () => import('@/views/fin/ar/index.vue'),
        meta: { title: '坏账计提', group: '财务管理', tab: 'provision' }
      },
      // 8.1 总账管理（add-gl-management：同组件五路由）
      {
        path: '/m/8.1.1',
        name: 'GlAccounts',
        component: () => import('@/views/fin/gl/index.vue'),
        meta: { title: '科目管理', group: '财务管理', tab: 'accounts' }
      },
      {
        path: '/m/8.1.2',
        name: 'GlEntry',
        component: () => import('@/views/fin/gl/index.vue'),
        meta: { title: '凭证录入', group: '财务管理', tab: 'entry' }
      },
      {
        path: '/m/8.1.3',
        name: 'GlAudit',
        component: () => import('@/views/fin/gl/index.vue'),
        meta: { title: '凭证审核', group: '财务管理', tab: 'audit' }
      },
      {
        path: '/m/8.1.4',
        name: 'GlPost',
        component: () => import('@/views/fin/gl/index.vue'),
        meta: { title: '凭证过账', group: '财务管理', tab: 'post' }
      },
      {
        path: '/m/8.1.5',
        name: 'GlClose',
        component: () => import('@/views/fin/gl/index.vue'),
        meta: { title: '期末结账', group: '财务管理', tab: 'close' }
      },
      // 7.8 资产报表（add-asset-report：同组件三路由）
      {
        path: '/m/7.8.1',
        name: 'EamReportChange',
        component: () => import('@/views/eam/report/index.vue'),
        meta: { title: '变动报表', group: '资产管理', tab: 'change' }
      },
      {
        path: '/m/7.8.2',
        name: 'EamReportDep',
        component: () => import('@/views/eam/report/index.vue'),
        meta: { title: '折旧明细', group: '资产管理', tab: 'depreciation' }
      },
      {
        path: '/m/7.8.3',
        name: 'EamReportCount',
        component: () => import('@/views/eam/report/index.vue'),
        meta: { title: '盘点报告', group: '资产管理', tab: 'count' }
      },
      // 7.8 资产报表（add-asset-report：同组件三路由）
      {
        path: '/m/7.8.1',
        name: 'EamReportChange',
        component: () => import('@/views/eam/report/index.vue'),
        meta: { title: '变动报表', group: '资产管理', tab: 'change' }
      },
      {
        path: '/m/7.8.2',
        name: 'EamReportDep',
        component: () => import('@/views/eam/report/index.vue'),
        meta: { title: '折旧明细', group: '资产管理', tab: 'depreciation' }
      },
      {
        path: '/m/7.8.3',
        name: 'EamReportCount',
        component: () => import('@/views/eam/report/index.vue'),
        meta: { title: '盘点报告', group: '资产管理', tab: 'count' }
      },
      // 7.7 技改大修（add-asset-rework）
      {
        path: '/m/7.7.1',
        name: 'EamRework',
        component: () => import('@/views/eam/rework/index.vue'),
        meta: { title: '资本确认', group: '资产管理', tab: 'rework' }
      },
      // 7.6 报废处置（add-asset-disposal：同组件三路由）
      {
        path: '/m/7.6.1',
        name: 'EamDisposalScrap',
        component: () => import('@/views/eam/disposal/index.vue'),
        meta: { title: '报废申请', group: '资产管理', tab: 'scrap' }
      },
      {
        path: '/m/7.6.2',
        name: 'EamDisposalSale',
        component: () => import('@/views/eam/disposal/index.vue'),
        meta: { title: '资产出售', group: '资产管理', tab: 'sale' }
      },
      {
        path: '/m/7.6.3',
        name: 'EamDisposalPl',
        component: () => import('@/views/eam/disposal/index.vue'),
        meta: { title: '损益处理', group: '资产管理', tab: 'pl' }
      },
      // 2.5.3 / 6.5 不合格品 NCR（add-quality-collaboration，同组件三路由）
      {
        path: '/m/2.5.3',
        name: 'NcrPurchase',
        component: () => import('@/views/qms/ncr/index.vue'),
        meta: { title: '不合格品', group: '采购管理', tab: 'all' }
      },
      {
        path: '/m/6.5.1',
        name: 'NcrIsolate',
        component: () => import('@/views/qms/ncr/index.vue'),
        meta: { title: '标识隔离', group: '质量管理', tab: 'isolate' }
      },
      {
        path: '/m/6.5.2',
        name: 'NcrDispose',
        component: () => import('@/views/qms/ncr/index.vue'),
        meta: { title: '处置跟踪', group: '质量管理', tab: 'dispose' }
      },
      {
        path: '/m/1.6.1',
        name: 'TaxCode',
        component: () => import('@/views/mdm/tax-code/index.vue'),
        meta: { title: '税码维护', group: '基础数据' }
      },
      {
        path: '/m/1.6.2',
        name: 'TaxPolicy',
        component: () => import('@/views/mdm/tax-policy/index.vue'),
        meta: { title: '政策更新', group: '基础数据' }
      },
      {
        path: '/m/1.6.3',
        name: 'TaxCodeBatch',
        component: () => import('@/views/mdm/tax-code-batch/index.vue'),
        meta: { title: '批量导入', group: '基础数据' }
      },
      {
        path: '/m/1.4.1',
        name: 'SupplierAdmission',
        component: () => import('@/views/mdm/supplier-admission/index.vue'),
        meta: { title: '准入审核', group: '基础数据' }
      },
      {
        path: '/m/1.4.2',
        name: 'SupplierMerge',
        component: () => import('@/views/mdm/supplier-merge/index.vue'),
        meta: { title: '合并去重', group: '基础数据' }
      },
      {
        path: '/m/1.2.1',
        name: 'ItemMaster',
        component: () => import('@/views/mdm/item/index.vue'),
        meta: { title: '物料管理', group: '基础数据' }
      },
      {
        path: '/m/1.2.2',
        name: 'ItemCategory',
        component: () => import('@/views/mdm/item-category/index.vue'),
        meta: { title: '分类维护', group: '基础数据' }
      },
      // 整合重定向：原 物料变更(1.2.2 旧语义由新分类维护覆盖)/停用/替代/分类(旧号) 入口收敛
      { path: '/m/1.2.3', redirect: '/m/1.2.2' },
      { path: '/m/1.2.4', redirect: '/m/1.2.1' },
      { path: '/m/1.2.5', redirect: '/m/1.2.1' },
      // 占位路由：承接 08 清单中尚未开发的三级菜单路径（/m/<编号>）
      {
        path: '/:pathMatch(.*)*',
        name: 'Placeholder',
        component: () => import('@/views/placeholder/index.vue'),
        meta: { title: '功能建设中', hidden: true }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to, from, next) => {
  document.title = to.meta.title || 'ERP 管理系统'

  if (to.path === '/login') {
    next()
    return
  }

  const userStore = (await import('@/store/user')).useUserStore()
  if (!userStore.token) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  if (!userStore.userInfo) {
    try {
      await userStore.getUserInfo()
    } catch {
      next({ path: '/login' })
      return
    }
  }

  // 强制改密拦截（add-account-lifecycle-console design D3）：
  // mustChangePwd=true 时除改密页/登录页外一律重定向（无标记时改密页同样可达，自主改密不拦）
  if (userStore.userInfo?.mustChangePwd && to.path !== '/my/change-password') {
    next({ path: '/my/change-password' })
    return
  }

  // 门户/内部双向隔离（spec supplier-portal-account，C-4.9-06 前端第一道防线）
  const rolesNow = (userStore.userInfo && userStore.userInfo.roles) || []
  const isPortalRoute = to.path.startsWith('/portal')
  if (isPortalRoute && !rolesNow.includes('ROLE_SUPPLIER')) {
    ElMessage.warning('需供应商门户账号访问')
    next('/dashboard')
    return
  }
  if (!isPortalRoute && rolesNow.includes('ROLE_SUPPLIER')) {
    next('/portal')
    return
  }

  // 权限拦截（01 文档 3.3 第一道防线）：直接输 URL 也无法进入受限页
  if (to.meta && to.meta.perm) {
    const needed = to.meta.perm.split(',').map(s => s.trim())
    const roles = userStore.userInfo?.roles || []
    const hit = roles.includes('ROLE_ADMIN') || needed.some(r => roles.includes(r))
    if (!hit) {
      ElMessage.warning('无权访问该页面')
      next('/dashboard')
      return
    }
  }

  if (!isPortalRoute) {
    const menuStore = (await import('@/store/menu')).useMenuStore()
    if (!menuStore.loaded && !menuStore.loading) {
      menuStore.fetchMenus().catch(() => {})
    }
    const tabStore = (await import('@/store/tab')).useTabStore()
    tabStore.addTab(to)
  }

  next()
})

export default router
