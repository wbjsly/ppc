import { ref } from 'vue'
import { getCredentialsApi } from '@/api/proc/interface-integration'

/**
 * 15.3 凭证签发/定期轮换/紧急吊销三页共享状态（拆分自原凭证管理操作台，design D2 composable 规则）。
 * 模块级单例：三页共享凭证列表与加载，等价原「同一组件被四条路由复用」的行为。
 */
const creds = ref([])

async function load() {
  try { const r = await getCredentialsApi(); creds.value = r.data?.data || [] } catch { creds.value = [] }
}

export function useCredentials() {
  return { creds, load }
}
