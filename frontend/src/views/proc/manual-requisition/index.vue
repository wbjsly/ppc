<template>
  <div class="manual-requisition">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">手工请购</span>
          <div class="header-actions">
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 150px;" @change="loadData(1)">
              <el-option v-for="(n, v) in statusNames" :key="v" :label="n" :value="v" />
            </el-select>
            <el-input v-model="query.keyword" placeholder="PR 单号" clearable style="width: 170px;" @keyup.enter="loadData(1)" />
            <el-button type="primary" @click="loadData(1)">查询</el-button>
            <el-button v-if="isAdmin" type="primary" :icon="Plus" @click="openCreate">新建手工请购</el-button>
          </div>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
        title="手工请购（S-4.2-02）：需求来源与预算来源各三选一；无预算来源 → 待预算确认（BR-4.2-09，补录后方可提交审批）。"
        description="物料须为已发布启用状态（BR-4.2-07 L1）；判级金额 = Σ(数量 × 预估单价)，提交审批走 2.1.4。" />

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="prNo" label="PR 单号" width="170">
          <template #default="{ row }"><b>{{ row.prNo }}</b></template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lineCount" label="行数" width="60" align="center" />
        <el-table-column prop="reqReason" label="需求理由" min-width="180" show-overflow-tooltip />
        <el-table-column label="预算来源" min-width="150">
          <template #default="{ row }">
            <span v-if="row.budgetSubject || row.budgetCostCenterId || row.budgetInternalOrderNo">
              {{ row.budgetSubject || row.budgetCostCenterId || row.budgetInternalOrderNo }}
            </span>
            <el-tag v-else type="danger" size="small">缺（待预算确认）</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="approvalAmount" label="判级金额" width="110" align="right" />
        <el-table-column prop="createBy" label="创建人" width="110" />
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <template v-if="isAdmin">
              <template v-if="['PENDING_BUDGET','PENDING_APPROVAL','PENDING_MODIFY'].includes(row.status)">
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
              </template>
              <el-button v-if="['PENDING_APPROVAL','PENDING_MODIFY'].includes(row.status)"
                         link type="warning" @click="submitRow(row)">提交审批</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination layout="total, prev, pager, next" style="margin-top: 12px; justify-content: flex-end;"
        :total="total" :page-size="query.size" :current-change="loadData" />
    </el-card>

    <!-- 创建/编辑弹窗 -->
    <el-dialog v-model="formVisible" :title="form.id ? `编辑 ${form.prNo || ''}` : '新建手工请购'" width="780px">
      <el-alert v-if="formMsg" type="error" show-icon :closable="false" :title="formMsg" style="margin-bottom: 10px;" />
      <el-form label-width="110px">
        <el-form-item label="需求理由" required>
          <el-input v-model="form.reqReason" maxlength="255" placeholder="≥2 字（试制/低耗等）" />
        </el-form-item>
        <el-form-item label="需求来源" required>
          <el-radio-group v-model="form.reqSource">
            <el-radio value="PROJECT">项目编号</el-radio>
            <el-radio value="COST_CENTER">成本中心</el-radio>
            <el-radio value="INTERNAL_ORDER">内部订单号</el-radio>
          </el-radio-group>
          <el-input v-if="form.reqSource === 'COST_CENTER'" v-model="form.reqCostCenterId"
                    placeholder="成本中心 ID（从成本中心维护复制）" style="margin-top: 6px;" />
          <el-input v-else-if="form.reqSource === 'PROJECT'" v-model="form.reqProjectNo"
                    placeholder="如 PRJ-2026-001" style="margin-top: 6px;" />
          <el-input v-else v-model="form.reqInternalOrderNo" placeholder="如 IO-2026-001" style="margin-top: 6px;" />
        </el-form-item>
        <el-form-item label="预算来源">
          <el-radio-group v-model="form.budgetSource">
            <el-radio value="NONE">无（待预算确认）</el-radio>
            <el-radio value="SUBJECT">预算科目</el-radio>
            <el-radio value="COST_CENTER">成本中心</el-radio>
            <el-radio value="INTERNAL_ORDER">内部订单号</el-radio>
          </el-radio-group>
          <el-input v-if="form.budgetSource === 'SUBJECT'" v-model="form.budgetSubject"
                    placeholder="如 行政预算-办公" style="margin-top: 6px;" />
          <el-input v-else-if="form.budgetSource === 'COST_CENTER'" v-model="form.budgetCostCenterId"
                    placeholder="成本中心 ID" style="margin-top: 6px;" />
          <el-input v-else-if="form.budgetSource === 'INTERNAL_ORDER'" v-model="form.budgetInternalOrderNo"
                    placeholder="如 IO-BUDGET-01" style="margin-top: 6px;" />
          <div class="tip">三字段全空 → 状态「待预算确认」，补录前不可提交审批（BR-4.2-09）</div>
        </el-form-item>

        <el-divider content-position="left">请购行</el-divider>
        <el-table :data="form.lines" size="small">
          <el-table-column label="物料编码" width="170">
            <template #default="{ row }">
              <el-input v-model="row.itemCode" size="small" placeholder="RM0001000001" style="text-transform: uppercase;" />
            </template>
          </el-table-column>
          <el-table-column label="数量" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.qty" :min="0.0001" :precision="4" size="small" style="width: 115px;" />
            </template>
          </el-table-column>
          <el-table-column label="需求日期" width="170">
            <template #default="{ row }">
              <el-date-picker v-model="row.reqDate" type="date" value-format="YYYY-MM-DD" size="small" style="width: 150px;" />
            </template>
          </el-table-column>
          <el-table-column label="预估单价" width="140">
            <template #default="{ row }">
              <el-input-number v-model="row.estUnitPrice" :min="0" :precision="4" size="small" style="width: 125px;" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="70">
            <template #default="{ $index }">
              <el-button link type="danger" :disabled="form.lines.length <= 1"
                         @click="form.lines.splice($index, 1)">删</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button link type="primary" style="margin-top: 8px;"
                  @click="form.lines.push({ itemCode: '', qty: 1, reqDate: '', estUnitPrice: 0 })">+ 增加行</el-button>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉（只读） -->
    <el-drawer v-model="dtVisible" :title="`请购单：${dt.pr.prNo || ''}`" size="70%">
      <el-descriptions :column="3" border size="small" style="margin-bottom: 10px;">
        <el-descriptions-item label="状态">
          <el-tag :type="statusTag(dt.pr.status)" size="small">{{ statusName(dt.pr.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="判级金额">{{ dt.pr.approvalAmount ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="驳回原因">{{ dt.pr.rejectReason || '—' }}</el-descriptions-item>
        <el-descriptions-item label="需求理由" :span="2">{{ dt.pr.reqReason || '—' }}</el-descriptions-item>
        <el-descriptions-item label="需求来源">
          {{ dt.pr.reqProjectNo || dt.pr.reqCostCenterId || dt.pr.reqInternalOrderNo || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="预算来源" :span="3">
          {{ dt.pr.budgetSubject || dt.pr.budgetCostCenterId || dt.pr.budgetInternalOrderNo || '（空 → 待预算确认）' }}
        </el-descriptions-item>
      </el-descriptions>
      <el-table :data="dt.lines" size="small" border>
        <el-table-column prop="lineNo" label="#" width="50" />
        <el-table-column prop="itemCode" label="物料" width="150" />
        <el-table-column prop="qty" label="数量" width="100" align="right" />
        <el-table-column prop="reqDate" label="需求日期" width="110" />
        <el-table-column prop="estUnitPrice" label="预估单价" width="100" align="right" />
        <el-table-column label="行状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.lineStatus === 'OPEN' ? 'success' : 'info'" size="small">
              {{ row.lineStatus === 'OPEN' ? '未关闭' : '已关闭' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/user'
import {
  getRequisitionPageApi, getRequisitionDetailApi, createManualApi,
  updateManualApi, deleteManualApi
} from '@/api/proc/requisition'
import { submitApprovalApi } from '@/api/proc/approval'

const userStore = useUserStore()
const isAdmin = computed(() => (userStore.userInfo?.roles || []).includes('ROLE_ADMIN'))
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ status: '', keyword: '', current: 1, size: 10 })

const statusNames = {
  PENDING_BUDGET: '待预算确认', PENDING_APPROVAL: '待审批', PENDING_MODIFY: '已驳回',
  APPROVING: '审批中', APPROVED: '已批准', PENDING_RFQ: '待询价', CLOSED: '已关闭',
  PENDING_CONFIRM: '待确认', CONFIRMED: '已确认'
}
function statusName(s) { return statusNames[s] || s }
function statusTag(s) {
  return { PENDING_BUDGET: 'danger', PENDING_APPROVAL: 'primary', PENDING_MODIFY: 'danger',
    APPROVING: 'primary', APPROVED: 'success', PENDING_RFQ: 'info', CLOSED: 'info' }[s] || 'info'
}

async function loadData(page) {
  if (page) query.value.current = page
  loading.value = true
  try {
    const res = await getRequisitionPageApi({ ...query.value, sourceType: 'MANUAL' })
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

// ---------- 创建/编辑 ----------
const formVisible = ref(false)
const form = ref({})
const formMsg = ref('')

function blankForm() {
  return {
    reqReason: '', reqSource: 'PROJECT', reqProjectNo: '', reqCostCenterId: '', reqInternalOrderNo: '',
    budgetSource: 'NONE', budgetSubject: '', budgetCostCenterId: '', budgetInternalOrderNo: '',
    lines: [{ itemCode: '', qty: 1, reqDate: '', estUnitPrice: 0 }]
  }
}
function openCreate() {
  form.value = blankForm()
  formMsg.value = ''
  formVisible.value = true
}
async function openEdit(row) {
  const res = await getRequisitionDetailApi(row.id)
  const pr = res.data.pr
  form.value = {
    ...blankForm(),
    id: pr.id, prNo: pr.prNo,
    reqReason: pr.reqReason || '',
    reqProjectNo: pr.reqProjectNo || '', reqCostCenterId: pr.reqCostCenterId || '',
    reqInternalOrderNo: pr.reqInternalOrderNo || '',
    reqSource: pr.reqCostCenterId ? 'COST_CENTER' : (pr.reqInternalOrderNo ? 'INTERNAL_ORDER' : 'PROJECT'),
    budgetSubject: pr.budgetSubject || '', budgetCostCenterId: pr.budgetCostCenterId || '',
    budgetInternalOrderNo: pr.budgetInternalOrderNo || '',
    budgetSource: (pr.budgetSubject || pr.budgetCostCenterId || pr.budgetInternalOrderNo)
      ? (pr.budgetSubject ? 'SUBJECT' : (pr.budgetCostCenterId ? 'COST_CENTER' : 'INTERNAL_ORDER')) : 'NONE',
    lines: res.data.lines.map(l => ({
      itemCode: l.itemCode, qty: l.qty, reqDate: l.reqDate, estUnitPrice: l.estUnitPrice
    }))
  }
  formMsg.value = ''
  formVisible.value = true
}

function payload() {
  const f = form.value
  return {
    reqReason: f.reqReason,
    reqProjectNo: f.reqSource === 'PROJECT' ? f.reqProjectNo : '',
    reqCostCenterId: f.reqSource === 'COST_CENTER' ? f.reqCostCenterId : '',
    reqInternalOrderNo: f.reqSource === 'INTERNAL_ORDER' ? f.reqInternalOrderNo : '',
    budgetSubject: f.budgetSource === 'SUBJECT' ? f.budgetSubject : '',
    budgetCostCenterId: f.budgetSource === 'COST_CENTER' ? f.budgetCostCenterId : '',
    budgetInternalOrderNo: f.budgetSource === 'INTERNAL_ORDER' ? f.budgetInternalOrderNo : '',
    lines: f.lines
  }
}

async function submitForm() {
  formMsg.value = ''
  saving.value = true
  try {
    if (form.value.id) {
      await updateManualApi(form.value.id, payload())
      ElMessage.success('已更新')
    } else {
      const res = await createManualApi(payload())
      const st = res.data.pr.status
      ElMessage.success(st === 'PENDING_BUDGET'
        ? '已创建（待预算确认：补录预算来源后可提交审批）'
        : '已创建（待审批）')
    }
    formVisible.value = false
    loadData()
  } catch (e) {
    if (e?.message) formMsg.value = e.message
  } finally {
    saving.value = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`删除手工请购单 [${row.prNo}]？`, '删除确认', { type: 'warning' })
  } catch (e) { return }
  try {
    await deleteManualApi(row.id)
    ElMessage.success('已删除')
    loadData()
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

async function submitRow(row) {
  try {
    await ElMessageBox.confirm(`提交 [${row.prNo}] 进入审批（2.1.4 按金额分级路由）？`, '提交审批', { type: 'warning' })
  } catch (e) { return }
  try {
    await submitApprovalApi(row.id)
    ElMessage.success('已提交审批')
    loadData()
  } catch (e) {
    ElMessage.error(e?.message || '提交失败')
  }
}

// ---------- 详情 ----------
const dtVisible = ref(false)
const dt = ref({ pr: {}, lines: [] })
async function openDetail(row) {
  const res = await getRequisitionDetailApi(row.id)
  dt.value = res.data
  dtVisible.value = true
}

onMounted(() => loadData())
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.tip { font-size: 12px; color: #909399; line-height: 1.5; }
</style>
