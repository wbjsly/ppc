import request from '@/utils/request'

/** 冻结库存查询（类型/状态/物料/批次/关键字；NCR 来源只读，spec freeze-management） */
export function getFreezesApi(params) {
  return request.get('/inv/freeze', { params })
}

/** 发起冻结（质量/财务单笔唯一，挂单节点审批 FR-4.4-5-1） */
export function applyFreezeApi(data) {
  return request.post('/inv/freeze', data)
}

/** 发起解冻（仅原发起人，处理结果与依据必填，独立审批 FR-4.4-5-6/7） */
export function applyUnfreezeApi(id, data) {
  return request.post(`/inv/freeze/${id}/unfreeze`, data)
}

/** 影响评估预估（FR-4.4-5-3 异常列：scope=ALL 或占比 ≥ 阈值 → needConfirm） */
export function estimateFreezeApi(params) {
  return request.get('/inv/freeze/estimate', { params })
}

// 审批签署复用通用底座 API（@/api/qms/approval：passQmsApprovalApi / rejectQmsApprovalApi /
// getQmsApprovalTodoApi / getQmsApprovalLogsApi），底座按节点角色校验（C-4.4-05）。
