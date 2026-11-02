<template>
  <div class="eam-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px;"
      title="凭证生成（7.2.3）：折旧凭证与明细双向反查，勾稽差异"
      description="凭证 sourceType=DEPRECIATION，来源单号=run 单号，明细↔凭证双向可反查。" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="vSearch" placeholder="来源单号（run 单号，如 JZ202609-0001）" style="width:260px;" clearable />
        <el-button type="primary" @click="loadVouchers">反查凭证</el-button>
      </div>

      <el-table :data="voucherRows" size="small" border v-loading="loading">
        <el-table-column prop="voucherNo" label="凭证号" width="150">
          <template #default="{ row }"><b>{{ row.voucherNo }}</b></template>
        </el-table-column>
        <el-table-column prop="voucherType" label="类型" width="80" align="center" />
        <el-table-column prop="period" label="会计月" width="90" align="center" />
        <el-table-column label="借方合计" width="120" align="right">
          <template #default="{ row }">¥ {{ fmt(row.totalDr) }}</template>
        </el-table-column>
        <el-table-column label="贷方合计" width="120" align="right">
          <template #default="{ row }">¥ {{ fmt(row.totalCr) }}</template>
        </el-table-column>
        <el-table-column prop="sourceDocNo" label="来源单号" width="150" />
        <el-table-column prop="status" label="状态" width="90" align="center" />
        <el-table-column label="操作" width="110" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openVoucherDetails(row)">凭证明细</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" style="margin-top:12px;" v-if="voucherDetail.rows">
      <template #header><span>凭证明细 —— {{ voucherDetail.voucherNo }}</span></template>
      <el-table :data="voucherDetail.rows" size="small" border>
        <el-table-column prop="lineNo" label="行号" width="70" align="center" />
        <el-table-column prop="accountCode" label="科目" width="110" align="center" />
        <el-table-column label="方向" width="70" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.direction === 'DR' ? 'danger' : 'success'">
              {{ row.direction === 'DR' ? '借' : '贷' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="130" align="right">
          <template #default="{ row }">¥ {{ fmt(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="summary" label="摘要" min-width="160" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
/**
 * 7.2.3 凭证生成（拆分自原折旧管理操作台，原为页内 v-else 隐藏 tab）：
 * 折旧凭证按来源单号反查与明细展开。共享格式化见 dep-helpers.js。
 */
import { ref, reactive, onMounted } from 'vue'
import { getVoucherApi, getDetailsByVoucherApi } from '@/api/eam/depreciation'
import { fmt } from './dep-helpers'

const loading = ref(false)
const vSearch = ref('')
const voucherRows = ref([])
const voucherDetail = reactive({ voucherNo: '', rows: null })

async function loadVouchers() {
  loading.value = true
  try {
    const res = await getVoucherApi(vSearch.value || undefined)
    voucherRows.value = res.data || []
  } finally { loading.value = false }
}

async function openVoucherDetails(row) {
  const res = await getDetailsByVoucherApi(row.id)
  voucherDetail.voucherNo = row.voucherNo
  voucherDetail.rows = res.data || []
}

onMounted(() => loadVouchers())
</script>

<style scoped>
.eam-page { padding: 12px; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
</style>
