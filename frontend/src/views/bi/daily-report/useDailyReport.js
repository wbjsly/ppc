import { ref } from 'vue'
import { getPushLogsApi, getStatsApi } from '@/api/bi/daily-report'

/**
 * 14.8 日报两页共享状态（拆分自原日报订阅操作台，design D2 composable 规则）。
 * 模块级单例：当月触达统计与推送日志（自动推送页展示，订阅配置页「试推」后刷新），
 * 等价原「同一组件被两条路由复用」的行为。
 */
const stats = ref({})
const logs = ref([])
const logTotal = ref(0)
const logQuery = ref({ receiver: '', status: '', page: 1, size: 20 })

async function loadStats() {
  try {
    const r = await getStatsApi()
    stats.value = r.data?.data || r.data || {}
  } catch { stats.value = {} }
}

async function loadLogs(page) {
  if (page) logQuery.value.page = page
  try {
    const r = await getPushLogsApi(logQuery.value)
    const d = r.data?.data || r.data || {}
    logs.value = d.rows || []
    logTotal.value = Number(d.total || 0)
  } catch { logs.value = []; logTotal.value = 0 }
}

export function useDailyReport() {
  return { stats, logs, logTotal, logQuery, loadStats, loadLogs }
}
