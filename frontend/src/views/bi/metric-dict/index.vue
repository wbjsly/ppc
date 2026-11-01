<template>
  <div>
    <el-page-header content="指标口径（14.3）— 口径定义/口径变更/口径校验三叶；十二要素（6.3.1）、变更两段式治理（BR-4.10-04 / C-4.10-08）、血缘四层图谱（S-4.10-15）"
                    style="margin-bottom: 8px" />

    <el-tabs v-model="tab">
      <!-- ==================== 14.3.1 口径定义 ==================== -->
      <el-tab-pane label="口径定义" name="define">
        <div class="toolbar">
          <el-input v-model="keyword" placeholder="指标名称" size="small" clearable style="width: 180px"
                    @keyup.enter="load" />
          <el-button size="small" type="primary" @click="load">查询</el-button>
          <el-button size="small" type="success" @click="openEdit(null)">登记指标</el-button>
        </div>
        <el-table :data="metrics" v-loading="loading" border stripe size="small" max-height="360">
          <el-table-column prop="metricKey" label="编码" width="120" />
          <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
          <el-table-column label="公式类型" width="90" align="center">
            <template #default="{ row }">{{ ftLabel(row.formulaType) }}</template>
          </el-table-column>
          <el-table-column prop="numerator" label="分子" min-width="130" show-overflow-tooltip />
          <el-table-column prop="denominator" label="分母" min-width="110" show-overflow-tooltip />
          <el-table-column prop="dimensions" label="维度" width="130" show-overflow-tooltip />
          <el-table-column prop="owner" label="责任人" width="100" />
          <el-table-column prop="version" label="版本" width="60" align="center" />
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="stTag(row.status)" size="small">{{ stLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" align="center">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click="openEdit(row)">编辑</el-button>
              <el-button v-if="row.status !== 'PUBLISHED'" size="small" type="success" link
                        @click="onPublish(row)">发布</el-button>
              <el-button size="small" type="primary" link @click="openHistory(row)">历史</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-dialog v-model="editVisible" :title="editKey ? '编辑指标（未发布）' : '登记指标（十二要素）'"
                   width="720px" :close-on-click-modal="false">
          <el-form label-width="100px" size="small">
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="指标编码" required>
                  <el-input v-model="form.metricKey" :disabled="!!editKey"
                            placeholder="M-<域>-<序号>，如 M-SD-001" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="指标名称" required>
                  <el-input v-model="form.name" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="业务定义" required>
              <el-input v-model="form.definition" type="textarea" :rows="2" />
            </el-form-item>
            <el-row :gutter="12">
              <el-col :span="8">
                <el-form-item label="公式类型" required>
                  <el-select v-model="form.formulaType" style="width: 100%">
                    <el-option label="比率型" value="RATIO" />
                    <el-option label="连乘型" value="PRODUCT" />
                    <el-option label="累加型" value="ACCUM" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="统计周期" required>
                  <el-select v-model="form.timeDim" style="width: 100%">
                    <el-option label="日" value="DAY" />
                    <el-option label="月" value="MONTH" />
                    <el-option label="季" value="QUARTER" />
                    <el-option label="年" value="YEAR" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="责任人">
                  <el-input v-model="form.owner" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="分子" required>
              <el-input v-model="form.numerator" placeholder="计算公式及过滤条件" />
            </el-form-item>
            <el-form-item :label="form.formulaType === 'RATIO' ? '分母（必填）' : '分母'">
              <el-input v-model="form.denominator"
                        :placeholder="form.formulaType === 'RATIO' ? '比率型必填' : '连乘/累加填 — 并写计算顺序'" />
            </el-form-item>
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="维度">
                  <el-input v-model="form.dimensions" placeholder="客户/物料/销售组织/期间" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="过滤条件">
                  <el-input v-model="form.filterCond" placeholder="已过账/法人主体/币种等" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="计算公式" required>
              <el-input v-model="form.formula" type="textarea" :rows="2"
                        placeholder='JSON 结构化，如 {"expr":"SUM(AMOUNT)","precision":2}（diff 校验基准）' />
            </el-form-item>
            <el-form-item label="来源映射" required>
              <el-input v-model="form.srcMapping" type="textarea" :rows="2"
                        placeholder='JSON，如 {"Invoice.amount":"revenue"}（血缘上游表来源）' />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button size="small" @click="editVisible = false">取消</el-button>
            <el-button size="small" type="primary" @click="save">保存</el-button>
          </template>
        </el-dialog>

        <el-drawer v-model="historyVisible" :title="`版本历史：${historyKey}`" size="480px">
          <el-table :data="history" border stripe size="small">
            <el-table-column prop="version" label="版本" width="60" align="center" />
            <el-table-column prop="status" label="状态" width="100" align="center" />
            <el-table-column prop="formula" label="公式" min-width="160" show-overflow-tooltip />
            <el-table-column prop="createDate" label="时间" width="150" />
          </el-table>
        </el-drawer>
      </el-tab-pane>

      <!-- ==================== 14.3.2 口径变更 ==================== -->
      <el-tab-pane label="口径变更" name="change">
        <el-alert type="info" :closable="false" show-icon style="margin-bottom: 8px"
                  title="变更两段式治理：申请 → 数据治理单签 → 灰度（旧版仍生效）→ 切换确认（数据治理+业务负责人双审 C-4.10-08，超 0.5% 容差须治理复核备注）" />
        <div class="toolbar">
          <el-select v-model="changeMetricKey" filterable placeholder="选择已发布指标" style="width: 260px"
                     @change="loadChangeView">
            <el-option v-for="m in publishedMetrics" :key="m.metricKey"
                       :label="`${m.metricKey} ${m.name}`" :value="m.metricKey" />
          </el-select>
          <el-button size="small" type="success" :disabled="!changeMetricKey"
                    @click="openChange">发起变更</el-button>
          <el-button size="small" @click="loadChangeView">刷新</el-button>
        </div>

        <h4>进行中/历史变更</h4>
        <el-table :data="changes" border stripe size="small" max-height="200">
          <el-table-column prop="version" label="版本" width="60" align="center" />
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="chTag(row.status)" size="small">{{ chLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="formula" label="新公式" min-width="150" show-overflow-tooltip />
          <el-table-column prop="remark" label="变更说明" min-width="180" show-overflow-tooltip />
          <el-table-column prop="createBy" label="申请人" width="100" />
          <el-table-column label="操作" width="260" align="center">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click="openImpact(row)">影响报告</el-button>
              <el-button v-if="row.status === 'GRAYING'" size="small" type="warning" link
                        @click="openSwitch(row)">切换确认</el-button>
              <el-button v-if="row.status === 'GRAYING'" size="small" type="danger" link
                        @click="onAbort(row)">终止灰度</el-button>
            </template>
          </el-table-column>
        </el-table>

        <h4 style="margin-top: 14px">引用方（USED_BY，待更新标记随确认清除）</h4>
        <el-table :data="consumers" border stripe size="small" max-height="160">
          <el-table-column prop="id" label="引用方" min-width="160" />
          <el-table-column label="状态" width="130" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'PENDING_UPDATE' ? 'warning' : 'success'" size="small">
                {{ row.status === 'PENDING_UPDATE' ? '待更新' : '正常' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" align="center">
            <template #default="{ row }">
              <el-button v-if="row.status === 'PENDING_UPDATE'" size="small" type="success" link
                        @click="onConfirmConsumer(row)">更新确认</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-dialog v-model="changeVisible" title="发起口径变更" width="640px" :close-on-click-modal="false">
          <el-form label-width="100px" size="small">
            <el-form-item label="新公式" required>
              <el-input v-model="changeForm.formula" type="textarea" :rows="2"
                        placeholder='JSON，如 {"expr":"SUM(NET)","precision":2}' />
            </el-form-item>
            <el-form-item label="变更原因" required>
              <el-input v-model="changeForm.reason" />
            </el-form-item>
            <el-form-item label="影响范围">
              <el-input v-model="changeForm.scope" placeholder="涉及报表数/部门等" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button size="small" @click="changeVisible = false">取消</el-button>
            <el-button size="small" type="primary" @click="saveChange">提交（治理单签）</el-button>
          </template>
        </el-dialog>

        <el-dialog v-model="switchVisible" title="切换确认（双审 C-4.10-08）" width="600px"
                   :close-on-click-modal="false">
          <el-form label-width="110px" size="small">
            <el-form-item label="对比说明" required>
              <el-input v-model="switchForm.diffNote" type="textarea" :rows="2"
                        placeholder="新旧口径结果对比说明" />
            </el-form-item>
            <el-form-item label="差异百分比">
              <el-input-number v-model="switchForm.diffPctNum" :min="0" :precision="3" size="small" />
              <span class="hint">（超 0.5 须填治理复核备注）</span>
            </el-form-item>
            <el-form-item label="治理复核备注">
              <el-input v-model="switchForm.reviewNote" placeholder="超容差时必填" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button size="small" @click="switchVisible = false">取消</el-button>
            <el-button size="small" type="primary" @click="saveSwitch">提交（双审）</el-button>
          </template>
        </el-dialog>

        <el-drawer v-model="impactVisible" :title="`血缘图谱与影响报告：${impactKey} v${impactVersion}`"
                   size="560px">
          <el-alert v-if="impact.summary" :closable="false" show-icon type="warning"
                    :title="impact.summary" style="margin-bottom: 8px" />
          <h4>上游表</h4>
          <el-tag v-for="t in impact.upstreamTables || []" :key="t.nodeId" size="small"
                  style="margin: 2px" type="info">{{ t.nodeName }}</el-tag>
          <h4>衍生指标（下游）</h4>
          <el-tag v-for="m in impact.derivedMetrics || []" :key="m.nodeId" size="small"
                  style="margin: 2px" type="warning">{{ m.nodeId }} {{ m.nodeName }}</el-tag>
          <h4>引用报表</h4>
          <el-table :data="impact.reports || []" border stripe size="small">
            <el-table-column prop="nodeName" label="模板" min-width="160" show-overflow-tooltip />
            <el-table-column label="风险" width="80" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.highRisk === '1'" type="danger" size="small">高风险</el-tag>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-drawer>
      </el-tab-pane>

      <!-- ==================== 14.3.3 口径校验 ==================== -->
      <el-tab-pane label="口径校验" name="check">
        <div class="toolbar">
          <el-button size="small" type="primary" @click="onCheckAll">执行批量校验</el-button>
          <span class="hint">遍历已发布模板指标引用 × 字典当前版（现算，C-4.10-01）</span>
        </div>
        <el-table :data="checkReport" v-loading="checkLoading" border stripe size="small" max-height="420">
          <el-table-column prop="templateCode" label="模板编码" width="130" />
          <el-table-column prop="templateName" label="模板名称" min-width="140" show-overflow-tooltip />
          <el-table-column prop="metricKey" label="指标编码" width="120" />
          <el-table-column label="注册" width="70" align="center">
            <template #default="{ row }">
              <el-tag :type="row.registered ? 'success' : 'danger'" size="small">
                {{ row.registered ? '已注册' : '未注册' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="结论" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.consistent ? 'success' : 'danger'" size="small">
                {{ row.consistent ? '一致' : '差异' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="差异明细" min-width="220">
            <template #default="{ row }">
              <template v-if="!row.consistent">
                <div v-for="(d, i) in asArray(row.report)" :key="i" class="diff-line">
                  {{ d.path }} · {{ d.kind }} · {{ d.detail }}
                </div>
              </template>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getMetricsApi, registerMetricApi, publishMetricApi, getHistoryApi,
  getConsumersApi, confirmConsumerApi,
  requestChangeApi, getChangesApi, switchConfirmApi, abortChangeApi,
  getImpactApi, checkAllApi
} from '@/api/bi/metric-dict'

const tab = ref('define')
const loading = ref(false)
const keyword = ref('')
const metrics = ref([])
const publishedMetrics = computed(() => metrics.value.filter(m => m.status === 'PUBLISHED'))

// ---------------- 口径定义 ----------------
const editVisible = ref(false)
const editKey = ref('')
const form = ref({})
const historyVisible = ref(false)
const historyKey = ref('')
const history = ref([])

async function load() {
  loading.value = true
  try {
    const res = await getMetricsApi({ current: 1, size: 100, keyword: keyword.value || undefined })
    metrics.value = res.data?.records || []
  } finally {
    loading.value = false
  }
}
function openEdit(row) {
  editKey.value = row ? row.metricKey : ''
  form.value = row
    ? { metricKey: row.metricKey, name: row.name, definition: row.definition,
        formula: row.formula, formulaType: row.formulaType || 'RATIO', timeDim: row.timeDim,
        numerator: row.numerator || '', denominator: row.denominator || '',
        dimensions: row.dimensions || '', filterCond: row.filterCond || '',
        owner: row.owner || '', srcMapping: row.srcMapping, dataPrecision: row.dataPrecision }
    : { metricKey: '', name: '', definition: '', formula: '', formulaType: 'RATIO',
        timeDim: 'DAY', numerator: '', denominator: '', dimensions: '', filterCond: '',
        owner: '', srcMapping: '{"Table.column":"var"}', dataPrecision: 2 }
  editVisible.value = true
}
async function save() {
  await registerMetricApi({ ...form.value })
  ElMessage.success('已保存（草稿态，发布后生效）')
  editVisible.value = false
  load()
}
async function onPublish(row) {
  await ElMessageBox.confirm(`发布「${row.name}」？发布前执行 diff 二次校验（C-4.10-01）。`,
    '发布', { type: 'info' })
  await publishMetricApi(row.metricKey, row.formula)
  ElMessage.success('已发布并生效')
  load(); loadPublished()
}
async function openHistory(row) {
  const res = await getHistoryApi(row.metricKey)
  history.value = res.data || []
  historyKey.value = row.metricKey
  historyVisible.value = true
}

// ---------------- 口径变更 ----------------
const changeMetricKey = ref('')
const changes = ref([])
const consumers = ref([])
const changeVisible = ref(false)
const changeForm = ref({})
const switchVisible = ref(false)
const switchRowId = ref('')
const switchForm = ref({})
const impactVisible = ref(false)
const impactKey = ref('')
const impactVersion = ref(0)
const impact = ref({})

async function loadPublished() {
  const res = await getMetricsApi({ current: 1, size: 100 })
  metrics.value = res.data?.records || []
}
async function loadChangeView() {
  if (!changeMetricKey.value) return
  const [c, cs] = await Promise.all([
    getChangesApi(changeMetricKey.value),
    getConsumersApi(changeMetricKey.value)
  ])
  changes.value = c.data || []
  consumers.value = cs.data?.consumers || []
}
function openChange() {
  changeForm.value = { formula: '', reason: '', scope: '' }
  changeVisible.value = true
}
async function saveChange() {
  await requestChangeApi(changeMetricKey.value, { ...changeForm.value })
  ElMessage.success('已提交治理审批（旧版继续生效）')
  changeVisible.value = false
  loadChangeView()
}
function openSwitch(row) {
  switchRowId.value = row.id
  switchForm.value = { diffNote: '', diffPctNum: 0, reviewNote: '' }
  switchVisible.value = true
}
async function saveSwitch() {
  await switchConfirmApi(switchRowId.value, {
    diffNote: switchForm.value.diffNote,
    diffPct: String(switchForm.value.diffPctNum ?? 0),
    reviewNote: switchForm.value.reviewNote
  })
  ElMessage.success('已提交双审（数据治理 + 业务负责人）')
  switchVisible.value = false
  loadChangeView()
}
async function onAbort(row) {
  const { value } = await ElMessageBox.prompt('终止原因：', '终止灰度', {
    type: 'warning', inputPlaceholder: '业务方放弃调整等'
  })
  await abortChangeApi(row.id, value || '-')
  ElMessage.success('已终止，旧口径续用')
  loadChangeView()
}
async function openImpact(row) {
  const res = await getImpactApi(changeMetricKey.value, row.version)
  impact.value = res.data || {}
  impactKey.value = changeMetricKey.value
  impactVersion.value = row.version
  impactVisible.value = true
}
async function onConfirmConsumer(row) {
  await confirmConsumerApi(changeMetricKey.value, row.id)
  ElMessage.success('已确认更新，标记清除')
  loadChangeView()
}

// ---------------- 口径校验 ----------------
const checkLoading = ref(false)
const checkReport = ref([])
async function onCheckAll() {
  checkLoading.value = true
  try {
    const res = await checkAllApi()
    checkReport.value = res.data || []
    const diffs = checkReport.value.filter(r => !r.consistent).length
    ElMessage(diffs === 0
      ? { type: 'success', message: `校验完成：${checkReport.value.length} 项全部一致` }
      : { type: 'warning', message: `校验完成：${checkReport.value.length} 项中 ${diffs} 项差异` })
  } finally {
    checkLoading.value = false
  }
}

// ---------------- helpers ----------------
function ftLabel(t) {
  return { RATIO: '比率', PRODUCT: '连乘', ACCUM: '累加' }[t] || t
}
function stLabel(s) {
  return { DRAFT: '草稿', PUBLISHED: '已发布', CHANGE_PENDING: '审批中',
    GRAYING: '灰度', PENDING_UPDATE: '待更新', ABORTED: '已废弃' }[s] || s
}
function stTag(s) {
  return { DRAFT: 'info', PUBLISHED: 'success', CHANGE_PENDING: 'warning',
    GRAYING: 'warning', ABORTED: 'info' }[s] || 'info'
}
function chLabel(s) {
  return { CHANGE_PENDING: '审批中', GRAYING: '灰度中', ABORTED: '已终止', DRAFT: '已驳回' }[s] || s
}
function chTag(s) {
  return { CHANGE_PENDING: 'warning', GRAYING: 'primary', ABORTED: 'info', DRAFT: 'info' }[s] || 'info'
}
function asArray(v) {
  return Array.isArray(v) ? v : (v ? [v] : [])
}

onMounted(() => {
  load(); loadPublished()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap; }
h4 { margin: 10px 0 6px; }
.hint { color: #909399; font-size: 12px; }
.diff-line { font-size: 12px; color: #e6a23c; line-height: 1.5; }
</style>
