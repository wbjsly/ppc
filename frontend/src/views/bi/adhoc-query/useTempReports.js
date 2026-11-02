import { ref } from 'vue'
import { getTempReportsApi } from '@/api/bi/adhoc-query'

/**
 * 14.6 即席查询临时报表共享状态（拆分自原即席查询操作台，design D2 composable 规则）。
 * 模块级单例：临时报表列表（临时报表页展示，即席查询页「另存为临时报表」后刷新），
 * 等价原「同一组件被三条路由复用」的行为。
 */
const tempReports = ref([])

async function loadTemp() {
  try { tempReports.value = (await getTempReportsApi()).data || [] } catch (e) { /* ignore */ }
}

export function useTempReports() {
  return { tempReports, loadTemp }
}
