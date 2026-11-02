import { ref } from 'vue'
import { reimburseDetail, listLoans, loanAgingView } from '@/api/fin/expense-reimbursement'

/**
 * 8.8 费用报销三页共享状态（拆分自原费用报销多 tab 页，design D2 composable 规则）。
 * 模块级单例：
 * - 报销详情抽屉（报销申请、借款冲销两页共用，见 _parts/ReimburseDetailDrawer.vue）
 * - 借款列表/账龄（借款管理页展示；借款冲销页「登记退款」后经 loadLoans 刷新）
 * 等价原「同一组件被三条路由复用」的行为。
 */

// ---- 报销详情抽屉（8.8.1 详情 / 8.8.3 冲销明细）----
const detailVisible = ref(false)
const detail = ref({})

async function openDetail(row) {
  const res = await reimburseDetail(row.id)
  detail.value = res.data || {}
  detailVisible.value = true
}

// ---- 借款列表与账龄（8.8.2 展示 / 8.8.3 退款后刷新）----
const loans = ref([])
const aging = ref([])

async function loadLoans() {
  const res = await listLoans({})
  loans.value = res.data || []
  const a = await loanAgingView()
  aging.value = a.data || []
}

export function useExpense() {
  return {
    detailVisible, detail, openDetail,
    loans, aging, loadLoans
  }
}
