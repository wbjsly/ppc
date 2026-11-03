import { reactive } from 'vue'
import { listReports } from '@/api/system/sla-report'

/**
 * 16.9 SLA 报告两页共享状态（拆分自原 SLA 报告操作台，design D2 composable 规则）。
 * 模块级单例：月度报告列表（月度报告页展示 + 归档发布页归档/发布后刷新），
 * 等价原「同一组件被两条路由复用」的行为。
 */
const query = reactive({ period: '', status: '' })
const reports = reactive({ rows: [], total: 0, page: 1, size: 20, loading: false })

const loadReports = async (page) => {
  if (page) reports.page = page
  reports.loading = true
  try {
    const params = { page: reports.page, size: reports.size }
    if (query.period) params.period = query.period
    if (query.status) params.status = query.status
    const { data } = await listReports(params)
    reports.rows = data.rows || []
    reports.total = data.total || 0
  } finally {
    reports.loading = false
  }
}

export function useSlaReport() {
  return { query, reports, loadReports }
}
