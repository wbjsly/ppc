<template>
  <div>
    <el-page-header content="报表模板管理（14.1）— 设计/测试/发布/版本四叶；口径 diff 硬阻断（C-4.10-01）、三方发布审批、快照回退"
                    style="margin-bottom: 8px" />

    <!-- ========== 14.1.2 模板测试 ========== -->
    <div class="toolbar">
      <el-select v-model="testTplId" placeholder="选择模板" size="small" style="width: 280px" filterable>
        <el-option v-for="t in allRows" :key="t.id" :label="`${t.code} ${t.name}`" :value="t.id" />
      </el-select>
      <el-button size="small" type="primary" :disabled="!testTplId" :loading="testing"
                 @click="doTest">执行测试</el-button>
      <span class="tip">三项报告：样本人工核对 / 性能阈值（标准10s·复杂30s）/ 权限行数对比；模板变更后旧报告作废须重测</span>
    </div>
    <el-table :data="tests" v-loading="testsLoading" border stripe size="small">
      <el-table-column label="执行时间" width="150">
        <template #default="{ row }">{{ (row.execAt || '').replace('T', ' ').slice(0, 16) }}</template>
      </el-table-column>
      <el-table-column prop="rowCount" label="行数" width="80" align="center" />
      <el-table-column prop="perfMs" label="耗时ms" width="90" align="center">
        <template #default="{ row }">
          <span :style="{ color: row.perfOk === '1' ? '#67C23A' : '#F56C6C' }">{{ row.perfMs }}</span>
        </template>
      </el-table-column>
      <el-table-column label="性能" width="70" align="center">
        <template #default="{ row }">
          <el-tag :type="row.perfOk === '1' ? 'success' : 'danger'" size="small">
            {{ row.perfOk === '1' ? '达标' : '超标' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="权限" width="70" align="center">
        <template #default="{ row }">
          <el-tag :type="row.permOk === '1' ? 'success' : 'danger'" size="small">
            {{ row.permOk === '1' ? '符合' : '异常' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="样本核对" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.accuracyOk === '1' ? 'success' : 'warning'" size="small">
            {{ row.accuracyOk === '1' ? '已核对' : '待核对' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="总体" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PASS' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="有效" width="70" align="center">
        <template #default="{ row }">{{ row.valid === '1' ? '有效' : '已作废' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="showSample(row)">查看样本</el-button>
          <el-button v-if="row.valid === '1' && row.accuracyOk === '0' && row.status !== 'PASS'"
                     size="small" type="success" link @click="doConfirm(row)">核对确认</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!testsLoading && tests.length === 0" description="暂无测试报告" />

    <el-dialog v-model="sampleVisible" title="样本人工核对（前10行）" width="720px">
      <el-table :data="sampleRows" border size="small" max-height="400">
        <el-table-column v-for="k in sampleCols" :key="k" :prop="k" :label="k" min-width="110"
                         show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 14.1.2 模板测试（拆分自原报表模板操作台）：执行测试 / 三项报告 / 样本人工核对。
 * 模板下拉数据见 useReportTemplate.js（版本控制页共用）。
 */
import { ref, computed, onMounted, onActivated } from 'vue'
import { ElMessage } from 'element-plus'
import { runTestApi, confirmAccuracyApi, getTestsApi } from '@/api/bi/report-template'
import { useReportTemplate } from './useReportTemplate'

const { allRows, loadAll } = useReportTemplate()

const testTplId = ref('')
const tests = ref([])
const testsLoading = ref(false)
const testing = ref(false)
const sampleVisible = ref(false)
const sampleRows = ref([])

async function loadTests() {
  if (!testTplId.value) return
  testsLoading.value = true
  try {
    const res = await getTestsApi(testTplId.value)
    tests.value = res.data || []
  } finally {
    testsLoading.value = false
  }
}

async function doTest() {
  testing.value = true
  try {
    const res = await runTestApi(testTplId.value)
    ElMessage.success(`测试完成：${res.data?.rowCount ?? 0} 行，耗时 ${res.data?.perfMs ?? '-'}ms`)
    await loadTests()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '测试失败')
  } finally {
    testing.value = false
  }
}

async function doConfirm(row) {
  try {
    await confirmAccuracyApi(row.id)
    ElMessage.success('样本核对确认，总体 PASS')
    await loadTests()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '确认失败（性能/权限项未达标不可核对通过）')
  }
}

function showSample(row) {
  try {
    const arr = JSON.parse(row.sampleJson || '[]')
    sampleRows.value = arr
  } catch { sampleRows.value = [] }
  sampleVisible.value = true
}

const sampleCols = computed(() => {
  const keys = new Set()
  sampleRows.value.forEach(r => Object.keys(r || {}).forEach(k => keys.add(k)))
  return [...keys]
})

onMounted(() => { loadAll() })
onActivated(() => { loadAll() })
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
</style>
