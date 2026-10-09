import request from '@/utils/request'

/** 出入库业务类型列表（含停用，携 enabled 标记；spec stock-doc-type） */
export function getDocTypesApi() {
  return request.get('/inv/doc-types')
}

/** 新增类型（ADMIN；TYPE_CODE 唯一且创建后锁定） */
export function createDocTypeApi(data) {
  return request.post('/inv/doc-types', data)
}

/** 修改类型（ADMIN；TYPE_CODE 不可改） */
export function updateDocTypeApi(data) {
  return request.put('/inv/doc-types', data)
}

/** 启停（ADMIN） */
export function enableDocTypeApi(id) {
  return request.put(`/inv/doc-types/${id}/enable`)
}

export function disableDocTypeApi(id) {
  return request.put(`/inv/doc-types/${id}/disable`)
}
