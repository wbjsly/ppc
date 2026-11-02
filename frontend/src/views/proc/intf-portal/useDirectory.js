import { ref } from 'vue'
import { getContractsApi } from '@/api/proc/interface-integration'

/**
 * 15.1 接口目录三页共享状态（拆分自原接口目录操作台，design D2 composable 规则）。
 * 模块级单例：目录登记/契约评审/版本登记三页共享契约列表与加载，
 * 等价原「同一组件被三条路由复用」的行为。
 */
const contracts = ref([])

async function load() {
  try { const r = await getContractsApi(); contracts.value = r.data?.data || [] } catch { contracts.value = [] }
}

export function useDirectory() {
  return { contracts, load }
}
