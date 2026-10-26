import request from '@/utils/request'

/** 版本列表（状态/父项/关键字；含版本历史留痕字段） */
export function getBomsApi(params) {
  return request.get('/mrp/boms', { params })
}

/** 版本详情（头 + 行 + 行级替代） */
export function getBomDetailApi(id) {
  return request.get(`/mrp/boms/${id}`)
}

/** 新建 BOM 草稿（head + items，FR-4.5-1-1/2/3，内嵌循环校验） */
export function createBomApi(data) {
  return request.post('/mrp/boms', data)
}

/** 覆盖保存草稿（仅 DRAFT） */
export function saveBomDraftApi(id, data) {
  return request.put(`/mrp/boms/${id}`, data)
}

/** 复制版本为独立草稿（copy_from_id 留痕） */
export function copyBomApi(id) {
  return request.post(`/mrp/boms/${id}/copy`)
}

/** 发起变更（仅已发布，变更原因必填，可升级主版本） */
export function changeBomApi(id, data) {
  return request.post(`/mrp/boms/${id}/change`, data)
}

/** 提交审核（挂 BomPublish 单节点审批，DRAFT→PENDING） */
export function submitBomApi(id) {
  return request.post(`/mrp/boms/${id}/submit`)
}

/** 手动废止（PROCESS_MGR/ADMIN，PUBLISHED|REVISED→OBSOLETE） */
export function obsoleteBomApi(id) {
  return request.post(`/mrp/boms/${id}/obsolete`)
}

/** 循环校验扫描（parentItemCode 空=全量） */
export function scanBomApi(params) {
  return request.get('/mrp/boms/scan', { params })
}

/** 行替代候选带出（读取 MDM 物料替代关系，只读） */
export function substituteCandidatesApi(itemCode) {
  return request.get('/mrp/boms/substitute-candidates', { params: { itemCode } })
}
