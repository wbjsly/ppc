<template>
  <div class="page">
    <el-button type="primary" @click="collect">手动采集一轮</el-button>
    <el-table :data="alerts.filter(a => a.source === 'sync-delay')" border size="small" style="margin-top: 10px">
      <el-table-column prop="alertNo" label="告警号" width="200" />
      <el-table-column prop="title" label="标题" />
      <el-table-column prop="metricValue" label="延迟(分)" width="100" />
      <el-table-column prop="threshold" label="阈值" width="80" />
      <el-table-column prop="createDate" label="时间" width="160">
        <template #default="{ row }">{{ fmt(row.createDate) }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 18.2.1 延迟采集（拆分自原监控处置操作台）：手动采集一轮并展示同步延迟告警。
 * 告警列表来自 useMonitor.js（与阈值告警页共享），时间格式见 monitor-helpers.js。
 */
import { onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { collectDelayApi } from '@/api/ops/ops-sla'
import { useMonitor } from './useMonitor'
import { fmt } from './monitor-helpers'

const { alerts, load } = useMonitor()

async function collect() {
  const r = await collectDelayApi()
  ElMessage.success(`采集完成（${r.data.collected} 类）`)
  load()
}

onMounted(load)
</script>
