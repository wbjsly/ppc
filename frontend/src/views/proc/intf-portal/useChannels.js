import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getChannelsApi, setRateTierApi } from '@/api/proc/interface-integration'

/**
 * 15.5 令牌限流/熔断降级/配额管理三页共享状态（拆分自原限流熔断操作台，design D2 composable 规则）。
 * 模块级单例：三页共享通道列表与加载，调档动作两页共用，
 * 等价原「同一组件被四条路由复用」的行为。
 */
const channels = ref([])

async function loadChannels() {
  try { const r = await getChannelsApi(); channels.value = r.data?.data || [] } catch { channels.value = [] }
}

async function setTier(row, tier) {
  await setRateTierApi(row.id, tier)
  ElMessage.success('配额档已更新（变更留痕）')
  loadChannels()
}

export function useChannels() {
  return { channels, loadChannels, setTier }
}
