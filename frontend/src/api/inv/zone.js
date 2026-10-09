import request from '@/utils/request'

/** 仓库区域（4.1.2，spec warehouse-zone-planning） */

export function getZonesApi(params) {
  return request.get('/inv/zones', { params })
}

/** 区域 + 仓位数（规划页左栏） */
export function getZonesWithCountApi(params) {
  return request.get('/inv/zones/with-count', { params })
}

export function getZoneApi(id) {
  return request.get(`/inv/zones/${id}`)
}

export function createZoneApi(data) {
  return request.post('/inv/zones', data)
}

export function updateZoneApi(data) {
  return request.put('/inv/zones', data)
}

export function enableZoneApi(id) {
  return request.put(`/inv/zones/${id}/enable`)
}

export function disableZoneApi(id) {
  return request.put(`/inv/zones/${id}/disable`)
}
