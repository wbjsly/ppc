<template>
  <div class="page">
    <el-tabs v-model="tab">
      <!-- 18.5.1 月度报告 -->
      <el-tab-pane label="月度报告" name="report">
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
      </el-tab-pane>

      <!-- 18.5.2 归档发布 -->
      <el-tab-pane label="归档发布" name="publish">
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
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { generateReportApi, getReportApi, archiveReportApi, publishReportApi, improvementsApi } from '@/api/ops/ops-sla'

const route = useRoute()
const tab = ref(route.meta.tab || 'report')
const period = ref('')
const pubPeriod = ref('')
const report = ref(null)
const improvements = ref([])

onMounted(() => {
  const now = new Date()
  const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  const p = `${prev.getFullYear()}-${String(prev.getMonth() + 1).padStart(2, '0')}`
  period.value = p
  pubPeriod.value = p
  load()
  loadImprovements()
})

async function load() {
  try {
    const r = await getReportApi(period.value)
    report.value = r.data
  } catch (e) {
    report.value = null
  }
}
async function generate() {
  const r = await generateReportApi(period.value)
  report.value = r.data
  ElMessage.success(`报告已生成（未达标改进项 ${(r.data.metrics?.improvementIds || []).length} 项）`)
  loadImprovements()
}
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
async function loadImprovements() {
  const r = await improvementsApi({ limit: 50 })
  improvements.value = r.data || []
}
</script>
