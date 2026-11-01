import request from '@/utils/request'

// ========== 1.7 通用版本中心 ==========

/** 实体选择器（13 类主数据） */
export function getEntitiesApi(bizType, params) {
  return request({ url: `/mdm/version-center/${bizType}/entities`, method: 'get', params })
}

/** 版本列表（1.7.2 历史回溯） */
export function getVersionsApi(bizType, entityId) {
  return request({ url: `/mdm/version-center/${bizType}/${entityId}/versions`, method: 'get' })
}

/** 版本快照查看 */
export function getSnapshotApi(bizType, entityId, versionNo) {
  return request({ url: `/mdm/version-center/${bizType}/${entityId}/versions/${versionNo}`, method: 'get' })
}

/** 两版本逐字段对比（1.7.1） */
export function getDiffApi(bizType, entityId, from, to) {
  return request({ url: `/mdm/version-center/${bizType}/${entityId}/diff`, method: 'get', params: { from, to } })
}

/** 一键回滚生成新版本（1.7.3，ADMIN） */
export function rollbackApi(bizType, entityId, sourceVersionNo, reason) {
  return request({
    url: `/mdm/version-center/${bizType}/${entityId}/rollback`,
    method: 'post',
    params: { sourceVersionNo, reason }
  })
}

// ========== 1.8 集成同步 ==========

/** 1.8.1 变更事件台账（outbox MDM 视图） */
export function getEventsApi(params) {
  return request({ url: '/mdm/events', method: 'get', params })
}

/** 1.8.2 内部监听执行台账 */
export function getListenerLogsApi(params) {
  return request({ url: '/mdm/events/listeners', method: 'get', params })
}

/** 1.8.2 外部投递状态（MDM 事件） */
export function getDeliveriesApi(params) {
  return request({ url: '/mdm/events/deliveries', method: 'get', params })
}

/** 复用 intf 投递重放（1.8.2 死信重放，ADMIN） */
export function replayDeliveryApi(deliveryId) {
  return request({ url: `/intf/deliveries/${deliveryId}/replay`, method: 'post' })
}

/** 缓存强刷（ADMIN） */
export function cacheInvalidateApi() {
  return request({ url: '/mdm/events/cache-invalidate', method: 'post' })
}
