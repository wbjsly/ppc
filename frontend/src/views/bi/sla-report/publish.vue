<template>
  <div class="app-container">
    <!-- 14.9.2 归档发布 -->
    <el-card shadow="never">
      <div slot="header">
        <span>归档发布</span>
        <el-date-picker v-model="period" type="month" value-format="yyyy-MM"
                        placeholder="选择月份" size="small" style="float:right;width:130px"
                        @change="loadReport" />
      </div>
      <template v-if="report">
        <el-steps :active="stepActive" finish-status="success" style="margin-bottom:16px">
          <el-step title="草稿" />
          <el-step title="审核中" />
          <el-step title="已归档" />
          <el-step title="已发布" />
        </el-steps>
        <el-alert v-if="report.status !== 'ARCHIVED' && report.status !== 'PUBLISHED'"
                  :title="'报告未归档，不可发布（当前状态：' + stText(report.status) + '）'"
                  type="warning" :closable="false" style="margin-bottom:12px" />
        <el-alert v-if="report.rejectReason"
                  :title="'最近驳回意见：' + report.rejectReason"
                  type="error" :closable="false" style="margin-bottom:12px" />
        <div>
          <el-button type="primary" size="small" :disabled="report.status !== 'ARCHIVED'"
                     @click="doPublish">对外发布</el-button>
          <el-button size="small" :disabled="report.status !== 'ARCHIVED' && report.status !== 'PUBLISHED'"
                     @click="doPdf">下载 PDF</el-button>
          <el-button size="small" :disabled="report.status !== 'ARCHIVED' && report.status !== 'PUBLISHED'"
                     @click="doNewVersion">新开版本修改</el-button>
        </div>
        <el-table :data="report.versions || []" border size="small" style="margin-top:12px">
          <el-table-column prop="version" label="版本" width="80" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="stTag(row.status)" size="mini">{{ stText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="archiveAt" label="归档时间" />
        </el-table>
      </template>
      <el-alert v-else title="该月暂无报告，请先在月度报告页生成" type="info" :closable="false" />
    </el-card>
  </div>
</template>

<script setup>
/**
 * 14.9.2 归档发布（拆分自原 SLA 报告操作台）：状态步骤条 / 对外发布 / 下载 PDF / 新开版本。
 * 所选月份与当月报告见 useSlaReport.js（月度报告页生成），状态映射见 sla-helpers.js。
 */
import { computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { newVersionApi, publishApi, getPdfApi } from '@/api/bi/sla-report'
import { useSlaReport } from './useSlaReport'
import { stText, stTag } from './sla-helpers'

const { period, report, loadReport } = useSlaReport()

const stepActive = computed(() => {
  const s = (report.value || {}).status
  return { DRAFT: 0, PENDING_APPROVAL: 1, APPROVED: 2, ARCHIVED: 2, PUBLISHED: 3 }[s] ?? 0
})

async function doPublish() {
  await ElMessageBox.confirm('确认对外发布该归档报告？（发布前请确认敏感数据已脱敏）', '提示', { type: 'warning' })
  await publishApi(period.value)
  ElMessage.success('已发布')
  loadReport()
}
async function doPdf() {
  const r = await getPdfApi(period.value)
  ElMessage.success('PDF 路径：' + (r.data?.data?.pdfRef || '-'))
}
async function doNewVersion() {
  await ElMessageBox.confirm('归档后修改将生成新版本（原归档版本保留），继续？', '提示', { type: 'warning' })
  await newVersionApi(period.value)
  ElMessage.success('新版本已创建（草稿）')
  loadReport()
}

onMounted(() => { loadReport() })
</script>
