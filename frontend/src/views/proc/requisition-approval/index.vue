<template>
  <div class="requisition-approval">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">请购审批</span>
          <el-button :loading="loading" @click="loadTodo">刷新待办</el-button>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
        title="分级审批（FR-4.2-1-2）：判级金额 = Σ(数量×预估单价)；≤部门限额→部门经理，≤总监限额→+采购总监，超出→+分管副总裁。"
        description="限额配置：app.proc.dept-approval-limit（默认 5 万）/ app.proc.director-approval-limit（默认 50 万）。节点 1 工作日超时提醒、3 工作日升级（懒扫描，通知 TODO 桩）。" />

      <el-table :data="todo" v-loading="loading" stripe>
        <el-table-column prop="prNo" label="PR 单号" width="170">
          <template #default="{ row }"><b>{{ row.prNo }}</b></template>
        </el-table-column>
        <el-table-column prop="sourceType" label="来源" width="80" />
        <el-table-column prop="amount" label="判级金额" width="110" align="right" />
        <el-table-column label="路由链" min-width="240">
          <template #default="{ row }">
            <el-tag v-for="n in row.routeChain" :key="n.nodeNo" size="small"
                    :type="n.status === 'ACTIVE' ? 'primary' : (n.status === 'PASSED' ? 'success' : 'info')"
                    style="margin-right: 4px;">
              {{ roleName(n.role) }}{{ n.status === 'PASSED' ? '✓' : (n.status === 'ACTIVE' ? '…' : '') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="超时/升级" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.timeoutFlag === '1'" type="warning" size="small">超时</el-tag>
            <el-tag v-if="row.escalateFlag === '1'" type="danger" size="small" style="margin-left:4px;">
              升级{{ row.activeRole ? '→' + roleName(nextRole(row)) : '' }}
            </el-tag>
            <span v-if="row.timeoutFlag !== '1' && row.escalateFlag !== '1'" style="color:#c0c4cc;">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <template v-if="isAdmin">
              <el-button link type="success" :disabled="!row.activeTaskId" @click="doPass(row)">通过</el-button>
              <el-button link type="danger" :disabled="!row.activeTaskId" @click="openReject(row)">驳回</el-button>
            </template>
            <el-button link type="primary" @click="openLogs(row)">日志</el-button>
            <el-button link type="info" @click="openHelpers(row)">辅助信息</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !todo.length" description="暂无审批待办（审批中的 PR 会出现在这里）" :image-size="90" />
    </el-card>

    <!-- 辅助信息抽屉 -->
    <el-drawer v-model="helperVisible" :title="`审批辅助信息：${helperRow.prNo || ''}`" size="520px">
      <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
                :title="helperRow.inventoryHint || '库存可供量待 WMS 接入'" />
      <div v-for="h in (helperRow.helpers || [])" :key="h.itemCode" style="margin-bottom: 12px;">
        <el-divider content-position="left">{{ h.itemCode }}（本单 {{ h.currentQty }}，需求日 {{ h.reqDate }}）</el-divider>
        <el-table :data="h.historyPrices" size="small" border>
          <el-table-column prop="estUnitPrice" label="历史预估单价" width="140" align="right" />
          <el-table-column prop="reqDate" label="需求日期" />
        </el-table>
        <div v-if="!h.historyPrices.length" class="tip">暂无同物料历史 PR 预估价</div>
      </div>
    </el-drawer>

    <!-- 审批日志抽屉 -->
    <el-drawer v-model="logVisible" :title="`审批日志：${logRow.prNo || ''}`" size="640px">
      <el-table :data="logs" size="small" border>
        <el-table-column label="批次" width="60" align="center">
          <template #default="{ row }">B{{ row.submitBatch }}</template>
        </el-table-column>
        <el-table-column label="节点" width="120">
          <template #default="{ row }">
            {{ row.nodeNo === 0 ? '提交' : `N${row.nodeNo} ${roleName(row.nodeRole)}` }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="logTag(row)">{{ logStatusName(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="标记" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.timeoutFlag === '1'" type="warning" size="small">超时</el-tag>
            <el-tag v-if="row.escalateFlag === '1'" type="danger" size="small" style="margin-left:2px;">升级</el-tag>
            <span v-if="row.timeoutFlag !== '1' && row.escalateFlag !== '1'" style="color:#c0c4cc;">—</span>
          </template>
        </el-table-column>
        <el-table-column label="意见" min-width="150">
          <template #default="{ row }">{{ row.actionReason || '—' }}</template>
        </el-table-column>
        <el-table-column prop="actedBy" label="操作人" width="110" />
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ (row.actionDate || row.createDate || '').replace('T', ' ').slice(0, 16) }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 驳回弹窗 -->
    <el-dialog v-model="rejectVisible" title="驳回（原因必填 ≥2 字，退回申请人修改）" width="480px">
      <el-input v-model="rejectReason" type="textarea" :rows="3" maxlength="255" placeholder="驳回原因" />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="doReject">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import { getApprovalTodoApi, passApprovalApi, rejectApprovalApi, getApprovalLogsApi } from '@/api/proc/approval'

const userStore = useUserStore()
const isAdmin = computed(() => (userStore.userInfo?.roles || []).includes('ROLE_ADMIN'))
const loading = ref(false)
const saving = ref(false)
const todo = ref([])

const roleNames = { DEPT_MANAGER: '部门经理', DIRECTOR: '采购总监', VP: '分管副总裁', APPLICANT: '申请人' }
function roleName(r) { return roleNames[r] || r }
function nextRole(row) {
  const chain = row.routeChain || []
  const idx = chain.findIndex(n => n.role === row.activeRole)
  return idx >= 0 && idx + 1 < chain.length ? chain[idx + 1].role : ''
}
function logTag(row) {
  if (row.status === 'REJECTED') return 'danger'
  if (row.status === 'PASSED' || row.status === 'DONE') return 'success'
  if (row.status === 'SUPERSEDED') return 'info'
  return 'primary'
}
function logStatusName(row) {
  return { DONE: '提交', PASSED: '通过', REJECTED: '驳回', ACTIVE: '待办',
    PENDING: '待激活', SUPERSEDED: '作废' }[row.status] || row.status
}

async function loadTodo() {
  loading.value = true
  try {
    const res = await getApprovalTodoApi()
    todo.value = res.data
  } finally {
    loading.value = false
  }
}

async function doPass(row) {
  try {
    await ElMessageBox.confirm(`通过 [${row.prNo}] 的${roleName(row.activeRole)}节点？`, '通过', { type: 'warning' })
  } catch (e) { return }
  try {
    await passApprovalApi(row.activeTaskId)
    ElMessage.success('已通过')
    loadTodo()
  } catch (e) {
    ElMessage.error(e?.message || '通过失败')
    loadTodo()
  }
}

const rejectVisible = ref(false)
const rejectReason = ref('')
const rejectRow = ref({})

function openReject(row) {
  rejectRow.value = row
  rejectReason.value = ''
  rejectVisible.value = true
}
async function doReject() {
  if (!rejectReason.value || rejectReason.value.trim().length < 2) {
    ElMessage.warning('驳回原因必填（≥2 字）')
    return
  }
  saving.value = true
  try {
    await rejectApprovalApi(rejectRow.value.activeTaskId, rejectReason.value.trim())
    ElMessage.success('已驳回，PR 退回申请人修改')
    rejectVisible.value = false
    loadTodo()
  } catch (e) {
    ElMessage.error(e?.message || '驳回失败')
  } finally {
    saving.value = false
  }
}

// ---------- 日志 / 辅助 ----------
const logVisible = ref(false)
const logRow = ref({})
const logs = ref([])

async function openLogs(row) {
  logRow.value = row
  const res = await getApprovalLogsApi(row.prId)
  logs.value = res.data
  logVisible.value = true
}

const helperVisible = ref(false)
const helperRow = ref({})
function openHelpers(row) {
  helperRow.value = row
  helperVisible.value = true
}

onMounted(() => loadTodo())
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.tip { font-size: 12px; color: #909399; }
</style>
