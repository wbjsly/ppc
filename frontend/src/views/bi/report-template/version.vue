<template>
  <div>

    <!-- ========== 14.1.4 版本控制 ========== -->
    <div class="toolbar">
      <el-select v-model="verTplId" placeholder="选择模板" size="small" style="width: 280px" filterable
                 @change="loadVersions">
        <el-option v-for="t in allRows" :key="t.id" :label="`${t.code} ${t.name}`" :value="t.id" />
      </el-select>
    </div>
    <el-table :data="versions" v-loading="versLoading" border stripe size="small">
      <el-table-column prop="versionNo" label="版本号" width="90" align="center">
        <template #default="{ row }">V{{ row.versionNo }}</template>
      </el-table-column>
      <el-table-column label="口径版本绑定" min-width="220">
        <template #default="{ row }">
          <template v-if="parseJson(row.metricVersionsJson).length">
            <el-tag v-for="m in parseJson(row.metricVersionsJson)" :key="m.metricKey" size="small"
                    style="margin-right: 4px">
              {{ m.metricKey }}@v{{ m.metricVersion ?? '-' }}
            </el-tag>
          </template>
          <span v-else class="tip">无指标引用</span>
        </template>
      </el-table-column>
      <el-table-column label="回退溯源" width="100" align="center">
        <template #default="{ row }">{{ row.rollbackFrom ? '自 V' + row.rollbackFrom : '—' }}</template>
      </el-table-column>
      <el-table-column prop="publishBy" label="发布人" width="120" />
      <el-table-column label="发布时间" width="150">
        <template #default="{ row }">{{ (row.publishAt || '').replace('T', ' ').slice(0, 16) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" align="center">
        <template #default="{ row }">
          <el-button size="small" type="warning" link @click="doRollback(row)">回退到此版</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!versLoading && versions.length === 0" description="暂无版本快照" />
  </div>
</template>

<script setup>
/**
 * 14.1.4 版本控制（拆分自原报表模板操作台）：版本快照列表 / 回退到指定版。
 * 模板下拉数据见 useReportTemplate.js（模板测试页共用）；回退后经 useReportTemplate.load()
 * 刷新模板列表（模板设计页、模板发布页共用同一份数据）。
 */
import { ref, onMounted, onActivated } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getVersionsApi, rollbackApi } from '@/api/bi/report-template'
import { useReportTemplate } from './useReportTemplate'

const { allRows, loadAll, load } = useReportTemplate()

const verTplId = ref('')
const versions = ref([])
const versLoading = ref(false)

async function loadVersions() {
  if (!verTplId.value) return
  versLoading.value = true
  try {
    const res = await getVersionsApi(verTplId.value)
    versions.value = res.data || []
  } finally {
    versLoading.value = false
  }
}

async function doRollback(row) {
  try {
    await ElMessageBox.confirm(
      `回退到 V${row.versionNo}？将生成新版本号（保留历史链），生效版切换。`, '回退确认', { type: 'warning' })
  } catch { return }
  try {
    const res = await rollbackApi(row.id)
    ElMessage.success(`已回退：生成 V${res.data?.versionNo}（源自 V${res.data?.rollbackFrom}）`)
    await loadVersions()
    await load()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '回退失败')
  }
}

function parseJson(s) {
  try { return JSON.parse(s || '[]') } catch { return [] }
}

onMounted(() => { loadAll() })
onActivated(() => { loadAll() })
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
</style>
