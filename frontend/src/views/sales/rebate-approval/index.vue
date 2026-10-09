<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.9.2 返利审批"
              description="正常额度由销售主管单节点审批；超季度预算自动升级销售总监（L2，C-4.3-05），审批人须关注达成率真实性与返利金额合理性，审批意见必填；驳回 → 单据退回发起人调整返利方案后重新提交（FR-4.3-8-4）。" />

    <el-row :gutter="12" class="mb12">
      <el-col :span="4" v-for="k in kpis" :key="k.label">
        <div class="kpi-box">
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value" :style="{ color: k.color || '#303133' }">{{ k.value }}</div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="12">
      <!-- 待我审批 -->
      <el-col :span="9">
        <el-card shadow="never" style="height:100%">
          <template #header>
            <div class="card-head">
              <b>待我审批（返利结算）</b>
              <el-button size="small" @click="loadTodo">刷新</el-button>
            </div>
          </template>
          <el-empty v-if="!myTasks.length" description="暂无待办" :image-size="70" />
          <div v-for="t in myTasks" :key="t.taskId" class="todo-item">
            <div class="todo-head">
              <el-tag size="small" :type="t.overBudget ? 'danger' : 'primary'">
                {{ t.overBudget ? '超预算·升级总监' : '销售主管审批' }}
              </el-tag>
              <b>{{ t.title }}</b>
            </div>
            <div class="todo-meta">{{ t.bizLabel }} ｜ 节点 {{ t.nodeName || t.seqNo }}</div>
            <div class="todo-op">
              <el-input v-model="opinions[t.taskId]" size="small" style="flex:1"
                        placeholder="审批意见必填（≥2 字）" />
              <el-button size="small" type="success" @click="doPass(t)">通过</el-button>
              <el-button size="small" type="danger" @click="doReject(t)">驳回</el-button>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 结算单列表 -->
      <el-col :span="15">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <b>返利结算单</b>
              <div>
                <el-select v-model="filterStatus" clearable placeholder="状态" size="small"
                           style="width:150px" @change="load">
                  <el-option label="草稿 DRAFT" value="DRAFT" />
                  <el-option label="审批中 APPROVING" value="APPROVING" />
                  <el-option label="已通过 APPROVED" value="APPROVED" />
                  <el-option label="已驳回 REJECTED" value="REJECTED" />
                  <el-option label="已执行 EXECUTED" value="EXECUTED" />
                </el-select>
                <el-button size="small" style="margin-left:8px" @click="load">刷新</el-button>
              </div>
            </div>
          </template>
          <el-table :data="rows" size="small" border @row-click="openDetail" highlight-current-row
                    v-loading="loading">
            <el-table-column prop="settleNo" label="结算单号" width="170" />
            <el-table-column prop="customerName" label="客户" min-width="140" show-overflow-tooltip />
            <el-table-column prop="quarter" label="季度" width="90" />
            <el-table-column label="达成率" width="90" align="right">
              <template #default="{ row }">{{ (Number(row.achieveRate) * 100).toFixed(1) }}%</template>
            </el-table-column>
            <el-table-column label="返利金额" width="120" align="right">
              <template #default="{ row }">¥{{ fmt(row.rebateAmt) }}</template>
            </el-table-column>
            <el-table-column label="预算标记" width="120">
              <template #default="{ row }">
                <el-tag v-if="row.overBudget === '1'" size="small" type="danger">超预算</el-tag>
                <el-tag v-else-if="row.pendingData === '1'" size="small" type="warning">待数据</el-tag>
                <el-tag v-else size="small" type="success">预算内</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="statusTag(row.status)">{{ statusName(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button size="small" @click.stop="openDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawerVisible" size="620px" :title="detail.settlement ? `返利结算单 ${detail.settlement.settleNo}` : '详情'">
      <template v-if="detail.settlement">
        <el-descriptions :column="2" size="small" border class="mb12">
          <el-descriptions-item label="客户">{{ detail.settlement.customerName }}</el-descriptions-item>
          <el-descriptions-item label="季度">{{ detail.settlement.quarter }}</el-descriptions-item>
          <el-descriptions-item label="季度目标">¥{{ fmt(detail.settlement.targetAmt) }}</el-descriptions-item>
          <el-descriptions-item label="返利基数">¥{{ fmt(detail.settlement.baseAmt) }}</el-descriptions-item>
          <el-descriptions-item label="达成率">
            {{ (Number(detail.settlement.achieveRate) * 100).toFixed(2) }}%
          </el-descriptions-item>
          <el-descriptions-item label="返利金额">
            <b>¥{{ fmt(detail.settlement.rebateAmt) }}</b>
          </el-descriptions-item>
          <el-descriptions-item label="季度预算">¥{{ fmt(detail.settlement.budgetAmt) }}</el-descriptions-item>
          <el-descriptions-item label="计算时点余额">¥{{ fmt(detail.settlement.budgetRemain) }}</el-descriptions-item>
          <el-descriptions-item label="超预算标记" :span="2">
            <el-tag v-if="detail.settlement.overBudget === '1'" type="danger" size="small">
              超季度预算（升级总监 C-4.3-05）
            </el-tag>
            <el-tag v-else type="success" size="small">预算内</el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.settlement.overReason" label="超预算说明" :span="2">
            {{ detail.settlement.overReason }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.settlement.balancePlan" label="平衡方案" :span="2">
            {{ detail.settlement.balancePlan }}
          </el-descriptions-item>
        </el-descriptions>

        <h4>超额累进分段明细</h4>
        <el-table :data="detail.segments || []" size="small" border class="mb12">
          <el-table-column label="阶梯" width="150">
            <template #default="{ row }">
              {{ Number(row.bandFrom) }}%~{{ row.bandTo == null ? '∞' : Number(row.bandTo) + '%' }}
            </template>
          </el-table-column>
          <el-table-column label="段基数" width="120" align="right">
            <template #default="{ row }">¥{{ fmt(row.bandBase) }}</template>
          </el-table-column>
          <el-table-column label="利率" width="80" align="right">
            <template #default="{ row }">{{ Number(row.rebateRate) }}%</template>
          </el-table-column>
          <el-table-column label="段返利" align="right">
            <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
          </el-table-column>
        </el-table>

        <h4>审批日志</h4>
        <el-timeline>
          <el-timeline-item v-for="(n, i) in nodes" :key="i"
                            :type="nodeType(n.status)" :hollow="n.status !== 'PASSED'">
            [{{ n.nodeName || ('节点' + n.seq) }}] {{ n.statusName || statusOf(n.status) }}
            <div v-if="n.signer" class="todo-meta">
              签署：{{ n.signerName || n.signer }} ｜ {{ n.opTime || '' }}
            </div>
            <div v-if="n.opinion" class="todo-meta">意见：{{ n.opinion }}</div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-if="!nodes.length" description="尚无审批记录" :image-size="60" />
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getSettlementsApi, getSettlementDetailApi } from '@/api/sd/rebate'
import {
  getQmsApprovalTodoApi, passQmsApprovalApi, rejectQmsApprovalApi, getQmsApprovalLogsApi
} from '@/api/qms/approval'

const loading = ref(false)
const rows = ref([])
const filterStatus = ref('APPROVING')
const allTodos = ref([])
const opinions = ref({})
const drawerVisible = ref(false)
const detail = ref({})

const myTasks = computed(() => allTodos.value.filter(t => t.bizType === 'Rebate'))

const kpis = computed(() => {
  const approving = rows.value.filter(r => r.status === 'APPROVING').length
  const over = rows.value.filter(r => r.overBudget === '1').length
  const total = rows.value.reduce((s, r) => s + Number(r.rebateAmt || 0), 0)
  const executed = rows.value.filter(r => r.status === 'EXECUTED')
    .reduce((s, r) => s + Number(r.rebateAmt || 0), 0)
  return [
    { label: '审批中', value: approving, color: '#409eff' },
    { label: '超预算单', value: over, color: '#f56c6c' },
    { label: '返利总额', value: `¥${fmt(total)}`, color: '#e6a23c' },
    { label: '已执行', value: `¥${fmt(executed)}`, color: '#67c23a' }
  ]
})

const nodes = computed(() => {
  const logs = detail.value.approvalLogs || {}
  return logs.nodes || logs.tasks || []
})

function fmt(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function statusName(s) {
  return ({ DRAFT: '草稿', APPROVING: '审批中', APPROVED: '已通过',
    REJECTED: '已驳回', EXECUTED: '已执行' })[s] || s
}
function statusTag(s) {
  return ({ DRAFT: 'info', APPROVING: 'warning', APPROVED: 'success',
    REJECTED: 'danger', EXECUTED: '' })[s] || 'info'
}
function nodeType(st) {
  return ({ PASSED: 'success', REJECTED: 'danger' })[st] || 'primary'
}
function statusOf(st) {
  return ({ ACTIVE: '待签', PASSED: '已通过', REJECTED: '已驳回', CANCELED: '已取消' })[st] || st
}

async function load() {
  loading.value = true
  try {
    const res = await getSettlementsApi({
      current: 1, size: 100, status: filterStatus.value || undefined
    })
    rows.value = res.data.records || res.data || []
  } finally {
    loading.value = false
  }
}

async function loadTodo() {
  try {
    const res = await getQmsApprovalTodoApi()
    allTodos.value = res.data || []
  } catch (e) {
    allTodos.value = []
  }
}

async function openDetail(row) {
  const res = await getSettlementDetailApi(row.id)
  detail.value = res.data || {}
  drawerVisible.value = true
  try {
    const logs = await getQmsApprovalLogsApi('Rebate', row.id)
    detail.value.approvalLogs = logs.data || {}
  } catch (e) { /* 无审批实例 */ }
}

async function doPass(t) {
  const op = (opinions.value[t.taskId] || '').trim()
  if (op.length < 2) return ElMessage.warning('审批意见必填（≥2 字，FR-4.3-8-4）')
  await passQmsApprovalApi(t.taskId, op)
  ElMessage.success('已通过')
  opinions.value[t.taskId] = ''
  loadTodo()
  load()
}

async function doReject(t) {
  const op = (opinions.value[t.taskId] || '').trim()
  if (op.length < 2) return ElMessage.warning('驳回意见必填（≥2 字）')
  await rejectQmsApprovalApi(t.taskId, op)
  ElMessage.success('已驳回，单据退回发起人调整方案')
  opinions.value[t.taskId] = ''
  loadTodo()
  load()
}

onMounted(() => {
  load()
  loadTodo()
})
</script>

<style scoped>
.page-wrap { padding: 12px; }
.mb12 { margin-bottom: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.kpi-box { background: #f5f7fa; border-radius: 4px; padding: 10px 12px; }
.kpi-label { font-size: 12px; color: #909399; margin-bottom: 4px; }
.kpi-value { font-size: 18px; font-weight: 600; }
.todo-item { border: 1px solid #ebeef5; border-radius: 4px; padding: 10px; margin-bottom: 10px; }
.todo-head { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; }
.todo-meta { font-size: 12px; color: #909399; margin-bottom: 6px; }
.todo-op { display: flex; gap: 8px; }
</style>
