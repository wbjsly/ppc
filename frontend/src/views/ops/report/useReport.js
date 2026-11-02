import { ref } from 'vue'
import { getReportApi, improvementsApi } from '@/api/ops/ops-sla'

/**
 * 18.5 月度报告两页共享状态（拆分自原服务报告操作台，design D2 composable 规则）。
 * 模块级单例：月度报告与归档发布间共享报告期、报告数据与改进项台账
 * （生成/归档/发布后的交叉刷新依赖同一份状态）。
 */
const period = ref('')
const pubPeriod = ref('')
const report = ref(null)
const improvements = ref([])

/** 两页各自的默认月份 = 上月（仅在尚未选择时初始化，保留模块级单例中的选择）。 */
function initPeriods() {
  const now = new Date()
  const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  const p = `${prev.getFullYear()}-${String(prev.getMonth() + 1).padStart(2, '0')}`
  if (!period.value) period.value = p
  if (!pubPeriod.value) pubPeriod.value = p
}

async function load() {
  try {
    const r = await getReportApi(period.value)
    report.value = r.data
  } catch (e) {
    report.value = null
  }
}

async function loadImprovements() {
  const r = await improvementsApi({ limit: 50 })
  improvements.value = r.data || []
}

export function useReport() {
  return { period, pubPeriod, report, improvements, initPeriods, load, loadImprovements }
}
