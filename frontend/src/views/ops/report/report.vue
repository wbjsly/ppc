<template>
  <div class="page">
    <el-form inline>
      <el-form-item label="月份">
        <el-date-picker v-model="period" type="month" value-format="YYYY-MM" style="width: 140px" />
      </el-form-item>
      <el-button type="primary" @click="generate">生成报告</el-button>
      <el-button @click="load">刷新</el-button>
    </el-form>
    <template v-if="report">
      <el-descriptions :column="3" border size="small" style="margin-bottom: 12px">
        <el-descriptions-item label="状态">
          <el-tag :type="report.status === 'ARCHIVED' ? 'success' : report.status === 'PUBLISHED' ? 'success' : 'info'">
            {{ report.status }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="生成时间">{{ report.generatedAt || '—' }}</el-descriptions-item>
        <el-descriptions-item label="未达标改进项">{{ (report.metrics?.improvementIds || []).length }} 项</el-descriptions-item>
      </el-descriptions>
      <el-table :data="report.metrics?.items || []" border size="small">
        <el-table-column prop="name" label="指标" />
        <el-table-column prop="actual" label="实际" width="110" />
        <el-table-column prop="target" label="目标" width="110" />
        <el-table-column label="达标" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.met ? 'success' : 'danger'">{{ row.met ? '达标' : '未达标' }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </div>
</template>

<script setup>
/**
 * 18.5.1 月度报告（拆分自原服务报告操作台）：生成与查看月度 SLA 报告。
 * 报告期与报告数据来自 useReport.js，生成后经其刷新共享改进项台账。
 */
import { onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { generateReportApi } from '@/api/ops/ops-sla'
import { useReport } from './useReport'

const { period, report, initPeriods, load, loadImprovements } = useReport()

async function generate() {
  const r = await generateReportApi(period.value)
  report.value = r.data
  ElMessage.success(`报告已生成（未达标改进项 ${(r.data.metrics?.improvementIds || []).length} 项）`)
  loadImprovements()
}

onMounted(() => {
  initPeriods()
  load()
})
</script>
