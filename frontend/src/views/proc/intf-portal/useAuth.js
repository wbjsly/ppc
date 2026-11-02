import { ref } from 'vue'
import { getAuthRejectionsApi } from '@/api/proc/interface-call-console'

/**
 * 15.2 令牌认证/签名校验两页共享状态（拆分自原认证鉴权操作台，design D2 composable 规则）。
 * 模块级单例：两页共享认证拒绝记录，等价原「同一组件被三条路由复用」的行为。
 */
const rejections = ref([])

async function loadRejections() {
  try { const r = await getAuthRejectionsApi(); rejections.value = r.data?.data || [] } catch { rejections.value = [] }
}

export function useAuth() {
  return { rejections, loadRejections }
}
