<template>
  <div>
    <el-page-header content="指标口径（14.3）— 口径定义/口径变更/口径校验三叶；十二要素（6.3.1）、变更两段式治理（BR-4.10-04 / C-4.10-08）、血缘四层图谱（S-4.10-15）"
                    style="margin-bottom: 8px" />

    <!-- ==================== 14.3.1 口径定义 ==================== -->
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
  </div>
</template>

<script setup>
/**
 * 14.3.1 口径定义（拆分自原口径字典操作台）：十二要素登记 / 发布 / 版本历史。
 * 指标列表见 useMetricDict.js（口径变更页「已发布指标」下拉共用同一份数据）。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  registerMetricApi, publishMetricApi, getHistoryApi
} from '@/api/bi/metric-dict'
import { useMetricDict } from './useMetricDict'

const { metrics, loading, loadMetrics } = useMetricDict()

const keyword = ref('')

// ---------------- 口径定义 ----------------
const editVisible = ref(false)
const editKey = ref('')
const form = ref({})
const historyVisible = ref(false)
const historyKey = ref('')
const history = ref([])

function load() {
  return loadMetrics(keyword.value)
}
function loadPublished() {
  return loadMetrics()
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

onMounted(() => {
  load(); loadPublished()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap; }
</style>
