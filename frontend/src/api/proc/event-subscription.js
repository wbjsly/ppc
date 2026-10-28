import request from '@/utils/request'

// ============ 事件定义（add-event-subscription，菜单 12.2.1） ============

/** 事件定义分页（类型码/状态） */
export function getEventTypesApi(params) {
  return request.get('/intf/event-types', { params })
}

/** 注册事件类型（命名 {对象}_{动作} 422；重复 409；限集成架构师/管理员） */
export function createEventTypeApi(data) {
  return request.post('/intf/event-types', data)
}

/** 修改事件类型（Schema 变更未升 Major → 422） */
export function updateEventTypeApi(id, data) {
  return request.put(`/intf/event-types/${id}`, data)
}

/** 启停事件类型（停用阻断该类型新事件发布） */
export function toggleEventTypeApi(id, status) {
  return request.post(`/intf/event-types/${id}/toggle`, { status })
}

// ============ 订阅配置（菜单 12.2.2） ============

/** 订阅分页（伙伴/类型/状态） */
export function getSubscriptionsApi(params) {
  return request.get('/intf/subscriptions', { params })
}

/** 创建订阅（UK 409；URL 空=回落通道；RETRY_MAX 1~5） */
export function createSubscriptionApi(data) {
  return request.post('/intf/subscriptions', data)
}

/** 修改订阅（改动对新投递生效——快照语义） */
export function updateSubscriptionApi(id, data) {
  return request.put(`/intf/subscriptions/${id}`, data)
}

/** 启停订阅（停用 → 该类型新投递回落通道 URL） */
export function toggleSubscriptionApi(id, status) {
  return request.post(`/intf/subscriptions/${id}/toggle`, { status })
}

// ============ 消费监控操作台（菜单 12.2.3，复用既有 deliveries/tickets 端点） ============

export { getDeliveriesApi, getDeliveryStatsApi, scanDeliveriesApi,
  replayDeliveryApi, abandonDeliveryApi, getTicketsApi, handleTicketApi } from './interface-integration'

// ============ 告警处理操作台（菜单 12.2.4，复用既有 sla 端点） ============

export { getAlertsApi, respondAlertApi, collectSlaApi } from './interface-integration'
