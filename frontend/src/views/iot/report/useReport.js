import { ref } from 'vue'
import { slaListApi } from '@/api/iot/todo'

/**
 * 13.9 服务报告两页共享状态（拆分自原服务报告操作台，design D2 composable 规则）。
 * 模块级单例：报告期、报告列表、加载态与结果条在移动看板/报告订阅两页间共享，
 * 等价原「同一组件被两条路由复用 + keep-alive」的行为。
 */
const period = ref(new Date().toISOString().slice(0, 7))
const reports = ref([])
const loading = ref(false)
const result = ref(null)

async function load() {
  loading.value = true
  try {
    const r = await slaListApi()
    reports.value = r.data || []
  } finally {
    loading.value = false
  }
}

export function useReport() {
  return { period, reports, loading, result, load }
}
