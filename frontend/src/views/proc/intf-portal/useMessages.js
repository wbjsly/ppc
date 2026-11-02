import { ref } from 'vue'
import { getMessagesApi, getDeliveriesApi } from '@/api/proc/interface-integration'

/**
 * 15.4 报文网关四页共享状态（拆分自原报文网关操作台，design D2 composable 规则）。
 * 模块级单例：逐级校验/幂等判定/结果回执/死信处理四页共享报文与投递列表，
 * 等价原「同一组件被四条路由复用」的行为。
 */
const msgs = ref([])
const deliveries = ref([])

async function load() {
  try { const r = await getMessagesApi(); msgs.value = r.data?.data || [] } catch { msgs.value = [] }
  try { const r = await getDeliveriesApi(); deliveries.value = r.data?.data || [] } catch { deliveries.value = [] }
}

export function useMessages() {
  return { msgs, deliveries, load }
}
