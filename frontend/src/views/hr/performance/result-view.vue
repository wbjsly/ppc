<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">绩效结果（10.10.4，薪酬核算取数来源）</span>
        <div>
          <el-input v-model="period" placeholder="周期 如 2026H2" clearable style="width:140px;margin-right:8px" @change="load" />
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-alert type="info" :closable="false" style="margin-bottom:12px"
                title="结果快照确认后不可改；薪酬核算按员工×核算期取等级系数，无结果按系数 1.0 计发并标注" />
      <el-table :data="results" height="460">
        <el-table-column prop="period" label="周期" width="100" />
        <el-table-column prop="empNo" label="工号" width="110" />
        <el-table-column prop="empName" label="姓名" width="100" />
        <el-table-column prop="grade" label="等级" width="70" />
        <el-table-column prop="coef" label="系数" width="70" />
        <el-table-column prop="confirmBy" label="确认人" width="110" />
        <el-table-column prop="confirmAt" label="确认时间" width="170" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { resultList } from '@/api/hr/performance'

const period = ref('')
const results = ref([])

async function load() {
  const { data } = await resultList(period.value ? { period: period.value } : {})
  results.value = data || []
}
onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
