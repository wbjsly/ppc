<template>
  <div class="page">
    <el-page-header content="工单审批（5.4.2）— 计划员提交 → 计划主管签署 → 已确认；驳回回计划可改"
                    style="margin-bottom: 8px" />

    <!-- 审批待办（MoApprove，计划主管可签） -->
    <div class="section">
      <div class="section-head">
        <span class="section-title">我的审批待办</span>
        <el-button size="small" @click="loadTodo">刷新</el-button>
        <span class="tip">仅显示当前角色可签的 MoApprove 待办；驳回意见须 ≥2 字，驳回后回到计划态</span>
      </div>
      <el-table :data="todos" v-loading="todoLoading" border stripe size="small">
        <el-table-column prop="apprNo" label="审批单号" width="150" />
        <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
        <el-table-column prop="applyBy" label="申请人" width="110" />
        <el-table-column label="申请时间" width="155">
          <template #default="{ row }">{{ fmtTime(row.applyDate) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="success" size="small" @click="openPass(row)">通过</el-button>
            <el-button link type="danger" size="small" @click="openReject(row)">驳回</el-button>
            <el-button link type="info" size="small" @click="openByBizId(row)">工单详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!todoLoading && todos.length === 0" description="暂无可签工单审批待办" :image-size="60" />
    </div>

    <!-- 工单列表 -->
    <div class="section">
      <div class="section-head">
        <span class="section-title">工单列表</span>
        <el-select v-model="query.status" style="width: 150px" size="small" @change="loadData">
          <el-option label="计划（可提交）" value="PLANNED" />
          <el-option label="待审核" value="PENDING" />
          <el-option label="已确认" value="CONFIRMED" />
          <el-option label="全部状态" value="" />
        </el-select>
        <el-input v-model="query.productCode" clearable placeholder="产品编码" size="small"
                  style="width: 170px; margin-left: 8px" @keyup.enter="loadData" />
        <el-button size="small" type="primary" @click="loadData">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" border stripe size="small">
        <el-table-column prop="moNo" label="工单号" width="160" />
        <el-table-column prop="productCode" label="产品" width="130" />
        <el-table-column prop="productName" label="名称" min-width="130" show-overflow-tooltip />
        <el-table-column prop="qty" label="数量" width="80" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="submitBy" label="提交人" width="100">
          <template #default="{ row }">{{ row.submitBy || '—' }}</template>
        </el-table-column>
        <el-table-column prop="approveBy" label="审批人" width="100">
          <template #default="{ row }">{{ row.approveBy || '—' }}</template>
        </el-table-column>
        <el-table-column prop="rejectReason" label="最近驳回" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canPlanner && row.status === 'PLANNED'" link type="primary" size="small"
                       @click="doSubmit(row)">提交审核</el-button>
            <el-button link type="info" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && rows.length === 0" description="暂无工单" :image-size="60" />
    </div>

    <!-- 通过意见 -->
    <el-dialog v-model="passVisible" title="工单审批 - 通过" width="480px">
      <el-alert v-if="currentTodo" :title="currentTodo.title" type="info" :closable="false"
                style="margin-bottom: 12px" />
      <el-input v-model="passOpinion" type="textarea" :rows="3" placeholder="审批意见（可空，默认同意）" />
      <template #footer>
        <el-button @click="passVisible = false">取消</el-button>
        <el-button type="success" :loading="saving" @click="doPass">确认通过</el-button>
      </template>
    </el-dialog>

    <!-- 驳回原因 -->
    <el-dialog v-model="rejectVisible" title="工单审批 - 驳回" width="480px">
      <el-alert v-if="currentTodo" :title="currentTodo.title" type="warning" :closable="false"
                style="margin-bottom: 12px" />
      <el-input v-model="rejectReason" type="textarea" :rows="3" maxlength="500"
                placeholder="驳回原因（必填，至少 2 字；驳回后回到计划态可改）" />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="doReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" title="工单详情" width="720px">
      <template v-if="detail">
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="工单号">{{ detail.moNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusText(detail.status) }}</el-descriptions-item>
          <el-descriptions-item label="产品">{{ detail.productCode }} {{ detail.productName }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ detail.qty }}</el-descriptions-item>
          <el-descriptions-item label="计划开工/完工">{{ detail.planStartDate }} ~ {{ detail.planEndDate }}</el-descriptions-item>
          <el-descriptions-item label="优先级">{{ detail.priority }}</el-descriptions-item>
          <el-descriptions-item label="提交留痕">{{ detail.submitBy || '—' }} {{ detail.submitAt || '' }}</el-descriptions-item>
          <el-descriptions-item label="审批留痕">{{ detail.approveBy || '—' }} {{ detail.approveAt || '' }}</el-descriptions-item>
          <el-descriptions-item label="驳回意见">{{ detail.rejectReason || '—' }}</el-descriptions-item>
          <el-descriptions-item label="来源 PMO">{{ detail.plannedMoNo || '—' }}</el-descriptions-item>
        </el-descriptions>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMosApi, getMoDetailApi, submitMoApi } from '@/api/mrp/mo'
import { getQmsApprovalTodoApi, passQmsApprovalApi, rejectQmsApprovalApi } from '@/api/qms/approval'
import { useUserStore } from '@/store/user'

const BIZ = 'MoApprove'
const userStore = useUserStore()
const roles = computed(() => userStore.userInfo?.roles || [])
const canPlanner = computed(() => roles.value.includes('ROLE_ADMIN') || roles.value.includes('ROLE_PLANNER'))

const statusText = s => ({
  PLANNED: '计划', PENDING: '待审核', CONFIRMED: '已确认', RELEASED: '已释放',
  HOLD: '挂起', COMPLETED: '已完工', CLOSED: '已关闭', CANCELLED: '已取消'
}[s] || s)
const statusTag = s => ({
  PLANNED: 'info', PENDING: 'warning', CONFIRMED: '', RELEASED: 'success',
  HOLD: 'warning', COMPLETED: 'success', CLOSED: 'info', CANCELLED: 'danger'
}[s] || 'info')
const fmtTime = t => t ? String(t).replace('T', ' ').slice(0, 19) : '—'

const loading = ref(false)
const todoLoading = ref(false)
const saving = ref(false)
const rows = ref([])
const todos = ref([])
const query = ref({ status: 'PLANNED', productCode: '' })

const passVisible = ref(false)
const rejectVisible = ref(false)
const passOpinion = ref('')
const rejectReason = ref('')
const currentTodo = ref(null)
const detailVisible = ref(false)
const detail = ref(null)

async function loadData() {
  loading.value = true
  try {
    const res = await getMosApi({
      status: query.value.status || undefined,
      productCode: query.value.productCode || undefined
    })
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function loadTodo() {
  todoLoading.value = true
  try {
    const res = await getQmsApprovalTodoApi()
    todos.value = (res.data || []).filter(t => t.bizType === BIZ && t.kind !== 'ESCALATE')
  } catch (e) {
    console.warn('[mo-approve] todo failed', e)
  } finally {
    todoLoading.value = false
  }
}

async function doSubmit(row) {
  await ElMessageBox.confirm(`提交工单 ${row.moNo}（${row.productCode}，数量 ${row.qty}）进入审核？`, '提交审核', {
    type: 'info'
  })
  saving.value = true
  try {
    await submitMoApi(row.id)
    ElMessage.success('已提交，等待计划主管签署')
    loadData()
  } catch (e) {
    console.warn('[mo-approve] submit failed', e)
  } finally {
    saving.value = false
  }
}

function openPass(row) {
  currentTodo.value = row
  passOpinion.value = ''
  passVisible.value = true
}

function openReject(row) {
  currentTodo.value = row
  rejectReason.value = ''
  rejectVisible.value = true
}

async function doPass() {
  saving.value = true
  try {
    await passQmsApprovalApi(currentTodo.value.taskId, passOpinion.value.trim())
    ElMessage.success('已通过，工单状态 → 已确认')
    passVisible.value = false
    loadTodo()
    loadData()
  } catch (e) {
    console.warn('[mo-approve] pass failed', e)
  } finally {
    saving.value = false
  }
}

async function doReject() {
  if ((rejectReason.value || '').trim().length < 2) {
    ElMessage.warning('驳回原因至少 2 字')
    return
  }
  saving.value = true
  try {
    await rejectQmsApprovalApi(currentTodo.value.taskId, rejectReason.value.trim())
    ElMessage.success('已驳回，工单回到计划态')
    rejectVisible.value = false
    loadTodo()
    loadData()
  } catch (e) {
    console.warn('[mo-approve] reject failed', e)
  } finally {
    saving.value = false
  }
}

async function openDetail(row) {
  const res = await getMoDetailApi(row.id)
  detail.value = res.data.mo
  detailVisible.value = true
}

async function openByBizId(row) {
  const res = await getMosApi({})
  const hit = (res.data || []).find(m => m.id === row.bizId)
  if (hit) openDetail(hit)
  else ElMessage.info('工单不在当前列表过滤范围内')
}

onMounted(() => { loadData(); loadTodo() })
</script>

<style scoped>
.section { margin-bottom: 14px; }
.section-head { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; }
.section-title { font-weight: 600; }
.tip { color: #909399; font-size: 12px; }
</style>
