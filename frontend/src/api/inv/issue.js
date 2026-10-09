import request from '@/utils/request'

/** 领料出库（4.5.2 + 2.8.1 寄售领用，spec material-issue） */

export function getIssuePageApi(params) {
  return request.get('/inv/issues', { params })
}

export function getIssueDetailApi(id) {
  return request.get(`/inv/issues/${id}`)
}

/** FIFO 配批预检（创建对话框预览，不落库） */
export function previewIssueApi(data) {
  return request.post('/inv/issues/preview', data)
}

/** 创建领料单（issueType=OWN/VMI，workOrderNo/dept/purpose + lines[{itemCode,qty}]） */
export function createIssueApi(data) {
  return request.post('/inv/issues', data)
}

/** 过账（OWN 扣减 / VMI 物权转移 + 转自有凭证 + 暂估） */
export function postIssueApi(id) {
  return request.post(`/inv/issues/${id}/postings`, {})
}

/** 作废（仅 DRAFT，原因必填） */
export function cancelIssueApi(id, reason) {
  return request.post(`/inv/issues/${id}/cancel`, { reason })
}
