import { ref } from 'vue'
import { getReportApi } from '@/api/bi/sla-report'

/**
 * 14.9 SLA 报告两页共享状态（拆分自原 SLA 报告操作台，design D2 composable 规则）。
 * 模块级单例：所选月份、当月报告（月度报告页展示/编辑，归档发布页发布）、
 * 未达标三要素与改进计划（由 loadReport 解析），等价原「同一组件被两条路由复用」的行为。
 */
const period = ref(new Date().toISOString().slice(0, 7))
const report = ref(null)
const unachieved = ref([])
const improve = ref({ measures: '', nextPlan: '' })

async function loadReport() {
  try {
    const r = await getReportApi(period.value)
    report.value = r.data?.data || null
    if (report.value) {
      let rc = []
      try { rc = JSON.parse(report.value.rootCauseJson || '[]') } catch { rc = [] }
      let imp = {}
      try { imp = JSON.parse(report.value.improveJson || '{}') } catch { imp = {} }
      improve.value = { measures: imp.measures || '', nextPlan: imp.nextPlan || '' }
      // 未达标项（从 metricsJson status 判定）三要素行
      let overview = []
      try { overview = JSON.parse(report.value.metricsJson || '[]') } catch { overview = [] }
      unachieved.value = overview
        .filter(o => o.status === 'WARN' || o.status === 'BELOW')
        .map(o => {
          const c = rc.find(x => x.metricKey === o.metricKey) || {}
          return { metricKey: o.metricKey, rootCause: c.rootCause || '', owner: c.owner || '', dueDate: c.dueDate || '' }
        })
    } else {
      report.value = null
    }
  } catch { report.value = null }
}

export function useSlaReport() {
  return { period, report, unachieved, improve, loadReport }
}
