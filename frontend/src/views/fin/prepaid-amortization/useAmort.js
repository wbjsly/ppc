import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listAmortDocs, amortDocDetail, generateAmort, yearEndCheck
} from '@/api/fin/amort'

/**
 * 8.9 预提待摊两页共享状态（拆分自原预提待摊多 tab 页，design D2 composable 规则）。
 * 模块级单例：
 * - 全局工具条（期间 + 生成本期凭证 + 年末余额检查）：预提管理、待摊管理两页均渲染，逻辑在此
 * - 单据列表 loadDocs（生成/登记后双列表刷新）
 * - 计划下钻抽屉（两页共用，见 _parts/AmortPlansDrawer.vue）
 * 等价原「同一组件被两条路由复用」的行为。
 */

const period = ref(new Date().toISOString().slice(0, 7).replace('-', ''))
const genResult = ref(null)
const yearEnd = ref(null)

const prepaidDocs = ref([])
const deferredDocs = ref([])

async function loadDocs() {
  const pre = await listAmortDocs('PREPAY')
  prepaidDocs.value = (pre.data || []).filter(d => d.bizType === 'PREPAY')
  const def = await listAmortDocs('AMORT')
  deferredDocs.value = (def.data || []).filter(d => d.bizType === 'AMORT')
}

async function doGenerate() {
  if (!/^\d{6}$/.test(period.value)) { ElMessage.warning('期间须为 yyyyMM'); return }
  const res = await generateAmort(period.value)
  genResult.value = res.data
  loadDocs()
}
async function doYearEndCheck() {
  if (!/^\d{6}$/.test(period.value)) { ElMessage.warning('期间须为 yyyyMM'); return }
  const res = await yearEndCheck(period.value)
  yearEnd.value = res.data
}

// ---- 计划下钻抽屉（8.9.1 计划 / 8.9.2 计划）----
const plansVisible = ref(false)
const plansTitle = ref('')
const plans = ref([])

async function openPlans(row) {
  const res = await amortDocDetail(row.id)
  plansTitle.value = res.data.docNo || ''
  plans.value = res.data.plans || []
  plansVisible.value = true
}

export function useAmort() {
  return {
    period, genResult, yearEnd,
    prepaidDocs, deferredDocs, loadDocs, doGenerate, doYearEndCheck,
    plansVisible, plansTitle, plans, openPlans
  }
}
