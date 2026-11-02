import { ref } from 'vue'
import { getReportsApi } from '@/api/proc/interface-integration'

/**
 * 15.8 月度生成/归档发布两页共享状态（拆分自原服务报告操作台，design D2 composable 规则）。
 * 模块级单例：两页共享 SLA 报告列表与加载，等价原「同一组件被两条路由复用」的行为。
 */
const reports = ref([])

async function load() {
  try { const r = await getReportsApi(); reports.value = r.data?.data || [] } catch { reports.value = [] }
}

export function useSla() {
  return { reports, load }
}
