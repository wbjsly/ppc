<template>
  <div class="kpi-wrap">
    <!-- ========== 14.7.1 指标达成 ========== -->
    <div class="toolbar">
      <el-button size="small" :loading="sumLoading" @click="loadSummary">刷新</el-button>
      <el-button size="small" type="primary" plain @click="runSnapshot">手动快照</el-button>
      <el-button v-if="canConfig" size="small" type="warning" plain
                 @click="openConfig">KPI 配置</el-button>
    </div>
    <el-empty v-if="!cards.length" description="暂无 KPI 数据（先在 KPI 配置维护并执行快照）" />
    <div class="kpi-grid">
      <div v-for="c in cards" :key="c.kpiKey" class="kpi-card"
           :class="cardClass(c)" @click="drill(c)">
        <div class="kpi-name">{{ c.name }}</div>
        <div class="kpi-actual">{{ fmt(c.actual) }}</div>
        <div class="kpi-sub">
          <span>目标 {{ c.target == null ? '—' : fmt(c.target) }}</span>
          <span v-if="c.ratio != null">达成 {{ (c.ratio * 100).toFixed(1) }}%</span>
          <span v-else class="no-target">无目标</span>
        </div>
        <div class="kpi-tag">
          <el-tag v-if="c.status === 'ACHIEVED'" type="success" size="small">达标</el-tag>
          <el-tag v-else-if="c.status === 'WARN'" type="warning" size="small">预警</el-tag>
          <el-tag v-else-if="c.status === 'BELOW'" type="danger" size="small">未达标</el-tag>
          <el-tag v-else size="info" small>无目标</el-tag>
          <el-tag v-if="c.hasDrill" size="small" type="primary" effect="plain">可下钻</el-tag>
        </div>
      </div>
    </div>

    <!-- KPI 配置抽屉 -->
    <el-drawer v-model="cfgVisible" title="KPI 配置" size="70%">
      <div class="toolbar">
        <el-button size="small" type="primary" @click="editDef(null)">新增 KPI</el-button>
      </div>
      <el-table :data="defs" size="small" border max-height="300">
        <el-table-column prop="kpiKey" label="编码" width="130" />
        <el-table-column prop="name" label="名称" width="130" />
        <el-table-column prop="metricKey" label="口径" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.metricKey" size="small">{{ row.metricKey }}</el-tag>
            <el-tag v-else size="small" type="info">未挂</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dimKey" label="维度" width="90" />
        <el-table-column prop="targetMode" label="目标模式" width="90" />
        <el-table-column prop="thresholdWarn" label="预警阈值" width="80" />
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="editDef(row)">编辑</el-button>
            <el-button link size="small" @click="openTargets(row)">目标</el-button>
            <el-button link type="danger" size="small" @click="removeDef(row)">删</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 定义编辑表单 -->
      <el-divider v-if="defForm" content-position="left">{{ defForm.id ? '编辑' : '新增' }} KPI</el-divider>
      <el-form v-if="defForm" label-width="90px" size="small">
        <el-row :gutter="10">
          <el-col :span="11">
            <el-form-item label="编码" required>
              <el-input v-model="defForm.kpiKey" placeholder="KPI-XXX" :disabled="!!defForm.id" />
            </el-form-item>
          </el-col>
          <el-col :span="13">
            <el-form-item label="名称" required><el-input v-model="defForm.name" /></el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="10">
          <el-col :span="11">
            <el-form-item label="口径指标">
              <el-select v-model="defForm.metricKey" clearable placeholder="可空=未挂口径"
                         style="width:100%" filterable>
                <el-option v-for="m in metrics" :key="m.metricKey" :value="m.metricKey"
                           :label="`${m.metricKey} ${m.name}`" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="维度列">
              <el-input v-model="defForm.dimKey" placeholder="可空=全局" />
            </el-form-item>
          </el-col>
          <el-col :span="7">
            <el-form-item label="责任人">
              <el-input v-model="defForm.owner" placeholder="快照执行身份" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="取数 SQL" required>
          <el-input v-model="defForm.sqlText" type="textarea" :rows="3"
                    placeholder="只读 SELECT，输出 kpi_key/dim_value/period_date/actual 四列" />
        </el-form-item>
        <el-row :gutter="10">
          <el-col :span="8">
            <el-form-item label="目标模式">
              <el-select v-model="defForm.targetMode" style="width:100%">
                <el-option value="PERIODIC" label="按期间维护" />
                <el-option value="FIXED" label="固定值" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="固定目标">
              <el-input-number v-model="defForm.fixedTarget" :precision="2"
                               style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="下钻模板">
              <el-select v-model="defForm.drillTemplateId" clearable style="width:100%"
                         filterable placeholder="可空=不可下钻">
                <el-option v-for="t in pubTemplates" :key="t.id" :value="t.id"
                           :label="`${t.code} ${t.name}`" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="10">
          <el-col :span="8">
            <el-form-item label="预警阈值">
              <el-input-number v-model="defForm.thresholdWarn" :precision="2"
                               :min="0" :max="1" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="未达阈值">
              <el-input-number v-model="defForm.thresholdCritical" :precision="2"
                               :min="0" :max="1" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="状态">
              <el-select v-model="defForm.status" style="width:100%">
                <el-option value="ENABLED" label="启用" />
                <el-option value="DISABLED" label="停用" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注"><el-input v-model="defForm.remark" /></el-form-item>
        <div>
          <el-button size="small" type="primary" :loading="defSaving"
                     @click="saveDef">保存（口径校验）</el-button>
          <el-button size="small" @click="defForm = null">取消</el-button>
        </div>
      </el-form>
    </el-drawer>

    <!-- 期间目标弹窗 -->
    <el-dialog v-model="tgtVisible" :title="`期间目标：${tgtKpiName}`" width="480px">
      <el-table :data="targets" size="small" max-height="240">
        <el-table-column prop="period" label="期间" width="110" />
        <el-table-column prop="targetValue" label="目标值" />
      </el-table>
      <div class="tgt-add">
        <el-input v-model="tgtForm.period" placeholder="yyyy-MM" style="width:120px" />
        <el-input-number v-model="tgtForm.targetValue" :precision="2" style="width:160px" />
        <el-button size="small" type="primary" @click="saveTarget">保存目标</el-button>
      </div>
    </el-dialog>

    <!-- 下钻抽屉（跨页共用） -->
    <DrillDrawer />
  </div>
</template>

<script setup>
/**
 * 14.7.1 指标达成（拆分自原 KPI 看板操作台）：KPI 卡片 / 手动快照 / KPI 配置与期间目标。
 * KPI 卡片与下钻状态见 useKpiDashboard.js（趋势展示页、异常预警页共用）。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDefsApi, saveDefApi, updateDefApi,
  deleteDefApi, getTargetsApi, saveTargetApi, runSnapshotApi
} from '@/api/bi/kpi-dashboard'
import { getTemplatesApi } from '@/api/bi/report-template'
import { getMetricsApi } from '@/api/bi/metric-dict'
import { useKpiDashboard } from './useKpiDashboard'
import DrillDrawer from './_parts/DrillDrawer.vue'

const { cards, sumLoading, loadSummary, openDrill } = useKpiDashboard()

const canConfig = ref(true) // 后端 403 时静默隐藏

function fmt(v) {
  if (v == null) return '—'
  const n = Number(v)
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

function cardClass(c) {
  if (c.status === 'ACHIEVED') return 'st-ok'
  if (c.status === 'WARN') return 'st-warn'
  if (c.status === 'BELOW') return 'st-bad'
  return 'st-none'
}

async function runSnapshot() {
  try {
    const res = await runSnapshotApi()
    const d = res.data || {}
    ElMessage.success(`快照完成：成功 ${d.success}，失败 ${d.failed}，预警扫描 ${d.alerts}`)
    loadSummary()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '快照失败（需 BI_DESIGNER/ADMIN）')
  }
}

function drill(c) {
  if (!c.hasDrill) {
    ElMessage.info('该 KPI 未挂下钻模板')
    return
  }
  openDrill(c.drillTemplateId, c.name)
}

// ---------------- 配置抽屉 ----------------
const cfgVisible = ref(false)
const defs = ref([])
const metrics = ref([])
const pubTemplates = ref([])
const defForm = ref(null)
const defSaving = ref(false)

async function openConfig() {
  cfgVisible.value = true
  await loadDefs()
  try {
    metrics.value = (await getMetricsApi({ page: 1, size: 200 })).data?.records
      || (await getMetricsApi({ page: 1, size: 200 })).data || []
  } catch (e) { metrics.value = [] }
  try {
    const t = await getTemplatesApi({ status: 'PUBLISHED', page: 1, size: 200 })
    pubTemplates.value = t.data?.records || t.data || []
  } catch (e) { pubTemplates.value = [] }
}

async function loadDefs() {
  defs.value = (await getDefsApi()).data || []
}

function editDef(row) {
  defForm.value = row ? { ...row } : {
    kpiKey: '', name: '', metricKey: '', dimKey: '', sqlText: '',
    drillTemplateId: '', targetMode: 'PERIODIC', fixedTarget: null,
    thresholdWarn: 0.8, thresholdCritical: 0.5, status: 'ENABLED',
    owner: 'admin', remark: ''
  }
}

async function saveDef() {
  defSaving.value = true
  try {
    if (defForm.value.id) {
      await updateDefApi(defForm.value.id, defForm.value)
    } else {
      await saveDefApi(defForm.value)
    }
    ElMessage.success('已保存（口径校验通过）')
    defForm.value = null
    loadDefs()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    defSaving.value = false
  }
}

async function removeDef(row) {
  await ElMessageBox.confirm(`删除 KPI「${row.name}」？`, '删除')
  await deleteDefApi(row.id)
  ElMessage.success('已删除')
  loadDefs()
}

// ---------------- 期间目标 ----------------
const tgtVisible = ref(false)
const tgtKpiName = ref('')
const tgtKpiKey = ref('')
const targets = ref([])
const tgtForm = ref({ period: '', targetValue: null })

async function openTargets(row) {
  tgtKpiKey.value = row.kpiKey
  tgtKpiName.value = row.name
  tgtForm.value = { period: new Date().toISOString().slice(0, 7), targetValue: null }
  targets.value = (await getTargetsApi(row.kpiKey)).data || []
  tgtVisible.value = true
}

async function saveTarget() {
  if (!tgtForm.value.period || tgtForm.value.targetValue == null) {
    ElMessage.warning('期间与目标值必填')
    return
  }
  await saveTargetApi({ kpiKey: tgtKpiKey.value, ...tgtForm.value })
  ElMessage.success('目标已保存')
  targets.value = (await getTargetsApi(tgtKpiKey.value)).data || []
}

onMounted(() => {
  loadSummary()
})
</script>

<style scoped>
.kpi-wrap { padding: 4px; }
.toolbar { margin-bottom: 12px; display: flex; gap: 8px; align-items: center; }
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 12px; }
.kpi-card { border: 2px solid #ebeef5; border-radius: 8px; padding: 14px; cursor: pointer; transition: box-shadow .2s; background: #fff; }
.kpi-card:hover { box-shadow: 0 4px 12px rgba(0,0,0,.1); }
.st-ok { border-color: #67C23A; }
.st-warn { border-color: #E6A23C; }
.st-bad { border-color: #F56C6C; }
.st-none { border-color: #ebeef5; opacity: .85; }
.kpi-name { font-weight: 600; color: #303133; margin-bottom: 8px; }
.kpi-actual { font-size: 26px; font-weight: 700; color: #303133; }
.kpi-sub { display: flex; gap: 10px; margin-top: 6px; color: #909399; font-size: 12px; }
.no-target { color: #c0c4cc; }
.kpi-tag { margin-top: 8px; display: flex; gap: 6px; }
.tgt-add { margin-top: 10px; display: flex; gap: 8px; align-items: center; }
</style>
