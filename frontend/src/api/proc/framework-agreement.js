import request from '@/utils/request'

/** 框架协议分页查询（按协议号/招标号关键字 + 状态） */
export function getAgreementsApi(params) {
  return request.get('/proc/framework-agreements', { params })
}

/** 协议详情（头 + 明细行） */
export function getAgreementDetailApi(id) {
  return request.get(`/proc/framework-agreements/${id}`)
}

/** 直接修改明细单价/份额 —— 后端固定 422（价格份额已锁定） */
export function updateAgreementLineApi(lineId, data) {
  return request.put(`/proc/framework-agreements/lines/${lineId}`, data)
}

/** 协议变更（经审批，留痕前后值/操作人/时间） */
export function changeAgreementLineApi(agreementId, lineId, data) {
  return request.post(`/proc/framework-agreements/${agreementId}/lines/${lineId}/change`, data)
}
