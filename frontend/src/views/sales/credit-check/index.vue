<template>
  <div class="cc-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="信用检查（3.3.1）"
      description="可用额度 = 信用额度（含有效临时额度）− 应收余额 − 未清 SO 预占 − 本次订单 − 寄售占用（BR-4.3-13）；附账龄超比（>20% 需审批）与近 12 个月付款及时率判定。"
    />

    <!-- 检查执行 -->
    <el-card style="margin-bottom: 12px;">
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold;">实时信用检查</span>
          <div class="header-actions">
            <el-select v-model="customerId" filterable remote :remote-method="searchCustomers"
                       :loading="custLoading" placeholder="选择客户" style="width: 280px;">
              <el-option v-for="c in customers" :key="c.id"
                         :label="`${c.customerCode} ${c.customerName}`" :value="c.id" />
            </el-select>
            <el-input-number v-model="orderAmount" :min="0" :precision="2" placeholder="本次订单金额" />
            <el-button type="primary" :loading="loading" @click="runCheck">执行检查</el-button>
          </div>
        </div>
      </template>

      <template v-if="result">
        <el-result :icon="resultIcon" :title="resultTitle" :sub-title="result.reason || ''">
          <template #extra>
            <el-descriptions :column="3" size="small" border>
              <el-descriptions-item label="信用额度（含临时）">¥ {{ fmt(result.factors.creditLimit) }}</el-descriptions-item>
              <el-descriptions-item label="应收余额">¥ {{ fmt(result.factors.arBalance) }}</el-descriptions-item>
              <el-descriptions-item label="未清 SO 预占">¥ {{ fmt(result.factors.soReserved) }}</el-descriptions-item>
              <el-descriptions-item label="本次订单">¥ {{ fmt(result.factors.orderAmount) }}</el-descriptions-item>
              <el-descriptions-item label="寄售占用">¥ {{ fmt(result.factors.consignOccupied) }}</el-descriptions-item>
              <el-descriptions-item label="可用额度">
                <b :style="{ color: Number(result.factors.available) < 0 ? '#f56c6c' : '#67c23a' }">
                  ¥ {{ fmt(result.factors.available) }}
                </b>
              </el-descriptions-item>
              <el-descriptions-item label="超 90 天应收占比">
                <span :style="{ color: over90High ? '#f56c6c' : '#67c23a' }">
                  {{ pct(result.aging.over90Ratio) }}
                </span>
                <el-tag v-if="over90High" type="danger" size="small" style="margin-left: 6px;">>20% 需审批</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="付款及时率（12 月）">
                <span :style="{ color: rateLow ? '#e6a23c' : '#67c23a' }">{{ pct(result.aging.onTimeRate) }}</span>
                <el-tag v-if="rateLow" type="warning" size="small" style="margin-left: 6px;"><80% 降评级</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="连续逾期次数">{{ result.aging.overdueStreak }}</el-descriptions-item>
            </el-descriptions>
            <div v-if="(result.actions || []).length" style="margin-top: 10px;">
              <el-alert v-for="(a, i) in result.actions" :key="i" type="warning" :closable="false"
                        show-icon :title="a" style="margin-bottom: 6px;" />
            </div>
          </template>
        </el-result>
      </template>
      <el-empty v-else description="选择客户后执行检查" :image-size="80" />
    </el-card>

    <!-- 检查记录 -->
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold;">检查记录（判定留痕）</span>
          <div>
            <el-select v-model="filterResult" placeholder="判定结果" clearable style="width: 150px;" @change="loadChecks(1)">
              <el-option label="通过" value="PASS" />
              <el-option label="信用冻结" value="FROZEN" />
              <el-option label="需审批（账龄）" value="NEED_APPROVAL" />
            </el-select>
            <el-button style="margin-left: 8px;" @click="loadChecks(1)">查询</el-button>
          </div>
        </div>
      </template>
      <el-table :data="checks" v-loading="checksLoading" stripe size="small">
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.checkAt) }}</template>
        </el-table-column>
        <el-table-column prop="customerCode" label="客户" width="110" />
        <el-table-column prop="soNo" label="关联 SO" width="150" show-overflow-tooltip />
        <el-table-column label="可用额度" width="110" align="right">
          <template #default="{ row }">
            <span :style="{ color: Number(row.available) < 0 ? '#f56c6c' : '#303133' }">¥ {{ fmt(row.available) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="超90占比" width="94" align="center">
          <template #default="{ row }">{{ pct(row.over90Ratio) }}</template>
        </el-table-column>
        <el-table-column label="及时率" width="86" align="center">
          <template #default="{ row }">{{ pct(row.payOnTimeRate) }}</template>
        </el-table-column>
        <el-table-column label="判定" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="resType(row.result)">{{ resName(row.result) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="200" show-overflow-tooltip />
      </el-table>
      <el-pagination small style="margin-top: 10px; justify-content: flex-end;"
        v-model:current-page="checkPage" :page-size="10" :total="checkTotal"
        layout="total, prev, pager, next" @current-change="loadChecks" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { checkCreditApi, getChecksApi } from '@/api/sd/credit'
import request from '@/utils/request'

const customerId = ref('')
const orderAmount = ref(0)
const loading = ref(false)
const result = ref(null)

const customers = ref([])
const custLoading = ref(false)

const checks = ref([])
const checksLoading = ref(false)
const checkTotal = ref(0)
const checkPage = ref(1)
const filterResult = ref('')

onMounted(() => {
  searchCustomers('')
  loadChecks(1)
})

async function searchCustomers(kw) {
  custLoading.value = true
  try {
    const res = await request.get('/mdm/customer-groups', { params: { current: 1, size: 20, keyword: kw || '' } })
    customers.value = res.data.records || []
  } finally {
    custLoading.value = false
  }
}

async function runCheck() {
  if (!customerId.value) {
    ElMessage.warning('请选择客户')
    return
  }
  loading.value = true
  try {
    const res = await checkCreditApi({ customerId: customerId.value, orderAmount: orderAmount.value })
    result.value = res.data
    loadChecks(1)
  } finally {
    loading.value = false
  }
}

async function loadChecks(page) {
  if (page) checkPage.value = page
  checksLoading.value = true
  try {
    const res = await getChecksApi({ current: checkPage.value, size: 10, result: filterResult.value || '' })
    checks.value = res.data.records || []
    checkTotal.value = res.data.total
  } finally {
    checksLoading.value = false
  }
}

const resultIcon = computed(() => {
  const r = result.value && result.value.result
  return r === 'PASS' ? 'success' : r === 'FROZEN' ? 'error' : 'warning'
})
const resultTitle = computed(() => {
  const r = result.value && result.value.result
  return r === 'PASS' ? '信用检查通过' : r === 'FROZEN'
    ? `信用冻结（缺口 ¥ ${fmt(result.value.gap)}）` : '账龄超比需信用管理员审批'
})
const over90High = computed(() => result.value && Number(result.value.aging.over90Ratio) > 0.2)
const rateLow = computed(() => result.value && result.value.aging.onTimeRate != null
  && Number(result.value.aging.onTimeRate) < 0.8)

function resName(s) { return { PASS: '通过', FROZEN: '信用冻结', NEED_APPROVAL: '需审批' }[s] || s }
function resType(s) { return { PASS: 'success', FROZEN: 'danger', NEED_APPROVAL: 'warning' }[s] || 'info' }
function pct(v) { return v == null ? '-' : (Number(v) * 100).toFixed(1) + '%' }
function fmt(v) { return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '-' }
</script>

<style scoped>
.cc-page { padding: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
</style>
