<template>
  <div class="page">
    <el-table :data="tickets" border size="small">
      <el-table-column prop="alertNo" label="告警号" width="190" />
      <el-table-column prop="level" label="级别" width="70" />
      <el-table-column prop="title" label="标题" />
      <el-table-column prop="escalationLevel" label="升级层级" width="90">
        <template #default="{ row }">L{{ row.escalationLevel }}</template>
      </el-table-column>
      <el-table-column prop="handler" label="当前处理人" width="120" />
      <el-table-column prop="status" label="状态" width="100" />
    </el-table>
  </div>
</template>

<script setup>
/**
 * 18.3.3 升级工单（拆分自原升级处置操作台）：升级产生的工单台账。
 * 列表数据来自 useEscalation.js，与层级升级页扫描交叉刷新。
 */
import { onMounted } from 'vue'
import { useEscalation } from './useEscalation'

const { tickets, loadTickets } = useEscalation()

onMounted(loadTickets)
</script>
