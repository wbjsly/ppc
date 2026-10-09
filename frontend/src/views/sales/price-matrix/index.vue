<template>
  <div class="pm-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="价格矩阵（3.6.1）"
      description="按客户 × SKU 展示最终取价结果：协议基准价 → 渠道/阶梯/促销三层折扣 → 叠加规则 → 最终生效价与命中来源。只读视图，数据来自协议试算与折扣引擎。"
    />

    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">取价矩阵</span>
          <div class="header-actions">
            <el-select v-model="customerId" filterable remote :remote-method="searchCustomers"
                       :loading="custLoading" placeholder="选择客户" style="width: 260px;">
              <el-option v-for="c in customers" :key="c.id"
                         :label="`${c.customerCode} ${c.customerName}`" :value="c.id" />
            </el-select>
            <el-input v-model="itemsText" placeholder="SKU，逗号分隔" style="width: 260px;" />
            <el-input-number v-model="qty" :min="1" style="width: 120px;" />
            <el-button type="primary" :loading="loading" @click="runMatrix">查询矩阵</el-button>
          </div>
        </div>
      </template>

      <div v-if="warnings.length" style="margin-bottom: 8px;">
        <el-alert v-for="(w, i) in warnings" :key="i" type="warning" :closable="false" show-icon
                  :title="w" style="margin-bottom: 6px;" />
      </div>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="itemCode" label="SKU" width="150" />
        <el-table-column prop="itemName" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="协议基准价" width="110" align="right">
          <template #default="{ row }">{{ row.basePrice == null ? '-' : row.basePrice }}</template>
        </el-table-column>
        <el-table-column label="协议" width="120">
          <template #default="{ row }">
            <template v-if="row.protocolMatched">
              <el-tag size="small">{{ row.paType }}</el-tag> {{ row.paCode }}
            </template>
            <span v-else style="color: #f56c6c;">无协议</span>
          </template>
        </el-table-column>
        <el-table-column label="渠道折扣" width="94" align="center">
          <template #default="{ row }">{{ pct(row.channelRate) }}</template>
        </el-table-column>
        <el-table-column label="阶梯折扣" width="94" align="center">
          <template #default="{ row }">{{ pct(row.ladderRate) }}</template>
        </el-table-column>
        <el-table-column label="促销折扣" width="94" align="center">
          <template #default="{ row }">{{ pct(row.promoRate) }}</template>
        </el-table-column>
        <el-table-column label="叠加" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.stackMode === 'STACKED' ? 'warning' : 'info'">
              {{ row.stackMode === 'STACKED' ? '逐层叠加' : '最优单层' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最终生效价" width="120" align="right">
          <template #default="{ row }">
            <b v-if="row.finalPrice != null">¥ {{ row.finalPrice }}</b>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="命中来源" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.appliedSource && row.appliedSource !== 'NONE'" size="small" type="success">
              {{ srcName(row.appliedSource) }}
            </el-tag>
            <span v-else>基准价</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="160">
          <template #default="{ row }">
            <el-tag v-if="row.marginBlocked" type="danger" size="small">低于毛利阈值（需特批）</el-tag>
            <span v-else-if="row.error" style="color: #f56c6c;">{{ row.error }}</span>
            <span v-else style="color: #67c23a;">正常</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="meta" class="meta">叠加规则：{{ meta === 'STACKED' ? '渠道 > 量价 > 时间 逐层递减' : '最优单层（默认）' }}</div>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getMatrixApi } from '@/api/sd/pricing'
import request from '@/utils/request'

const customerId = ref('')
const itemsText = ref('')
const qty = ref(100)
const loading = ref(false)
const rows = ref([])
const warnings = ref([])
const meta = ref('')

const customers = ref([])
const custLoading = ref(false)

async function searchCustomers(kw) {
  custLoading.value = true
  try {
    const res = await request.get('/mdm/customer-groups', { params: { current: 1, size: 20, keyword: kw || '' } })
    customers.value = res.data.records || []
  } finally {
    custLoading.value = false
  }
}

async function runMatrix() {
  if (!customerId.value) {
    ElMessage.warning('请选择客户')
    return
  }
  const items = itemsText.value.split(',').map(s => s.trim()).filter(Boolean)
  if (!items.length) {
    ElMessage.warning('请输入至少一个 SKU（逗号分隔）')
    return
  }
  loading.value = true
  try {
    const res = await getMatrixApi(customerId.value, items.join(','), qty.value)
    rows.value = res.data.rows || []
    meta.value = res.data.stackMode
    warnings.value = rows.value.flatMap(r => r.warnings || [])
  } finally {
    loading.value = false
  }
}

function pct(v) { return v == null ? '-' : (Number(v) * 100).toFixed(1) + '%' }
function srcName(s) {
  return { CHANNEL: '渠道', LADDER: '阶梯', PROMO: '促销', STACKED: '叠加', PROTOCOL: '协议', NONE: '无' }[s] || s
}
</script>

<style scoped>
.pm-page { padding: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.meta { margin-top: 8px; color: #909399; font-size: 12px; }
</style>
