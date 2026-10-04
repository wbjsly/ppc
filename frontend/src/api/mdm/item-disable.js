import request from '@/utils/request'

/** 影响分析：替代引用真实清单 + 下游桩（downstreamStub 明示未接入） */
export function getImpactApi(id) {
  return request.get(`/mdm/items/${id}/impact`)
}

/** 停用（原因必填；替代引用 409 阻断） */
export function disableApi(id, reason) {
  return request.put(`/mdm/items/${id}/disable`, null, { params: { reason } })
}

/** 启用回退（仅停用态；替代指向目标失效 422） */
export function enableApi(id, reason) {
  return request.put(`/mdm/items/${id}/enable`, null, { params: { reason } })
}

/** 标记式归档（仅停用态无引用；归档为终态） */
export function archiveApi(id, reason) {
  return request.put(`/mdm/items/${id}/archive`, null, { params: { reason } })
}

/** 批量停用：逐条结果分组 succeeded/failed */
export function disableBatchApi(ids, reason) {
  return request.post('/mdm/items/disable-batch', { ids, reason })
}
