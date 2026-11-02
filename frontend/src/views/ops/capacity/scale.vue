<template>
  <div class="page">
    <el-button type="primary" @click="collect">立即采集评估</el-button>
    <el-table :data="latest" border size="small" style="margin-top: 10px">
      <el-table-column prop="metricKey" label="指标" width="130">
        <template #default="{ row }">{{ keyLabel(row.metricKey) }}</template>
      </el-table-column>
      <el-table-column prop="metricDate" label="采集日" width="120" />
      <el-table-column prop="metricValue" label="当前值" width="120" />
      <el-table-column label="扩容建议">
        <template #default="{ row }">
          {{ row.metricValue > 80 ? '建议扩容/清理（排入变更窗口执行）' : '水位正常' }}
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 18.4.2 扩容执行（拆分自原容量运维操作台）：采集评估与扩容建议。
 * 指标数据来自 useCapacity.js 与预警页共享。
 */
import { onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { collectCapacityApi } from '@/api/ops/ops-sla'
import { useCapacity } from './useCapacity'
import { keyLabel } from './capacity-helpers'

const { latest, load } = useCapacity()

async function collect() {
  const r = await collectCapacityApi()
  ElMessage.success(`采集完成（${r.data.collected} 指标）`)
  load()
}

onMounted(load)
</script>
