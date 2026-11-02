import { ref } from 'vue'
import { escalationTicketsApi } from '@/api/ops/ops-sla'

/**
 * 18.3 升级工单共享状态（拆分自原升级处置操作台，design D2 composable 规则）。
 * 模块级单例：升级工单页展示 + 层级升级页扫描后交叉刷新共用，
 * 等价原「同一组件被多条路由复用」的行为。
 */
const tickets = ref([])

async function loadTickets() {
  const r = await escalationTicketsApi()
  tickets.value = r.data || []
}

export function useEscalation() {
  return { tickets, loadTickets }
}
