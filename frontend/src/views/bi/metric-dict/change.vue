<template>
  <div>
    <el-page-header content="指标口径（14.3）— 口径定义/口径变更/口径校验三叶；十二要素（6.3.1）、变更两段式治理（BR-4.10-04 / C-4.10-08）、血缘四层图谱（S-4.10-15）"
                    style="margin-bottom: 8px" />

    <!-- ==================== 14.3.2 口径变更 ==================== -->
    <PageInfo title="变更两段式治理：申请 → 数据治理单签 → 灰度（旧版仍生效）→ 切换确认（数据治理+业务负责人双审 C-4.10-08，超 0.5% 容差须治理复核备注）" style="margin-bottom: 8px" />
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
  </div>
</template>

<script setup>
/**
 * 14.3.2 口径变更（拆分自原口径字典操作台）：两段式变更申请 / 灰度切换双审 / 血缘影响报告。
 * 已发布指标下拉的数据见 useMetricDict.js（口径定义页登记/发布后同一份列表）。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getConsumersApi, confirmConsumerApi,
  requestChangeApi, getChangesApi, switchConfirmApi, abortChangeApi,
  getImpactApi
} from '@/api/bi/metric-dict'
import { useMetricDict } from './useMetricDict'

const { metrics, loadMetrics } = useMetricDict()

const publishedMetrics = computed(() => metrics.value.filter(m => m.status === 'PUBLISHED'))

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

// ---------------- helpers ----------------
function chLabel(s) {
  return { CHANGE_PENDING: '审批中', GRAYING: '灰度中', ABORTED: '已终止', DRAFT: '已驳回' }[s] || s
}
function chTag(s) {
  return { CHANGE_PENDING: 'warning', GRAYING: 'primary', ABORTED: 'info', DRAFT: 'info' }[s] || 'info'
}

onMounted(() => {
  if (!metrics.value.length) loadMetrics()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap; }
h4 { margin: 10px 0 6px; }
.hint { color: #909399; font-size: 12px; }
</style>
