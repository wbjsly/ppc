<template>
  <div class="page">
    <div class="head">
      <span class="title">进项税额转出（直接转出 / 分摊转出 BR-4.14-22 → 转出凭证 DRAFT）</span>
      <div>
        <el-button type="primary" size="small" @click="openDirect">直接转出</el-button>
        <el-button type="success" size="small" @click="openApportion">分摊转出</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" class="mb8"
      title="仅已认证（CERTIFIED）发票可转出；单票累计转出不超过已抵扣税额；凭证为草稿态，审核过账见总账凭证页" />

    <el-table :data="rows" size="small" border>
      <el-table-column prop="invoiceNo" label="发票号" width="150" />
      <el-table-column prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
      <el-table-column prop="invoiceDate" label="开票日" width="100" />
      <el-table-column prop="taxAmount" label="已抵扣税额" width="100" />
      <el-table-column label="累计转出" width="100">
        <template #default="{ row }">{{ row.transferOutAmount || 0 }}</template>
      </el-table-column>
      <el-table-column label="剩余可转出" width="100">
        <template #default="{ row }">
          <span :class="{ warn: Number(remain(row)) < 0 }">{{ remain(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="certPeriod" label="认证所属期" width="95" />
      <el-table-column label="凭证" width="130">
        <template #default="{ row }">
          <span v-if="row.voucherId" class="muted">{{ row.voucherId.slice(0, 8) }}…</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button v-if="row.certStatus === 'CERTIFIED'" link type="warning" size="small"
            @click="openDirect(row)">直接转出</el-button>
          <span v-else class="muted">未认证不可转出</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 直接转出 -->
    <el-dialog v-model="directVisible" title="直接转出（选票 + 金额；累计不超已抵扣税额）" width="500px">
      <el-form label-width="110px" size="small">
        <el-form-item label="进项发票" required>
          <el-select v-model="directForm.invoiceId" filterable :disabled="!!directRow" style="width: 100%">
            <el-option v-for="inv in certOptions" :key="inv.id"
              :label="`${inv.invoiceNo}｜已抵扣 ${inv.taxAmount}｜已转 ${inv.transferOutAmount || 0}`"
              :value="inv.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="转出金额" required>
          <el-input-number v-model="directForm.amount" :min="0.01" :precision="2" :step="1000" style="width: 200px" />
        </el-form-item>
        <el-form-item label="转出原因" required>
          <el-select v-model="directForm.reason" style="width: 240px">
            <el-option label="集体福利" value="集体福利" />
            <el-option label="个人消费" value="个人消费" />
            <el-option label="免税项目" value="免税项目" />
            <el-option label="简易计税项目" value="简易计税项目" />
            <el-option label="非正常损失" value="非正常损失" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="directVisible = false">取消</el-button>
        <el-button type="primary" @click="doDirect">确认转出</el-button>
      </template>
    </el-dialog>

    <!-- 分摊转出（计算器） -->
    <el-dialog v-model="apportionVisible" title="分摊转出（BR-4.14-22：转出税额 = 无法划分进项税额 × 免税简易项目销售额占比）" width="560px">
      <el-form label-width="170px" size="small">
        <el-form-item label="无法划分的全部进项税额" required>
          <el-input-number v-model="apportionForm.totalInputTax" :min="0.01" :precision="2" :step="1000" style="width: 220px" />
        </el-form-item>
        <el-form-item label="免税与简易项目销售额" required>
          <el-input-number v-model="apportionForm.exemptSales" :min="0" :precision="2" :step="10000" style="width: 220px" />
        </el-form-item>
        <el-form-item label="当期全部销售额" required>
          <el-input-number v-model="apportionForm.totalSales" :min="0.01" :precision="2" :step="10000" style="width: 220px" />
        </el-form-item>
        <el-form-item label="转出税额（公式试算）">
          <span class="calc">{{ calcAmount }}</span>
        </el-form-item>
        <el-form-item label="所属期 YYYYMM" required>
          <el-input v-model="apportionForm.period" placeholder="如 209909" style="width: 160px" />
        </el-form-item>
        <el-form-item label="转出原因" required>
          <el-input v-model="apportionForm.reason" placeholder="无法划分的不得抵扣进项转出" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="apportionVisible = false">取消</el-button>
        <el-button type="primary" @click="doApportion">确认分摊转出</el-button>
      </template>
    </el-dialog>

    <!-- 分摊结果 -->
    <el-dialog v-model="resultVisible" title="分摊转出结果（逐票回写 + 汇总凭证）" width="560px">
      <template v-if="result">
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="转出总额">{{ result.transferTotal }}</el-descriptions-item>
          <el-descriptions-item label="分摊比例">{{ (Number(result.ratio) * 100).toFixed(2) }}%</el-descriptions-item>
          <el-descriptions-item label="凭证号">{{ result.voucherNo }}</el-descriptions-item>
          <el-descriptions-item label="涉及发票">{{ result.invoiceCount }} 张</el-descriptions-item>
        </el-descriptions>
        <el-table :data="result.detail" size="small" border class="mt8">
          <el-table-column prop="invoiceNo" label="发票号" />
          <el-table-column prop="deductibleTax" label="已抵扣税额" />
          <el-table-column prop="transferOut" label="本次转出" />
          <el-table-column prop="cumulativeTransferOut" label="累计转出" />
        </el-table>
      </template>
      <template #footer>
        <el-button type="primary" @click="resultVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  directTransferOut, apportionTransferOut, inputInvoiceList
} from '@/api/fin/red-invoice-void'

const rows = ref([])
const certOptions = ref([])
const directVisible = ref(false)
const directRow = ref(null)
const directForm = ref({})
const apportionVisible = ref(false)
const apportionForm = ref({})
const resultVisible = ref(false)
const result = ref(null)

async function load() {
  try {
    const { data } = await inputInvoiceList({ size: 200 })
    rows.value = data.records || data
  } catch (e) { /* ignore */ }
}

function remain(row) {
  return (Number(row.taxAmount || 0) - Number(row.transferOutAmount || 0)).toFixed(2)
}

async function openDirect(row) {
  directRow.value = row || null
  directForm.value = row
    ? { invoiceId: row.id, amount: undefined, reason: '集体福利' }
    : { invoiceId: '', amount: undefined, reason: '集体福利' }
  try {
    const { data } = await inputInvoiceList({ size: 200 })
    certOptions.value = (data.records || data || []).filter(inv => inv.certStatus === 'CERTIFIED')
  } catch (e) {
    certOptions.value = []
  }
  directVisible.value = true
}

async function doDirect() {
  try {
    const { data } = await directTransferOut(directForm.value)
    ElMessage.success(`转出成功：${data.invoiceNo} 转出 ${data.transferOutAmount}，凭证 ${data.voucherNo}（DRAFT）`)
    directVisible.value = false
    load()
  } catch (e) { /* 422 已提示 */ }
}

function openApportion() {
  const now = new Date()
  apportionForm.value = {
    totalInputTax: undefined, exemptSales: undefined, totalSales: undefined,
    period: `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}`,
    reason: '无法划分的不得抵扣进项转出'
  }
  apportionVisible.value = true
}

const calcAmount = computed(() => {
  const f = apportionForm.value
  if (!f.totalInputTax || !f.totalSales || !f.exemptSales && f.exemptSales !== 0) return '—'
  if (Number(f.totalSales) <= 0) return '—'
  return (Number(f.totalInputTax) * Number(f.exemptSales) / Number(f.totalSales)).toFixed(2)
})

async function doApportion() {
  try {
    const { data } = await apportionTransferOut(apportionForm.value)
    result.value = data
    apportionVisible.value = false
    resultVisible.value = true
    load()
  } catch (e) { /* 422 已提示 */ }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 12px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.title { font-size: 15px; font-weight: 600; }
.mb8 { margin-bottom: 8px; }
.muted { color: #909399; }
.warn { color: #f56c6c; }
.calc { font-size: 16px; font-weight: 600; color: #e6a23c; }
.mt8 { margin-top: 8px; }
</style>
