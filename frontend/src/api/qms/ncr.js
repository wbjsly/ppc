import request from '@/utils/request'

/** 不合格品 NCR（2.5.3 / 6.5.1 / 6.5.2，spec ncr-management） */

/** 分页（状态/严重度/关键字） */
export function getNcrPageApi(params) {
  return request.get('/qms/ncrs', { params })
}

/** 详情（检验批快照/检验项/CAPA/复检批/退货与让步单/日志） */
export function getNcrDetailApi(id) {
  return request.get(`/qms/ncrs/${id}`)
}

/** 操作日志 */
export function getNcrLogsApi(id) {
  return request.get(`/qms/ncrs/${id}/logs`)
}

/** 评审：必须选定处置（安全/法规 CTQ 禁让步 BR-4.12-26） */
export function reviewNcrApi(id, disposition, opinion) {
  return request.post(`/qms/ncrs/${id}/review`, { disposition, opinion })
}

/** 处置方案录入（挑选/返工；返工须结构化三字段 + CTQ 全含勾选） */
export function planNcrApi(id, plan, rework = {}) {
  return request.post(`/qms/ncrs/${id}/disposition-plan`, {
    plan,
    reworkSteps: rework.steps ?? null,
    reworkParams: rework.params ?? null,
    reworkCheckReq: rework.checkReq ?? null,
    ctqConfirmed: rework.ctqConfirmed ?? false
  })
}

/**
 * 处置执行确认（凭证齐全 → DISPOSED；挑选/返工生成复检批）
 * 挑选须结构化四字段（合计校验后端执行）；costAmount 非空归集 COPQ。
 * 返回 { ncr, warnings }——warnings 为软提示（如低合格率）。
 */
export function confirmNcrApi(id, result, opts = {}) {
  return request.post(`/qms/ncrs/${id}/dispose-confirm`, {
    result,
    costAmount: opts.costAmount ?? null,
    sortTotalQty: opts.sortTotalQty ?? null,
    sortOkQty: opts.sortOkQty ?? null,
    sortNgQty: opts.sortNgQty ?? null,
    sortHours: opts.sortHours ?? null
  })
}

/** 隔离确认（6.5.1：货位/贴标人/时间留痕，不改状态机） */
export function isolateNcrApi(id, bin) {
  return request.post(`/qms/ncrs/${id}/isolate`, { bin })
}

/** 针对已放行批次创建 NCR（6.1 漏检召回评估入口：校验 RELEASED+幂等） */
export function createReleasedLotNcrApi(payload) {
  return request.post('/qms/ncrs/released-lots', payload)
}

/** 发起召回评估（6.2：建追溯单双向关联，不代执行冻结）；返回 { traceId, traceNo, existing } */
export function recallEvaluationApi(id) {
  return request.post(`/qms/ncrs/${id}/recall-evaluation`)
}

/** 关闭（CAPA 有效 + 复检放行 → CLOSED 解冻只读） */
export function closeNcrApi(id, opinion) {
  return request.post(`/qms/ncrs/${id}/close`, { opinion })
}

/** 作废（仅评审前，填原因；解冻） */
export function cancelNcrApi(id, reason) {
  return request.post(`/qms/ncrs/${id}/cancel`, { reason })
}
