import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/permission-request'
import { roleListAll } from '@/api/system/permission'
import { accountPage } from '@/api/system/account'

/**
 * 16.3/16.4 权限申请五页共享状态（拆分自原权限申请操作台，design D2 composable 规则）。
 * 模块级单例：
 * - 公共数据 allRoles/users（新建申请、职责预检共用）
 * - 申请列表 requests/loadRequests（权限申请页展示 + 就地签署后刷新）
 * - 申请详情抽屉（权限申请/窗口外升级/双人复核三页共用，见 _parts/RequestDetailDrawer.vue）
 * - 新建申请弹窗（权限申请/到期续期两页共用，见 _parts/CreateRequestDialog.vue）
 * 等价原「同一组件被五条路由复用」的行为。
 */

// ---------- 公共数据 ----------
const allRoles = ref([])
const users = ref([])

async function loadCommon() {
  const [r, u] = await Promise.all([roleListAll(), accountPage({ current: 1, size: 500 })])
  allRoles.value = r.data || []
  users.value = (u.data && u.data.records) || []
}

// ---------- 权限申请列表（16.3.1，供签署后刷新） ----------
const requests = ref([])
const reqLoading = ref(false)
const reqQuery = ref({ status: '', keyword: '' })

async function loadRequests() {
  reqLoading.value = true
  try {
    const { data } = await api.requestList({
      status: reqQuery.value.status || undefined,
      keyword: reqQuery.value.keyword || undefined
    })
    requests.value = data || []
  } finally {
    reqLoading.value = false
  }
}

// ---------- 新建申请弹窗状态（16.3.1 新建 / 16.3.3 续期） ----------
const createDlg = ref(false)
const form = ref(blankForm())

function blankForm() {
  return {
    targetUserId: '', roleCodes: [], dataScope: '', reason: '',
    validMonths: 12, activationMode: 'IMMEDIATE', activateAt: null, ticketNo: ''
  }
}

function openCreate() {
  form.value = blankForm()
  createDlg.value = true
}

async function submitCreate() {
  const f = form.value
  if (!f.targetUserId || !f.roleCodes.length || !f.dataScope || !f.reason) {
    ElMessage.error('被授权人 / 目标角色 / 数据范围 / 理由必填')
    return
  }
  if (f.reason.trim().length < 10) {
    ElMessage.error('业务理由不少于 10 字')
    return
  }
  if (f.activationMode === 'SCHEDULED' && !f.activateAt) {
    ElMessage.error('请选择生效时点')
    return
  }
  const payload = {
    ...f,
    activateAt: f.activationMode === 'SCHEDULED' && f.activateAt
      ? new Date(f.activateAt).toISOString().slice(0, 19) : undefined
  }
  await api.submitRequest(payload)
  ElMessage.success('已提交，进入风险路由审批')
  createDlg.value = false
  loadRequests()
}

// ---------- 申请详情抽屉状态（16.3.1 详情 / 16.4.2 详情·签署 / 16.4.3 详情） ----------
const detailDlg = ref(false)
const detail = ref(null)

const logTasks = computed(() => (detail.value && detail.value.approvalLogs
  && detail.value.approvalLogs.tasks) || [])
const snapConflicts = computed(() => (detail.value && detail.value.sodSnapshot
  && detail.value.sodSnapshot.conflicts) || [])
const snapBeyond = computed(() => (detail.value && detail.value.sodSnapshot
  && detail.value.sodSnapshot.beyondBaseline) || [])

async function openDetail(row) {
  await openDetailById(row.id)
}

async function openDetailById(id) {
  const { data } = await api.requestDetail(id)
  detail.value = data
  detailDlg.value = true
}

/** 就地签署/驳回（复用既有审批端点；无集中审批台页面，D8 跳转降级为就地操作） */
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
  ElMessage.success(pass ? '已签署通过' : '已驳回')
  await openDetailById(detail.value.request.id)
  loadRequests()
}

export function usePermissionRequest() {
  return {
    allRoles, users, loadCommon,
    requests, reqLoading, reqQuery, loadRequests,
    createDlg, form, blankForm, openCreate, submitCreate,
    detailDlg, detail, logTasks, snapConflicts, snapBeyond,
    openDetail, openDetailById, signTask
  }
}
