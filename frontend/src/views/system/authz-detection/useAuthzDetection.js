import { reactive } from 'vue'
import { listIncidents } from '@/api/system/authz-detection'

/**
 * 16.8 越权检测两页共享状态（拆分自原越权检测操作台，design D2 composable 规则）。
 * 模块级单例：告警列表（拒绝告警页展示 + 临时降权「提前恢复」后刷新），
 * 等价原「同一组件被两条路由复用」的行为。
 */
const alertQuery = reactive({ status: '' })
const alerts = reactive({ rows: [], total: 0, page: 1, size: 50, loading: false })

const loadAlerts = async (page) => {
  if (page) alerts.page = page
  alerts.loading = true
  try {
    const params = { page: alerts.page, size: alerts.size }
    if (alertQuery.status) params.status = alertQuery.status
    const { data } = await listIncidents(params)
    alerts.rows = data.rows || []
    alerts.total = data.total || 0
  } finally {
    alerts.loading = false
  }
}

export function useAuthzDetection() {
  return { alertQuery, alerts, loadAlerts }
}
