<template>
  <div class="page ap-mgmt-page">
    <!-- ================= 8.3.2 应付核销（账龄 + 对账单 + 核销记录） ================= -->
    <el-form inline>
      <el-input v-model="agSupplierId" clearable placeholder="供应商 ID（账龄）" style="width:160px" />
      <el-button type="primary" @click="loadAging" style="margin-left:8px">账龄分析</el-button>
      <el-button type="warning" plain @click="onBackfillDueDate">补录存量到期日</el-button>
    </el-form>
    <template v-if="agData">
      <el-row :gutter="10" style="margin-bottom:10px">
        <el-col :span="4" v-for="b in agBuckets" :key="b.label">
          <el-card shadow="never" body-style="padding:8px">
            <div class="bucket-label" :class="{ overdue: isOverdue(b.label) }">{{ b.label }}</div>
            <div class="bucket-base">{{ b.base }}</div>
            <div class="bucket-ratio">{{ (Number(b.ratio) * 100).toFixed(1) }}%</div>
          </el-card>
        </el-col>
      </el-row>
      <el-alert v-if="Number(agData.overdueRatio) > 0" type="warning" :closable="false" show-icon style="margin-bottom:8px"
        :title="`超期合计 ${agData.overdue}（占比 ${(Number(agData.overdueRatio) * 100).toFixed(1)}%）；未补录到期日 ${agData.nullDueCount} 张已跳过`" />
    </template>
    <el-divider />
    <el-tabs type="border-card">
      <el-tab-pane label="供应商对账单">
        <el-table :data="stRows" border size="small">
          <el-table-column prop="statementNo" label="对账单号" width="160" show-overflow-tooltip />
          <el-table-column prop="supplierName" label="供应商" min-width="150" show-overflow-tooltip />
          <el-table-column prop="period" label="期间" width="90" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="primary" @click="onStatementDetail(row)">详情</el-button>
              <el-button v-if="row.status === 'MATCHED'" link type="success" @click="onConfirmStatement(row)">确认</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="核销记录">
        <el-table :data="woRows" border size="small">
          <el-table-column prop="invoiceNo" label="发票号" width="150" show-overflow-tooltip />
          <el-table-column prop="supplierName" label="供应商" min-width="140" show-overflow-tooltip />
          <el-table-column prop="amount" label="核销金额" width="120" align="right" />
          <el-table-column prop="kind" label="类型" width="90" />
          <el-table-column prop="writeoffDate" label="核销日" width="110" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
/**
 * 8.3.2 应付核销（拆分自原应付管理多 tab 页，design D1）。
 * 本页独有逻辑，无跨页共享状态（D2：无共享未抽）。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  apAging, backfillDueDate,
  statementPage, statementDetail, confirmStatement, writeoffPage
} from '@/api/fin/ap'

const agSupplierId = ref('')
const agData = ref(null)
const agBuckets = ref([])
const stRows = ref([])
const woRows = ref([])

function isOverdue(label) {
  const m = String(label).match(/^(\d+)-/)
  if (m) return Number(m[1]) > 0
  return String(label).endsWith('+')
}

async function onBackfillDueDate() {
  await ElMessageBox.confirm('按供应商账期批量回填存量发票到期日（DUE_DATE 为空且账期为数字天数的发票）？', '补录到期日', { type: 'warning' })
  const { data } = await backfillDueDate()
  ElMessage.success(`已回填 ${data.updated} 张，无账期跳过 ${data.skippedNoTerms} 张`)
  loadAging()
}

async function loadAging() {
  const { data } = await apAging(agSupplierId.value ? { supplierId: agSupplierId.value } : {})
  agData.value = data
  agBuckets.value = data.buckets || []
}

async function loadStatements() {
  const { data } = await statementPage({ current: 1, size: 50 })
  stRows.value = data.records || []
}

async function onStatementDetail(row) {
  const { data } = await statementDetail(row.id)
  ElMessageBox.alert(JSON.stringify(data, null, 2), '对账单详情', { customStyle: { width: '60%' } })
}

async function onConfirmStatement(row) {
  await ElMessageBox.confirm(`确认对账单 ${row.statementNo}？`, '确认', { type: 'warning' })
  await confirmStatement(row.id)
  ElMessage.success('已确认')
  loadStatements()
}

async function loadWriteoffs() {
  const { data } = await writeoffPage({ current: 1, size: 50 })
  woRows.value = data.records || []
}

onMounted(() => {
  loadAging()
  loadStatements()
  loadWriteoffs()
})
</script>

<style scoped>
.bucket-label { font-size: 12px; color: #909399; }
.bucket-label.overdue { color: #f56c6c; font-weight: 600; }
.bucket-base { font-size: 17px; font-weight: 600; margin: 3px 0 2px; }
.bucket-ratio { font-size: 12px; color: #606266; }
</style>
