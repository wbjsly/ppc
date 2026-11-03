<template>
  <div class="app-container">
    <!-- ==================== 16.9.1 月度报告 ==================== -->
    <el-form :inline="true" size="small" @submit.prevent>
      <el-form-item label="月份">
        <el-date-picker v-model="query.period" type="month" value-format="YYYY-MM"
          placeholder="YYYY-MM" style="width:130px" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" placeholder="全部" clearable style="width:130px">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已归档" value="ARCHIVED" />
          <el-option label="已发布" value="PUBLISHED" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadReports(1)">查询</el-button>
        <el-button type="success" @click="doGenerate">按月生成</el-button>
        <el-button @click="doSweep">上月扫描（补缺）</el-button>
      </el-form-item>
    </el-form>
    <el-alert type="info" :closable="false" style="margin-bottom:8px"
      title="月度系统 SLA 报告：接口可用率/P95/错误率 + 审计写入 + 各域安全事件量（现有表推导，零埋点）。状态机 DRAFT → ARCHIVED（版本锁定）→ PUBLISHED；已归档不可重生成。" />
    <el-table :data="reports.rows" size="small" v-loading="reports.loading" border>
      <el-table-column prop="period" label="月份" width="100" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="mini" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="dataAsOf" label="数据截止" width="160" />
      <el-table-column label="生成人" width="110">
        <template #default="{ row }">{{ row.createBy || '—' }}</template>
      </el-table-column>
      <el-table-column label="归档" width="150">
        <template #default="{ row }">
          <span v-if="row.archiveAt">{{ row.archiveBy }} · {{ row.archiveAt }}</span>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="发布" width="150">
        <template #default="{ row }">
          <span v-if="row.publishAt">{{ row.publishBy }} · {{ row.publishAt }}</span>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button size="mini" type="primary" @click="openDetail(row)">指标详情</el-button>
        </template>
      </el-table-column>
    </el-table>
        <AppPagination
      style="margin-top:8px;text-align:right"
      background
      :total="reports.total"
      v-model:page-size="reports.size"
      :current-page="reports.page"
      @current-change="loadReports"
    />

    <!-- 指标详情抽屉 -->
    <el-drawer v-model="detailDrawer" :title="`SLA 指标详情（${current.period || ''}）`" size="640px">
      <el-descriptions :column="2" size="small" border>
        <el-descriptions-item label="月份">{{ current.period }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusText(current.status) }}</el-descriptions-item>
        <el-descriptions-item label="数据截止">{{ current.dataAsOf }}</el-descriptions-item>
        <el-descriptions-item label="发布次数">{{ current.publishCount || 0 }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="metricsRows" size="mini" border style="margin-top:12px">
        <el-table-column prop="code" label="编码" width="140" />
        <el-table-column prop="name" label="指标" width="120" />
        <el-table-column label="值" width="90">
          <template #default="{ row }">{{ row.value }}{{ row.unit && row.value !== '不适用' ? row.unit : '' }}</template>
        </el-table-column>
        <el-table-column prop="target" label="目标" width="80" />
        <el-table-column label="达标" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.pass === true" size="mini" type="success">达标</el-tag>
            <el-tag v-else-if="row.pass === false" size="mini" type="danger">未达标</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="备注" show-overflow-tooltip />
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import AppPagination from '@/components/AppPagination.vue'
/**
 * 16.9.1 月度报告（拆分自原 SLA 报告操作台）：报告台账 / 按月生成 / 上月补缺 / 指标详情。
 * 共享月度报告列表见 useSlaReport.js（归档发布页操作后亦刷新该列表）。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { generateReport, sweepReports } from '@/api/system/sla-report'
import { useSlaReport } from './useSlaReport'
import { statusType, statusText } from './sla-helpers'

const { query, reports, loadReports } = useSlaReport()

const doGenerate = async () => {
  if (!query.period) {
    ElMessage.warning('请先选择月份（YYYY-MM）')
    return
  }
  const { data } = await generateReport(query.period)
  ElMessage.success(`已生成 ${data.period} 报告（${data.metricsCount} 项指标，数据截止 ${data.dataAsOf}）`)
  loadReports(1)
}
const doSweep = async () => {
  const { data } = await sweepReports()
  if (data.generated === 1) {
    ElMessage.success(`已补生成上月报告（${data.period}）`)
  } else {
    ElMessage.info(`上月报告已存在（${data.period}），跳过`)
  }
  loadReports(1)
}

// ---------- 指标详情 ----------
const detailDrawer = ref(false)
const current = reactive({})
const metricsRows = computed(() => {
  if (!current.metricsJson) return []
  try {
    const rows = typeof current.metricsJson === 'string'
      ? JSON.parse(current.metricsJson) : current.metricsJson
    return rows
  } catch (e) {
    return []
  }
})
const openDetail = (row) => {
  Object.assign(current, row)
  detailDrawer.value = true
}

onMounted(() => {
  loadReports(1)
})
</script>
