import { ref, computed } from 'vue'
import { capacityMetricsApi } from '@/api/ops/ops-sla'

/**
 * 18.4 容量运维预警/扩容两页共享状态（拆分自原容量运维操作台，design D2 composable 规则）。
 * 模块级单例：容量预警与扩容执行间切换保持天数与指标数据，
 * 等价原「同一组件被多条路由复用」的行为。
 */
const days = ref(30)
const metrics = ref([])

const latest = computed(() => {
  const byKey = {}
  for (const m of metrics.value) {
    if (!byKey[m.metricKey]) byKey[m.metricKey] = m
  }
  return Object.values(byKey)
})

async function load() {
  const r = await capacityMetricsApi(days.value)
  metrics.value = r.data || []
}

export function useCapacity() {
  return { days, metrics, latest, load }
}
