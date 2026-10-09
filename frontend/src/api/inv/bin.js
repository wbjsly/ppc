import request from '@/utils/request'

/** 仓库仓位（4.1.2，spec warehouse-zone-planning） */

export function getBinsApi(params) {
  return request.get('/inv/bins', { params })
}

export function getBinApi(id) {
  return request.get(`/inv/bins/${id}`)
}

/** 单个新建（编号服务端生成，binCode 忽略；属性继承区域默认可覆盖） */
export function createBinApi(data) {
  return request.post('/inv/bins', data)
}

/** 批量规划：排/列/层区间 → 冲突 409 整体拒绝 → 单事务生成 */
export function batchCreateBinsApi(data) {
  return request.post('/inv/bins/batch', data)
}

export function updateBinApi(data) {
  return request.put('/inv/bins', data)
}

export function enableBinApi(id) {
  return request.put(`/inv/bins/${id}/enable`)
}

export function disableBinApi(id) {
  return request.put(`/inv/bins/${id}/disable`)
}
