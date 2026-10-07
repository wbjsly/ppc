<template>
  <div class="ac-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="应付暂估（2.7.1）：入库过账自动生成「借 存货 / 贷 应付暂估」（BR-4.2-30）"
      description="台账只读 + 手工冲回（仅 ADMIN，须填关联发票号与原因）；三方匹配通过时由 2.7.2 自动冲回并转正式应付。FREE 无单价收货不生成暂估；合并迁移批次须经财务确认后才参与匹配与冲减。" />

    <!-- 汇总卡 -->
    <el-row :gutter="12" style="margin-bottom: 12px;">
      <el-col :span="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-label">暂估余额（未冲回）</div>
          <div class="kpi-value">{{ summary.balance }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-label">本月生成</div>
          <div class="kpi-value">{{ summary.monthGenerated }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-label">本月冲回</div>
          <div class="kpi-value">{{ summary.monthReversed }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-label">未冲回 / 已冲回（单数）</div>
          <div class="kpi-value">{{ summary.openCount }} / {{ summary.reversedCount }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="filters.supplierId" placeholder="供应商" clearable filterable
          style="width: 200px;" @change="load">
          <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
        </el-select>
        <el-input v-model="filters.poNo" placeholder="PO 号" clearable style="width: 170px;"
          @keyup.enter="load" @clear="load" />
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 140px;" @change="load">
          <el-option label="未冲回(OPEN)" value="OPEN" />
          <el-option label="已冲回(REVERSED)" value="REVERSED" />
        </el-select>
        <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD"
          start-placeholder="生成开始" end-placeholder="生成结束" style="width: 250px;" @change="load" />
        <el-button type="primary" @click="load">查询</el-button>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading" @expand-change="onExpand">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div style="padding: 8px 16px;" v-loading="row._loading">
              <h4 class="sec">入库过账行明细（来源 {{ row.sourceType === 'VMI_TRANSFER' ? '寄售转自有' : '收货过账' }}）</h4>
              <el-table :data="(row._detail && row._detail.lines) || []" size="mini" border>
                <el-table-column prop="lineNo" label="#" width="45" />
                <el-table-column prop="itemCode" label="物料" width="140" />
                <el-table-column prop="itemName" label="名称" min-width="120" show-overflow-tooltip />
                <el-table-column prop="qty" label="过账数量" width="100" align="right" />
                <el-table-column prop="unitPrice" label="单价" width="90" align="right" />
                <el-table-column prop="amount" label="金额" width="110" align="right" />
                <el-table-column prop="postingDocNo" label="入库凭证号" width="165" />
              </el-table>
              <div style="margin-top: 8px; font-size: 12px; color: #606266;">
                关联链：收货单 {{ row.grNo || '—' }} · 入库凭证 {{ row.postingDocNo || '—' }} ·
                PO {{ row.poNo || '—' }} · 批次 {{ (row._detail && row._detail.batchNo) || '—' }}
                <template v-if="row.migrationBatchNo">
                  · 迁移批次 {{ row.migrationBatchNo }}（{{ migrationText(row) }}）
                </template>
                <template v-if="row.reverseInvoiceNo">
                  · 冲回发票 {{ row.reverseInvoiceNo }}（{{ row.reverseSource === 'MANUAL' ? '手工' : '三方匹配自动' }}）
                </template>
              </div>
              <div v-if="row._detail && row._detail.voucher" style="margin-top: 6px; font-size: 12px; color: #606266;">
                暂估凭证：<b>{{ row._detail.voucher.voucherNo }}</b>
                （借 {{ row._detail.voucher.totalDr }} / 贷 {{ row._detail.voucher.totalCr }}，
                {{ row._detail.voucher.status }}）
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="accrualNo" label="暂估单号" width="160">
          <template #default="{ row }"><b style="color:#409EFF;">{{ row.accrualNo }}</b></template>
        </el-table-column>
        <el-table-column label="来源" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.sourceType === 'VMI_TRANSFER' ? 'warning' : 'info'">
              {{ row.sourceType === 'VMI_TRANSFER' ? '寄售转自有' : '收货过账' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
        <el-table-column prop="poNo" label="PO" width="150" show-overflow-tooltip />
        <el-table-column prop="grNo" label="收货单" width="150" show-overflow-tooltip />
        <el-table-column prop="amount" label="暂估金额" width="110" align="right" />
        <el-table-column prop="offsettedAmount" label="已冲减" width="95" align="right" />
        <el-table-column label="余额" width="100" align="right">
          <template #default="{ row }"><b>{{ row.balance }}</b></template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'OPEN' ? 'success' : 'info'">
              {{ row.status === 'OPEN' ? '未冲回' : '已冲回' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createDate" label="生成时间" width="165" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canReverse && row.status === 'OPEN'" link type="primary" size="small"
              @click="openReverse(row)">冲回</el-button>
            <el-button v-if="canReverse && row.migrationBatchNo && row.migrationConfirmed === 0"
              link type="warning" size="small" @click="confirmBatch(row)">批次确认</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top: 10px; justify-content: flex-end;"
        layout="total, prev, pager, next" :total="total"
        v-model:current-page="filters.current" :page-size="filters.size" @current-change="load" />
    </el-card>

    <!-- 手工冲回对话框（仅 ADMIN） -->
    <el-dialog v-model="reverseVisible" title="手工冲回应付暂估" width="480px">
      <el-alert type="warning" :closable="false" style="margin-bottom: 10px;"
        title="冲回后状态置 REVERSED 并生成红字冲销凭证，不可恢复（幂等拒绝重复冲回）" />
      <el-form label-width="110px">
        <el-form-item label="暂估单号">
          <el-input :model-value="current && current.accrualNo" readonly />
        </el-form-item>
        <el-form-item label="关联发票号" required>
          <el-input v-model="reverseForm.invoiceNo" placeholder="如 INV-20261005-001" />
        </el-form-item>
        <el-form-item label="冲回原因" required>
          <el-input v-model="reverseForm.reason" type="textarea" :rows="3"
            placeholder="如：发票提前到达，按实际发票冲回暂估" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reverseVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitReverse">确认冲回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getAccrualPageApi, getAccrualSummaryApi, getAccrualDetailApi,
  reverseAccrualApi, confirmMigrationApi
} from '@/api/fin/accrual'
import { getSupplierPageApi } from '@/api/mdm/supplier-admission'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const isAdmin = computed(() =>
  ((userStore.userInfo && userStore.userInfo.roles) || []).includes('ROLE_ADMIN'))
// 冲回 / 批次确认仅 ADMIN（Q5 甲 + SecurityConfig /api/fin/accruals/*/reverse）
const canReverse = isAdmin

const loading = ref(false)
const submitting = ref(false)
const rows = ref([])
const total = ref(0)
const suppliers = ref([])
const dateRange = ref([])
const summary = reactive({ balance: 0, monthGenerated: 0, monthReversed: 0, openCount: 0, reversedCount: 0 })
const filters = reactive({ supplierId: '', poNo: '', status: '', current: 1, size: 10 })

const reverseVisible = ref(false)
const current = ref(null)
const reverseForm = reactive({ invoiceNo: '', reason: '' })

async function loadSummary() {
  const res = await getAccrualSummaryApi()
  Object.assign(summary, res.data || {})
}

async function load() {
  loading.value = true
  try {
    const res = await getAccrualPageApi({
      current: filters.current, size: filters.size,
      supplierId: filters.supplierId || undefined,
      poNo: filters.poNo || undefined,
      status: filters.status || undefined,
      dateFrom: (dateRange.value && dateRange.value[0]) || undefined,
      dateTo: (dateRange.value && dateRange.value[1]) || undefined
    })
    rows.value = (res.data && res.data.records) || []
    total.value = (res.data && res.data.total) || 0
    await loadSummary()
  } finally {
    loading.value = false
  }
}

/** 展开时懒加载行明细（GR 行关联链 + 暂估凭证） */
async function onExpand(row) {
  if (row._detail || row._loading) return
  row._loading = true
  try {
    const res = await getAccrualDetailApi(row.id)
    row._detail = res.data || {}
  } finally {
    row._loading = false
  }
}

function migrationText(row) {
  if (row.migrationConfirmed === 1) return '已财务确认'
  if (row.migrationConfirmed === 0) return '挂起待财务确认'
  return '未挂起'
}

function openReverse(row) {
  current.value = row
  reverseForm.invoiceNo = ''
  reverseForm.reason = ''
  reverseVisible.value = true
}

async function submitReverse() {
  if (!reverseForm.invoiceNo || !reverseForm.invoiceNo.trim()) {
    ElMessage.warning('必须填写关联发票号'); return
  }
  if (!reverseForm.reason || !reverseForm.reason.trim()) {
    ElMessage.warning('必须填写冲回原因'); return
  }
  submitting.value = true
  try {
    await reverseAccrualApi(current.value.accrualNo, {
      invoiceNo: reverseForm.invoiceNo.trim(), reason: reverseForm.reason.trim()
    })
    ElMessage.success('冲回成功（OPEN → REVERSED，已生成红字冲销凭证）')
    reverseVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function confirmBatch(row) {
  try {
    const res = await confirmMigrationApi(row.migrationBatchNo)
    ElMessage.success(`批次 ${row.migrationBatchNo} 财务确认完成（${res.data && res.data.confirmed} 条），已触发三方匹配重跑`)
    await load()
  } catch (e) { /* 拦截器已提示 */ }
}

onMounted(async () => {
  await load()
  try {
    const { data } = await getSupplierPageApi({ current: 1, size: 200 })
    suppliers.value = (data && data.records) || data || []
  } catch (e) { /* 供应商下拉失败不阻断台账 */ }
})
</script>

<style scoped>
.ac-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.sec { margin: 4px 0 8px; font-size: 13px; color: #303133; }
.kpi { text-align: center; }
.kpi-label { font-size: 12px; color: #909399; margin-bottom: 6px; }
.kpi-value { font-size: 22px; font-weight: 700; color: #303133; }
</style>
