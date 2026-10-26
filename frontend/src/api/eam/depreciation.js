import request from '@/utils/request'

/** 折旧管理（add-asset-depreciation，spec asset-depreciation） */

// ---- 7.2.1 月度计提 ----

/** 执行本月计提（同事务：幂等卡控→应提清单→三方法→凭证→台账回写） */
export function runDepreciationApi(periodTag) {
  return request.post(`/eam/depreciation/runs/${periodTag}/execute`)
}

/** 应提预览（未落库：应提卡清单+预计折旧+缺失告警+残值预警） */
export function getPreviewApi(periodTag) {
  return request.get('/eam/depreciation/preview', { params: { periodTag } })
}

/** 计提单分页 */
export function getRunPageApi(params) {
  return request.get('/eam/depreciation/runs', { params })
}

/** 计提单详情（含明细行） */
export function getRunDetailApi(runId) {
  return request.get(`/eam/depreciation/runs/${runId}`)
}

/** 整单冲回（红冲凭证+明细标 REVERSED+台账回滚+放行重提） */
export function reverseRunApi(runId, reason) {
  return request.post(`/eam/depreciation/runs/${runId}/reverse`, null, { params: { reason } })
}

// ---- 7.2.2 变动处理 ----

/** 变动单分页（POLICY/IMPAIRMENT/REVERSE） */
export function getChangePageApi(params) {
  return request.get('/eam/depreciation/changes', { params })
}

/** 政策变更提交（单签 ROLE_FIN_ASSET；生效期默认系统下期，不追溯） */
export function changePolicyApi(data) {
  return request.post('/eam/depreciation/changes/policy', data)
}

/** 减值提交（单签；通过出减值凭证+卡片 IMPAIRMENT 落值+次月重算） */
export function impairmentApi(data) {
  return request.post('/eam/depreciation/changes/impairment', data)
}

/** 变动单审核通过（回调入口；幂等） */
export function approveChangeApi(changeId) {
  return request.post(`/eam/depreciation/changes/${changeId}/approve`)
}

/** 变动单驳回 */
export function rejectChangeApi(changeId, reason) {
  return request.post(`/eam/depreciation/changes/${changeId}/reject`, null, { params: { reason } })
}

// ---- 7.2.3 凭证生成（反查） ----

/** 折旧凭证反查（sourceType=DEPRECIATION，来源单号=run 单号） */
export function getVoucherApi(sourceDocNo) {
  return request.get('/eam/depreciation/vouchers', { params: { sourceDocNo } })
}

/** 明细按凭证反查（明细↔凭证双向） */
export function getDetailsByVoucherApi(voucherId) {
  return request.get(`/eam/depreciation/vouchers/${voucherId}/details`)
}
