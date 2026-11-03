<template>
  <div>
    <el-page-header content="数据抽取（14.2）— 抽取配置/脱敏规则/质量校验三叶；波动暂停（BR-4.10-12）、断点续抽（BR-4.10-13）、质量分级阻断（BR-4.10-14）、schema 变更检测（BR-4.10-15）"
                    style="margin-bottom: 8px" />

    <!-- ==================== 14.2.3 质量校验 ==================== -->
    <PageInfo title="NOT_NULL 非空率（默认≥99.9%）/ RECONCILE 跨表勾稽+容差 / RANGE 数值范围；SEVERE 失败阻断批次就绪并告警，WARN 告警放行（BR-4.10-14）" style="margin-bottom: 8px" />
    <div class="toolbar">
      <el-button size="small" type="success" @click="openRuleEdit(null)">新建规则</el-button>
      <el-button size="small" @click="loadQualityRules">刷新</el-button>
    </div>
    <el-table :data="qRules" border stripe size="small" max-height="260">
      <el-table-column prop="name" label="规则名" min-width="150" show-overflow-tooltip />
      <el-table-column label="类型" width="100" align="center">
        <template #default="{ row }">{{ qType(row.ruleType) }}</template>
      </el-table-column>
      <el-table-column label="目标" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.ruleType === 'RECONCILE' ? `${row.leftSql} vs ${row.rightSql}`
            : `${row.targetTable}.${row.targetColumn}` }}
        </template>
      </el-table-column>
      <el-table-column label="阈值/容差" width="120" align="center">
        <template #default="{ row }">
          {{ row.ruleType === 'NOT_NULL' ? `≥${row.notNullMin}%`
            : row.ruleType === 'RECONCILE' ? `±${row.tolerance}`
            : `${row.minVal ?? '-∞'} ~ ${row.maxVal ?? '+∞'}` }}
        </template>
      </el-table-column>
      <el-table-column label="级别" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="row.severeLevel === 'SEVERE' ? 'danger' : 'warning'" size="small">
            {{ row.severeLevel }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
            {{ row.status === 'ENABLED' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="onRunNow(row)">立即校验</el-button>
          <el-button size="small" type="primary" link @click="openRuleEdit(row)">编辑</el-button>
          <el-button size="small" type="primary" link @click="toggleRule(row)">
            {{ row.status === 'ENABLED' ? '停用' : '启用' }}</el-button>
          <el-button size="small" type="danger" link @click="removeRule(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h4 style="margin-top: 14px">校验报告</h4>
    <div class="toolbar">
      <el-input v-model="resBatchNo" placeholder="批次号" size="small" clearable
                style="width: 160px" @keyup.enter="loadResults" />
      <el-button size="small" type="primary" @click="loadResults">查询</el-button>
      <el-button v-if="lastBlockedBatch" size="small" type="warning" @click="onRecheck">
        复验阻断批次 {{ lastBlockedBatch }}（通过自动就绪）
      </el-button>
    </div>
    <el-table :data="results" border stripe size="small" max-height="300">
      <el-table-column prop="batchNo" label="批次" width="110">
        <template #default="{ row }">{{ row.batchNo || '（手动）' }}</template>
      </el-table-column>
      <el-table-column prop="ruleName" label="规则" min-width="140" show-overflow-tooltip />
      <el-table-column label="类型" width="90" align="center">
        <template #default="{ row }">{{ qType(row.ruleType) }}</template>
      </el-table-column>
      <el-table-column prop="expected" label="期望" width="150" show-overflow-tooltip />
      <el-table-column prop="actual" label="实际" min-width="160" show-overflow-tooltip />
      <el-table-column label="结论" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="row.conclusion === 'PASS' ? 'success' : 'danger'" size="small">
            {{ row.conclusion }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="severeLevel" label="级别" width="80" align="center" />
      <el-table-column prop="execAt" label="执行时间" width="150" />
    </el-table>

    <el-dialog v-model="ruleVisible" :title="ruleId ? '编辑质量规则' : '新建质量规则'" width="620px"
               :close-on-click-modal="false">
      <el-form label-width="100px" size="small">
        <el-form-item label="规则名" required>
          <el-input v-model="ruleForm.name" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="ruleForm.ruleType" :disabled="!!ruleId" style="width: 200px">
            <el-option label="NOT_NULL 非空率" value="NOT_NULL" />
            <el-option label="RECONCILE 跨表勾稽" value="RECONCILE" />
            <el-option label="RANGE 数值范围" value="RANGE" />
          </el-select>
        </el-form-item>
        <template v-if="ruleForm.ruleType !== 'RECONCILE'">
          <el-form-item label="目标表" required>
            <el-input v-model="ruleForm.targetTable" placeholder="仅字母数字下划线" />
          </el-form-item>
          <el-form-item label="目标字段" required>
            <el-input v-model="ruleForm.targetColumn" />
          </el-form-item>
        </template>
        <el-form-item v-if="ruleForm.ruleType === 'NOT_NULL'" label="非空率阈值">
          <el-input-number v-model="ruleForm.notNullMin" :min="0.001" :max="100"
                           :step="0.1" :precision="3" size="small" />
          <span class="mask-preview">（默认 99.9%）</span>
        </el-form-item>
        <template v-if="ruleForm.ruleType === 'RECONCILE'">
          <el-form-item label="左侧 SQL" required>
            <el-input v-model="ruleForm.leftSql" placeholder="SELECT 合计（只读白名单）" />
          </el-form-item>
          <el-form-item label="右侧 SQL" required>
            <el-input v-model="ruleForm.rightSql" />
          </el-form-item>
          <el-form-item label="容差">
            <el-input-number v-model="ruleForm.tolerance" :min="0" :precision="4"
                             size="small" />
          </el-form-item>
        </template>
        <template v-if="ruleForm.ruleType === 'RANGE'">
          <el-form-item label="下限">
            <el-input-number v-model="ruleForm.minVal" :precision="4" size="small" />
          </el-form-item>
          <el-form-item label="上限">
            <el-input-number v-model="ruleForm.maxVal" :precision="4" size="small" />
          </el-form-item>
        </template>
        <el-form-item label="严重级别">
          <el-radio-group v-model="ruleForm.severeLevel">
            <el-radio label="SEVERE">SEVERE（阻断就绪）</el-radio>
            <el-radio label="WARN">WARN（告警放行）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="所属数据源">
          <el-select v-model="ruleForm.datasourceId" clearable style="width: 220px"
                     placeholder="批次自动校验归集范围">
            <el-option v-for="d in datasources" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="ruleVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 14.2.3 质量校验（拆分自原数据抽取操作台）：质量规则 / 立即校验 / 校验报告与阻断批次复验。
 * 数据源列表见 useDataExtract.js（规则表单「所属数据源」下拉）。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getQualityRulesApi, createQualityRuleApi, updateQualityRuleApi, deleteQualityRuleApi,
  runQualityNowApi, recheckBatchApi, getQualityResultsApi
} from '@/api/bi/data-extract'
import { useDataExtract } from './useDataExtract'

const { datasources, loadDs } = useDataExtract()

const qRules = ref([])
const results = ref([])
const resBatchNo = ref('')
const lastBlockedBatch = ref('')
const ruleVisible = ref(false)
const ruleId = ref('')
const ruleForm = ref({})

async function loadQualityRules() {
  const res = await getQualityRulesApi()
  qRules.value = res.data || []
}
async function loadResults() {
  const res = await getQualityResultsApi({ batchNo: resBatchNo.value || undefined })
  results.value = res.data || []
}
function openRuleEdit(row) {
  ruleId.value = row ? row.id : ''
  ruleForm.value = row
    ? { name: row.name, ruleType: row.ruleType, targetTable: row.targetTable || '',
        targetColumn: row.targetColumn || '', leftSql: row.leftSql || '', rightSql: row.rightSql || '',
        notNullMin: Number(row.notNullMin ?? 99.9), tolerance: Number(row.tolerance ?? 0),
        minVal: row.minVal === null || row.minVal === undefined ? undefined : Number(row.minVal),
        maxVal: row.maxVal === null || row.maxVal === undefined ? undefined : Number(row.maxVal),
        severeLevel: row.severeLevel, datasourceId: row.datasourceId || '' }
    : { name: '', ruleType: 'NOT_NULL', targetTable: '', targetColumn: '', leftSql: '',
        rightSql: '', notNullMin: 99.9, tolerance: 0, severeLevel: 'SEVERE', datasourceId: '' }
  ruleVisible.value = true
}
async function saveRule() {
  const payload = { ...ruleForm.value }
  for (const k of ['minVal', 'maxVal']) {
    if (payload[k] === undefined || payload[k] === '') delete payload[k]
  }
  if (ruleId.value) {
    await updateQualityRuleApi(ruleId.value, payload)
  } else {
    await createQualityRuleApi(payload)
  }
  ElMessage.success('已保存')
  ruleVisible.value = false
  loadQualityRules()
}
async function toggleRule(row) {
  await updateQualityRuleApi(row.id, { status: row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED' })
  loadQualityRules()
}
async function removeRule(row) {
  await ElMessageBox.confirm(`删除规则「${row.name}」？有报告时将拒绝删除。`, '提示', { type: 'warning' })
  await deleteQualityRuleApi(row.id)
  ElMessage.success('已删除')
  loadQualityRules()
}
async function onRunNow(row) {
  const res = await runQualityNowApi(row.id)
  const d = res.data
  ElMessage({ type: d.conclusion === 'PASS' ? 'success' : 'error',
    message: `${d.conclusion}：期望 ${d.expected} / 实际 ${d.actual}` })
  if (d.conclusion === 'FAIL' && d.severeLevel === 'SEVERE') {
    ElMessage.warning('SEVERE 级失败：如属某批次，批次将保持质量阻断，修复后请复验')
  }
  loadResults()
}
async function onRecheck() {
  const res = await recheckBatchApi(lastBlockedBatch.value)
  if (res.data.status === 'READY') {
    ElMessage.success('复验通过，批次已解除阻断置就绪')
  } else {
    ElMessage.warning(`仍存在 SEVERE 失败 ${res.data.severeFail} 条：${res.data.status}`)
  }
  loadResults()
}
function qType(t) {
  return { NOT_NULL: '非空率', RECONCILE: '跨表勾稽', RANGE: '数值范围' }[t] || t
}

onMounted(() => {
  loadDs(); loadQualityRules(); loadResults()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap; }
h4 { margin: 4px 0 8px; }
.mask-preview { color: #909399; font-size: 12px; margin-left: 8px; }
</style>
