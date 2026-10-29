<template>
  <div class="app-container">
    <el-tabs v-model="tab">
      <!-- ==================== 16.9.1 月度报告 ==================== -->
      <el-tab-pane label="月度报告" name="reports">
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
        <el-pagination style="margin-top:8px;text-align:right" background layout="total, prev, pager, next"
          :total="reports.total" :page-size="reports.size" :current-page="reports.page"
          @current-change="loadReports" />

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
      </el-tab-pane>

      <!-- ==================== 16.9.2 归档发布 ==================== -->
      <el-tab-pane label="归档发布" name="publish">
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
        <el-pagination style="margin-top:8px;text-align:right" background layout="total, prev, pager, next"
          :total="pubReports.total" :page-size="pubReports.size" :current-page="pubReports.page"
          @current-change="loadPubReports" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listReports, generateReport, sweepReports, archiveReport, publishReport, downloadReport
} from '@/api/system/sla-report'

const route = useRoute()

// ---------- Tab 定位（沿 16.1~16.8 模式）----------
const TAB_WHITELIST = ['reports', 'publish']
const pickTab = () => {
  const m = route.meta?.tab
  const q = route.query?.tab
  if (TAB_WHITELIST.includes(m)) return m
  if (TAB_WHITELIST.includes(q)) return q
  return 'reports'
}
const tab = ref(pickTab())
watch(() => route.meta?.tab, () => { tab.value = pickTab() })

// ---------- 月度报告 ----------
const query = reactive({ period: '', status: '' })
const reports = reactive({ rows: [], total: 0, page: 1, size: 50, loading: false })
const loadReports = async (page) => {
  if (page) reports.page = page
  reports.loading = true
  try {
    const params = { page: reports.page, size: reports.size }
    if (query.period) params.period = query.period
    if (query.status) params.status = query.status
    const { data } = await listReports(params)
    reports.rows = data.rows || []
    reports.total = data.total || 0
  } finally {
    reports.loading = false
  }
}
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

// ---------- 归档发布 ----------
const pubReports = reactive({ rows: [], total: 0, page: 1, size: 50, loading: false })
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

// ---------- 展示辅助 ----------
const statusType = (s) => ({ DRAFT: 'info', ARCHIVED: 'warning', PUBLISHED: 'success' }[s] || 'info')
const statusText = (s) => ({ DRAFT: '草稿', ARCHIVED: '已归档', PUBLISHED: '已发布' }[s] || s)

// ---------- 挂载 ----------
watch(tab, (t) => {
  if (t === 'publish' && !pubReports.rows.length) loadPubReports(1)
})
onMounted(() => {
  loadReports(1)
  if (tab.value === 'publish') loadPubReports(1)
})
</script>
