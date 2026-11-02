import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import {
  accountPage, manualPage, manualDetail, manualAudit, manualPost
} from '@/api/fin/gl'

/**
 * 8.1 总账五页共享状态（拆分自原总账多 tab 页，design D2 composable 规则）。
 * 模块级单例：
 * - 科目分页与 ACTIVE 科目下拉 loadAccounts（科目管理页列表 + 凭证录入页辅助维度选择共用）
 * - 手工凭证三列表 manRows/auditRows/postRows 与 loadManual（凭证录入/审核/过账共用一次请求回填）
 * - 凭证详情抽屉（凭证录入、凭证审核两页共用，见 _parts/VoucherDetailDrawer.vue）
 * - 审核/过账动作（详情抽屉与审核页、过账页共用，刷新同一批列表）
 * 等价原「同一组件被五条路由复用」的行为。
 */

// ---------- 科目（8.1.1 列表 / 8.1.2 录入页下拉）----------
const accQuery = reactive({ current: 1, size: 50, keyword: '', status: '' })
const accRows = ref([])
const accTotal = ref(0)
const accOptions = ref([])

async function loadAccounts() {
  const { data } = await accountPage({ ...accQuery, keyword: accQuery.keyword || undefined, status: accQuery.status || undefined })
  accRows.value = data.records || []
  accTotal.value = Number(data.total || 0)
  accOptions.value = accRows.value.filter((a) => a.status === 'ACTIVE')
}

// ---------- 手工凭证（8.1.2 录入 / 8.1.3 审核 / 8.1.4 过账）----------
const manQuery = reactive({ keyword: '' })
const manRows = ref([])
const auditRows = ref([])
const postRows = ref([])

async function loadManual(status) {
  const { data } = await manualPage({ current: 1, size: 50, status, keyword: manQuery.keyword || undefined })
  const rows = data.records || []
  if (status === 'DRAFT') { manRows.value = rows; auditRows.value = rows }
  else if (status === 'AUDITED') { postRows.value = rows }
}

// ---------- 详情/打印抽屉（8.1.2 明细 / 8.1.3 明细）----------
const detailVisible = ref(false)
const detail = ref(null)

async function openDetail(id) {
  const { data } = await manualDetail(id)
  detail.value = data
  detailVisible.value = true
}
function printVoucher() { window.print() }

// ---------- 审核/过账（详情抽屉 + 审核页/过账页共用）----------
async function doAudit(id) {
  await manualAudit(id)
  ElMessage.success('审核通过（AUDITED）')
  detailVisible.value = false
  loadManual('DRAFT'); loadManual('AUDITED')
}

async function doPost(id) {
  await manualPost(id)
  ElMessage.success('已过账（余额已回写）')
  detailVisible.value = false
  loadManual('AUDITED')
}

export function useGl() {
  return {
    accQuery, accRows, accTotal, accOptions, loadAccounts,
    manQuery, manRows, auditRows, postRows, loadManual,
    detailVisible, detail, openDetail, printVoucher,
    doAudit, doPost
  }
}
