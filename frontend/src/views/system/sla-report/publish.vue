<template>
  <div class="app-container">
    <!-- ==================== 16.9.2 归档发布 ==================== -->
    <el-alert type="warning" :closable="false" style="margin-bottom:8px"
      title="归档 = 版本锁定（指标不可改、不可重生成）；对外发布必须先归档——未归档发布将被硬阻断（BR-5.6-16）并记录违规尝试、通知报告责任人与内审（C-5.6-05）。" />
    <el-table :data="pubReports.rows" size="small" v-loading="pubReports.loading" border>
      <el-table-column prop="period" label="月份" width="100" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="mini" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="dataAsOf" label="数据截止" width="160" />
      <el-table-column prop="createBy" label="生成人" width="110" />
      <el-table-column label="操作" width="240">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DRAFT'" size="mini" type="primary"
            @click="doArchive(row)">归档</el-button>
          <el-button v-if="row.status === 'ARCHIVED'" size="mini" type="success"
            @click="doPublish(row)">对外发布</el-button>
          <el-button v-if="row.status === 'DRAFT'" size="mini" type="danger" plain
            @click="blockedPublish">发布</el-button>
          <el-button v-if="row.status !== 'DRAFT'" size="mini"
            @click="doDownload(row)">下载 CSV</el-button>
        </template>
      </el-table-column>
    </el-table>
        <AppPagination
      style="margin-top:8px;text-align:right"
      background
      :total="pubReports.total"
      v-model:page-size="pubReports.size"
      :current-page="pubReports.page"
      @current-change="loadPubReports"
    />
  </div>
</template>

<script setup>
import AppPagination from '@/components/AppPagination.vue'
/**
 * 16.9.2 归档发布（拆分自原 SLA 报告操作台）：归档（版本锁定）/ 对外发布 / 下载 CSV。
 * 归档发布后同步刷新共享月度报告列表，见 useSlaReport.js。
 */
import { reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listReports, archiveReport, publishReport, downloadReport } from '@/api/system/sla-report'
import { useSlaReport } from './useSlaReport'
import { statusType, statusText } from './sla-helpers'

const { reports, loadReports } = useSlaReport()

// ---------- 归档发布 ----------
const pubReports = reactive({ rows: [], total: 0, page: 1, size: 20, loading: false })
const loadPubReports = async (page) => {
  if (page) pubReports.page = page
  pubReports.loading = true
  try {
    const { data } = await listReports({ page: pubReports.page, size: pubReports.size })
    pubReports.rows = data.rows || []
    pubReports.total = data.total || 0
  } finally {
    pubReports.loading = false
  }
}
const doArchive = async (row) => {
  await ElMessageBox.confirm(
    `归档 ${row.period} 报告？归档后版本锁定（指标不可修改、不可重生成）。`, '归档确认',
    { type: 'warning' })
  await archiveReport(row.id)
  ElMessage.success('已归档（审计留痕）')
  loadPubReports(pubReports.page)
  loadReports(reports.page)
}
const doPublish = async (row) => {
  await ElMessageBox.confirm(
    `对外发布 ${row.period} 报告？发布后状态置为已发布并留痕。`, '发布确认', { type: 'warning' })
  try {
    await publishReport(row.id)
    ElMessage.success('已对外发布（留痕）')
    loadPubReports(pubReports.page)
    loadReports(reports.page)
  } catch (e) {
    // 后端 422（未归档阻断 + 违规留痕）——message 由拦截器带出
    ElMessage.error(e?.response?.data?.message || e?.message || '发布被拒绝')
    loadPubReports(pubReports.page)
  }
}
const blockedPublish = () => {
  ElMessage.error('报告未归档，不可发布（BR-5.6-16）')
}
const doDownload = async (row) => {
  const res = await downloadReport(row.id)
  const blob = new Blob([res], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `sla-report-${row.period}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
  ElMessage.success('已下载（动作已留痕）')
}

onMounted(() => {
  loadPubReports(1)
})
</script>
