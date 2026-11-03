<template>
  <div class="page ar-mgmt-page">
    <!-- ================= 8.2.1 应收确认 ================= -->
    <el-form inline>
      <el-radio-group v-model="cfConfirmed" @change="loadConfirm">
        <el-radio-button :value="false">未确认</el-radio-button>
        <el-radio-button :value="true">已确认</el-radio-button>
      </el-radio-group>
      <el-input v-model="cfQuery.keyword" clearable placeholder="单号/客户" style="width:180px;margin-left:12px" />
      <el-button type="primary" @click="loadConfirm">查询</el-button>
      <el-button type="success" :disabled="cfSelection.length === 0" @click="onBatchConfirm">
        批量确认（{{ cfSelection.length }}）
      </el-button>
    </el-form>
    <el-table :data="cfRows" border size="small" @selection-change="(v) => (cfSelection = v)">
      <el-table-column v-if="!cfConfirmed" type="selection" width="40" />
      <el-table-column prop="arNo" label="应收单号" width="160" />
      <el-table-column prop="customerName" label="客户" min-width="160" show-overflow-tooltip />
      <el-table-column prop="invoiceDate" label="开票日" width="110" />
      <el-table-column prop="dueDate" label="到期日" width="110" />
      <el-table-column label="未核销余额" width="120" align="right">
        <template #default="{ row }">{{ balOf(row) }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90" />
      <el-table-column v-if="cfConfirmed" prop="confirmBy" label="确认人" width="110" />
      <el-table-column v-if="cfConfirmed" prop="confirmDate" label="确认时间" width="160">
        <template #default="{ row }">{{ fmtDt(row.confirmDate) }}</template>
      </el-table-column>
      <el-table-column v-if="!cfConfirmed" label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="onConfirm(row)">确认</el-button>
        </template>
      </el-table-column>
    </el-table>
        <AppPagination
      v-model:current-page="cfQuery.current"
      v-model:page-size="cfQuery.size"
      :total="cfTotal"
      @current-change="loadConfirm"
      style="margin-top:8px;justify-content:flex-end"
    />
    <el-alert type="info" :closable="false" show-icon style="margin-top:8px"
      title="确认标记是账龄起算锚点：未确认的应收不计入账龄分析与坏账计提基数；已确认不可撤销。" />
  </div>
</template>

<script setup>
import AppPagination from '@/components/AppPagination.vue'
/**
 * 8.2.1 应收确认（拆分自原应收管理多 tab 页，design D1）。
 * 本页独有逻辑，无跨页共享状态（D2：无共享未抽）。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { confirmPage, confirmAr, batchConfirm } from '@/api/fin/ar-aging'

const cfConfirmed = ref(false)
const cfQuery = reactive({ current: 1, size: 20, keyword: '' })
const cfRows = ref([])
const cfTotal = ref(0)
const cfSelection = ref([])

function balOf(row) {
  const a = Number(row.amount || 0)
  const p = Number(row.paidAmount || 0)
  const r = Number(row.redAmount || 0)
  return (a - p - r).toFixed(2)
}
function fmtDt(v) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : ''
}

async function loadConfirm() {
  const { data } = await confirmPage({
    ...cfQuery, confirmed: cfConfirmed.value, keyword: cfQuery.keyword || undefined
  })
  cfRows.value = data.records || []
  cfTotal.value = Number(data.total || 0)
}

async function onConfirm(row) {
  await ElMessageBox.confirm(`确认应收 ${row.arNo}？确认后不可撤销，且作为账龄起算锚点。`, '确认', { type: 'warning' })
  await confirmAr(row.id)
  ElMessage.success('已确认')
  loadConfirm()
}

async function onBatchConfirm() {
  await ElMessageBox.confirm(`批量确认选中的 ${cfSelection.length} 张应收？确认后不可撤销。`, '批量确认', { type: 'warning' })
  const { data } = await batchConfirm(cfSelection.map((r) => r.id))
  ElMessage.success(`已确认 ${data.confirmed} 张`)
  if (data.skipped?.length) {
    ElMessage.warning(`跳过 ${data.skipped.length} 张：${data.skipped[0]}`)
  }
  loadConfirm()
}

onMounted(() => {
  loadConfirm()
})
</script>
