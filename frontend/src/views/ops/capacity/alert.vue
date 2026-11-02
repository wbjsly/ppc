<template>
  <div class="page">
    <el-form inline>
      <el-form-item label="天数">
        <el-input-number v-model="days" :min="7" :max="90" @change="load" />
      </el-form-item>
      <el-button @click="load">查询</el-button>
    </el-form>
    <el-table :data="metrics" border size="small">
      <el-table-column prop="metricDate" label="日期" width="120" />
      <el-table-column prop="metricKey" label="指标" width="130">
        <template #default="{ row }">{{ keyLabel(row.metricKey) }}</template>
      </el-table-column>
      <el-table-column prop="metricValue" label="数值" width="120">
        <template #default="{ row }">
          <span :style="{ color: row.metricValue > 80 ? '#f56c6c' : '#67c23a' }">{{ row.metricValue }}{{ row.unit === '%' ? '%' : ' MB' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="unit" label="单位" width="70" />
    </el-table>
    <el-alert type="info" :closable="false" style="margin-top: 10px"
              title="任一指标连续 3 天 >80% 自动产生 P1 容量告警并外推红线日期（BR-5.8-10）；CPU 为进程级口径" />
  </div>
</template>

<script setup>
/**
 * 18.4.1 容量预警（拆分自原容量运维操作台）：天数筛选与容量指标台账。
 * 共享状态见 useCapacity.js，指标键映射见 capacity-helpers.js。
 */
import { onMounted } from 'vue'
import { useCapacity } from './useCapacity'
import { keyLabel } from './capacity-helpers'

const { days, metrics, load } = useCapacity()

onMounted(load)
</script>
