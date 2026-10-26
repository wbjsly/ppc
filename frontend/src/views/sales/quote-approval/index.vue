<template>
  <div class="quote-approval-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="报价审批（11.2.2，spec sales-quote 报价审批台）"
      description="待办按当前用户角色聚合（普通/大额=销售经理→销售总监，负毛利=销售主管+财务并行会签，小额高毛利自动发布无节点）；驳回意见 ≥2 字且整单驳回；签署语义完全沿用审批底座。"
    />

    <el-tabs v-model="tab">
      <!-- ==================== 待办 ==================== -->
      <el-tab-pane :label="`待办（${todo.length}）`" name="todo">
        <el-table :data="todo" v-loading="loading" stripe>
          <el-table-column prop="apprNo" label="审批单号" width="180" />
          <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
          <el-table-column prop="nodeName" label="节点" width="150">
            <template #default="{ row }">{{ row.nodeName || row.roleRequired }}</template>
          </el-table-column>
          <el-table-column prop="roleRequired" label="签署角色" width="150">
            <template #default="{ row }">{{ roleName(row.roleRequired) }}</template>
          </el-table-column>
          <el-table-column prop="applyBy" label="发起人" width="110" />
          <el-table-column prop="applyDate" label="发起时间" width="160">
            <template #default="{ row }">{{ (row.applyDate || '').replace('T', ' ').slice(0, 16) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button link type="success" @click="doPass(row)">通过</el-button>
              <el-button link type="danger" @click="openReject(row)">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!loading && !todo.length" style="color: #909399; padding: 24px; text-align: center;">
          暂无报价审批待办（小额高毛利报价自动发布，不产生节点）
        </div>
      </el-tab-pane>

      <!-- ==================== 已审历史 ==================== -->
      <el-tab-pane label="已审历史" name="history">
        <el-table :data="history" v-loading="historyLoading" stripe>
          <el-table-column prop="apprNo" label="审批单号" width="180" />
          <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
          <el-table-column label="结论" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'APPROVED' ? 'success' : 'danger'">
                {{ row.status === 'APPROVED' ? '已通过' : row.status === 'REJECTED' ? '已驳回' : row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="applyBy" label="发起人" width="110" />
          <el-table-column prop="applyDate" label="发起时间" width="160">
            <template #default="{ row }">{{ (row.applyDate || '').replace('T', ' ').slice(0, 16) }}</template>
          </el-table-column>
          <el-table-column prop="finishDate" label="办结时间" width="160">
            <template #default="{ row }">{{ (row.finishDate || '').replace('T', ' ').slice(0, 16) || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetailByBiz(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 报价详情抽屉 -->
    <el-drawer v-model="detailVisible" :title="`报价详情 - ${detail.draftNo || ''}`" size="62%">
      <el-descriptions :column="3" border size="small" style="margin-bottom: 12px;">
        <el-descriptions-item label="草稿号">{{ detail.draftNo }}</el-descriptions-item>
        <el-descriptions-item label="正式编号">{{ detail.quoteNo || '未发布' }}</el-descriptions-item>
        <el-descriptions-item label="客户">{{ detail.customerName }}</el-descriptions-item>
        <el-descriptions-item label="报价金额">¥{{ detail.totalAmount }}</el-descriptions-item>
        <el-descriptions-item label="标准成本">¥{{ detail.totalCost }}</el-descriptions-item>
        <el-descriptions-item label="毛利率">
          <el-tag size="small" :type="Number(detail.marginRate) < 0 ? 'danger' : Number(detail.marginRate) < 5 ? 'warning' : 'success'">
            {{ detail.marginRate }}%
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="取价命中" :span="3">
          <span class="muted">{{ detail.priceMatch || '—' }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <el-table :data="detailLines" size="small" border max-height="360">
        <el-table-column prop="itemCode" label="SKU" width="130" />
        <el-table-column prop="itemName" label="名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="qty" label="数量" width="80" />
        <el-table-column prop="unitPrice" label="含税单价" width="100" />
        <el-table-column prop="amount" label="行金额" width="110" />
        <el-table-column prop="priceSource" label="价源" width="110">
          <template #default="{ row }">{{ sourceName(row.priceSource) }}</template>
        </el-table-column>
        <el-table-column prop="expectDeliveryDate" label="期望交期" width="110" />
      </el-table>
    </el-drawer>

    <!-- 驳回 -->
    <el-dialog v-model="rejectVisible" title="驳回报价（意见 ≥2 字，整单驳回）" width="440px">
      <el-input v-model="rejectOpinion" type="textarea" :rows="3" placeholder="驳回原因" />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="acting" :disabled="(rejectOpinion || '').trim().length < 2" @click="doReject">
          驳回
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { approvalTodoApi, approvalPassApi, approvalRejectApi, approvalHistoryApi } from '@/api/sd/quote-approval'
import { getQuoteApi, getQuoteLinesApi } from '@/api/sd/quote'

const tab = ref('todo')
const loading = ref(false)
const historyLoading = ref(false)
const acting = ref(false)
const todo = ref([])
const history = ref([])

const detailVisible = ref(false)
const detail = ref({})
const detailLines = ref([])

const rejectVisible = ref(false)
const rejectOpinion = ref('')
const current = ref({})

const roleName = (r) => ({ ROLE_SALES_MGR: '销售主管', ROLE_SALES_DIRECTOR: '销售总监',
  ROLE_FINANCE_MGR: '财务总监' }[r] || r)
const sourceName = (s) => ({ EXCLUSIVE: '客户专属', LADDER: '量价阶梯', TIME: '时间促销',
  MANUAL: '手工' }[s] || s || '—')

async function loadTodo() {
  loading.value = true
  try {
    const res = await approvalTodoApi()
    todo.value = (res.data || []).filter(r => r.bizType === 'Quote')
  } finally {
    loading.value = false
  }
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const res = await approvalHistoryApi({ bizType: 'Quote' })
    history.value = res.data || []
  } finally {
    historyLoading.value = false
  }
}

async function openDetail(row) {
  try {
    const [q, l] = await Promise.all([getQuoteApi(row.bizId), getQuoteLinesApi(row.bizId)])
    detail.value = q.data || {}
    detailLines.value = l.data || []
    detailVisible.value = true
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

async function openDetailByBiz(row) {
  await openDetail({ bizId: row.bizId })
}

async function doPass(row) {
  try {
    await ElMessageBox.confirm(`确认通过「${row.title}」？`, '通过确认', { type: 'success' })
    await approvalPassApi({ taskId: row.taskId, opinion: '同意' })
    ElMessage.success('已通过')
    loadTodo()
    loadHistory()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e?.response?.data?.msg || e?.message) }
}

function openReject(row) {
  current.value = row
  rejectOpinion.value = ''
  rejectVisible.value = true
}

async function doReject() {
  if ((rejectOpinion.value || '').trim().length < 2) { ElMessage.warning('驳回意见至少 2 字'); return }
  acting.value = true
  try {
    await approvalRejectApi({ taskId: current.value.taskId, opinion: rejectOpinion.value.trim() })
    ElMessage.success('已驳回，整单退回')
    rejectVisible.value = false
    loadTodo()
    loadHistory()
  } finally {
    acting.value = false
  }
}

onMounted(() => { loadTodo(); loadHistory() })
</script>

<style scoped>
.quote-approval-page { padding: 16px; }
.muted { color: #909399; font-size: 12px; }
</style>
