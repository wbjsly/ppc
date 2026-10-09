<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="11.11.3 合同变更（新版本 + 销售经理审批）"
              description="已签订合同支持金额/交期/名称变更：生成新版本快照、历史版本只读可查；变更经销售经理审批，金额差异扩大（>10%）自动升级销售总监 L2；变更生效后重算差异率，并提示受影响的收款计划期次待调整（14.6）。" />

    <el-row :gutter="12" class="mb12">
      <el-col :span="6" v-for="k in kpis" :key="k.label">
        <div class="kpi-box">
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value" :style="{ color: k.color || '#303133' }">{{ k.value }}</div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="12">
      <!-- 已签订合同列表 -->
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <b>已签订合同（可变更）</b>
              <el-button size="small" @click="load">刷新</el-button>
            </div>
          </template>
          <div v-for="c in signed" :key="c.id" class="queue-item"
               :class="{ active: current && current.id === c.id }" @click="open(c)">
            <div class="q-head">
              <b>{{ c.contractNo }}</b>
              <el-tag v-if="c.approvalId" size="small" type="warning">变更审批中</el-tag>
              <el-tag v-else size="small" type="success">SIGNED</el-tag>
            </div>
            <div class="q-meta">
              {{ c.customerName }} ｜ ¥{{ fmt(c.amount) }} ｜ 差异
              {{ c.diffRate == null ? '—' : (Number(c.diffRate) * 100).toFixed(1) + '%' }}
              ｜ 变更 {{ c.changeCount || 0 }} 次
            </div>
          </div>
          <el-empty v-if="!signed.length" description="暂无已签订合同" :image-size="60" />
        </el-card>
      </el-col>

      <!-- 变更区 -->
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <b>{{ current ? `变更：${current.contractNo}` : '选择左侧合同发起变更' }}</b>
              <el-button size="small" type="primary" :disabled="!canChange"
                         @click="changeVisible = true">发起变更</el-button>
            </div>
          </template>

          <template v-if="current">
            <el-descriptions :column="3" size="small" border class="mb12">
              <el-descriptions-item label="名称" :span="2">{{ current.title }}</el-descriptions-item>
              <el-descriptions-item label="版本">v{{ current.versionNo }}</el-descriptions-item>
              <el-descriptions-item label="金额">¥{{ fmt(current.amount) }}</el-descriptions-item>
              <el-descriptions-item label="商机预期">
                {{ current.oppAmount == null ? '—' : '¥' + fmt(current.oppAmount) }}
              </el-descriptions-item>
              <el-descriptions-item label="差异率">
                <span :style="{ color: Math.abs(Number(current.diffRate || 0)) > 0.1
                  ? '#f56c6c' : '#67c23a' }">
                  {{ current.diffRate == null ? '—'
                    : (Number(current.diffRate) * 100).toFixed(2) + '%' }}
                </span>
              </el-descriptions-item>
              <el-descriptions-item label="期间" :span="2">
                {{ current.startDate || '—' }} ~ {{ current.endDate || '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="收款计划">
                <el-tag size="small" :type="adjust && adjust.needed ? 'warning' : 'success'">
                  {{ adjust && adjust.needed
                    ? `待调整（差额 ¥${fmt(adjust.diff)}）` : '与合同额一致' }}
                </el-tag>
              </el-descriptions-item>
            </el-descriptions>

            <!-- 变更审批待办 -->
            <div v-if="current.status === 'APPROVING'" class="mb12">
              <h4>变更审批（{{ needL2 ? '销售经理 → 销售总监 L2' : '销售经理' }}）</h4>
              <div v-for="t in myTasks" :key="t.taskId" class="todo-item">
                <div class="todo-meta">[{{ t.nodeName }}] {{ t.title }}</div>
                <div class="todo-op">
                  <el-input v-model="opinions[t.taskId]" size="small" style="flex:1"
                            placeholder="审批意见必填（≥2 字）" />
                  <el-button size="small" type="success" @click="doPass(t)">通过</el-button>
                  <el-button size="small" type="danger" @click="doReject(t)">驳回</el-button>
                </div>
              </div>
              <el-alert v-if="!myTasks.length" type="info" :closable="false" show-icon
                        title="等待对应角色审批" />
            </div>

            <h4>版本历史（只读）</h4>
            <el-table :data="versions" size="small" border>
              <el-table-column label="版本" width="70" align="center">
                <template #default="{ row }">v{{ row.version.versionNo }}</template>
              </el-table-column>
              <el-table-column label="类型" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.version.opType === 'CREATE' ? 'info' : 'primary'">
                    {{ row.version.opType === 'CREATE' ? '创建' : '变更' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="金额" width="120" align="right">
                <template #default="{ row }">
                  {{ row.snapshot && row.snapshot.amount != null
                    ? '¥' + fmt(row.snapshot.amount) : '—' }}
                </template>
              </el-table-column>
              <el-table-column label="变更说明" min-width="170" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ row.version.diffSummary || row.version.changeReason || '—' }}
                </template>
              </el-table-column>
              <el-table-column label="状态" width="95">
                <template #default="{ row }">
                  <el-tag size="small" :type="vTag(row.version.status)">
                    {{ vName(row.version.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="时间" width="140">
                <template #default="{ row }">
                  {{ (row.version.createDate || '').replace('T', ' ').slice(0, 16) }}
                </template>
              </el-table-column>
            </el-table>
          </template>
          <el-empty v-else description="请选择合同" :image-size="70" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 变更弹窗 -->
    <el-dialog v-model="changeVisible" title="发起合同变更" width="560px">
      <el-form label-width="92px" size="small">
        <el-form-item label="合同名称"><el-input v-model="changeForm.title" /></el-form-item>
        <el-form-item label="合同金额">
          <el-input-number v-model="changeForm.amount" :min="0" :precision="2"
                           style="width:100%" />
          <span v-if="newDiff !== null" class="hint">
            新差异率
            <span :style="{ color: Math.abs(newDiff) > 0.1 ? '#f56c6c' : '#67c23a' }">
              {{ (newDiff * 100).toFixed(2) }}%</span>
            {{ Math.abs(newDiff) > 0.1 ? '（>10%，将升级销售总监 L2）' : '（销售经理单节点）' }}
          </span>
        </el-form-item>
        <el-form-item label="期间">
          <el-date-picker v-model="changeForm.startDate" type="date" value-format="YYYY-MM-DD"
                          style="width:45%" />
          <span style="margin:0 6px">~</span>
          <el-date-picker v-model="changeForm.endDate" type="date" value-format="YYYY-MM-DD"
                          style="width:45%" />
        </el-form-item>
        <el-form-item label="变更原因" required>
          <el-input v-model="changeForm.reason" type="textarea" :rows="2"
                    placeholder="必填（≥2 字，14.6 留痕）" />
        </el-form-item>
        <el-alert type="warning" :closable="false" show-icon
                  title="通过后生成新版本（历史只读）、重算差异率，并提示收款计划待调整" />
      </el-form>
      <template #footer>
        <el-button @click="changeVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doChange">提交审批</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getContractsApi, getContractDetailApi, changeContractApi
} from '@/api/crm/contract'
import {
  getQmsApprovalTodoApi, passQmsApprovalApi, rejectQmsApprovalApi
} from '@/api/qms/approval'

const list = ref([])
const current = ref(null)
const versions = ref([])
const adjust = ref(null)
const allTodos = ref([])
const opinions = ref({})
const changeVisible = ref(false)
const saving = ref(false)

const changeForm = reactive({ title: '', amount: 0, startDate: '', endDate: '', reason: '' })

const signed = computed(() => list.value.filter(c =>
  ['SIGNED', 'APPROVING'].includes(c.status)))
const canChange = computed(() => current.value && current.value.status === 'SIGNED')
const needL2 = computed(() => newDiff.value !== null
  && Math.abs(newDiff.value) > 0.1)
const newDiff = computed(() => {
  if (!current.value || !current.value.oppAmount) return null
  return (Number(changeForm.amount) - Number(current.value.oppAmount))
    / Number(current.value.oppAmount)
})
const myTasks = computed(() => allTodos.value.filter(t =>
  t.bizType === 'ContractChange' && current.value && versions.value.length
  && t.bizId === (versions.value.find(v => v.version.status === 'APPROVING') || {})
    .version?.id))

const kpis = computed(() => {
  const pending = list.value.filter(c => c.status === 'APPROVING').length
  const total = list.value.reduce((s, c) => s + Number(c.changeCount || 0), 0)
  const verCnt = versions.value.length
  return [
    { label: '可变更合同', value: signed.value.length, color: '#67c23a' },
    { label: '变更审批中', value: pending, color: '#e6a23c' },
    { label: '累计变更次数', value: total, color: '#409eff' },
    { label: '当前合同版本数', value: verCnt }
  ]
})

function fmt(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function vName(s) {
  return ({ APPROVING: '审批中', APPROVED: '已生效', REJECTED: '已驳回' })[s] || s
}
function vTag(s) {
  return ({ APPROVING: 'warning', APPROVED: 'success', REJECTED: 'danger' })[s] || 'info'
}

async function load() {
  const res = await getContractsApi({ current: 1, size: 100 })
  list.value = res.data.records || res.data || []
}

async function open(c) {
  const d = (await getContractDetailApi(c.id)).data || {}
  current.value = d.contract
  versions.value = d.versions || []
  adjust.value = d.planAdjust || null
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

async function doChange() {
  if (changeForm.reason.trim().length < 2) return ElMessage.warning('变更原因必填')
  saving.value = true
  try {
    await changeContractApi(current.value.id, { ...changeForm,
      amount: changeForm.amount || undefined })
    ElMessage.success(needL2.value ? '已提交（差异 >10%，销售经理→销售总监 L2）'
      : '已提交（销售经理审批）')
    changeVisible.value = false
    open({ id: current.value.id })
    load()
  } finally {
    saving.value = false
  }
}

async function doPass(t) {
  const op = (opinions.value[t.taskId] || '').trim()
  if (op.length < 2) return ElMessage.warning('审批意见必填（≥2 字）')
  await passQmsApprovalApi(t.taskId, op)
  ElMessage.success('已通过，变更生效（新版本）')
  opinions.value[t.taskId] = ''
  open({ id: current.value.id })
  loadTodo()
  load()
}

async function doReject(t) {
  const op = (opinions.value[t.taskId] || '').trim()
  if (op.length < 2) return ElMessage.warning('驳回意见必填（≥2 字）')
  await rejectQmsApprovalApi(t.taskId, op)
  ElMessage.success('已驳回，合同维持原版本')
  open({ id: current.value.id })
  loadTodo()
  load()
}

onMounted(load)
</script>

<style scoped>
.page-wrap { padding: 12px; }
.mb12 { margin-bottom: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.kpi-box { background: #f5f7fa; border-radius: 4px; padding: 10px 12px; }
.kpi-label { font-size: 12px; color: #909399; margin-bottom: 4px; }
.kpi-value { font-size: 18px; font-weight: 600; }
.queue-item { border: 1px solid #ebeef5; border-radius: 4px; padding: 8px 10px;
  margin-bottom: 8px; cursor: pointer; }
.queue-item:hover { border-color: #c6e2ff; }
.queue-item.active { border-color: #409eff; background: #f5faff; }
.q-head { display: flex; gap: 6px; align-items: center; margin-bottom: 4px; }
.q-meta { font-size: 12px; color: #909399; }
.todo-item { border: 1px solid #ebeef5; border-radius: 4px; padding: 10px; margin-bottom: 8px; }
.todo-meta { font-size: 12px; color: #606266; margin-bottom: 6px; }
.todo-op { display: flex; gap: 8px; }
.hint { font-size: 12px; color: #909399; display: block; margin-top: 4px; }
</style>
