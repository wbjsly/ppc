<template>
  <div class="page">
    <el-form inline>
      <el-form-item label="天数">
        <el-input-number v-model="days" :min="7" :max="90" @change="loadTrend" />
      </el-form-item>
      <el-button @click="loadTrend">查询</el-button>
    </el-form>
    <el-table :data="trend" border size="small">
      <el-table-column prop="metricDate" label="日期" width="120" />
      <el-table-column prop="category" label="类别" width="100">
        <template #default="{ row }">{{ catLabel(row.category) }}</template>
      </el-table-column>
      <el-table-column prop="avgDelayMin" label="平均延迟(分)" width="130" />
      <el-table-column prop="cnt" label="样本数" width="90" />
    </el-table>
  </div>
</template>

<script setup>
/**
 * 18.2.3 趋势分析（拆分自原监控处置操作台）：延迟趋势按类别聚合查询。
 * 单页独有逻辑，未抽 composable。
 */
import { ref, onMounted } from 'vue'
import { delayTrendApi } from '@/api/ops/ops-sla'

const days = ref(14)
const trend = ref([])

onMounted(loadTrend)

async function loadTrend() {
  const r = await delayTrendApi(days.value)
  trend.value = r.data || []
}

function catLabel(c) { return { ORDER: '订单', STOCK: '库存', RECON: '对账' }[c] || c }
</script>
