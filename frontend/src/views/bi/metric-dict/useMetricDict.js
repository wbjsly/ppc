import { ref } from 'vue'
import { getMetricsApi } from '@/api/bi/metric-dict'

/**
 * 14.3 指标口径字典共享状态（拆分自原口径字典操作台，design D2 composable 规则）。
 * 模块级单例：指标列表（口径定义页检索展示 + 发布后刷新，口径变更页「已发布指标」下拉），
 * 等价原「同一组件被三条路由复用」的行为。
 */
const metrics = ref([])
const loading = ref(false)

async function loadMetrics(keyword) {
  loading.value = true
  try {
    const res = await getMetricsApi({ current: 1, size: 100, keyword: keyword || undefined })
    metrics.value = res.data?.records || []
  } finally {
    loading.value = false
  }
}

export function useMetricDict() {
  return { metrics, loading, loadMetrics }
}
