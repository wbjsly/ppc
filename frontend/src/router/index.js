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
        path: '/system/menu',
        name: 'MenuManagement',
        component: () => import('@/views/system/menu/index.vue'),
        meta: { title: '菜单管理', group: '系统管理', perm: 'ROLE_ADMIN' }
      },
      {
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
      // 6.1 标准管理（add-quality-collaboration）
      {
        path: '/m/6.1.1',
        name: 'StandardApply',
        component: () => import('@/views/qms/standard/index.vue'),
        meta: { title: '标准申请', group: '质量管理', tab: 'apply' }
      },
      {
        path: '/m/6.1.2',
        name: 'StandardApproval',
        component: () => import('@/views/qms/standard/index.vue'),
        meta: { title: '标准审批', group: '质量管理', tab: 'approval' }
      },
      {
        path: '/m/6.1.3',
        name: 'StandardPublish',
        component: () => import('@/views/qms/standard/index.vue'),
        meta: { title: '版本发布', group: '质量管理', tab: 'publish' }
      },
      {
        path: '/m/6.1.4',
        name: 'SamplingPlan',
        component: () => import('@/views/qms/standard/index.vue'),
        meta: { title: '抽样方案', group: '质量管理', tab: 'sampling' }
      },
      {
        path: '/m/6.1.5',
        name: 'StandardRetire',
        component: () => import('@/views/qms/standard/index.vue'),
        meta: { title: '标准退役', group: '质量管理', tab: 'retire' }
      },
      // 2.5 质量协同 + 6.2/6.3/6.4 检验执行（add-quality-collaboration，同组件多路由）
      {
        path: '/m/2.5.1',
        name: 'IncomingInspection',
        component: () => import('@/views/qms/inspection/index.vue'),
        meta: { title: '来料检验', group: '采购管理', tab: 'board', lotType: 'IQC' }
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
        // 4.5.2 领料出库（add-consignment-procurement，spec material-issue）
        path: '/m/4.5.2',
        name: 'MaterialIssue',
        component: () => import('@/views/inv/material-issue/index.vue'),
        meta: { title: '领料出库', group: '库存管理' }
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
        component: () => import('@/views/qms/inspection/index.vue'),
        meta: { title: '来料检验', group: '质量管理', tab: 'board', lotType: 'IQC' }
      },
      {
        path: '/m/6.2.2',
        name: 'TaskIpqc',
        component: () => import('@/views/qms/inspection/index.vue'),
        meta: { title: '过程检验', group: '质量管理', tab: 'board', lotType: 'IPQC' }
      },
      {
        path: '/m/6.2.3',
        name: 'TaskOqc',
        component: () => import('@/views/qms/inspection/index.vue'),
        meta: { title: '出货检验', group: '质量管理', tab: 'board', lotType: 'OQC' }
      },
      {
        path: '/m/6.3.1',
        name: 'SamplingExecute',
        component: () => import('@/views/qms/inspection/index.vue'),
        meta: { title: '抽样执行', group: '质量管理', tab: 'input' }
      },
      {
        path: '/m/6.3.2',
        name: 'InspectionRecord',
        component: () => import('@/views/qms/inspection/index.vue'),
        meta: { title: '检验记录', group: '质量管理', tab: 'input' }
      },
      {
        path: '/m/6.4.1',
        name: 'QualifiedRelease',
        component: () => import('@/views/qms/inspection/index.vue'),
        meta: { title: '合格放行', group: '质量管理', tab: 'board' }
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
        meta: { title: '8D 报告', group: '质量管理', tab: 'list' }
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
