import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/break-glass'
import { accountPage } from '@/api/system/account'

/**
 * 16.5 Break-Glass 三页共享状态（拆分自原 Break-Glass 操作台，design D2 composable 规则）。
 * 模块级单例：
 * - 公共数据 users/incidents/templates（新建申请、工单/模板维护）
 * - 申请列表（紧急申请页展示 + 签署后刷新）
 * - 待办列表（双人审批页展示 + 签署后刷新）
 * - 详情/签署弹窗（紧急申请与双人审批两页共用，见 _parts/BgDetailDialog.vue）
 * 等价原「同一组件被三条路由复用」的行为。
 */

// ---------- 公共数据 ----------
const users = ref([])
const incidents = ref([])
const templates = ref([])
const maxHours = 2 // BREAK_GLASS_MAX_HOURS 默认值展示口径

const processingIncidents = computed(() => incidents.value.filter((i) => i.status === 'PROCESSING'))
const activeTemplates = computed(() => templates.value.filter((t) => t.status === '1'))

async function loadCommon() {
  const [u, inc, tpl] = await Promise.all([
    accountPage({ current: 1, size: 500 }),
    api.incidentList(),
    api.templateList()
  ])
  users.value = (u.data && u.data.records) || []
  incidents.value = inc.data || []
  templates.value = tpl.data || []
}

// ---------- 紧急申请列表（16.5.1，供签署后刷新） ----------
const requests = ref([])
const reqLoading = ref(false)
const reqQuery = ref({ status: '' })

async function loadRequests() {
  reqLoading.value = true
  try {
    const { data } = await api.bgRequestList({ status: reqQuery.value.status || undefined })
    requests.value = data || []
  } finally {
    reqLoading.value = false
  }
}

// ---------- 待办列表（16.5.2 双人审批，供签署后刷新） ----------
const pending = ref([])
const pendLoading = ref(false)

async function loadPending() {
  pendLoading.value = true
  try {
    const { data } = await api.bgRequestList({ status: 'PENDING_APPROVAL' })
    pending.value = data || []
  } finally {
    pendLoading.value = false
  }
}

// ---------- 详情/签署弹窗状态（16.5.1 详情·归还 / 16.5.2 审批） ----------
const detailDlg = ref(false)
const detail = ref(null)

const logTasks = computed(() => (detail.value && detail.value.approvalLogs
  && detail.value.approvalLogs.tasks) || [])

async function openDetail(row) {
  const { data } = await api.bgDetail(row.id)
  detail.value = data
  detailDlg.value = true
}

async function signTask(row, pass) {
  let opinion = '同意'
  if (pass) {
    const { value } = await ElMessageBox.prompt('审批意见（默认：同意）', '签署', {
      inputValue: '同意'
    })
    opinion = value || '同意'
  } else {
    const { value } = await ElMessageBox.prompt('驳回理由（至少 2 字）', '驳回', {
      inputValidator: (v) => (v && v.trim().length >= 2) || '驳回理由至少 2 字'
    })
    opinion = value
  }
  if (pass) {
    await api.approvalPass(row.id, opinion)
  } else {
    await api.approvalReject(row.id, opinion)
  }
  ElMessage.success('已签署')
  const { data } = await api.bgDetail(detail.value.request.id)
  detail.value = data
  loadRequests()
  loadPending()
}

export function useBreakGlass() {
  return {
    users, incidents, templates, maxHours,
    processingIncidents, activeTemplates, loadCommon,
    requests, reqLoading, reqQuery, loadRequests,
    pending, pendLoading, loadPending,
    detailDlg, detail, logTasks, openDetail, signTask
  }
}
