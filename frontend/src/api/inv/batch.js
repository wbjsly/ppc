import request from '@/utils/request'

/** 批次台账（4.2.1，spec batch-master） */

export function getBatchesApi(params) {
  return request.get('/inv/batches', { params })
}

export function getBatchApi(id) {
  return request.get(`/inv/batches/${id}`)
}

/** 新建（batchNo 留空 = 系统生成 B+yyMMdd+-+4位流水） */
export function createBatchApi(data) {
  return request.post('/inv/batches', data)
}

export function updateBatchApi(data) {
  return request.put('/inv/batches', data)
}

export function enableBatchApi(id) {
  return request.put(`/inv/batches/${id}/enable`)
}

export function disableBatchApi(id) {
  return request.put(`/inv/batches/${id}/disable`)
}
