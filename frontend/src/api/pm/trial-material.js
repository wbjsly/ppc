import request from '@/utils/request'

// ---------- 研发物料试制（add-rnd-material，M11.9） ----------

// ---- 试制领料（11.9.1，沿现有领料端点 + trialFlag） ----

/** 试制领料走现有领料提交端点，data 带 trialFlag=1/trialPurpose（C-4.8-12 服务端校验 TRIAL 任务） */
export function submitTrialIssue(data) {
  return request({ url: '/api/pm/cost/material-issues/submit', method: 'post', data })
}

// ---- 消耗确认 + 样品台账（11.9.1） ----

/** 试制消耗确认（防重 409；差异>10% L4；样品台账随确认落库） */
export function confirmConsumption(data) {
  return request({ url: '/api/pm/trial/consumptions', method: 'post', data })
}

/** 消耗确认列表 */
export function consumptionList(params) {
  return request({ url: '/api/pm/trial/consumptions', method: 'get', params })
}

/** 消耗确认详情（含消耗行+样品） */
export function consumptionDetail(id) {
  return request({ url: `/api/pm/trial/consumptions/${id}`, method: 'get' })
}

/** 样品台账（按批次/去向筛选，BR-4.8-39 追溯） */
export function sampleLedger(params) {
  return request({ url: '/api/pm/trial/samples', method: 'get', params })
}

// ---- 结余退库（11.9.1） ----

/** 保存退料单草稿（质检结论/跨项目 L1/结余校验） */
export function saveReturn(data) {
  return request({ url: '/api/pm/trial/returns', method: 'post', data })
}

/** 提交退料单 */
export function submitReturn(data) {
  return request({ url: '/api/pm/trial/returns/submit', method: 'post', data })
}

/** 审核退料单（APPROVED 时 REUSABLE 入库+成本冲减，SCRAP 保留） */
export function reviewReturn(id, data) {
  return request({ url: `/api/pm/trial/returns/${id}/review`, method: 'put', data })
}

/** 退料单列表 */
export function returnList(params) {
  return request({ url: '/api/pm/trial/returns', method: 'get', params })
}

/** 退料单详情（含退库行） */
export function returnDetail(id) {
  return request({ url: `/api/pm/trial/returns/${id}`, method: 'get' })
}

// ---- 试制成本分析（11.9.2） ----

/** 试制成本汇总（TRIAL 任务聚合 + 预算比对 + 偏差率） */
export function trialCostSummary(projectId) {
  return request({ url: '/api/pm/trial/costs/summary', method: 'get', params: { projectId } })
}
