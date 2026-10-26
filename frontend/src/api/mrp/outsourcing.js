import request from '@/utils/request'

// ================= 5.10.1 外协订单工作台（spec outsourcing-execution） =================

/** 工作台聚合：外协派工单按回联状态分组；状态/供应商/WC 筛选 + 关键词 */
export function getWorkbenchApi(params) {
  return request.get('/mrp/dispatch/os/workbench', { params })
}

/** 维护外协字段：交期/备注/供料方式（SUPPLIER_BUY/COMPANY_SUPPLY） */
export function maintainOsFieldsApi(id, body) {
  return request.put(`/mrp/dispatch/os/fields/${id}`, body)
}

// ================= 5.10.3 甲供发料单（spec outsourcing-material-supply） =================

/** 发料单列表（派工单号/供应商/状态筛选） */
export function getOsMaterialListApi(params) {
  return request.get('/mrp/os-material/list', { params })
}

/** 发料单详情（头 + 行；行含实发/定额冲减/余料退回三累计） */
export function getOsMaterialDetailApi(id) {
  return request.get(`/mrp/os-material/${id}`)
}

/** 创建发料单（DRAFT；仅甲供派工单，库存不足 422 逐行列缺口） */
export function createOsMaterialApi(body) {
  return request.post('/mrp/os-material', body)
}

/** 删除草稿（仅 DRAFT） */
export function deleteOsMaterialApi(id) {
  return request.delete(`/mrp/os-material/${id}`)
}

/** 出库过账（DRAFT → POSTED） */
export function postOsMaterialApi(id) {
  return request.post(`/mrp/os-material/${id}/post`)
}

/** 余料退回（POSTED → RETURNED；rows=[{lineId, qty}]） */
export function returnOsMaterialApi(id, rows) {
  return request.post(`/mrp/os-material/${id}/return`, { rows })
}

// ================= 5.10.4 外协对账与应付挂账（spec outsourcing-settlement） =================

/** 对账单列表（PO 号/供应商/状态筛选） */
export function getOsSettlementListApi(params) {
  return request.get('/mrp/os-settlements/list', { params })
}

/** 对账单详情（头 + 行） */
export function getOsSettlementDetailApi(id) {
  return request.get(`/mrp/os-settlements/${id}`)
}

/** 按 PO 生成对账单（DRAFT；金额=数量×PO 单价，已引用行自动排除） */
export function generateOsSettlementApi(poNo) {
  return request.post('/mrp/os-settlements/generate', { poNo })
}

/** 删除草稿（仅 DRAFT；物理释放收货行引用） */
export function deleteOsSettlementApi(id) {
  return request.delete(`/mrp/os-settlements/${id}`)
}

/** 提交审批（DRAFT → CONFIRMED，单签 ROLE_PLAN_MGR） */
export function submitOsSettlementApi(id) {
  return request.post(`/mrp/os-settlements/${id}/submit`)
}

/** 应付挂账列表（供应商/状态筛选） */
export function getOsApListApi(params) {
  return request.get('/mrp/os-settlements/ap/list', { params })
}
