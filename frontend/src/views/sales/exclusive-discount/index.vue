<template>
  <div class="ed-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="专属折扣（3.6.3）"
      description="客户专属价（EXCLUSIVE 协议）列表视图，取价优先级最高（专属 > 阶梯 > 时间）。协议的创建与维护在客户管理 1.3.3，本页只读展示销售侧口径。"
    />

    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">EXCLUSIVE 专属协议</span>
          <div class="header-actions">
            <el-input v-model="keyword" placeholder="协议编号/名称" clearable
                      :prefix-icon="Search" style="width: 200px;" @keyup.enter="load(1)" />
            <el-select v-model="status" placeholder="状态" clearable style="width: 130px;" @change="load(1)">
              <el-option label="生效中" value="0" />
              <el-option label="待生效" value="1" />
              <el-option label="已过期" value="2" />
              <el-option label="已停用" value="3" />
            </el-select>
            <el-button type="primary" @click="load(1)">查询</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe @expand-change="loadLines">
        <el-table-column type="expand">
          <template #default="{ row }">
            <el-table :data="row._lines || []" size="small" v-loading="row._loading"
                      style="margin: 8px 40px;" empty-text="无价格行">
              <el-table-column prop="itemCode" label="SKU" width="160" />
              <el-table-column label="专属价" width="120" align="right">
                <template #default="{ row: l }">¥ {{ l.unitPrice }}</template>
              </el-table-column>
              <el-table-column prop="minQty" label="数量下限" width="100" align="right" />
              <el-table-column prop="maxQty" label="数量上限" width="100" align="right" />
            </el-table>
          </template>
        </el-table-column>
        <el-table-column prop="paCode" label="协议编号" width="130" />
        <el-table-column prop="paName" label="协议名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="挂靠客户" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.customerName || row.customerGroupId || '-' }}</template>
        </el-table-column>
        <el-table-column label="有效期" width="200">
          <template #default="{ row }">{{ row.effectiveDate }} ~ {{ row.expireDate || '按协议' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="94">
          <template #default="{ row }">
            <el-tag size="small" :type="stType(row.status)">{{ stName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end;"
        v-model:current-page="current" :page-size="size" :total="total"
        layout="total, prev, pager, next" @current-change="load" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import request from '@/utils/request'

const loading = ref(false)
const rows = ref([])
const current = ref(1)
const size = 10
const total = ref(0)
const keyword = ref('')
const status = ref('')

onMounted(() => load(1))

async function load(page) {
  if (page) current.value = page
  loading.value = true
  try {
    const res = await request.get('/mdm/price-agreements', {
      params: { current: current.value, size, keyword: keyword.value || '', agreementType: 'EXCLUSIVE', status: status.value || '' }
    })
    rows.value = (res.data.records || []).map(r => ({ ...r, _lines: [], _loading: false }))
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

async function loadLines(row) {
  if (row._lines.length || row._loading) return
  row._loading = true
  try {
    const res = await request.get(`/mdm/price-agreements/${row.id}`)
    row._lines = res.data.lines || []
  } finally {
    row._loading = false
  }
}

function stName(s) {
  return { '0': '生效中', '1': '待生效', '2': '已过期', '3': '已停用' }[s] || s
}
function stType(s) {
  return { '0': 'success', '1': 'info', '2': 'info', '3': 'danger' }[s] || 'info'
}
</script>

<style scoped>
.ed-page { padding: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
