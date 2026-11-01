<template>
  <div class="adhoc-wrap">
    <el-tabs v-model="tab">
      <!-- ========== 14.6.1 即席查询 ========== -->
      <el-tab-pane label="即席查询" name="query">
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
      </el-tab-pane>

      <!-- ========== 14.6.2 查询治理 ========== -->
      <el-tab-pane label="查询治理" name="governance">
        <el-row :gutter="12">
          <el-col :span="6">
            <el-card shadow="never">
              <div class="quota-card">
                <div class="quota-num">{{ gov.remaining }} / {{ gov.quota }}</div>
                <div class="quota-label">今日剩余配额</div>
                <div class="quota-sub">已用 {{ gov.usedToday }} 次</div>
              </div>
            </el-card>
          </el-col>
          <el-col :span="18">
            <el-card shadow="never">
              <template #header><span class="card-title">用量汇总（按用户）</span></template>
              <el-table :data="gov.usage || []" size="small" max-height="420">
                <el-table-column prop="userName" label="用户" />
                <el-table-column prop="total" label="总次数" width="90" />
                <el-table-column prop="scanned" label="扫描行合计" width="110" />
                <el-table-column prop="queued" label="转后台" width="90" />
                <el-table-column prop="rejected" label="被拦截" width="90" />
              </el-table>
            </el-card>
          </el-col>
        </el-row>
      </el-tab-pane>

      <!-- ========== 14.6.3 临时报表 ========== -->
      <el-tab-pane label="临时报表" name="temp">
        <el-card shadow="never">
          <template #header><span class="card-title">临时报表（我的 + 共享给我的）</span></template>
          <el-table :data="tempReports" size="small">
            <el-table-column prop="name" label="名称" />
            <el-table-column prop="relation" label="关系" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.relation === 'mine' ? 'primary' : 'success'">
                  {{ row.relation === 'mine' ? '我创建' : '共享给我' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="shareCount" label="共享数" width="80" />
            <el-table-column prop="daysLeft" label="剩余天" width="80" />
            <el-table-column prop="expireAt" label="到期时间" width="160">
              <template #default="{ row }">{{ (row.expireAt || '').slice(0, 10) }}</template>
            </el-table-column>
            <el-table-column label="转正" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.promotedTo" type="success" size="small">已转正</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openTemp(row)">打开</el-button>
                <el-button v-if="row.relation === 'mine' && !row.promotedTo" link size="small"
                           @click="openShare(row)">共享</el-button>
                <el-button v-if="row.relation === 'mine' && !row.promotedTo" link type="warning" size="small"
                           @click="doPromote(row)">转正</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

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

    <!-- 共享弹窗 -->
    <el-dialog v-model="shareVisible" title="共享设置" width="440px">
      <div v-for="(s, i) in shareList" :key="i" class="filter-row">
        <el-select v-model="s.type" style="width:100px">
          <el-option value="user" label="用户" /><el-option value="role" label="角色" />
        </el-select>
        <el-input v-model="s.code" placeholder="用户名或角色码" style="width:200px" />
        <el-button link type="danger" @click="shareList.splice(i, 1)">删</el-button>
      </div>
      <el-button size="small" @click="shareList.push({ type: 'user', code: '' })">+ 授权</el-button>
      <template #footer>
        <el-button @click="shareVisible = false">取消</el-button>
        <el-button type="primary" @click="doShare">保存</el-button>
      </template>
    </el-dialog>

    <!-- 结果抽屉（任务/临时报表打开） -->
    <el-drawer v-model="drawerVisible" :title="drawerTitle" size="60%">
      <el-table :data="drawerRows" size="small" border max-height="60vh">
        <el-table-column v-for="h in drawerHeaders" :key="h" :prop="h" :label="h" />
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDatasetsApi, runQueryApi, getMyTasksApi, getTaskDetailApi, getGovernanceApi,
  getSavedQueriesApi, saveQueryApi, deleteSavedQueryApi,
  getTempReportsApi, saveAsTempApi, openTempApi, shareTempApi, promoteTempApi
} from '@/api/bi/adhoc-query'

const route = useRoute()
const tab = ref(route.meta?.tab || 'query')

// ---------------- 即席查询 ----------------
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
  drawerHeaders.value = d.headers || Object.keys((d.rows || [{}])[0] || {})
  drawerRows.value = (d.rows || []).map(r => {
    if (typeof r === 'object' && !Array.isArray(r)) return r
    const o = {}; drawerHeaders.value.forEach((h, i) => { o[h] = r?.[i] }); return o
  })
  drawerTitle.value = `任务结果 ${row.taskNo}`
  drawerVisible.value = true
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

// ---------------- 治理 ----------------
const gov = ref({})
async function loadGov() {
  try { gov.value = (await getGovernanceApi()).data || {} } catch (e) { /* 非治理角色静默 */ }
}

// ---------------- 临时报表 ----------------
const tempReports = ref([])
const saveAsVisible = ref(false)
const saveAsForm = ref({ name: '', remark: '' })
const shareVisible = ref(false)
const shareList = ref([])
const shareRow = ref(null)
const drawerVisible = ref(false)
const drawerTitle = ref('')
const drawerHeaders = ref([])
const drawerRows = ref([])

async function loadTemp() {
  try { tempReports.value = (await getTempReportsApi()).data || [] } catch (e) { /* ignore */ }
}

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

async function openTemp(row) {
  const res = await openTempApi(row.id, 1, 200)
  const d = res.data || {}
  drawerHeaders.value = d.headers || []
  drawerRows.value = (d.rows || []).map(r => {
    if (typeof r === 'object' && !Array.isArray(r)) return r
    const o = {}; drawerHeaders.value.forEach((h, i) => { o[h] = r?.[i] }); return o
  })
  drawerTitle.value = d.name || row.name
  drawerVisible.value = true
}

function openShare(row) {
  shareRow.value = row
  try { shareList.value = JSON.parse(row.shareJson || '[]') } catch (e) { shareList.value = [] }
  if (!shareList.value.length) shareList.value = [{ type: 'user', code: '' }]
  shareVisible.value = true
}

async function doShare() {
  await shareTempApi(shareRow.value.id, shareList.value.filter(s => s.code))
  ElMessage.success('共享已更新')
  shareVisible.value = false
  loadTemp()
}

async function doPromote(row) {
  await ElMessageBox.confirm('转正将生成正式模板草稿并执行口径一致性校验，继续？', '转正')
  try {
    const res = await promoteTempApi(row.id)
    ElMessage.success('已转正，模板草稿 ID：' + (res.data?.templateId || ''))
    loadTemp()
  } catch (e) {
    const msg = e?.response?.data?.message || '转正失败（口径校验未通过）'
    ElMessage.error(msg)
  }
}

function statusType(s) {
  return { DONE: 'success', FAILED: 'danger', TIMEOUT: 'warning', REJECTED: 'info', RUNNING: 'primary' }[s] || 'info'
}

onMounted(() => {
  loadDatasets(); loadSavedList(); loadTasks(); loadGov(); loadTemp()
})

async function loadDatasets() {
  try { datasets.value = (await getDatasetsApi()).data || [] } catch (e) { /* ignore */ }
}
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
.quota-card { text-align: center; padding: 20px 0; }
.quota-num { font-size: 32px; font-weight: 700; color: #409eff; }
.quota-label { color: #909399; margin-top: 6px; }
.quota-sub { color: #c0c4cc; font-size: 12px; margin-top: 4px; }
.quota-warn { color: #e6a23c; margin-left: 12px; font-size: 12px; }
.result-foot { margin-top: 8px; display: flex; gap: 12px; align-items: center; color: #909399; font-size: 12px; }
</style>
