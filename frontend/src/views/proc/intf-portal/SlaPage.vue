<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="服务报告（15.8）：接口 SLA 月度生成、归档发布"
      description="复用 interface-sla-monitoring。四指标（可用率/P95/错误率/同步延迟）月度采集；未归档不得对外发布（BR-5.5-16 / C-5.5-04）。" />

    <el-tabs v-model="tab">
      <!-- 15.8.1 月度生成 -->
      <el-tab-pane label="月度生成" name="generate">
        <el-table :data="reports" border size="small">
          <el-table-column prop="period" label="月份" width="100" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="stTag(row.status)">{{ stText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="dataAsOf" label="数据截止" width="160" />
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <el-button size="small" type="primary" @click="generate(row)">月度生成</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-alert type="info" :closable="false" style="margin-top:10px"
                  title="月度采集四指标并生成报告快照；重复生成覆盖当期草稿" />
      </el-tab-pane>

      <!-- 15.8.2 归档发布 -->
      <el-tab-pane label="归档发布" name="publish">
        <el-table :data="reports" border size="small">
          <el-table-column prop="period" label="月份" width="100" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="stTag(row.status)">{{ stText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="dataAsOf" label="数据截止" width="160" />
          <el-table-column label="操作" width="240">
            <template #default="{ row }">
              <el-button v-if="row.status === 'DRAFT' || row.status === 'REVIEWING'"
                         size="small" type="warning"
                         @click="archive(row)">归档</el-button>
              <el-button size="small" type="primary"
                         :disabled="row.status !== 'ARCHIVED'"
                         @click="publish(row)">对外发布</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-alert type="warning" :closable="false" style="margin-top:10px"
                  title="流程：草稿 → 归档（版本锁定）→ 对外发布；未归档发布将被硬阻断并留痕（BR-5.5-16 / C-5.5-04）" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getReportsApi, generateReportApi, archiveReportApi, publishReportApi } from '@/api/proc/interface-integration'

const route = useRoute()
const tab = ref(route.meta.tab || 'generate')

const reports = ref([])

function stTag(s) {
  return { PUBLISHED: 'success', ARCHIVED: 'success', DRAFT: 'info', REVIEWING: 'warning' }[s] || 'info'
}
function stText(s) {
  return { DRAFT: '草稿', REVIEWING: '审核中', ARCHIVED: '已归档', PUBLISHED: '已发布' }[s] || s
}

async function load() {
  try { const r = await getReportsApi(); reports.value = r.data?.data || [] } catch { reports.value = [] }
}
onMounted(load)

async function generate(row) {
  await generateReportApi({ period: row.period })
  ElMessage.success('已生成月度 SLA 报告')
  load()
}

async function archive(row) {
  await ElMessageBox.confirm(`归档 ${row.period} 报告？归档后版本锁定，可对外发布`, '提示', { type: 'warning' })
  try {
    await archiveReportApi(row.id)
    ElMessage.success('已归档')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '归档失败')
  }
}

async function publish(row) {
  await ElMessageBox.confirm(`发布 ${row.period} SLA 报告？未归档将被硬阻断`, '提示', { type: 'warning' })
  try {
    await publishReportApi(row.id, 'PARTNER')
    ElMessage.success('已发布')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '发布被阻断（未归档不可发布）')
  }
}
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
