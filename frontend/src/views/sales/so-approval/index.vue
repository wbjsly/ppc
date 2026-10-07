<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.5.2 订单审批"
              description="金额三档：<10 万自动确认免审；中额销售经理单节点；>200 万销售经理→销售总监。链尾两类加签：低毛利持特批单→财务复核；改价行→价格变更复核。审批通过自动确认并生成批次预留（锁不足整体回滚）。" />

    <!-- 档位参数 -->
    <el-row :gutter="12" class="mb12">
      <el-col :span="6" v-for="k in kpis" :key="k.label">
        <el-card shadow="never">
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value">{{ k.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>待审与历史订单</span>
          <div>
            <el-select v-model="filterStatus" clearable placeholder="状态筛选" size="small"
                       style="width:170px" @change="load">
              <el-option label="审批中 PENDING" value="PENDING" />
              <el-option label="草稿 DRAFT" value="DRAFT" />
              <el-option label="已确认 CONFIRMED" value="CONFIRMED" />
              <el-option label="信用冻结" value="CREDIT_FREEZE" />
            </el-select>
            <el-button size="small" style="margin-left:8px" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" size="small" border @row-click="openDetail"
                highlight-current-row v-loading="loading">
        <el-table-column prop="soNo" label="订单号" width="160" />
        <el-table-column prop="customerName" label="客户" min-width="150" show-overflow-tooltip />
        <el-table-column prop="orderType" label="类型" width="80">
          <template #default="{ row }">
            <el-tag size="small">{{ typeMap[row.orderType] || row.orderType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalAmount" label="金额" width="120" align="right">
          <template #default="{ row }">¥{{ Number(row.totalAmount).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="毛利率" width="90" align="right">
          <template #default="{ row }">
            <span :class="marginClass(row.marginRate)">{{ pct(row.marginRate) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="payConfirmBy" label="付款确认" width="110" show-overflow-tooltip />
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT'" size="small" type="primary"
                       @click.stop="doSubmit(row)">提交审批</el-button>
            <el-button v-else size="small" link @click.stop="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 详情 + 审批链 -->
    <el-dialog v-model="detailVisible" title="订单审批详情" width="920px" top="6vh">
      <template v-if="detail">
        <el-descriptions :column="3" size="small" border class="mb12">
          <el-descriptions-item label="订单号">{{ detail.so.soNo }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ detail.so.customerName }}</el-descriptions-item>
          <el-descriptions-item label="金额">¥{{ Number(detail.so.totalAmount).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="毛利率">
            <span :class="marginClass(detail.so.marginRate)">{{ pct(detail.so.marginRate) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusName(detail.so.status) }}</el-descriptions-item>
          <el-descriptions-item label="来源">{{ detail.so.sourceType }}</el-descriptions-item>
        </el-descriptions>

        <!-- 付款条件差异（BR-4.3-25） -->
        <el-alert v-if="detail.payTermsDiff && detail.payTermsDiff.differs"
                  :type="detail.payTermsDiff.confirmed ? 'success' : 'warning'"
                  :closable="false" class="mb12">
          <template #title>
            付款条件差异：订单「{{ detail.payTermsDiff.actual }}」 vs 协议「{{ detail.payTermsDiff.expected }}」
            <span v-if="detail.payTermsDiff.confirmed">
              —— 已由 {{ detail.payTermsDiff.confirmedBy }} 确认：{{ detail.payTermsDiff.confirmReason }}
            </span>
            <el-button v-else size="small" type="primary" link @click="payDialog = true">
              销售主管确认
            </el-button>
          </template>
        </el-alert>

        <h4>审批链（档位 + 加签节点）</h4>
        <el-table :data="logs.tasks || []" size="small" border class="mb12">
          <el-table-column prop="seq" label="SEQ" width="60" />
          <el-table-column prop="nodeName" label="节点" min-width="160" />
          <el-table-column prop="roleRequired" label="签署角色" width="170" />
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="nodeTag(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="signer" label="签署人" width="130" />
          <el-table-column prop="opinion" label="意见" min-width="150" show-overflow-tooltip />
        </el-table>
        <el-empty v-if="!logs.tasks || !logs.tasks.length" description="无审批实例（自动确认档或未提交）"
                  :image-size="60" />

        <h4>明细行</h4>
        <el-table :data="detail.lines" size="small" border>
          <el-table-column prop="lineNo" label="#" width="45" />
          <el-table-column prop="itemCode" label="SKU" width="150" />
          <el-table-column prop="itemName" label="名称" min-width="120" show-overflow-tooltip />
          <el-table-column prop="qty" label="数量" width="90" align="right" />
          <el-table-column label="单价" width="100" align="right">
            <template #default="{ row }">
              <span :class="{ 'price-locked': row.priceLocked === '1' }">¥{{ row.unitPrice }}</span>
            </template>
          </el-table-column>
          <el-table-column label="价源" width="90">
            <template #default="{ row }">
              <el-tag size="small" type="info">{{ row.priceSource || '—' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="行状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.priceLocked === '1' ? 'danger' : 'success'">
                {{ row.priceLocked === '1' ? '改价锁定' : (row.lineStatus || 'OPEN') }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 付款条件主管确认 -->
    <el-dialog v-model="payDialog" title="付款条件差异 · 销售主管确认" width="460px">
      <el-input v-model="payReason" type="textarea" :rows="3" placeholder="确认原因（必填）" />
      <template #footer>
        <el-button @click="payDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!payReason" @click="doConfirmPay">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getSoPageApi, getSoDetailApi, getSoApprovalLogsApi,
  submitSoApi, confirmPaymentTermsApi
} from '@/api/sd/so'
import { getParamsApi } from '@/api/system/param'

const rows = ref([])
const loading = ref(false)
const filterStatus = ref('PENDING')
const detailVisible = ref(false)
const detail = ref(null)
const logs = ref({})
const payDialog = ref(false)
const payReason = ref('')
const params = ref([])

const typeMap = { STANDARD: '标准', CONSIGN: '寄售', SAMPLE: '样品' }

const kpis = computed(() => {
  const find = k => (params.value.find(p => p.paramKey === k) || {}).paramValue || '—'
  return [
    { label: '自动确认阈值 SO_APPROVAL_AUTO', value: '¥' + find('SO_APPROVAL_AUTO') },
    { label: '大额阈值 SO_APPROVAL_MAJOR', value: '¥' + find('SO_APPROVAL_MAJOR') },
    { label: '最低毛利 MIN_MARGIN_RATE', value: pct(find('MIN_MARGIN_RATE')) },
    { label: '本页订单数', value: String(rows.value.length) }
  ]
})

function pct(v) {
  if (v == null || v === '') return '—'
  return (Number(v) * 100).toFixed(2) + '%'
}
function marginClass(v) {
  if (v == null) return ''
  return Number(v) < 0 ? 'margin-neg' : (Number(v) < 0.05 ? 'margin-low' : 'margin-ok')
}
function statusName(s) {
  return {
    DRAFT: '草稿', PENDING: '审批中', CONFIRMED: '已确认', PARTIAL_SHIPPED: '部分发货',
    SHIPPED: '已发货', SIGNED: '已签收', INVOICED: '已开票', CLOSED: '已关闭',
    CREDIT_FREEZE: '信用冻结(挂起)', CHANGING: '变更中(挂起)', CANCELLED: '已取消'
  }[s] || s
}
function statusTag(s) {
  return { DRAFT: 'info', PENDING: 'warning', CONFIRMED: 'success',
    CREDIT_FREEZE: 'danger', CHANGING: 'warning', CLOSED: 'info' }[s] || ''
}
function nodeTag(s) {
  return { ACTIVE: 'warning', PASSED: 'success', REJECTED: 'danger', SKIPPED: 'info' }[s] || ''
}

async function load() {
  loading.value = true
  try {
    const res = await getSoPageApi({
      current: 1, size: 50, status: filterStatus.value || undefined
    })
    rows.value = res.data.records || []
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  try {
    const [d, l] = await Promise.all([
      getSoDetailApi(row.id), getSoApprovalLogsApi(row.id)
    ])
    detail.value = d.data
    logs.value = l.data || {}
    detailVisible.value = true
  } catch (e) {
    ElMessage.error(e?.message || '加载详情失败')
  }
}

async function doSubmit(row) {
  try {
    const res = await submitSoApi(row.id)
    if (res.data.mode === 'AUTO') {
      ElMessage.success('小额高毛利自动确认，订单已确认并生成预留')
    } else {
      ElMessage.success(`已提交审批：${res.data.basis}，${res.data.chain.length} 段节点`)
    }
    await load()
    await openDetail({ id: row.id })
  } catch (e) {
    ElMessage.error(e?.message || '提交失败')
  }
}

async function doConfirmPay() {
  try {
    await confirmPaymentTermsApi(detail.value.so.id, payReason.value)
    ElMessage.success('已记录销售主管确认')
    payDialog.value = false
    const d = await getSoDetailApi(detail.value.so.id)
    detail.value = d.data
  } catch (e) {
    ElMessage.error(e?.message || '确认失败')
  }
}

onMounted(async () => {
  await load()
  try {
    const p = await getParamsApi()
    params.value = p.data || []
  } catch (e) { /* 参数读取失败不影响主流程 */ }
})
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.mb12 { margin-bottom: 12px; }
.kpi-label { font-size: 12px; color: #909399; }
.kpi-value { font-size: 18px; font-weight: 600; margin-top: 4px; }
.margin-ok { color: #67c23a; }
.margin-low { color: #e6a23c; }
.margin-neg { color: #f56c6c; font-weight: 600; }
.price-locked { color: #f56c6c; font-weight: 600; }
h4 { margin: 12px 0 8px; font-size: 14px; }
</style>
