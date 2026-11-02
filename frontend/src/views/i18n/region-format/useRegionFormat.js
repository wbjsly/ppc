import { ref } from 'vue'
import { getLocalesApi } from '@/api/i18n/i18n'

/**
 * 17.2 区域配置三页共享状态（拆分自原区域配置页，design D2 composable 规则）。
 * 模块级单例：日期/数字/货币三页共用同一份 Locale 配置列表。
 */
const rows = ref([])

async function load() {
  try {
    const r = await getLocalesApi()
    rows.value = r.data || []
  } catch { rows.value = [] }
}

export function useRegionFormat() {
  return { rows, load }
}
