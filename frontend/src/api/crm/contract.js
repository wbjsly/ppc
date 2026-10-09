import request from '@/utils/request'

// ---------- 销售合同（spec sales-contract，11.11.1~11.11.3） ----------

export function getContractsApi(params) {
  return request({ url: '/crm/contracts', method: 'get', params })
}

/** 详情（SO 链/计划达成/应收汇总/版本历史/审批日志/调整提示） */
export function getContractDetailApi(id) {
  return request({ url: `/crm/contracts/${id}`, method: 'get' })
}

/** 生成草稿（商机带出，CT_NO 生成锁定，FR-4.8-1-7） */
export function createContractApi(data) {
  return request({ url: '/crm/contracts', method: 'post', data })
}

export function updateContractApi(id, data) {
  return request({ url: `/crm/contracts/${id}/update`, method: 'post', data })
}

/** 提交：差异 >10% → L2，否则直接法务审核 */
export function submitContractApi(id) {
  return request({ url: `/crm/contracts/${id}/submit`, method: 'post' })
}

/** 法务审核占位（审核人/意见必填；驳回退回修改） */
export function legalReviewApi(id, data) {
  return request({ url: `/crm/contracts/${id}/legal-review`, method: 'post', data })
}

/** 收款计划全量保存（不参与记账；合计不符返回待调整提示） */
export function savePlansApi(contractId, plans) {
  return request({ url: `/crm/contracts/${contractId}/plans`, method: 'post',
    data: { plans } })
}

export function deletePlanApi(planId) {
  return request({ url: `/crm/contracts/plans/${planId}/delete`, method: 'post' })
}

/** 14.5 计划达成对比（合同→SO→应收链路，超期标红） */
export function getProgressApi(contractId) {
  return request({ url: `/crm/contracts/${contractId}/progress`, method: 'get' })
}

/** 14.6 变更（新版本+销售经理审批，差异扩大升级 L2） */
export function changeContractApi(id, data) {
  return request({ url: `/crm/contracts/${id}/change`, method: 'post', data })
}

export function getVersionsApi(id) {
  return request({ url: `/crm/contracts/${id}/versions`, method: 'get' })
}
