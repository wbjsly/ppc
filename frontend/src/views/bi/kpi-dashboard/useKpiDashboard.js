import { ref } from 'vue'
import { getSummaryApi } from '@/api/bi/kpi-dashboard'
import { renderApi } from '@/api/bi/report-template'
import { ElMessage } from 'element-plus'

/**
 * 14.7 KPI 看板三页共享状态（拆分自原 KPI 看板操作台，design D2 composable 规则）。
 * 模块级单例：KPI 卡片（指标达成页展示、趋势页 KPI 下拉、异常预警页下钻定位）与
 * 下钻抽屉状态（指标达成页「下钻」、异常预警页「查看原因分析」），
 * 等价原「同一组件被三条路由复用」的行为。
 */
const cards = ref([])
const sumLoading = ref(false)

async function loadSummary() {
  sumLoading.value = true
  try {
    cards.value = (await getSummaryApi()).data || []
  } catch (e) {
    cards.value = []
  } finally {
    sumLoading.value = false
  }
}

// ---------------- 下钻（挂模板渲染） ----------------
const drillVisible = ref(false)
const drillName = ref('')
const drillHeaders = ref([])
const drillRows = ref([])

async function openDrill(templateId, name) {
  try {
    const res = await renderApi(templateId, 1, 100)
    const d = res.data || {}
    drillHeaders.value = d.headers || []
    drillRows.value = (d.rows || []).map(r => {
      if (typeof r === 'object' && !Array.isArray(r)) return r
      const o = {}; drillHeaders.value.forEach((h, i) => { o[h] = r?.[i] }); return o
    })
    drillName.value = name
    drillVisible.value = true
  } catch (e) {
    ElMessage.error('下钻模板渲染失败')
  }
}

export function useKpiDashboard() {
  return { cards, sumLoading, loadSummary, drillVisible, drillName, drillHeaders, drillRows, openDrill }
}
