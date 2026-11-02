<template>
  <div class="i18n-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="服务报告（17.7）：月度报告、归档发布"
      description="五指标（版本数/三语覆盖率/缺口闭环率/渲染异常/灰度回退）；未归档发布 422 阻断留痕（BR-5.7-14 / C-5.7-05，复用 18.5 范式）。" />
    <el-tabs v-model="tab">
      <!-- 17.7.1 月度报告 -->
      <el-tab-pane label="月度报告" name="generate">
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
      </el-tab-pane>

      <!-- 17.7.2 归档发布 -->
      <el-tab-pane label="归档发布" name="publish">
        <el-form inline size="small">
          <el-form-item label="月份">
            <el-date-picker v-model="pubPeriod" type="month" value-format="YYYY-MM" style="width:140px" />
          </el-form-item>
          <el-button type="warning" size="small" @click="archive">归档</el-button>
          <el-button type="primary" size="small" @click="publish">对外发布</el-button>
        </el-form>
        <el-alert type="warning" :closable="false" style="margin-bottom:10px"
          title="流程：DRAFT → 归档（版本锁定）→ PUBLISHED；未归档发布 422 硬阻断并留痕（BR-5.7-14 / C-5.7-05）" />
        <el-table :data="slaList" border size="small">
          <el-table-column label="月份" width="140">
            <template #default="{ row }">{{ String(row.k || '').replace('I18N_SLA_STATUS_', '') }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="130">
            <template #default="{ row }">
              <el-tag size="small" :type="stTag(row.status)">{{ stText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" width="180" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { generateSlaApi, getSlaApi, archiveSlaApi, publishSlaApi, getSlaListApi } from '@/api/i18n/i18n'

const route = useRoute()
const tab = ref(route.meta.tab || 'generate')

const now = new Date()
const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1)
const defaultPeriod = `${prev.getFullYear()}-${String(prev.getMonth() + 1).padStart(2, '0')}`

const period = ref(defaultPeriod)
const pubPeriod = ref(defaultPeriod)
const report = ref(null)
const slaList = ref([])

function stTag(s) {
  return { DRAFT: 'info', ARCHIVED: 'warning', PUBLISHED: 'success' }[s] || 'info'
}
function stText(s) {
  return { DRAFT: '草稿', ARCHIVED: '已归档', PUBLISHED: '已发布' }[s] || s || '—'
}

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
async function loadList() {
  try {
    const r = await getSlaListApi()
    slaList.value = r.data || []
    if (period.value && !report.value) {
      try {
        const rr = await getSlaApi(period.value)
        report.value = rr.data
      } catch { report.value = null }
    }
  } catch { slaList.value = [] }
}
async function archive() {
  await ElMessageBox.confirm(`归档 ${pubPeriod.value} 报告？归档后版本锁定`, '提示', { type: 'warning' })
  try {
    await archiveSlaApi(pubPeriod.value)
    ElMessage.success('已归档')
    loadList()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '归档失败')
  }
}
async function publish() {
  try {
    await publishSlaApi(pubPeriod.value, 'ALL')
    ElMessage.success('已发布')
    loadList()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '发布被阻断（未归档 BR-5.7-14）')
  }
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
</style>
