import request from '@/utils/request'

// ================= 8.2.1 应收确认 =================
/** 确认清单分页（confirmed=true 已确认/false 未确认） */
export const confirmPage = (params) => request.get('/fin/ar-mgmt/confirm', { params })
/** 单笔确认（账龄起算锚点，已确认不可撤销） */
export const confirmAr = (id) => request.post(`/fin/ar-mgmt/confirm/${id}`)
/** 批量确认（上限 200） */
export const batchConfirm = (arIds) => request.post('/fin/ar-mgmt/confirm/batch', arIds)

// ================= 8.2.2 账龄分析 =================
/** 账龄六段分析（超90占比=BR-4.3-14 判定源 + 环比） */
export const aging = (params) => request.get('/fin/ar-mgmt/aging', { params })
/** 某客户分段基数（坏账试算复用） */
export const bucketBases = (params) => request.get('/fin/ar-mgmt/aging/buckets', { params })

// ================= 8.2.3 坏账计提 =================
/** 坏账计提试算（只算不落库） */
export const provisionTrial = (customerId, period) =>
  request.post('/fin/ar-mgmt/provisions/trial', null, { params: { customerId, period } })
/** 确认计提（冲回上期+本期重提+台账落库） */
export const provisionConfirm = (customerId, period) =>
  request.post('/fin/ar-mgmt/provisions/confirm', null, { params: { customerId, period } })
/** 计提台账分页 */
export const provisionPage = (params) => request.get('/fin/ar-mgmt/provisions', { params })
/** 计提台账详情（六段快照+凭证链） */
export const provisionDetail = (id) => request.get(`/fin/ar-mgmt/provisions/${id}`)
