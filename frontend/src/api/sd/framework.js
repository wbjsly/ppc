import request from '@/utils/request'

// ---------- 销售框架协议（spec sales-framework-agreement，3.11.1~3.11.2） ----------

export function getFrameworksApi(params) {
  return request({ url: '/sd/frameworks', method: 'get', params })
}

export function getFrameworkDetailApi(id) {
  return request({ url: `/sd/frameworks/${id}`, method: 'get' })
}

/** 3.11.2 分批执行视图（行三量余量 + 执行时间表） */
export function getFrameworkExecutionApi(id) {
  return request({ url: `/sd/frameworks/${id}/execution`, method: 'get' })
}

/** 创建协议（FW_NO 生成后锁定） */
export function createFrameworkApi(data) {
  return request({ url: '/sd/frameworks', method: 'post', data })
}

/** 维护（编号不可改；已下达行不可改量价） */
export function updateFrameworkApi(id, data) {
  return request({ url: `/sd/frameworks/${id}/update`, method: 'post', data })
}

/** 13.3 下达框架订单（超剩余可下达量 C-4.3-10 阻断） */
export function releaseFrameworkApi(id, data) {
  return request({ url: `/sd/frameworks/${id}/release`, method: 'post', data })
}

/** 取消未发货下达单（回退已下达量） */
export function cancelReleaseApi(releaseId, reason) {
  return request({ url: `/sd/frameworks/releases/${releaseId}/cancel`, method: 'post',
    data: { reason } })
}

/** 13.6 执行视图发起分批发货（过账回写已发量） */
export function shipReleaseApi(releaseId, data) {
  return request({ url: `/sd/frameworks/releases/${releaseId}/ship`, method: 'post', data })
}

/** 13.5 变更/终止（总量/单价/终止，销售总监 L2 审批，S-4.3-11） */
export function changeFrameworkApi(id, data) {
  return request({ url: `/sd/frameworks/${id}/change`, method: 'post', data })
}
