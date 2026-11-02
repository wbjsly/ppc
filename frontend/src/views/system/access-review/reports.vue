<template>
  <div class="uar-page">
    <!-- ================= 16.6.4 复核报告 ================= -->
    <div class="toolbar">
      <el-button type="primary" @click="loadReports">刷新</el-button>
    </div>
    <el-table :data="reports" v-loading="repLoading" border stripe>
      <el-table-column prop="cycleTag" label="周期" width="110" />
      <el-table-column label="覆盖率" width="160">
        <template #default="{ row }">
          <el-progress :percentage="Number(row.coverageRate || 0)" :status="Number(row.coverageRate) >= 100 ? 'success' : ''" />
        </template>
      </el-table-column>
      <el-table-column label="收回" width="140">
        <template #default="{ row }">{{ row.reclaimedItems }} 项（{{ row.reclaimRate }}%）</template>
      </el-table-column>
      <el-table-column label="ADMIN 确认" width="160">
        <template #default="{ row }">
          <span v-if="row.confirmAt">{{ fmtTime(row.confirmAt) }}</span>
          <el-button v-else link type="primary" @click="doConfirm(row)">确认</el-button>
        </template>
      </el-table-column>
      <el-table-column label="内审会签" width="180">
        <template #default="{ row }">
          <el-tag v-if="row.archived === '1'" type="success" size="small">已归档</el-tag>
          <el-button v-else-if="row.confirmAt" link type="warning" @click="doSign(row)">内审会签</el-button>
          <span v-else class="muted">待 ADMIN 确认</span>
        </template>
      </el-table-column>
      <el-table-column prop="taskStatus" label="任务状态" width="100">
        <template #default="{ row }">
          <el-tag :type="taskType(row.taskStatus)" size="small">{{ taskLabel(row.taskStatus) }}</el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 16.6.4 复核报告（拆分自原存取复核操作台）：报告台账 / ADMIN 确认 / 内审会签归档。
 * 报告数据为本页独有；任务状态标签映射见 uar-helpers.js。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/access-review'
import { useAccessReview } from './useAccessReview'
import { fmtTime, taskType, taskLabel } from './uar-helpers'

const { loadTasks } = useAccessReview()

const reports = ref([])
const repLoading = ref(false)

async function loadReports() {
  repLoading.value = true
  try {
    const { data } = await api.uarReportList()
    reports.value = data || []
  } finally {
    repLoading.value = false
  }
}

async function doConfirm(row) {
  await api.uarConfirm(row.taskId)
  ElMessage.success('已确认（双签第一步）')
  loadReports()
}

async function doSign(row) {
  await ElMessageBox.confirm('确认内审会签并归档？会签后报告不可更改（C-5.6-05）。', '内审会签',
    { type: 'warning' })
  await api.uarSign(row.taskId)
  ElMessage.success('已会签归档')
  loadReports()
  loadTasks()
}

onMounted(loadReports)
</script>

<style scoped>
.uar-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.muted { color: #909399; font-size: 12px; }
</style>
