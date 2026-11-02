<template>
  <div class="adhoc-wrap">
    <!-- ========== 14.6.1 即席查询 ========== -->
    <el-row :gutter="12">
      <el-col :span="6">
        <el-card shadow="never">
          <template #header><span class="card-title">数据集</span></template>
          <el-select v-model="datasetId" placeholder="选择已发布数据集" style="width:100%"
                     filterable @change="onDatasetChange">
            <el-option v-for="d in datasets" :key="d.id" :value="d.id"
                       :label="`${d.name}（${d.code}）`" />
          </el-select>
          <!-- 字段树：维度/指标分组，未注册口径置灰 -->
          <div v-if="dataset" class="field-tree">
            <div class="tree-group" v-if="dataset.dimensions?.length">
              <div class="tree-head">维度</div>
              <el-checkbox-group v-model="pickedDims">
                <div v-for="f in dataset.dimensions" :key="f.field" class="tree-item">
                  <el-checkbox :value="f.field">{{ f.label || f.field }}</el-checkbox>
                  <el-tag v-if="f.timeFlag" size="small" type="warning">时间</el-tag>
                  <el-tag v-if="f.roleFlag" size="small" type="info">组织</el-tag>
                </div>
              </el-checkbox-group>
            </div>
            <div class="tree-group" v-if="dataset.measures?.length">
              <div class="tree-head">指标（选聚合方式）</div>
              <div v-for="f in dataset.measures" :key="f.field" class="tree-item">
                <el-checkbox v-model="f._picked"
                             :disabled="f.agg?.includes('UNREGISTERED')"
                             @change="syncMeasures">
                  {{ f.label || f.field }}
                  <el-tag v-if="f.agg?.includes('UNREGISTERED')" size="small" type="danger">未注册口径</el-tag>
                </el-checkbox>
                <el-select v-if="f._picked" v-model="f._agg" size="small" style="width:90px;margin-left:6px"
                           @change="syncMeasures">
                  <el-option v-for="a in aggOptions(f)" :key="a" :value="a" :label="a" />
                </el-select>
              </div>
            </div>
          </div>
        </el-card>
        <!-- 我的查询 -->
        <el-card shadow="never" class="mt">
          <template #header><span class="card-title">我的查询</span></template>
          <div v-for="q in savedQueries" :key="q.id" class="saved-item">
            <el-link type="primary" @click="loadSaved(q)">{{ q.name }}</el-link>
            <el-button link type="danger" size="small" @click="removeSaved(q.id)">删</el-button>
          </div>
          <el-button v-if="datasetId" size="small" @click="saveCurrent" class="mt">保存当前查询</el-button>
        </el-card>
      </el-col>
      <el-col :span="18">
        <el-card shadow="never">
          <template #header><span class="card-title">查询构建</span></template>
          <!-- 筛选条件构建器 -->
          <div v-for="(fd, i) in filters" :key="i" class="filter-row">
            <el-select v-model="fd.field" placeholder="字段" style="width:180px" filterable>
              <el-option v-for="f in filterFields" :key="f.field" :value="f.field" :label="f.label || f.field" />
            </el-select>
            <el-select v-model="fd.op" placeholder="运算符" style="width:110px">
              <el-option v-for="o in ops" :key="o" :value="o" :label="o" />
            </el-select>
            <template v-if="fd.op === 'BETWEEN'">
              <el-input v-model="fd.v1" placeholder="下界" style="width:130px" />
              <el-input v-model="fd.v2" placeholder="上界" style="width:130px" />
            </template>
            <el-input v-else v-model="fd.value" placeholder="值" style="width:180px" />
            <el-button link type="danger" @click="filters.splice(i, 1)">删</el-button>
          </div>
          <el-button size="small" @click="filters.push({ field: '', op: '=', value: '' })">+ 筛选条件</el-button>
          <div class="mt">
            <el-button type="primary" :loading="running" @click="runQuery">执行查询</el-button>
            <span v-if="quotaWarn" class="quota-warn">{{ quotaWarn }}</span>
          </div>
          <!-- 结果 -->
          <el-alert v-if="blocked" :title="blocked" type="warning" :closable="false" class="mt" />
          <el-alert v-if="queuedMsg" :title="queuedMsg" type="info" :closable="false" class="mt" />
          <div v-if="resultHeaders.length" class="mt">
            <el-table :data="resultRows" size="small" border max-height="420">
              <el-table-column v-for="h in resultHeaders" :key="h" :prop="h" :label="h" />
            </el-table>
            <div class="result-foot">
              共 {{ resultTotal }} 行
              <el-tag v-if="resultTruncated" type="warning" size="small">已截断</el-tag>
              <el-button size="small" type="success" @click="openSaveAs">另存为临时报表</el-button>
            </div>
          </div>
        </el-card>
        <!-- 我的任务 -->
        <el-card shadow="never" class="mt">
          <template #header><span class="card-title">我的任务</span></template>
          <el-table :data="tasks" size="small" max-height="240">
            <el-table-column prop="taskNo" label="任务号" width="170" />
            <el-table-column prop="status" label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="estRows" label="预估行" width="80" />
            <el-table-column prop="actualRows" label="实际行" width="80" />
            <el-table-column prop="execMs" label="耗时ms" width="80" />
            <el-table-column prop="rejectReason" label="阻断原因" />
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button v-if="row.status === 'DONE'" link type="primary" size="small"
                           @click="viewTask(row)">查看</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 另存弹窗 -->
    <el-dialog v-model="saveAsVisible" title="另存为临时报表" width="440px">
      <el-form label-width="70px">
        <el-form-item label="名称" required><el-input v-model="saveAsForm.name" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="saveAsForm.remark" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveAsVisible = false">取消</el-button>
        <el-button type="primary" @click="doSaveAs">保存</el-button>
      </template>
    </el-dialog>

    <!-- 结果抽屉（任务结果，跨页共用） -->
    <ResultDrawer ref="drawerRef" />
  </div>
</template>

<script setup>
/**
 * 14.6.1 即席查询（拆分自原即席查询操作台）：数据集选择 / 查询构建与执行 / 我的查询 / 我的任务。
 * 临时报表列表（另存后刷新）见 useTempReports.js，任务与临时报表结果抽屉见 _parts/ResultDrawer.vue。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDatasetsApi, runQueryApi, getMyTasksApi, getTaskDetailApi,
  getSavedQueriesApi, saveQueryApi, deleteSavedQueryApi, saveAsTempApi
} from '@/api/bi/adhoc-query'
import { useTempReports } from './useTempReports'
import ResultDrawer from './_parts/ResultDrawer.vue'

const { loadTemp } = useTempReports()
const drawerRef = ref(null)

// ---------------- 数据集与字段 ----------------
const datasets = ref([])
const datasetId = ref('')
const pickedDims = ref([])
const filters = ref([{ field: '', op: '=', value: '' }])
const ops = ['=', '≠', '>', '≥', '<', '≤', 'LIKE', 'BETWEEN']
const opMap = { '=': 'EQ', '≠': 'NE', '>': 'GT', '≥': 'GE', '<': 'LT', '≤': 'LE', 'LIKE': 'LIKE', 'BETWEEN': 'BETWEEN' }
const running = ref(false)
const blocked = ref('')
const queuedMsg = ref('')
const quotaWarn = ref('')
const resultHeaders = ref([])
const resultRows = ref([])
const resultTotal = ref(0)
const resultTruncated = ref(false)
const tasks = ref([])
const savedQueries = ref([])

const dataset = computed(() => datasets.value.find(d => d.id === datasetId.value))
const filterFields = computed(() => {
  const d = dataset.value
  if (!d) return []
  return [...(d.dimensions || []), ...(d.measures || [])]
})

function aggOptions(f) {
  const a = (f.agg || []).filter(x => x !== 'UNREGISTERED')
  return a.length ? a : ['SUM', 'COUNT', 'AVG', 'MIN', 'MAX']
}

function syncMeasures() { /* 响应式由 _picked 驱动，无需额外处理 */ }

function onDatasetChange() {
  pickedDims.value = []
  filters.value = [{ field: '', op: '=', value: '' }]
}

function buildQueryJson() {
  const d = dataset.value
  const measures = (d?.measures || []).filter(f => f._picked)
    .map(f => ({ field: f.field, agg: f._agg || 'SUM' }))
  const fl = filters.value.filter(f => f.field && f.op)
    .map(f => {
      if (f.op === 'BETWEEN') return { field: f.field, op: 'BETWEEN', value: [f.v1, f.v2] }
      return { field: f.field, op: opMap[f.op] || 'EQ', value: f.value }
    })
  return JSON.stringify({ dimensions: pickedDims.value, measures, filters: fl })
}

async function runQuery() {
  blocked.value = ''; queuedMsg.value = ''; quotaWarn.value = ''
  running.value = true
  try {
    const res = await runQueryApi(datasetId.value, buildQueryJson())
    const d = res.data || {}
    if (d.mode === 'sync') {
      resultHeaders.value = d.headers || []
      resultRows.value = (d.rows || []).map(r => {
        const o = {}; (d.headers || []).forEach((h, i) => { o[h] = r[i] }); return o
      })
      resultTotal.value = resultRows.value.length
      resultTruncated.value = !!d.truncated
    } else {
      queuedMsg.value = `已转入后台任务（${d.taskNo}），完成后可在我的任务查看`
      resultHeaders.value = []; resultRows.value = []
    }
    if (d.quotaWarn) quotaWarn.value = d.quotaWarn
    if (d.quotaMsg) queuedMsg.value = d.quotaMsg
    loadTasks()
  } catch (e) {
    blocked.value = e?.response?.data?.message || e?.message || '查询被拦截'
  } finally {
    running.value = false
  }
}

async function loadTasks() {
  try { tasks.value = (await getMyTasksApi()).data || [] } catch (e) { /* 治理页权限受限时静默 */ }
}

async function viewTask(row) {
  const res = await getTaskDetailApi(row.id, 1, 200)
  const d = res.data || {}
  drawerRef.value.open({
    title: `任务结果 ${row.taskNo}`,
    headers: d.headers || Object.keys((d.rows || [{}])[0] || {}),
    rows: d.rows || []
  })
}

// ---------------- 我的查询 ----------------
async function loadSavedList() {
  try { savedQueries.value = (await getSavedQueriesApi()).data || [] } catch (e) { /* ignore */ }
}

function loadSaved(q) {
  try {
    const def = JSON.parse(q.queryJson)
    datasetId.value = def.templateId || q.templateId
    pickedDims.value = def.dimensions || []
    filters.value = (def.filters || []).map(f => ({
      field: f.field, op: Object.keys(opMap).find(k => opMap[k] === f.op) || '=',
      value: Array.isArray(f.value) ? '' : f.value,
      v1: Array.isArray(f.value) ? f.value[0] : '', v2: Array.isArray(f.value) ? f.value[1] : ''
    }))
    if (!filters.value.length) filters.value = [{ field: '', op: '=', value: '' }]
    ElMessage.success('已加载：' + q.name)
  } catch (e) {
    ElMessage.warning('查询定义解析失败')
  }
}

async function saveCurrent() {
  const { value } = await ElMessageBox.prompt('查询名称', '保存我的查询')
  await saveQueryApi({ name: value, templateId: datasetId.value, queryJson: buildQueryJson() })
  ElMessage.success('已保存')
  loadSavedList()
}

async function removeSaved(id) {
  await deleteSavedQueryApi(id)
  loadSavedList()
}

// ---------------- 另存为临时报表 ----------------
const saveAsVisible = ref(false)
const saveAsForm = ref({ name: '', remark: '' })

function openSaveAs() {
  saveAsForm.value = { name: '', remark: '' }
  saveAsVisible.value = true
}

async function doSaveAs() {
  await saveAsTempApi({
    name: saveAsForm.value.name, remark: saveAsForm.value.remark,
    templateId: datasetId.value, queryJson: buildQueryJson()
  })
  ElMessage.success('已另存（30 天有效期）')
  saveAsVisible.value = false
  loadTemp()
}

function statusType(s) {
  return { DONE: 'success', FAILED: 'danger', TIMEOUT: 'warning', REJECTED: 'info', RUNNING: 'primary' }[s] || 'info'
}

async function loadDatasets() {
  try { datasets.value = (await getDatasetsApi()).data || [] } catch (e) { /* ignore */ }
}

onMounted(() => {
  loadDatasets(); loadSavedList(); loadTasks()
})
</script>

<style scoped>
.adhoc-wrap { padding: 4px; }
.card-title { font-weight: 600; }
.mt { margin-top: 12px; }
.field-tree { margin-top: 10px; max-height: 300px; overflow: auto; }
.tree-group { margin-bottom: 10px; }
.tree-head { font-size: 12px; color: #909399; margin: 6px 0 4px; }
.tree-item { display: flex; align-items: center; padding: 1px 0; }
.filter-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; }
.saved-item { display: flex; justify-content: space-between; align-items: center; padding: 2px 0; }
.quota-warn { color: #e6a23c; margin-left: 12px; font-size: 12px; }
.result-foot { margin-top: 8px; display: flex; gap: 12px; align-items: center; color: #909399; font-size: 12px; }
</style>
