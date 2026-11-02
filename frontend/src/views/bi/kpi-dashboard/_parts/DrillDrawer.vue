<template>
  <!-- 下钻抽屉（14.7.1 指标达成「下钻」/ 14.7.3 异常预警「查看原因分析」两页共用） -->
  <el-drawer v-model="drillVisible" :title="`下钻：${drillName}`" size="70%">
    <el-table :data="drillRows" size="small" border max-height="60vh">
      <el-table-column v-for="h in drillHeaders" :key="h" :prop="h" :label="h" />
    </el-table>
  </el-drawer>
</template>

<script setup>
/**
 * KPI 下钻结果抽屉（跨页共用，design D2：状态放 useKpiDashboard.js 单例）。
 * 由指标达成页与异常预警页经 openDrill() 打开。
 */
import { useKpiDashboard } from '../useKpiDashboard'

const { drillVisible, drillName, drillHeaders, drillRows } = useKpiDashboard()
</script>
