import { ref } from 'vue'
import { getSlaApi, getSlaListApi } from '@/api/i18n/i18n'

/**
 * 17.7 服务报告两页共享状态（拆分自原服务报告页，design D2 composable 规则）。
 * 模块级单例：月度报告与归档发布两页共用报告数据、SLA 状态列表与刷新函数。
 */
const now = new Date()
const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1)
const defaultPeriod = `${prev.getFullYear()}-${String(prev.getMonth() + 1).padStart(2, '0')}`

const period = ref(defaultPeriod)
const report = ref(null)
const slaList = ref([])

async function loadList() {
  try {
    const r = await getSlaListApi()
    slaList.value = r.data || []
    if (period.value && !report.value) {
      try {
        const rr = await getSlaApi(period.value)
        report.value = rr.data
      } catch { report.value = null }
    }
  } catch { slaList.value = [] }
}

export function useSlaReport() {
  return { defaultPeriod, period, report, slaList, loadList }
}
