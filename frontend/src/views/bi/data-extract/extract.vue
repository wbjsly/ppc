<template>
  <div>
    <el-page-header content="数据抽取（14.2）— 抽取配置/脱敏规则/质量校验三叶；波动暂停（BR-4.10-12）、断点续抽（BR-4.10-13）、质量分级阻断（BR-4.10-14）、schema 变更检测（BR-4.10-15）"
                    style="margin-bottom: 8px" />

    <!-- ==================== 14.2.1 抽取配置 ==================== -->
    <el-alert v-if="ready.ready !== undefined" :type="ready.ready ? 'success' : 'warning'"
              :title="ready.ready ? `数据就绪（批次 ${ready.batchNo}，${ready.readyAt || ''}）`
                : `数据待就绪（最近批次 ${ready.batchNo || '—'}：${ready.status || '无'}）`"
              show-icon :closable="false" style="margin-bottom: 8px" />

    <h4>数据源（连接加密存储，schema 快照检测变更）</h4>
    <div class="toolbar">
      <el-button size="small" type="success" @click="openDsEdit(null)">新建数据源</el-button>
      <el-button size="small" @click="loadDs">刷新</el-button>
    </div>
    <el-table :data="datasources" border stripe size="small" max-height="220">
      <el-table-column prop="name" label="名称" width="140" />
      <el-table-column prop="type" label="类型" width="70" align="center" />
      <el-table-column prop="dbName" label="库名" width="110" />
      <el-table-column prop="connMasked" label="连接" width="110" show-overflow-tooltip />
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success'
            : row.status === 'SCHEMA_CHANGED' ? 'danger' : 'info'" size="small">
            {{ dsStatus(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="snapshotAt" label="快照时间" width="150" />
      <el-table-column label="操作" width="260" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openDsEdit(row)">编辑</el-button>
          <el-button size="small" type="primary" link @click="onTestConn(row)">连通测试</el-button>
          <el-button size="small" type="warning" link @click="onCheckSchema(row)">结构比对</el-button>
          <el-button size="small" type="success" link @click="onRefreshSnap(row)">更新快照</el-button>
          <el-button size="small" type="danger" link @click="removeDs(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h4 style="margin-top: 14px">抽取配置（绑定已发布模板；全量/增量 + 水位 + DAG 层序）</h4>
    <div class="toolbar">
      <el-select v-model="cfgDsFilter" placeholder="按数据源过滤" size="small" clearable
                 style="width: 180px" @change="loadConfigs">
        <el-option v-for="d in datasources" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <el-button size="small" type="success" @click="openCfgEdit(null)">新建配置</el-button>
    </div>
    <el-table :data="configs" border stripe size="small" max-height="220">
      <el-table-column prop="code" label="编码" width="120" />
      <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
      <el-table-column label="策略" width="80" align="center">
        <template #default="{ row }">{{ row.mode === 'INCREMENTAL' ? '增量' : '全量' }}</template>
      </el-table-column>
      <el-table-column prop="watermarkField" label="水位字段" width="110" />
      <el-table-column prop="lastWatermark" label="当前水位" width="110" />
      <el-table-column prop="seq" label="层序" width="60" align="center" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
            {{ row.status === 'ENABLED' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openCfgEdit(row)">编辑</el-button>
          <el-button size="small" type="primary" link @click="toggleCfg(row)">
            {{ row.status === 'ENABLED' ? '停用' : '启用' }}</el-button>
          <el-button size="small" type="danger" link @click="removeCfg(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h4 style="margin-top: 14px">批次运行（01:00-05:00 窗口；手动补跑绕过）</h4>
    <div class="toolbar">
      <el-date-picker v-model="runDate" type="date" value-format="YYYY-MM-DD" size="small"
                      style="width: 150px" @change="loadRuns" />
      <el-button size="small" type="primary" @click="onRunBatch">执行/推进批次</el-button>
      <el-button size="small" @click="loadRuns">刷新</el-button>
    </div>
    <el-table :data="runs" border stripe size="small" max-height="260">
      <el-table-column prop="configId" label="配置" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ cfgName(row.configId) }}</template>
      </el-table-column>
      <el-table-column prop="seq" label="层序" width="60" align="center" />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="runTag(row.status)" size="small">{{ runStatus(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="rowCount" label="行数" width="90" align="right" />
      <el-table-column prop="prevRowCount" label="前次行数" width="90" align="right" />
      <el-table-column prop="watermark" label="水位" width="100" />
      <el-table-column prop="retry" label="重试" width="60" align="center" />
      <el-table-column prop="elapsedMs" label="耗时ms" width="80" align="right" />
      <el-table-column prop="errorMsg" label="异常" min-width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="150" align="center">
        <template #default="{ row }">
          <template v-if="row.status === 'PAUSED'">
            <el-button size="small" type="success" link @click="onConfirm(row)">确认继续</el-button>
            <el-button size="small" type="danger" link @click="onAbort(row)">确认放弃</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dsVisible" :title="dsId ? '编辑数据源' : '新建数据源'" width="560px"
               :close-on-click-modal="false">
      <el-form label-width="100px" size="small">
        <el-form-item label="名称" required>
          <el-input v-model="dsForm.name" :disabled="!!dsId" placeholder="唯一名称" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="dsForm.type" style="width: 160px">
            <el-option label="数据库 DB" value="DB" />
            <el-option label="API（登记形态）" value="API" />
            <el-option label="文件 FILE（登记形态）" value="FILE" />
          </el-select>
        </el-form-item>
        <el-form-item label="逻辑库名">
          <el-input v-model="dsForm.dbName" placeholder="DB 类型登记；空 = 本库" />
        </el-form-item>
        <el-form-item label="连接信息">
          <el-input v-model="dsForm.conn" type="password" show-password
                    placeholder="加密存储，明文不出接口（FR-4.10-2-1）" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="dsForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="dsVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="saveDs">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="cfgVisible" :title="cfgId ? '编辑抽取配置' : '新建抽取配置'" width="600px"
               :close-on-click-modal="false">
      <el-form label-width="100px" size="small">
        <el-form-item label="编码" required>
          <el-input v-model="cfgForm.code" :disabled="!!cfgId" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="cfgForm.name" />
        </el-form-item>
        <el-form-item label="数据源" required>
          <el-select v-model="cfgForm.datasourceId" style="width: 220px">
            <el-option v-for="d in datasources" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="报表模板" required>
          <el-select v-model="cfgForm.templateId" filterable style="width: 300px"
                     placeholder="仅已发布模板">
            <el-option v-for="t in publishedTemplates" :key="t.id"
                       :label="`${t.code} ${t.name}`" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="抽取策略">
          <el-radio-group v-model="cfgForm.mode">
            <el-radio label="FULL">全量</el-radio>
            <el-radio label="INCREMENTAL">增量</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="cfgForm.mode === 'INCREMENTAL'" label="水位字段" required>
          <el-input v-model="cfgForm.watermarkField" placeholder="须在模板列清单内" />
        </el-form-item>
        <el-form-item label="DAG 层序">
          <el-input-number v-model="cfgForm.seq" :min="10" :step="10" size="small" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="cfgVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="saveCfg">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 14.2.1 抽取配置（拆分自原数据抽取操作台）：数据源 / 抽取配置 / 批次运行与推进。
 * 数据源列表见 useDataExtract.js（质量校验页规则表单亦引用），已发布模板下拉留页内。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createDatasourceApi, updateDatasourceApi, deleteDatasourceApi,
  testConnectionApi, checkSchemaApi, refreshSnapshotApi,
  getExtractConfigsApi, createExtractConfigApi, updateExtractConfigApi, deleteExtractConfigApi,
  getRunsApi, runBatchApi, confirmRunApi, abortRunApi, getReadyApi
} from '@/api/bi/data-extract'
import { getTemplatesApi } from '@/api/bi/report-template'
import { useDataExtract } from './useDataExtract'

const { datasources, loadDs } = useDataExtract()

const configs = ref([])
const runs = ref([])
const ready = ref({})
const runDate = ref(new Date().toISOString().slice(0, 10))
const cfgDsFilter = ref('')
const publishedTemplates = ref([])

const dsVisible = ref(false)
const dsId = ref('')
const dsForm = ref({ name: '', type: 'DB', dbName: '', conn: '', remark: '' })
const cfgVisible = ref(false)
const cfgId = ref('')
const cfgForm = ref({})

async function loadConfigs() {
  const res = await getExtractConfigsApi(cfgDsFilter.value || undefined)
  configs.value = res.data || []
}
async function loadRuns() {
  const res = await getRunsApi(runDate.value)
  runs.value = res.data || []
}
async function loadReady() {
  const res = await getReadyApi()
  ready.value = res.data || {}
}
async function loadTemplates() {
  const res = await getTemplatesApi({ status: 'PUBLISHED' })
  publishedTemplates.value = res.data || []
}

function openDsEdit(row) {
  dsId.value = row ? row.id : ''
  dsForm.value = row
    ? { name: row.name, type: row.type, dbName: row.dbName || '', conn: '', remark: row.remark || '' }
    : { name: '', type: 'DB', dbName: '', conn: '', remark: '' }
  dsVisible.value = true
}
async function saveDs() {
  const payload = { ...dsForm.value }
  if (!payload.conn) delete payload.conn
  if (dsId.value) {
    await updateDatasourceApi(dsId.value, payload)
  } else {
    await createDatasourceApi(payload)
  }
  ElMessage.success('已保存')
  dsVisible.value = false
  loadDs()
}
async function removeDs(row) {
  await ElMessageBox.confirm(`删除数据源「${row.name}」？`, '提示', { type: 'warning' })
  await deleteDatasourceApi(row.id)
  ElMessage.success('已删除')
  loadDs()
}
async function onTestConn(row) {
  const res = await testConnectionApi(row.id)
  ElMessage({ type: res.data.ok ? 'success' : 'error', message: res.data.msg })
}
async function onCheckSchema(row) {
  const res = await checkSchemaApi(row.id)
  if (res.data.changed) {
    ElMessage.warning(`检测到结构变更 ${res.data.diff.length} 处，其下配置已暂停自动执行`)
  } else {
    ElMessage.success('结构一致')
  }
  loadDs()
}
async function onRefreshSnap(row) {
  await refreshSnapshotApi(row.id)
  ElMessage.success('快照已更新，数据源恢复 ACTIVE')
  loadDs()
}

function openCfgEdit(row) {
  cfgId.value = row ? row.id : ''
  cfgForm.value = row
    ? { code: row.code, name: row.name, datasourceId: row.datasourceId, templateId: row.templateId,
        mode: row.mode, watermarkField: row.watermarkField || '', seq: row.seq }
    : { code: '', name: '', datasourceId: '', templateId: '', mode: 'FULL',
        watermarkField: '', seq: 10 }
  cfgVisible.value = true
}
async function saveCfg() {
  const payload = { ...cfgForm.value }
  if (cfgId.value) {
    delete payload.code
    await updateExtractConfigApi(cfgId.value, payload)
  } else {
    await createExtractConfigApi(payload)
  }
  ElMessage.success('已保存')
  cfgVisible.value = false
  loadConfigs()
}
async function toggleCfg(row) {
  await updateExtractConfigApi(row.id, { status: row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED' })
  loadConfigs()
}
async function removeCfg(row) {
  await ElMessageBox.confirm(`删除配置「${row.name}」？有执行记录时将拒绝删除。`, '提示', { type: 'warning' })
  await deleteExtractConfigApi(row.id)
  ElMessage.success('已删除')
  loadConfigs()
}

async function onRunBatch() {
  const res = await runBatchApi(runDate.value, true)
  const d = res.data
  if (d.skipped) {
    ElMessage.info('窗口外自动调度不启动，请使用手动补跑')
  } else {
    ElMessage.success(`批次推进：${d.batchStatus || d.note}（执行 ${d.executed ?? 0} 个任务）`)
  }
  loadRuns(); loadReady()
}
async function onConfirm(row) {
  await confirmRunApi(row.id)
  ElMessage.success('已确认继续，重新执行批次推进')
  onRunBatch()
}
async function onAbort(row) {
  await ElMessageBox.confirm('放弃该暂停任务？将终止并留痕。', '提示', { type: 'warning' })
  await abortRunApi(row.id)
  ElMessage.success('已终止')
  loadRuns()
}

function cfgName(id) {
  const c = configs.value.find(x => x.id === id)
  return c ? c.code : id
}
function dsStatus(s) {
  return { ACTIVE: '正常', SCHEMA_CHANGED: '结构变更', DISABLED: '停用' }[s] || s
}
function runStatus(s) {
  return { PENDING: '待执行', RUNNING: '执行中', READY: '完成', PAUSED: '波动暂停', FAILED: '失败' }[s] || s
}
function runTag(s) {
  return { PENDING: 'info', RUNNING: 'primary', READY: 'success', PAUSED: 'warning', FAILED: 'danger' }[s] || 'info'
}

onMounted(() => {
  loadDs(); loadConfigs(); loadRuns(); loadReady(); loadTemplates()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap; }
h4 { margin: 4px 0 8px; }
</style>
