<template>
  <div class="page">
    <el-form inline>
      <el-form-item label="月份">
        <el-date-picker v-model="pubPeriod" type="month" value-format="YYYY-MM" style="width: 140px" />
      </el-form-item>
      <el-button type="success" @click="archive">归档</el-button>
      <el-button type="primary" @click="publish">对外发布</el-button>
    </el-form>
    <el-alert type="warning" :closable="false" style="margin-bottom: 10px"
              title="报告未归档时发布将被阻断并留痕（BR-5.8-16 / C-0-01）" />
    <h4>改进项跟踪台账（BR-5.8-07）</h4>
    <el-table :data="improvements" border size="small">
      <el-table-column prop="incNo" label="编号" width="180" />
      <el-table-column prop="title" label="改进项" />
      <el-table-column prop="rootCause" label="根因" show-overflow-tooltip />
      <el-table-column prop="improveOwner" label="责任人" width="110" />
      <el-table-column prop="improveDueDate" label="关闭时限" width="110" />
      <el-table-column prop="status" label="状态" width="90" />
    </el-table>
  </div>
</template>

<script setup>
/**
 * 18.5.2 归档发布（拆分自原服务报告操作台）：报告归档、对外发布与改进项台账。
 * 报告期、报告数据与改进项来自 useReport.js（与月度报告页交叉刷新）。
 */
import { onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { archiveReportApi, publishReportApi } from '@/api/ops/ops-sla'
import { useReport } from './useReport'

const { period, pubPeriod, improvements, initPeriods, load, loadImprovements } = useReport()

async function archive() {
  const r = await archiveReportApi(pubPeriod.value)
  ElMessage.success(`已归档（状态 ${r.data.status}）`)
  if (period.value === pubPeriod.value) load()
}

async function publish() {
  try {
    await ElMessageBox.confirm(`发布 ${pubPeriod.value} 报告？未归档将被硬阻断`, '提示', { type: 'warning' })
    await publishReportApi(pubPeriod.value, 'ALL')
    ElMessage.success('已发布')
    if (period.value === pubPeriod.value) load()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || '发布被阻断（未归档 BR-5.8-16）')
    }
  }
}

onMounted(() => {
  initPeriods()
  loadImprovements()
})
</script>
