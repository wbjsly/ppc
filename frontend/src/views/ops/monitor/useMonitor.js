import { ref } from 'vue'
import { listAlertsApi } from '@/api/ops/ops-sla'

/**
 * 18.2 告警列表共享状态（拆分自原监控处置操作台，design D2 composable 规则）。
 * 模块级单例：延迟采集与阈值告警两页共享告警列表及其筛选条件
 * （load 读取筛选，延迟采集的「采集一轮」按原行为同样带筛选刷新）。
 */
const level = ref('')
const status = ref('')
const alerts = ref([])

async function load() {
  const r = await listAlertsApi({ level: level.value || undefined, status: status.value || undefined, page: 1, size: 50 })
  alerts.value = r.data || []
}

export function useMonitor() {
  return { level, status, alerts, load }
}
