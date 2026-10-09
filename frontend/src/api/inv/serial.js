import request from '@/utils/request'

/** 序列号台账（4.2.2，spec serial-master） */

export function getSerialsApi(params) {
  return request.get('/inv/serials', { params })
}

export function getSerialApi(id) {
  return request.get(`/inv/serials/${id}`)
}

/** 判重查询（BR-4.11-15 口径预留，只读） */
export function checkSerialApi(params) {
  return request.get('/inv/serials/check', { params })
}

export function createSerialApi(data) {
  return request.post('/inv/serials', data)
}

export function updateSerialApi(data) {
  return request.put('/inv/serials', data)
}

/** 状态流转（白名单合法边；解冻 reason 必填由服务端校验） */
export function transitionSerialApi(id, params) {
  return request.post(`/inv/serials/${id}/transition`, null, { params })
}

/** 流转记录（倒序） */
export function getSerialLogsApi(id) {
  return request.get(`/inv/serials/${id}/logs`)
}
