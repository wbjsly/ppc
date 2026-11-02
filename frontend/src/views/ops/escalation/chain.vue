<template>
  <div class="page">
    <el-button type="primary" @click="scan">手动触发升级扫描</el-button>
    <span style="margin-left: 10px; color: #909399; font-size: 12px">
      调度每 5 分钟自动扫描：P1 15 分钟未认领升级 / 层级 30 分钟 L1→L2→L3（BR-5.8-02/03）
    </span>
    <el-table :data="logs" border size="small" style="margin-top: 10px">
      <el-table-column prop="alertId" label="告警ID" width="200" show-overflow-tooltip />
      <el-table-column prop="fromLevel" label="原层级" width="80">
        <template #default="{ row }">{{ row.fromLevel || '—' }}</template>
      </el-table-column>
      <el-table-column prop="toLevel" label="升级至" width="80" />
      <el-table-column prop="reason" label="原因" />
      <el-table-column prop="escalateAt" label="升级时间" width="160">
        <template #default="{ row }">{{ fmt(row.escalateAt) }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 18.3.2 层级升级（拆分自原升级处置操作台）：升级扫描与升级日志。
 * 扫描后除刷新本页日志外，经 useEscalation.js 交叉刷新升级工单。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { escalationLogsApi, scanEscalationApi } from '@/api/ops/ops-sla'
import { useEscalation } from './useEscalation'

const { loadTickets } = useEscalation()

const logs = ref([])

onMounted(loadLogs)

async function loadLogs() {
  const r = await escalationLogsApi({ limit: 50 })
  logs.value = r.data || []
}

async function scan() {
  const r = await scanEscalationApi()
  ElMessage.success(`扫描完成，处理 ${r.data.handled} 条`)
  loadLogs(); loadTickets()
}

function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '' }
</script>
