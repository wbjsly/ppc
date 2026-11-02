import { ref } from 'vue'
import { getContractsApi } from '@/api/proc/interface-integration'

/**
 * 15.6 沙箱配置/联调测试/生产放行三页共享状态（拆分自原沙箱联调操作台，design D2 composable 规则）。
 * 模块级单例：三页共享契约列表与加载，等价原「同一组件被三条路由复用」的行为。
 */
const contracts = ref([])

async function loadContracts() {
  try { const r = await getContractsApi(); contracts.value = r.data?.data || [] } catch { contracts.value = [] }
}

export function useSandbox() {
  return { contracts, loadContracts }
}
