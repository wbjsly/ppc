<template>
  <div class="bg-page">
    <el-tabs v-model="tab">
      <!-- ================= 16.5.1 紧急申请 ================= -->
      <el-tab-pane label="紧急申请" name="requests">
        <div class="toolbar">
          <el-select v-model="reqQuery.status" placeholder="状态" clearable style="width: 150px"
            @change="loadRequests">
            <el-option label="审批中" value="PENDING_APPROVAL" />
            <el-option label="已生效" value="ACTIVE" />
            <el-option label="已回收" value="RECLAIMED" />
            <el-option label="已审计" value="AUDITED" />
            <el-option label="已驳回" value="REJECTED" />
          </el-select>
          <el-button type="primary" @click="loadRequests">查询</el-button>
          <el-button type="danger" plain @click="openCreate">新建紧急申请</el-button>
          <el-button @click="incidentDlg = true">故障工单</el-button>
          <el-button @click="templateDlg = true">权限模板</el-button>
        </div>
        <el-alert type="warning" :closable="false" style="margin-bottom: 10px"
          title="Break-Glass 是变更窗口约束下唯一的合规临时越权通道：须关联处理中故障工单，双人会签，到期自动回收并生成事后审计报告。" />
        <el-table :data="requests" v-loading="reqLoading" border stripe>
          <el-table-column prop="targetUsername" label="被授权人" width="120" />
          <el-table-column label="模板" min-width="160">
            <template #default="{ row }">{{ row.templateCode }}（{{ row.templateName }}）</template>
          </el-table-column>
          <el-table-column label="关联工单" min-width="180">
            <template #default="{ row }">{{ row.incNo }} {{ row.incidentTitle }}</template>
          </el-table-column>
          <el-table-column prop="durationHours" label="时长(h)" width="80" align="center" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="stType(row.status)" size="small">{{ stLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情/签署</el-button>
              <el-button v-if="row.status === 'ACTIVE'" link type="warning"
                @click="doReclaim(row)">提前归还</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ================= 16.5.2 双人审批 ================= -->
      <el-tab-pane label="双人审批" name="approvals">
        <div class="toolbar">
          <el-button type="primary" @click="loadPending">刷新待办</el-button>
          <el-tag type="info">双人会签：值班经理 + 安全管理员，缺一不可；30 分钟未完成自动升级运维负责人</el-tag>
        </div>
        <el-table :data="pending" v-loading="pendLoading" border stripe>
          <el-table-column prop="targetUsername" label="被授权人" width="120" />
          <el-table-column label="模板" min-width="140">
            <template #default="{ row }">{{ row.templateCode }}</template>
          </el-table-column>
          <el-table-column prop="durationHours" label="时长(h)" width="80" align="center" />
          <el-table-column prop="reason" label="处置说明" min-width="220" show-overflow-tooltip />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">审批</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ================= 16.5.3 事后审计 ================= -->
      <el-tab-pane label="事后审计" name="audits">
        <div class="toolbar">
          <el-button type="primary" @click="loadAudits">刷新</el-button>
          <el-button type="warning" plain @click="doSweep">逾期扫描（24h 未签每 12h 提醒）</el-button>
        </div>
        <el-table :data="audits" v-loading="audLoading" border stripe>
          <el-table-column prop="targetUsername" label="被授权人" width="120" />
          <el-table-column label="生效 → 回收" min-width="200">
            <template #default="{ row }">{{ fmtTime(row.grantAt) }} → {{ fmtTime(row.reclaimAt) }}</template>
          </el-table-column>
          <el-table-column prop="logCount" label="期间操作数" width="100" align="center" />
          <el-table-column label="结论" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.conclusion === 'NO_VIOLATION'" type="success" size="small">无越权</el-tag>
              <el-tag v-else-if="row.conclusion === 'VIOLATION'" type="danger" size="small">有越权待查</el-tag>
              <el-tag v-else type="info" size="small">未签署</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="签署" min-width="140">
            <template #default="{ row }">
              <span v-if="row.signedAt">{{ row.signedByName || row.signedBy }} {{ fmtTime(row.signedAt) }}</span>
              <template v-else>
                <el-button link type="success" @click="sign(row, 'NO_VIOLATION')">无越权</el-button>
                <el-button link type="danger" @click="sign(row, 'VIOLATION')">有越权</el-button>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="专项评审" width="90" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.specialReview === '1'" type="danger" size="small">月度≥3</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 新建申请对话框 -->
    <el-dialog v-model="createDlg" title="新建 Break-Glass 紧急申请" width="620px">
      <el-form label-width="110px">
        <el-form-item label="被授权人" required>
          <el-select v-model="form.targetUserId" filterable placeholder="选择用户" style="width: 100%">
            <el-option v-for="u in users" :key="u.id"
              :label="u.username + (u.nickName ? '（' + u.nickName + '）' : '')" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="故障工单" required>
          <el-select v-model="form.incidentId" placeholder="仅列处理中工单" style="width: 100%">
            <el-option v-for="i in processingIncidents" :key="i.id"
              :label="i.incNo + ' ' + i.title" :value="i.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="权限模板" required>
          <el-select v-model="form.templateId" placeholder="选择启用模板" style="width: 100%">
            <el-option v-for="t in activeTemplates" :key="t.id"
              :label="t.code + '（' + t.name + '）'" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="申请时长" required>
          <el-input-number v-model="form.durationHours" :min="1" :max="maxHours" />
          <span class="muted" style="margin-left: 8px">小时（上限 {{ maxHours }}）</span>
        </el-form-item>
        <el-form-item label="录屏声明" required>
          <el-radio-group v-model="form.screenDeclare">
            <el-radio label="YES">需要录屏</el-radio>
            <el-radio label="NO">不需要（说明理由）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="处置说明" required>
          <el-input v-model="form.reason" type="textarea" :rows="3"
            placeholder="≥10 字：故障现象、预期操作、影响范围" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDlg = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitCreate">提交申请</el-button>
      </template>
    </el-dialog>

    <!-- 工单管理对话框 -->
    <el-dialog v-model="incidentDlg" title="故障工单管理" width="720px">
      <div class="toolbar">
        <el-button type="primary" size="small" @click="incidentCreateDlg = true">新建工单</el-button>
      </div>
      <el-table :data="incidents" border size="small">
        <el-table-column prop="incNo" label="编号" width="160" />
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'PROCESSING' ? 'warning' : 'info'" size="small">
              {{ incLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <template v-if="row.status === 'PROCESSING'">
              <el-button link type="success" @click="transition(row, 'RESOLVED')">解决</el-button>
              <el-button link type="info" @click="transition(row, 'CLOSED')">关闭</el-button>
            </template>
            <span v-else class="muted">已完结</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 新建工单对话框 -->
    <el-dialog v-model="incidentCreateDlg" title="新建故障工单" width="480px">
      <el-form label-width="80px">
        <el-form-item label="编号" required>
          <el-input v-model="incForm.incNo" placeholder="INC-YYYYMMDD-XXX" />
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="incForm.title" placeholder="故障现象简述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="incidentCreateDlg = false">取消</el-button>
        <el-button type="primary" @click="saveIncident">保存</el-button>
      </template>
    </el-dialog>

    <!-- 模板管理对话框 -->
    <el-dialog v-model="templateDlg" title="紧急权限模板" width="720px">
      <el-alert type="info" :closable="false" style="margin-bottom: 10px"
        title="模板为最小可用角色集合，禁止包含特权角色（ROLE_ADMIN / 审批节点角色等）。" />
      <el-table :data="templates" border size="small">
        <el-table-column prop="code" label="编码" width="180" />
        <el-table-column prop="name" label="名称" width="160" />
        <el-table-column label="角色集合" min-width="180">
          <template #default="{ row }">{{ fmtRoles(row.rolesJson) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === '1' ? 'success' : 'danger'" size="small">
              {{ row.status === '1' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 详情/签署对话框 -->
    <el-dialog v-model="detailDlg" :title="'Break-Glass 申请详情'" width="760px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="被授权人">{{ detail.targetUsername }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ stLabel(detail.request.status) }}</el-descriptions-item>
          <el-descriptions-item label="时长">{{ detail.request.durationHours }} 小时</el-descriptions-item>
          <el-descriptions-item label="录屏声明">{{ detail.request.screenDeclare === 'YES' ? '需要' : '不需要' }}</el-descriptions-item>
          <el-descriptions-item label="处置说明" :span="2">{{ detail.request.reason }}</el-descriptions-item>
        </el-descriptions>
        <h4>审批链（双人会签）</h4>
        <el-table :data="logTasks" border size="small">
          <el-table-column prop="nodeName" label="节点" width="200" />
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              <el-tag :type="taskType(row.status)" size="small">{{ taskLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="signerName" label="签署人" width="120">
            <template #default="{ row }">{{ row.signerName || row.signer || '—' }}</template>
          </el-table-column>
          <el-table-column prop="opinion" label="意见" min-width="140">
            <template #default="{ row }">{{ row.opinion || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <template v-if="row.status === 'ACTIVE'">
                <el-button link type="success" @click="signTask(row, true)">通过</el-button>
                <el-button link type="danger" @click="signTask(row, false)">驳回</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/break-glass'
import { accountPage } from '@/api/system/account'

const route = useRoute()

// Tab 定位（沿 16.1~16.4 模式）
const TAB_WHITELIST = ['requests', 'approvals', 'audits']
const pickTab = () => {
  const m = route.meta?.tab
  return TAB_WHITELIST.includes(m) ? m : 'requests'
}
const tab = ref(pickTab())
watch(() => route.meta?.tab, () => { tab.value = pickTab() })

// ---------- 公共数据 ----------
const users = ref([])
const incidents = ref([])
const templates = ref([])
const maxHours = 2 // BREAK_GLASS_MAX_HOURS 默认值展示口径

const processingIncidents = computed(() => incidents.value.filter((i) => i.status === 'PROCESSING'))
const activeTemplates = computed(() => templates.value.filter((t) => t.status === '1'))

// ---------- Tab1 紧急申请 ----------
const requests = ref([])
const reqLoading = ref(false)
const reqQuery = ref({ status: '' })
const createDlg = ref(false)
const incidentDlg = ref(false)
const incidentCreateDlg = ref(false)
const templateDlg = ref(false)
const detailDlg = ref(false)
const detail = ref(null)
const submitting = ref(false)
const form = ref(blankForm())
const incForm = ref({ incNo: '', title: '' })

function blankForm() {
  return { targetUserId: '', templateId: '', incidentId: '', durationHours: 2,
    screenDeclare: 'YES', reason: '' }
}

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

async function loadRequests() {
  reqLoading.value = true
  try {
    const { data } = await api.bgRequestList({ status: reqQuery.value.status || undefined })
    requests.value = data || []
  } finally {
    reqLoading.value = false
  }
}

function openCreate() {
  form.value = blankForm()
  createDlg.value = true
}

async function submitCreate() {
  const f = form.value
  if (!f.targetUserId || !f.templateId || !f.incidentId || !f.reason) {
    ElMessage.error('被授权人 / 工单 / 模板 / 处置说明必填')
    return
  }
  if (f.reason.trim().length < 10) {
    ElMessage.error('处置说明不少于 10 字')
    return
  }
  submitting.value = true
  try {
    await api.bgSubmit(f)
    ElMessage.success('已提交，进入双人会签（值班经理 + 安全管理员）')
    createDlg.value = false
    loadRequests()
  } finally {
    submitting.value = false
  }
}

async function doReclaim(row) {
  await ElMessageBox.confirm('确认提前归还紧急权限？角色将立即回收并生成事后审计报告。', '提前归还',
    { type: 'warning' })
  await api.bgReclaim(row.id)
  ElMessage.success('已归还，审计报告已生成')
  loadRequests()
  if (tab.value === 'audits') loadAudits()
}

// ---------- Tab2 双人审批 ----------
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

// ---------- Tab3 事后审计 ----------
const audits = ref([])
const audLoading = ref(false)

async function loadAudits() {
  audLoading.value = true
  try {
    const { data } = await api.auditList()
    audits.value = data || []
  } finally {
    audLoading.value = false
  }
}

async function sign(row, conclusion) {
  const label = conclusion === 'NO_VIOLATION' ? '无越权' : '有越权待查'
  await ElMessageBox.confirm(`确认签署结论「${label}」？签署后不可更改。`, '签署审计结论',
    { type: conclusion === 'NO_VIOLATION' ? 'success' : 'warning' })
  await api.signAudit(row.id, conclusion)
  ElMessage.success('已签署')
  loadAudits()
}

async function doSweep() {
  const { data } = await api.sweepAuditOverdue()
  ElMessage.success(`扫描完成，本次提醒 ${data.reminded} 条`)
  loadAudits()
}

// ---------- 工单/模板管理 ----------
async function saveIncident() {
  if (!incForm.value.incNo || !incForm.value.title) {
    ElMessage.error('编号与标题必填')
    return
  }
  await api.incidentSave(incForm.value)
  ElMessage.success('工单已创建（处理中）')
  incidentCreateDlg.value = false
  incForm.value = { incNo: '', title: '' }
  const { data } = await api.incidentList()
  incidents.value = data || []
}

async function transition(row, status) {
  await api.incidentTransition(row.id, status)
  ElMessage.success('已流转')
  const { data } = await api.incidentList()
  incidents.value = data || []
}

// ---------- 详情与签署 ----------
async function openDetail(row) {
  const { data } = await api.bgDetail(row.id)
  detail.value = data
  detailDlg.value = true
}

const logTasks = computed(() => (detail.value && detail.value.approvalLogs
  && detail.value.approvalLogs.tasks) || [])

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

// ---------- 展示辅助 ----------
function fmtRoles(json) {
  try {
    return (JSON.parse(json || '[]')).join('、')
  } catch (e) {
    return json
  }
}
function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}
function stType(s) {
  return ({ PENDING_APPROVAL: 'warning', ACTIVE: 'success', RECLAIMED: 'info',
    REJECTED: 'danger', AUDITED: 'success' })[s] || 'info'
}
function stLabel(s) {
  return ({ PENDING_APPROVAL: '审批中', ACTIVE: '已生效', RECLAIMED: '已回收',
    REJECTED: '已驳回', AUDITED: '已审计' })[s] || s
}
function incLabel(s) {
  return ({ PROCESSING: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' })[s] || s
}
function taskType(s) {
  return s === 'PASSED' ? 'success' : s === 'ACTIVE' ? 'warning'
    : s === 'REJECTED' ? 'danger' : 'info'
}
function taskLabel(s) {
  return ({ ACTIVE: '待签', PASSED: '已签', REJECTED: '驳回', PENDING: '未到',
    SKIPPED: '跳过' })[s] || s
}

// ---------- 挂载 ----------
watch(tab, (t) => {
  if (t === 'approvals') loadPending()
  if (t === 'audits') loadAudits()
})

onMounted(async () => {
  await loadCommon()
  loadRequests()
  if (tab.value === 'approvals') loadPending()
  if (tab.value === 'audits') loadAudits()
})
</script>

<style scoped>
.bg-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.muted { color: #909399; font-size: 12px; }
h4 { margin: 14px 0 8px; }
</style>
