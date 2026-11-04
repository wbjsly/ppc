<template>
  <div class="i18n-page">
    <PageInfo title="服务报告：月度报告、归档发布" description="五指标（版本数/三语覆盖率/缺口闭环率/渲染异常/灰度回退）；未归档发布 422 阻断留痕（BR-5.7-14 / C-5.7-05，复用范式）。" style="margin-bottom:12px" />

    <!-- 17.7.1 月度报告 -->
    <el-form inline size="small">
      <el-form-item label="月份">
        <el-date-picker v-model="period" type="month" value-format="YYYY-MM" style="width:140px" />
      </el-form-item>
      <el-button type="primary" size="small" @click="generate">生成报告</el-button>
    </el-form>
    <template v-if="report">
      <el-descriptions :column="3" border size="small" style="margin-bottom:12px">
        <el-descriptions-item label="状态">
          <el-tag :type="stTag(report.status)">{{ stText(report.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="语言包版本数">{{ report.metrics.packCount }}</el-descriptions-item>
        <el-descriptions-item label="en-US 覆盖率">{{ report.metrics.enCoverage }}%</el-descriptions-item>
        <el-descriptions-item label="ja-JP 覆盖率">{{ report.metrics.jaCoverage }}%</el-descriptions-item>
        <el-descriptions-item label="缺口闭环率">{{ report.metrics.gapCloseRate }}%</el-descriptions-item>
        <el-descriptions-item label="渲染异常 / 灰度回退">
          {{ report.metrics.renderErr }} / {{ report.metrics.canaryRollback }}
        </el-descriptions-item>
      </el-descriptions>
      <el-table :data="report.metrics.items || []" border size="small">
        <el-table-column prop="name" label="指标" min-width="180" />
        <el-table-column prop="actual" label="实际" width="120" />
        <el-table-column prop="target" label="目标" width="110" />
        <el-table-column label="达标" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.met ? 'success' : 'danger'">{{ row.met ? '达标' : '未达标' }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-alert v-if="(report.metrics.improvementIds || []).length" type="warning" :closable="false"
        style="margin-top:10px"
        :title="`未达标自动生成改进项 ${report.metrics.improvementIds.length} 项（30 天时限，进入缺口台账跟踪）`" />
    </template>
  </div>
</template>

<script setup>
/**
 * 17.7.1 月度报告（拆分自原服务报告页）：五指标报告生成与展示。
 * 报告/列表共享状态见 useSlaReport.js；状态标签映射见 sla-helpers.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { generateSlaApi } from '@/api/i18n/i18n'
import { stTag, stText } from './sla-helpers'
import { useSlaReport } from './useSlaReport'

const { period, report, loadList } = useSlaReport()

onMounted(loadList)

async function generate() {
  try {
    const r = await generateSlaApi(period.value)
    report.value = r.data
    ElMessage.success('报告已生成')
    loadList()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '生成失败')
  }
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
</style>
