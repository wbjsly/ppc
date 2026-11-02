import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  planPage, savePlan, submitPlanApproval, disablePlan, generatePlanOrders,
  woPage, woAssign, woStart, woExecution, woSubWorkOrder, woSubmitAcceptance,
  woAccept, woClose, woSpares,
  reportPage, submitReport, convertReport, rejectReport,
  lockPage, lockAsset, releaseLock,
  maintenanceHistory, maintenanceStats
} from '@/api/eam/maintenance'
import request from '@/utils/request'

/**
 * 7.3 运维管理三页共享状态与动作（拆分自原运维管理操作台，design D2 composable 规则）。
 * 模块级单例：点检保养/故障维修/备件领用三页共享工单动作、执行记录对话框与展示辅助，
 * 等价原「同一组件被三条路由复用」的行为。act() 动作完成后经 setRefresh 注册的
 * 当前页加载器回刷（替代原 loadAll 的 tab 分支）。
 */
export const loading = ref(false)
export const saving = ref(false)
export const generating = ref(false)

let refreshFn = null
export function setRefresh(fn) { refreshFn = fn }
async function refreshNow() { if (refreshFn) await refreshFn() }

// ---- 7.3.1 计划 ----
export const planRows = ref([])
export const planWoRows = ref([])
export const planVisible = ref(false)
export const planForm = reactive({ id: '', cardId: '', itemName: '', cycle: 'M', standard: '',
  sparesJson: '', estHours: null, criticalFlag: '0', nextPlanDate: '' })
export const genResult = ref(null)

// ---- 7.3.2 报修 / 工单 / 挂锁 / 履历 ----
export const reportRows = ref([])
export const repairWoRows = ref([])
export const reportVisible = ref(false)
export const reportForm = reactive({ cardId: '', faultSymptom: '', faultTime: '', impactScope: '',
  detail: '', safetyRisk: '0' })
export const convertVisible = ref(false)
export const convertForm = reactive({ id: '', reportNo: '', faultSymptom: '', assignee: '',
  resolvePlan: 'SELF', outsourceVendor: '' })
export const execVisible = ref(false)
export const execForm = reactive({ woNo: '', execDesc: '', issueDesc: '', partsChanged: '', hours: null, postType: 'TECH' })
export const execTimes = ref([])
export const subVisible = ref(false)
export const subForm = reactive({ parentWoNo: '', desc: '' })
export const lockRows = ref([])
export const lockForm = reactive({ cardId: '', reason: '' })
export const historyCardId = ref('')
export const historyVisible = ref(false)
export const history = reactive({ workOrders: [], safetyLocks: [], notices: [], stats: null })

// ---- 7.3.3 备件 ----
export const spareWoNo = ref('')
export const issueRows = ref([])
export const spareVisible = ref(false)
export const spareLines = ref([{ itemCode: '', qty: 1 }])
export const spareOverReason = ref('')

// ---- 展示辅助 ----
export const fmt = (v) => (v == null ? '0.00' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2 }))
export const cycleLabel = (c) => ({ D: '日', W: '周', M: '月', Q: '季', Y: '年' }[c] || c)
export const statusLabel = (s) => ({ PENDING: '待执行', ASSIGNED: '已派工', EXECUTING: '执行中',
  PENDING_ACCEPT: '待验收', CLOSED: '已关闭', CANCELLED: '已取消' }[s] || s)
export const statusTag = (s) => ({ PENDING: 'info', ASSIGNED: 'warning', EXECUTING: 'primary',
  PENDING_ACCEPT: 'warning', CLOSED: 'success', CANCELLED: 'info' }[s] || 'info')
export const approvalLabel = (s) => ({ NOT_REQUIRED: '免审批', PENDING_APPROVE: '待审批',
  APPROVED: '已通过', REJECTED: '已驳回' }[s] || s)
export const approvalTag = (s) => ({ NOT_REQUIRED: 'info', PENDING_APPROVE: 'warning',
  APPROVED: 'success', REJECTED: 'danger' }[s] || 'info')
export const reportLabel = (s) => ({ PENDING: '待确认', CONVERTED: '已转工单', REJECTED: '已驳回' }[s] || s)

export async function act(p, msg) {
  try {
    await p
    ElMessage.success(msg)
    await refreshNow()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e.message || '操作失败')
  }
}

// ================= 加载 =================
export async function loadPlans() {
  loading.value = true
  try {
    planRows.value = (await planPage({ current: 1, size: 100 })).data.records
    planWoRows.value = (await woPage({ current: 1, size: 100, woType: 'PREVENTIVE' })).data.records
  } finally { loading.value = false }
}
export async function loadReports() {
  reportRows.value = (await reportPage({ current: 1, size: 100 })).data.records
  repairWoRows.value = (await woPage({ current: 1, size: 100, woType: 'REPAIR' })).data.records
  lockRows.value = (await lockPage({ current: 1, size: 100 })).data.records
}
export async function loadWoIssues() {
  if (!spareWoNo.value) return
  const res = await request.get('/eam/work-orders/' + spareWoNo.value)
  issueRows.value = res.data.issues || []
}

// ================= 计划 =================
export function openPlanDialog(row) {
  Object.assign(planForm, row || { id: '', cardId: '', itemName: '', cycle: 'M', standard: '',
    sparesJson: '', estHours: null, criticalFlag: '0', nextPlanDate: '' })
  planVisible.value = true
}
export async function doSavePlan() {
  if (!planForm.cardId || !planForm.itemName) { ElMessage.warning('卡片与维护项目必填'); return }
  saving.value = true
  try {
    await savePlan({ ...planForm })
    ElMessage.success('计划已保存')
    planVisible.value = false
    await loadPlans()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '保存失败') } finally { saving.value = false }
}
export async function doSubmitApproval(row) {
  await act(submitPlanApproval(row.id), '已提交设备主管审批')
}
export async function doDisablePlan(row) {
  await ElMessageBox.confirm(`停用计划 ${row.planNo}？`, '提示', { type: 'warning' })
  await act(disablePlan(row.id), '已停用')
}
export function describeGen(r) {
  const parts = []
  if (r.created.length) parts.push(`新建：${r.created.join('、')}`)
  if (r.postponed.length) parts.push(`顺延（停机中）：${r.postponed.join('、')}`)
  if (r.blocked.length) parts.push(`阻断：${r.blocked.join('、')}`)
  if (r.spareWarnings.length) parts.push(`备件不足预警：${r.spareWarnings.map(w => `${w.itemCode}(需${w.required}/可用${w.available})`).join('、')}`)
  return parts.join('；') || '窗口内无到期计划'
}
export async function doGenerate() {
  generating.value = true
  try {
    const res = await generatePlanOrders()
    genResult.value = res.data
    ElMessage.success(`生成完成：新建 ${res.data.created.length} 张`)
    await loadPlans()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '生成失败') } finally { generating.value = false }
}

// ================= 工单动作（计划 + 维修共用） =================
export async function doAssign(row) {
  const { value } = await ElMessageBox.prompt('指派维修人员', `派工 ${row.woNo}`, { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await act(woAssign(row.woNo, value), '派工成功（已校验安全停机）')
}
export function openExecDialog(row) {
  Object.assign(execForm, { woNo: row.woNo, execDesc: row.execDesc || '', issueDesc: row.issueDesc || '',
    partsChanged: row.partsChanged || '', hours: null, postType: 'TECH' })
  execTimes.value = []
  execVisible.value = true
}
export async function addTime() {
  if (!execForm.hours) { ElMessage.warning('填写工时数'); return }
  execTimes.value.push({ hours: execForm.hours, postType: execForm.postType })
  execForm.hours = null
}
export async function doSaveExecution() {
  saving.value = true
  try {
    await woExecution(execForm.woNo, { execDesc: execForm.execDesc, issueDesc: execForm.issueDesc, partsChanged: execForm.partsChanged })
    for (const t of execTimes.value) {
      await request.post('/eam/work-orders/' + execForm.woNo + '/times',
        { woNo: execForm.woNo, worker: 'self', postType: t.postType, workHours: t.hours, workDate: new Date().toISOString().slice(0, 10) })
    }
    ElMessage.success('执行记录已保存')
    execVisible.value = false
    await refreshNow()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '保存失败') } finally { saving.value = false }
}
export function openSubDialog(row) {
  Object.assign(subForm, { parentWoNo: row.woNo, desc: '' })
  subVisible.value = true
}
export async function doCreateSub() {
  if (!subForm.desc) { ElMessage.warning('问题描述必填'); return }
  await act(woSubWorkOrder(subForm.parentWoNo, { desc: subForm.desc }), '子工单已创建')
  subVisible.value = false
}
export async function doAccept(row, who) {
  const evalOpts = who === 'ENG' ? ['GOOD', 'OK', 'BAD'] : []
  let resultEval
  if (evalOpts.length) {
    const { value } = await ElMessageBox.prompt('维护结果评价（GOOD/OK/BAD）', `验收 ${row.woNo}`,
      { inputValue: 'GOOD', inputPattern: /GOOD|OK|BAD/, inputErrorMessage: 'GOOD/OK/BAD' })
    resultEval = value
  }
  await act(woAccept(row.woNo, who, resultEval), who === 'OP' ? '操作员已验收' : '工程师已验收')
}
export async function doClose(row) {
  await ElMessageBox.confirm(`关闭工单 ${row.woNo}？将执行 BR-20 四前置校验并归集成本`, '提示', { type: 'warning' })
  await act(woClose(row.woNo), '工单已关闭，成本已归集')
}
export { woStart, woSubmitAcceptance }

// ================= 报修 =================
export async function doSubmitReport() {
  if (!reportForm.cardId || !reportForm.faultSymptom || !reportForm.faultTime) {
    ElMessage.warning('卡片/故障现象/发现时间必填'); return
  }
  saving.value = true
  try {
    await submitReport({ ...reportForm })
    ElMessage.success('报修已提交' + (reportForm.safetyRisk === '1' ? '（已自动安全挂锁）' : ''))
    reportVisible.value = false
    Object.assign(reportForm, { cardId: '', faultSymptom: '', faultTime: '', impactScope: '', detail: '', safetyRisk: '0' })
    await loadReports()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '提交失败') } finally { saving.value = false }
}
export function openConvertDialog(row) {
  Object.assign(convertForm, { id: row.id, reportNo: row.reportNo, faultSymptom: row.faultSymptom,
    assignee: '', resolvePlan: 'SELF', outsourceVendor: '' })
  convertVisible.value = true
}
export async function doConvert() {
  if (!convertForm.assignee) { ElMessage.warning('须指派维修人员'); return }
  saving.value = true
  try {
    await convertReport(convertForm.id, { assignee: convertForm.assignee, resolvePlan: convertForm.resolvePlan,
      outsourceVendor: convertForm.outsourceVendor })
    ElMessage.success('已转维修工单')
    convertVisible.value = false
    await loadReports()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '转工单失败（检查挂锁/评估冻结）') } finally { saving.value = false }
}
export async function doRejectReport(row) {
  const { value } = await ElMessageBox.prompt('驳回原因', `驳回 ${row.reportNo}`, { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await act(rejectReport(row.id, value), '已驳回')
}

// ================= 挂锁 =================
export async function doLock() {
  if (!lockForm.cardId || !lockForm.reason) { ElMessage.warning('卡片与原因必填'); return }
  await act(lockAsset({ cardId: lockForm.cardId, reason: lockForm.reason }), '挂锁成功')
  lockForm.reason = ''
}
export async function doRelease(row) {
  const { value } = await ElMessageBox.prompt('解除原因', `解锁 ${row.lockNo}（须设备主管）`, { inputValue: '排查完成' })
  await act(releaseLock(row.id, value), '已解锁')
}

// ================= 履历 =================
export async function loadHistory() {
  if (!historyCardId.value) { ElMessage.warning('输入卡片 ID'); return }
  try {
    const [h, s] = await Promise.all([
      maintenanceHistory(historyCardId.value),
      maintenanceStats(historyCardId.value, 12)
    ])
    Object.assign(history, h.data, { stats: s.data })
    historyVisible.value = true
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '履历加载失败') }
}

// ================= 备件 =================
export async function doRequestSpares() {
  const lines = spareLines.value.filter(l => l.itemCode && l.qty > 0)
  if (!lines.length) { ElMessage.warning('至少一行有效明细'); return }
  saving.value = true
  try {
    await woSpares(spareWoNo.value, { lines, overReason: spareOverReason.value })
    ElMessage.success('领料单已生成（DRAFT，待仓库过账）')
    spareVisible.value = false
    spareOverReason.value = ''
    await loadWoIssues()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '领料失败（超领须填原因 BR-17）') } finally { saving.value = false }
}
