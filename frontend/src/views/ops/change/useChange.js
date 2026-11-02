import { ref } from 'vue'
import { listChangeApi } from '@/api/ops/ops-sla'

/**
 * 18.1 变更管理五页共享状态（拆分自原变更管理操作台，design D2 composable 规则）。
 * 模块级单例：变更申请/变更日历/窗口发布/失败回滚/回滚原因五页间共享变更单列表，
 * 等价原「同一组件被多条路由复用」的行为。
 */
const tickets = ref([])

async function load() {
  const r = await listChangeApi({ page: 1, size: 50 })
  tickets.value = r.data || []
}

export function useChange() {
  return { tickets, load }
}
