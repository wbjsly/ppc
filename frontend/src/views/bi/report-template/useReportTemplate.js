import { ref } from 'vue'
import { getTemplatesApi } from '@/api/bi/report-template'

/**
 * 14.1 报表模板四页共享状态（拆分自原报表模板操作台，design D2 composable 规则）。
 * 模块级单例，两组：
 * 1) 模板列表 rows + 检索条件 keyword/statusFilter + load()（模板设计页检索/编辑后刷新，
 *    模板发布页刷新与签署后刷新，版本控制页回退后刷新）；
 * 2) 全量模板 allRows + loadAll()（模板测试页、版本控制页的模板下拉）。
 * 等价原「同一组件被四条路由复用」的行为。
 */
const rows = ref([])
const loading = ref(false)
const keyword = ref('')
const statusFilter = ref('')
const allRows = ref([])

async function load() {
  loading.value = true
  try {
    const params = {}
    if (keyword.value) params.keyword = keyword.value
    if (statusFilter.value) params.status = statusFilter.value
    const res = await getTemplatesApi(params)
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function loadAll() {
  const res = await getTemplatesApi({})
  allRows.value = res.data || []
}

export function useReportTemplate() {
  return { rows, loading, keyword, statusFilter, load, allRows, loadAll }
}
