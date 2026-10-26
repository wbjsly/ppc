<template>
  <div class="page">
    <div class="head">
      <span class="title">{{ pageTitle }}（三单匹配 → 查验 → 认证 → 进项凭证）</span>
      <div>
        <el-input v-model="query.period" placeholder="认证所属期 YYYYMM" style="width: 150px; margin-right: 8px" size="small" />
        <el-button size="small" @click="load">查询</el-button>
        <el-button type="primary" size="small" @click="openRegister">登记进项发票</el-button>
        <el-button type="success" size="small" @click="doGenerateVouchers">生成本期进项凭证</el-button>
      </div>
    </div>

    <el-alert v-if="expiryRows.length" type="warning" :closable="false" class="mb8"
      :title="`C-4.14-07 预警：${expiryRows.length} 张发票临认证到期，请尽快勾选认证`" />

    <el-table :data="rows" size="small" border>
      <el-table-column prop="invoiceNo" label="发票号" width="140" />
      <el-table-column prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
      <el-table-column prop="poNo" label="PO" width="130" />
      <el-table-column prop="amount" label="金额" width="100" />
      <el-table-column prop="taxAmount" label="税额" width="90" />
      <el-table-column label="三单匹配" width="100">
        <template #default="{ row }">
          <el-tag :type="row.matchStatus === 'MATCHED' ? 'success' : 'danger'" size="small">
            {{ row.matchStatus === 'MATCHED' ? '通过' : '超差' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="amountDiffRate" label="差异率" width="90">
        <template #default="{ row }">{{ row.amountDiffRate ? (Number(row.amountDiffRate) * 100).toFixed(2) + '%' : '—' }}</template>
      </el-table-column>
      <el-table-column label="认证状态" width="110">
        <template #default="{ row }">
          <el-tag :type="certTag(row.certStatus)" size="small">{{ certText(row.certStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="certPeriod" label="所属期" width="80" />
      <el-table-column label="抵扣" width="80">
        <template #default="{ row }">
          <el-tag :type="row.deductible === 'Y' ? 'success' : 'danger'" size="small">
            {{ row.deductible === 'Y' ? '可抵扣' : '不可抵' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="凭证" width="130">
        <template #default="{ row }">
          <span v-if="row.voucherId" class="muted">{{ row.voucherId.slice(0, 8) }}…</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <template v-if="row.certStatus === 'PENDING_INSPECT'">
            <el-button link type="warning" size="small" @click="openInspect(row)">查验登记</el-button>
          </template>
          <template v-else-if="row.certStatus === 'INVALID'">
            <span class="muted">查验失败作废（FR-4.14-3-2）</span>
          </template>
          <template v-else>
            <el-button v-if="row.certStatus === 'PENDING_CERT'" link type="primary" size="small"
              @click="openCertify(row)">认证登记</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <!-- 登记对话框 -->
    <el-dialog v-model="regVisible" title="进项发票登记（挂 PO 自动三单匹配）" width="540px">
      <el-form label-width="100px" size="small">
        <el-form-item label="发票号" required>
          <el-input v-model="regForm.invoiceNo" />
        </el-form-item>
        <el-form-item label="供应商" required>
          <el-select v-model="regForm.supplierId" filterable style="width: 100%"
            @change="onSupplierChange">
            <el-option v-for="po in poOptions" :key="po.id" :label="po.supplierName + '（' + po.poNo + ' 总额 ' + po.totalAmt + '）'" :value="po.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="开票日" required>
          <el-date-picker v-model="regForm.invoiceDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="价税合计" required>
          <el-input v-model="regForm.amount" />
        </el-form-item>
        <el-form-item label="税额" required>
          <el-input v-model="regForm.taxAmount" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="regVisible = false">取消</el-button>
        <el-button type="primary" size="small" @click="doRegister">登记并匹配</el-button>
      </template>
    </el-dialog>

    <!-- 认证对话框 -->
    <el-dialog v-model="certVisible" title="认证登记（外部勾选后回填）" width="420px">
      <el-form label-width="90px" size="small">
        <el-form-item label="发票号">
          <span>{{ certRow?.invoiceNo }}（税额 {{ certRow?.taxAmount }}）</span>
        </el-form-item>
        <el-form-item label="认证日期" required>
          <el-date-picker v-model="certForm.certDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="certVisible = false">取消</el-button>
        <el-button type="primary" size="small" @click="doCertify">确认认证</el-button>
      </template>
    </el-dialog>

    <!-- 查验登记对话框（add-input-certification D5：FAIL 二次确认置作废） -->
    <el-dialog v-model="inspVisible" title="真伪查验登记（M9-3-1）" width="420px">
      <el-form label-width="90px" size="small">
        <el-form-item label="发票号">
          <span>{{ inspRow?.invoiceNo }}</span>
        </el-form-item>
        <el-form-item label="查验方式">
          <el-select v-model="inspForm.method" style="width: 100%">
            <el-option label="人工核对 MANUAL" value="MANUAL" />
            <el-option label="平台查验 PLATFORM" value="PLATFORM" />
          </el-select>
        </el-form-item>
        <el-form-item label="查验结果" required>
          <el-radio-group v-model="inspForm.result">
            <el-radio-button value="PASS">通过 PASS</el-radio-button>
            <el-radio-button value="FAIL">失败 FAIL</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <el-alert v-if="inspForm.result === 'FAIL'" type="error" :closable="false"
        title="FAIL 将置作废（INVALID）并阻断入账——留档备查，不得认证/凭证" />
      <template #footer>
        <el-button size="small" @click="inspVisible = false">取消</el-button>
        <el-button :type="inspForm.result === 'FAIL' ? 'danger' : 'primary'" size="small"
          @click="doInspect">提交查验</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  registerInput, inputList, inspectInput, certifyInput, generateInputVouchers, nearingExpiry
} from '@/api/fin/invoice-management'
import request from '@/utils/request'

const route = useRoute()
// 9.3.1/9.3.2 接线本页（add-input-certification D5：meta.title 分支，同 CostElement 先例）
const pageTitle = computed(() => route.meta.title || '进项收票')

const query = ref({ period: '' })
const rows = ref([])
const expiryRows = ref([])
const regVisible = ref(false)
const certVisible = ref(false)
const regForm = ref({})
const certForm = ref({})
const certRow = ref(null)
const poOptions = ref([])

async function load() {
  try {
    const params = {}
    if (query.value.period) params.period = query.value.period
    const { data } = await inputList(params)
    rows.value = data
    const { data: exp } = await nearingExpiry()
    expiryRows.value = exp
  } catch (e) { /* ignore */ }
}

async function openRegister() {
  regForm.value = { invoiceNo: '', supplierId: '', invoiceDate: '', amount: '', taxAmount: '' }
  try {
    // PO 选择器数据源（未关闭 PO 列表）
    const { data } = await request({ url: '/api/proc/purchase-orders', method: 'get', params: { status: 'CONFIRMED', size: 100 } })
    poOptions.value = data.records || data || []
  } catch (e) {
    poOptions.value = []
  }
  regVisible.value = true
}

function onSupplierChange(poId) {
  const po = poOptions.value.find(p => p.id === poId)
  if (po) regForm.value.supplierId = po.supplierId
}

async function doRegister() {
  try {
    const { data } = await registerInput({ ...regForm.value })
    ElMessage.success(`登记成功，三单匹配：${data.matchStatus === 'MATCHED' ? '通过' : '超差（阻断认证，需采购确认）'}`)
    regVisible.value = false
    load()
  } catch (e) { /* 422 已提示 */ }
}

const inspVisible = ref(false)
const inspForm = ref({ method: 'MANUAL', result: 'PASS' })
const inspRow = ref(null)

function openInspect(row) {
  inspRow.value = row
  inspForm.value = { method: 'MANUAL', result: 'PASS' }
  inspVisible.value = true
}

async function doInspect() {
  if (inspForm.value.result === 'FAIL') {
    try {
      await ElMessageBox.confirm(
        '查验失败将置「作废（INVALID）」并阻断入账（FR-4.14-3-2）——发票留档备查但不得认证/生成凭证。确认？',
        '查验 FAIL 确认', { type: 'warning' })
    } catch (e) { return } // 取消
  }
  try {
    const { data } = await inspectInput(inspRow.value.id, inspForm.value)
    ElMessage.success(data.certStatus === 'INVALID'
      ? '已置作废（INVALID，阻断入账）' : '查验通过，已进入待认证（三单匹配已执行）')
    inspVisible.value = false
    load()
  } catch (e) { /* 422 已提示 */ }
}

function openCertify(row) {
  certRow.value = row
  certForm.value = { certDate: new Date().toISOString().slice(0, 10) }
  certVisible.value = true
}

async function doCertify() {
  try {
    const { data } = await certifyInput(certRow.value.id, certForm.value)
    ElMessage.success(data.deductible === 'N'
      ? '认证完成：超期自动标记不可抵扣（C-4.14-07）' : '认证完成，可抵扣')
    certVisible.value = false
    load()
  } catch (e) { /* 422 已提示 */ }
}

async function doGenerateVouchers() {
  const period = query.value.period
  if (!period) {
    ElMessage.warning('请先在右上角填写认证所属期（YYYYMM）')
    return
  }
  try {
    const { data } = await generateInputVouchers(period)
    ElMessage.success(`生成 ${data.generated} 张 DRAFT 进项凭证（待审核过账）`)
    load()
  } catch (e) { /* 422 已提示 */ }
}

function certText(s) {
  return { PENDING_INSPECT: '待查验', PENDING_CERT: '待认证', CERTIFIED: '已认证',
    NOT_DEDUCTIBLE: '不可抵扣', INVALID: '作废' }[s] || s
}

function certTag(s) {
  return { PENDING_INSPECT: 'warning', PENDING_CERT: 'info', CERTIFIED: 'success',
    NOT_DEDUCTIBLE: 'danger', INVALID: 'danger' }[s] || ''
}

onMounted(() => { load() })
</script>

<style scoped>
.page { padding: 12px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-size: 16px; font-weight: 600; }
.mb8 { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
</style>
