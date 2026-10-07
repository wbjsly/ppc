import request from '@/utils/request'

// ---------- ATP 承诺与分批交付（spec sales-atp-reservation，3.4.1 / 3.4.2） ----------

/** 承诺试算：四因子 + 延迟剔除 + 安全库存 + L4 提示（BR-4.3-19~22） */
export function trialAtpApi(data) {
  return request({ url: '/sd/atp/trial', method: 'post', data })
}

/** 保存试算记录（spec 场景「可保存试算记录」） */
export function saveTrialApi(result, remark) {
  return request({ url: '/sd/atp/trials', method: 'post', data: { result, remark } })
}

/** 试算记录分页 */
export function getTrialsApi(params) {
  return request({ url: '/sd/atp/trials', method: 'get', params })
}

/** 分批交付拆行（守恒校验 + 逐行交期与预留绑定，BR-4.3-23） */
export function splitLineApi(lineId, batches) {
  return request({ url: `/sd/atp/lines/${lineId}/split`, method: 'post', data: { batches } })
}

/** SO 行级 ATP 检查（C-4.3-02 L4 提示数据） */
export function checkSoLinesApi(soId) {
  return request({ url: `/sd/atp/so/${soId}/lines`, method: 'get' })
}
