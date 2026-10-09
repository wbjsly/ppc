<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="11.11.1 合同签订"
              description="从商机生成合同草稿（自动带出名称、客户与预期金额，CT_NO 生成后锁定）；合同金额与商机预期差异 >10% 走 L2 审批（销售经理→销售总监）后进法务审核；法务审核占位：审核人与意见必填，驳回退回修改并注明原因（FR-4.8-1-7）。" />

    <el-row :gutter="12" class="mb12">
      <el-col :span="6" v-for="k in kpis" :key="k.label">
        <div class="kpi-box">
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value" :style="{ color: k.color || '#303133' }">{{ k.value }}</div>
        </div>
      </el-col>
    </el-row>

    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <b>合同列表</b>
          <div>
            <el-input v-model="keyword" placeholder="编号/客户/名称" size="small"
                      style="width:190px" clearable @change="load" />
            <el-select v-model="filterStatus" clearable placeholder="状态" size="small"
                       style="width:150px;margin-left:8px" @change="load">
              <el-option label="草稿 DRAFT" value="DRAFT" />
              <el-option label="审批中 APPROVING" value="APPROVING" />
              <el-option label="法务审核 LEGAL_REVIEW" value="LEGAL_REVIEW" />
              <el-option label="已签订 SIGNED" value="SIGNED" />
              <el-option label="被驳回 REJECTED" value="REJECTED" />
            </el-select>
            <el-button size="small" type="primary" style="margin-left:8px"
                       @click="openCreate">新建合同</el-button>
            <el-button size="small" @click="load">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table :data="list" size="small" border @row-click="openDetail" highlight-current-row
                v-loading="loading">
        <el-table-column prop="contractNo" label="合同编号" width="170" />
        <el-table-column prop="customerName" label="客户" min-width="140" show-overflow-tooltip />
        <el-table-column prop="title" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="金额" width="120" align="right">
          <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="商机差异" width="110" align="right">
          <template #default="{ row }">
            <span v-if="row.diffRate != null"
                  :style="{ color: Math.abs(Number(row.diffRate)) > 0.1 ? '#f56c6c' : '#67c23a' }">
              {{ (Number(row.diffRate) * 100).toFixed(1) }}%
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="stTag(row.status)">{{ stName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="SO 数" width="70" align="center">
          <template #default="{ row }">{{ row.soCount }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button size="small" @click.stop="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawerVisible" size="720px"
               :title="current ? `合同 ${current.contractNo}` : '详情'">
      <template v-if="current">
        <el-steps :active="stepActive" simple class="mb12" finish-status="success">
          <el-step title="草稿" />
          <el-step title="差异审批" />
          <el-step title="法务审核" />
          <el-step title="已签订" />
        </el-steps>

        <el-descriptions :column="3" size="small" border class="mb12">
          <el-descriptions-item label="编号">
            <b>{{ current.contractNo }}</b>（锁定）
          </el-descriptions-item>
          <el-descriptions-item label="客户">{{ current.customerName }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="stTag(current.status)">{{ stName(current.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="合同金额">¥{{ fmt(current.amount) }}</el-descriptions-item>
          <el-descriptions-item label="商机预期">
            {{ current.oppAmount == null ? '—' : '¥' + fmt(current.oppAmount) }}
          </el-descriptions-item>
          <el-descriptions-item label="差异率">
            <span :style="{ color: needL2 ? '#f56c6c' : '#67c23a' }">
              {{ current.diffRate == null ? '—'
                : (Number(current.diffRate) * 100).toFixed(2) + '%' }}
            </span>
            <el-tag v-if="needL2" size="small" type="danger">超 10% → L2</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="期间" :span="2">
            {{ current.startDate || '—' }} ~ {{ current.endDate || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="商机">
            {{ current.oppName || '手工创建' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="current.legalReviewBy" label="法务审核" :span="3">
            {{ current.legalReviewBy }}：{{ current.legalReviewOpinion }}
            <span v-if="current.legalReviewAt">
              （{{ (current.legalReviewAt || '').replace('T', ' ').slice(0, 16) }}）
            </span>
          </el-descriptions-item>
          <el-descriptions-item v-if="current.signedBy" label="签订" :span="3">
            {{ current.signedBy }} @ {{ (current.signedAt || '').replace('T', ' ').slice(0, 16) }}
          </el-descriptions-item>
          <el-descriptions-item label="名下 SO" :span="3">
            <el-tag v-for="s in sos" :key="s.id" size="small" class="mr4">{{ s.soNo }}</el-tag>
            <span v-if="!sos.length">—</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="card-head mb12">
          <h4 style="margin:0">操作</h4>
          <div>
            <el-button v-if="canEdit" size="small" @click="openEdit">编辑</el-button>
            <el-button v-if="canEdit" size="small" type="primary"
                       @click="doSubmit">提交（差异超 10% 走 L2）</el-button>
          </div>
        </div>

        <!-- 法务审核 -->
        <el-card v-if="current.status === 'LEGAL_REVIEW'" shadow="never" class="mb12">
          <template #header><b>法务审核（占位登记）</b></template>
          <el-form label-width="90px" size="small">
            <el-form-item label="审核人" required>
              <el-input v-model="legalForm.reviewer" placeholder="必填" />
            </el-form-item>
            <el-form-item label="审核意见" required>
              <el-input v-model="legalForm.opinion" type="textarea" :rows="2"
                        placeholder="必填（≥2 字）；驳回将退回修改并注明原因" />
            </el-form-item>
            <el-form-item>
              <el-button type="success" @click="doLegal(true)">审核通过（签订）</el-button>
              <el-button type="danger" @click="doLegal(false)">驳回退回</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 差异审批待办 -->
        <div v-if="current.status === 'APPROVING'" class="mb12">
          <h4>差异 L2 审批（销售经理 → 销售总监）</h4>
          <div v-for="t in myTasks" :key="t.taskId" class="todo-item">
            <div class="todo-meta">[{{ t.nodeName }}] {{ t.title }}</div>
            <div class="todo-op">
              <el-input v-model="opinions[t.taskId]" size="small" style="flex:1"
                        placeholder="审批意见必填（≥2 字）" />
              <el-button size="small" type="success" @click="doPass(t)">通过</el-button>
              <el-button size="small" type="danger" @click="doRejectTask(t)">驳回</el-button>
            </div>
          </div>
          <el-alert v-if="!myTasks.length" type="info" :closable="false" show-icon
                    title="等待对应角色审批" />
        </div>
      </template>
    </el-drawer>

    <!-- 新建（商机带出 / 手工） -->
    <el-dialog v-model="createVisible" title="新建合同草稿" width="560px">
      <el-form label-width="100px" size="small">
        <el-form-item label="来源商机">
          <el-select v-model="createForm.oppId" clearable filterable style="width:100%"
                     placeholder="选择商机（自动带出客户与预期金额）" @change="onOpp">
            <el-option v-for="o in opps" :key="o.id"
                       :label="`${o.oppName}（预期 ¥${fmt(o.expectAmount)}）`" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="客户">
          <el-select v-model="createForm.customerId" filterable :disabled="!!createForm.oppId"
                     style="width:100%">
            <el-option v-for="c in customers" :key="c.id"
                       :label="`${c.customerCode} ${c.customerName}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="合同名称" required>
          <el-input v-model="createForm.title" />
        </el-form-item>
        <el-form-item label="合同金额" required>
          <el-input-number v-model="createForm.amount" :min="0" :precision="2"
                           style="width:100%" />
          <span v-if="previewDiff !== null" class="hint">
            商机预期 ¥{{ fmt(createForm.oppAmount) }}，差异
            <span :style="{ color: Math.abs(previewDiff) > 0.1 ? '#f56c6c' : '#67c23a' }">
              {{ (previewDiff * 100).toFixed(2) }}%</span>
            {{ Math.abs(previewDiff) > 0.1 ? '（>10%，提交将走 L2 审批）' : '（≤10%，直接法务审核）' }}
          </span>
        </el-form-item>
        <el-form-item label="期间">
          <el-date-picker v-model="createForm.startDate" type="date" value-format="YYYY-MM-DD"
                          placeholder="开始" style="width:45%" />
          <span style="margin:0 6px">~</span>
          <el-date-picker v-model="createForm.endDate" type="date" value-format="YYYY-MM-DD"
                          placeholder="结束" style="width:45%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreate">创建草稿</el-button>
      </template>
    </el-dialog>

    <!-- 编辑 -->
    <el-dialog v-model="editVisible" title="编辑合同草稿" width="520px">
      <el-form label-width="90px" size="small">
        <el-form-item label="编号">
          <el-input :model-value="current && current.contractNo" disabled />
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="editForm.title" /></el-form-item>
        <el-form-item label="金额">
          <el-input-number v-model="editForm.amount" :min="0" :precision="2"
                           style="width:100%" />
        </el-form-item>
        <el-form-item label="期间">
          <el-date-picker v-model="editForm.startDate" type="date" value-format="YYYY-MM-DD"
                          style="width:45%" />
          <span style="margin:0 6px">~</span>
          <el-date-picker v-model="editForm.endDate" type="date" value-format="YYYY-MM-DD"
                          style="width:45%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getContractsApi, getContractDetailApi, createContractApi, updateContractApi,
  submitContractApi, legalReviewApi
} from '@/api/crm/contract'
import { getOpportunitiesApi } from '@/api/crm/opportunity'
import { getGroupPageApi } from '@/api/mdm/customer'
import {
  getQmsApprovalTodoApi, passQmsApprovalApi, rejectQmsApprovalApi
} from '@/api/qms/approval'

const list = ref([])
const loading = ref(false)
const saving = ref(false)
const keyword = ref('')
const filterStatus = ref('')
const opps = ref([])
const customers = ref([])
const drawerVisible = ref(false)
const current = ref(null)
const sos = ref([])
const allTodos = ref([])
const opinions = ref({})
const createVisible = ref(false)
const editVisible = ref(false)

const createForm = reactive({
  oppId: '', customerId: '', title: '', amount: 0, oppAmount: null,
  startDate: '', endDate: ''
})
const editForm = reactive({ title: '', amount: 0, startDate: '', endDate: '' })
const legalForm = reactive({ reviewer: '', opinion: '' })

const needL2 = computed(() => current.value && current.value.diffRate != null
  && Math.abs(Number(current.value.diffRate)) > 0.1)
const canEdit = computed(() => current.value
  && ['DRAFT', 'REJECTED'].includes(current.value.status))
const stepActive = computed(() => ({
  DRAFT: 0, REJECTED: 0, APPROVING: 1, LEGAL_REVIEW: 2, SIGNED: 3
})[current.value ? current.value.status : 'DRAFT'] ?? 0)
const previewDiff = computed(() => {
  if (!createForm.oppAmount || !createForm.amount) return null
  return (Number(createForm.amount) - Number(createForm.oppAmount))
    / Number(createForm.oppAmount)
})
const myTasks = computed(() => allTodos.value.filter(t =>
  t.bizType === 'Contract' && current.value && t.bizId === current.value.id))

const kpis = computed(() => {
  const signed = list.value.filter(c => c.status === 'SIGNED').length
  const legal = list.value.filter(c => c.status === 'LEGAL_REVIEW').length
  const approving = list.value.filter(c => c.status === 'APPROVING').length
  const amount = list.value.filter(c => c.status === 'SIGNED')
    .reduce((s, c) => s + Number(c.amount || 0), 0)
  return [
    { label: '已签订', value: signed, color: '#67c23a' },
    { label: '待法务审核', value: legal, color: '#e6a23c' },
    { label: '审批中', value: approving, color: '#409eff' },
    { label: '签订金额', value: `¥${fmt(amount)}`, color: '#303133' }
  ]
})

function fmt(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function stName(s) {
  return ({ DRAFT: '草稿', APPROVING: '审批中', LEGAL_REVIEW: '法务审核',
    SIGNED: '已签订', REJECTED: '被驳回', CHANGED: '已变更', TERMINATED: '已终止' })[s] || s
}
function stTag(s) {
  return ({ DRAFT: 'info', APPROVING: 'warning', LEGAL_REVIEW: 'warning',
    SIGNED: 'success', REJECTED: 'danger', TERMINATED: 'info' })[s] || 'info'
}

async function load() {
  loading.value = true
  try {
    const res = await getContractsApi({
      current: 1, size: 100,
      keyword: keyword.value || undefined,
      status: filterStatus.value || undefined
    })
    list.value = res.data.records || res.data || []
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  const res = await getContractDetailApi(row.id)
  const d = res.data || {}
  current.value = d.contract
  sos.value = d.sos || []
  drawerVisible.value = true
  loadTodo()
}

async function loadTodo() {
  try {
    const res = await getQmsApprovalTodoApi()
    allTodos.value = res.data || []
  } catch (e) {
    allTodos.value = []
  }
}

async function openCreate() {
  createForm.oppId = ''
  createForm.customerId = ''
  createForm.title = ''
  createForm.amount = 0
  createForm.oppAmount = null
  createForm.startDate = new Date().toISOString().slice(0, 10)
  createForm.endDate = ''
  createVisible.value = true
}

function onOpp(oppId) {
  const o = opps.value.find(x => x.id === oppId)
  if (o) {
    createForm.customerId = o.customerId
    createForm.title = o.oppName
    createForm.oppAmount = Number(o.expectAmount || 0)
  } else {
    createForm.oppAmount = null
  }
}

async function doCreate() {
  if (!createForm.title) return ElMessage.warning('合同名称必填')
  if (!createForm.oppId && !createForm.customerId) return ElMessage.warning('请选择商机或客户')
  saving.value = true
  try {
    const res = await createContractApi(createForm)
    ElMessage.success('草稿已生成：' + res.data.contractNo + '（编号锁定）')
    createVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

function openEdit() {
  editForm.title = current.value.title
  editForm.amount = Number(current.value.amount)
  editForm.startDate = current.value.startDate
  editForm.endDate = current.value.endDate
  editVisible.value = true
}

async function doEdit() {
  saving.value = true
  try {
    await updateContractApi(current.value.id, editForm)
    ElMessage.success('已保存（差异率已重算）')
    editVisible.value = false
    openDetail({ id: current.value.id })
    load()
  } finally {
    saving.value = false
  }
}

async function doSubmit() {
  await submitContractApi(current.value.id)
  ElMessage.success(needL2.value ? '已提交 L2 审批（销售经理→销售总监）'
    : '差异 ≤10%，已直接进入法务审核')
  openDetail({ id: current.value.id })
  load()
}

async function doLegal(pass) {
  if (!legalForm.reviewer.trim()) return ElMessage.warning('审核人必填')
  if (legalForm.opinion.trim().length < 2) return ElMessage.warning('审核意见必填')
  await legalReviewApi(current.value.id, { pass, ...legalForm })
  ElMessage.success(pass ? '审核通过，合同已签订' : '已驳回，退回修改')
  legalForm.reviewer = ''
  legalForm.opinion = ''
  openDetail({ id: current.value.id })
  load()
}

async function doPass(t) {
  const op = (opinions.value[t.taskId] || '').trim()
  if (op.length < 2) return ElMessage.warning('审批意见必填（≥2 字）')
  await passQmsApprovalApi(t.taskId, op)
  ElMessage.success('已通过')
  opinions.value[t.taskId] = ''
  openDetail({ id: current.value.id })
  loadTodo()
  load()
}

async function doRejectTask(t) {
  const op = (opinions.value[t.taskId] || '').trim()
  if (op.length < 2) return ElMessage.warning('驳回意见必填（≥2 字）')
  await rejectQmsApprovalApi(t.taskId, op)
  ElMessage.success('已驳回，退回修改')
  openDetail({ id: current.value.id })
  loadTodo()
  load()
}

onMounted(async () => {
  load()
  const o = await getOpportunitiesApi({ current: 1, size: 200 })
  opps.value = o.data.records || o.data || []
  const c = await getGroupPageApi({ current: 1, size: 200 })
  customers.value = c.data.records || c.data || []
})
</script>

<style scoped>
.page-wrap { padding: 12px; }
.mb12 { margin-bottom: 12px; }
.mr4 { margin-right: 4px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.kpi-box { background: #f5f7fa; border-radius: 4px; padding: 10px 12px; }
.kpi-label { font-size: 12px; color: #909399; margin-bottom: 4px; }
.kpi-value { font-size: 18px; font-weight: 600; }
.todo-item { border: 1px solid #ebeef5; border-radius: 4px; padding: 10px; margin-bottom: 8px; }
.todo-meta { font-size: 12px; color: #606266; margin-bottom: 6px; }
.todo-op { display: flex; gap: 8px; }
.hint { font-size: 12px; color: #909399; display: block; margin-top: 4px; }
</style>
