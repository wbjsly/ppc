import request from '@/utils/request'

/** 4.6.1 B 试算：FIFO+FEFO 批次推荐（只读模拟，不占库存） */
export function simulateFifoApi(params) {
  return request.get('/inv/fifo/simulate', { params })
}

/** 4.6.1 C 偏离监控：改批台账分页 */
export function getDeviationsApi(params) {
  return request.get('/inv/fifo/deviations', { params })
}

/** 4.6.2 效期预警清单（黄/橙/红 + 锁定标识） */
export function getExpiryWarningsApi(params) {
  return request.get('/inv/expiry/warnings', { params })
}

/** 4.6.3 拣货推荐：队列单据生成推荐 */
export function generatePickRecommendApi(data) {
  return request.post('/inv/pick-recommend/generate', data)
}

/** 4.6.3 确认回写（批次+仓位，不建预留） */
export function confirmPickRecommendApi(data) {
  return request.post('/inv/pick-recommend/confirm', data)
}
