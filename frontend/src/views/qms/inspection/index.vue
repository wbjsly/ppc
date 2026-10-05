<template>
  <div class="qms-inspection">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">{{ title }}</span>
          <div>
            <el-button size="small" @click="$router.push('/m/qms-approval')">审批待办</el-button>
            <el-button size="small" type="primary" :loading="loading" @click="reload">刷新</el-button>
          </div>
        </div>
      </template>

      <el-tabs :model-value="activeTab" @tab-change="onTabChange">
        <!-- 待检看板 -->
        <el-tab-pane label="待检看板" name="board">
          <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
                    title="检验前置于入库过账（B 口径）：批次未放行 → 过账被 C-4.12-06 阻断；时限自 GR 登记提交起算（偏差 D1）。"
                    description="无标准批次标红 BLOCKED（发布标准后一键激活）；免检批次直接 SKIPPED 可过账。" />
          <div style="margin-bottom: 10px; display: flex; gap: 8px;">
            <el-select v-model="statusFilter" placeholder="状态" clearable style="width: 150px;" @change="loadBoard">
              <el-option label="无标准阻断" value="BLOCKED" />
              <el-option label="待检" value="PENDING" />
              <el-option label="录入中" value="INPUTTING" />
              <el-option label="边界复核" value="REVIEWING" />
              <el-option label="不合格冻结" value="FROZEN" />
            </el-select>
            <el-select v-model="lotTypeFilter" placeholder="类型" clearable style="width: 120px;" @change="loadBoard">
              <el-option label="IQC 来料" value="IQC" />
              <el-option label="IPQC 过程" value="IPQC" />
              <el-option label="OQC 出货" value="OQC" />
            </el-select>
          </div>
          <el-table :data="boardRows" v-loading="loading" stripe>
            <el-table-column prop="lotNo" label="检验批号" width="170">
              <template #default="{ row }"><b>{{ row.lotNo }}</b></template>
            </el-table-column>
            <el-table-column prop="lotType" label="类型" width="70" />
            <el-table-column prop="itemCode" label="物料" min-width="160">
              <template #default="{ row }">{{ row.itemCode }} {{ row.itemName }}</template>
            </el-table-column>
            <el-table-column prop="supplierName" label="供应商" width="130" show-overflow-tooltip />
            <el-table-column prop="lotQty" label="批量" width="90" align="right" />
            <el-table-column prop="riskGrade" label="风险" width="60" align="center" />
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="lotStatusTag(row.status)">{{ lotStatusName(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="时限" width="150">
              <template #default="{ row }">
                <span :style="row.overdue ? 'color:#f56c6c;font-weight:bold' : ''">
                  {{ row.dueTime || '—' }}{{ row.overdue ? ' 超时' : '' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="抽样依据" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">{{ row.sampleBasis || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'BLOCKED' && canEdit" link type="warning"
                           @click="doActivate(row)">激活</el-button>
                <el-button v-if="canInspect && ['PENDING','INPUTTING'].includes(row.status)"
                           link type="primary" @click="openInput(row)">录入/判定</el-button>
                <el-button v-if="row.status === 'REVIEWING' && canReview" link type="warning"
                           @click="openReview(row)">复核</el-button>
                <el-button v-if="canInspect && row.result === 'PASS' && row.status === 'INPUTTING'"
                           link type="success" @click="doRelease(row)">放行</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && !boardRows.length" description="暂无在途检验批" :image-size="80" />
        </el-tab-pane>

        <!-- 录入与判定 -->
        <el-tab-pane label="录入与判定" name="input">
          <div style="display: flex; gap: 10px; margin-bottom: 10px; align-items: center;">
            <span>选择检验批：</span>
            <el-select v-model="currentLotId" filterable placeholder="选择批次" style="width: 340px;"
                       @change="loadDetail">
              <el-option v-for="r in selectableLots" :key="r.id"
                         :label="`${r.lotNo} ${r.itemCode} (${lotStatusName(r.status)})`" :value="r.id" />
            </el-select>
            <el-tag v-if="detail.lot">{{ lotStatusName(detail.lot.status) }} / {{ detail.lot.result }}</el-tag>
          </div>

          <template v-if="detail.lot">
            <el-descriptions :column="4" border size="small" style="margin-bottom: 12px;">
              <el-descriptions-item label="标准">{{ detail.lot.standardCode || '—' }}
                V{{ detail.lot.standardVersion || '—' }}</el-descriptions-item>
              <el-descriptions-item label="样本量">{{ detail.lot.sampleQty }}（Ac={{ detail.lot.acValue }}
                / Re={{ detail.lot.reValue }}）</el-descriptions-item>
              <el-descriptions-item label="严格度">{{ detail.lot.strictness }}</el-descriptions-item>
              <el-descriptions-item label="时限">{{ detail.lot.dueTime }}</el-descriptions-item>
              <el-descriptions-item label="抽样依据" :span="4">{{ detail.lot.sampleBasis }}</el-descriptions-item>
            </el-descriptions>

            <el-table :data="detail.items" size="small" border>
              <el-table-column prop="sequenceNo" label="#" width="50" />
              <el-table-column prop="characteristicName" label="检验项" min-width="150" />
              <el-table-column label="CTQ" width="70" align="center">
                <template #default="{ row }">
                  <el-tag v-if="row.ctqFlag === '1'" size="small" type="danger">CTQ</el-tag>
                  <span v-else>—</span>
                </template>
              </el-table-column>
              <el-table-column label="规格" width="150">
                <template #default="{ row }">
                  {{ row.lowerLimit ?? '—' }} ~ {{ row.upperLimit ?? '—' }} {{ row.unit || '' }}
                </template>
              </el-table-column>
              <el-table-column label="实测值" width="140">
                <template #default="{ row }">
                  <el-input v-model="row.measuredValue" size="small" :disabled="!editable"
                            :placeholder="row.specType === 'LOOK' ? '外观选判定' : '数值'" />
                </template>
              </el-table-column>
              <el-table-column label="外观判定" width="120">
                <template #default="{ row }">
                  <el-select v-model="row.judge" size="small" :disabled="row.specType !== 'LOOK' || !editable"
                             clearable placeholder="—">
                    <el-option label="合格 PASS" value="PASS" />
                    <el-option label="不合格 FAIL" value="FAIL" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="器具编码" width="140">
                <template #default="{ row }">
                  <el-input v-model="row.instrumentCode" size="small" :disabled="!editable" placeholder="可空" />
                </template>
              </el-table-column>
              <el-table-column label="结果" width="90" align="center">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.judge === 'PASS' ? 'success'
                    : (row.judge === 'FAIL' ? 'danger' : 'info')">{{ row.judge || '待录' }}</el-tag>
                </template>
              </el-table-column>
            </el-table>

            <div style="margin-top: 12px; display: flex; gap: 8px;">
              <el-button v-if="canInspect && editable" type="primary" :loading="busy"
                         @click="saveInput">保存录入</el-button>
              <el-button v-if="canInspect" type="warning" :loading="busy"
                         @click="doJudge">自动判定</el-button>
              <el-button v-if="canInspect && detail.lot.result === 'PASS' && detail.lot.status === 'INPUTTING'"
                         type="success" :loading="busy" @click="doRelease(detail.lot)">确认合格放行</el-button>
              <span v-if="abnormal.length" style="color: #e6a23c; line-height: 32px;">
                ⚠ 数据异常待二次确认：{{ abnormal.join('、') }}（再次保存即确认保留原值）
              </span>
            </div>
          </template>
          <el-empty v-else description="请选择检验批" :image-size="80" />
        </el-tab-pane>

        <!-- 巡检计划（IPQC） -->
        <el-tab-pane label="巡检计划" name="plan">
          <el-alert type="warning" :closable="false" style="margin-bottom: 10px;"
                    title="偏差 D5：工单首件/工序报工触发为 5.x 工单域桩，本期以「手工创建 + 巡检计划定时触发」落地；OQC 发货拦截同样挂桩。" />
          <div style="margin-bottom: 10px; display: flex; gap: 8px;">
            <el-button v-if="canEdit" type="success" @click="openPlan()">新建巡检计划</el-button>
            <el-button v-if="canEdit" @click="manualVisible = true">手工创建 IPQC/OQC 批</el-button>
          </div>
          <el-table :data="plans" stripe>
            <el-table-column prop="planCode" label="计划编码" width="160" />
            <el-table-column prop="name" label="名称" min-width="150" />
            <el-table-column prop="itemCode" label="物料" width="150" />
            <el-table-column prop="intervalMinutes" label="间隔(分)" width="90" align="center" />
            <el-table-column prop="nextRunTime" label="下次触发" width="170" />
            <el-table-column prop="lastLotNo" label="最近生成批" width="170" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                  {{ row.status === 'ACTIVE' ? '运行中' : '已暂停' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button v-if="canEdit" link type="primary" @click="doTogglePlan(row)">
                  {{ row.status === 'ACTIVE' ? '暂停' : '启用' }}</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 免检与严格度 -->
        <el-tab-pane label="免检与严格度" name="exempt">
          <div style="margin-bottom: 10px;">
            <el-button v-if="canEdit" type="success" @click="exemptVisible = true">提交免检申请</el-button>
          </div>
          <el-divider content-position="left">免检（BR-4.12-12，质量经理审批，不合格自动取消）</el-divider>
          <el-table :data="exempts" size="small" stripe>
            <el-table-column prop="materialCode" label="物料" width="160" />
            <el-table-column prop="materialName" label="名称" min-width="150" />
            <el-table-column prop="supplierName" label="供应商" width="140" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success'
                  : (row.status === 'PENDING' ? 'warning' : 'info')">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="disableReason" label="停用原因" min-width="180" show-overflow-tooltip />
          </el-table>
          <el-divider content-position="left">严格度（连 2 批不合格转加严 / 加严下连 3 批合格解除）</el-divider>
          <el-table :data="strictnessRows" size="small" stripe>
            <el-table-column prop="materialCode" label="物料" width="160" />
            <el-table-column prop="supplierId" label="供应商" width="150" />
            <el-table-column label="严格度" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="row.strictness === 'TIGHTENED' ? 'danger' : 'success'">
                  {{ row.strictness }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="triggerType" label="触发" width="100" />
            <el-table-column prop="consecPass" label="连续合格" width="100" align="center" />
            <el-table-column prop="status" label="状态" width="100" />
            <el-table-column prop="releaseBasis" label="解除依据" min-width="200" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 巡检计划编辑 -->
    <el-dialog v-model="planVisible" title="巡检计划" width="520px">
      <el-form label-width="110px">
        <el-form-item label="计划名称" required><el-input v-model="planForm.name" /></el-form-item>
        <el-form-item label="物料编码" required><el-input v-model="planForm.itemCode" /></el-form-item>
        <el-form-item label="物料名称"><el-input v-model="planForm.itemName" /></el-form-item>
        <el-form-item label="工序"><el-input v-model="planForm.processName" /></el-form-item>
        <el-form-item label="间隔（分钟）"><el-input-number v-model="planForm.intervalMinutes" :min="1" /></el-form-item>
        <el-form-item label="批量"><el-input-number v-model="planForm.lotQty" :min="1" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="savePlan">保存</el-button>
      </template>
    </el-dialog>

    <!-- 手工建批 -->
    <el-dialog v-model="manualVisible" title="手工创建 IPQC / OQC 检验批" width="520px">
      <el-form label-width="100px">
        <el-form-item label="类型" required>
          <el-radio-group v-model="manualForm.lotType">
            <el-radio label="IPQC">IPQC 过程</el-radio>
            <el-radio label="OQC">OQC 出货</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="物料编码" required><el-input v-model="manualForm.itemCode" /></el-form-item>
        <el-form-item label="物料名称"><el-input v-model="manualForm.itemName" /></el-form-item>
        <el-form-item label="数量" required><el-input-number v-model="manualForm.qty" :min="1" /></el-form-item>
        <el-form-item label="批次号"><el-input v-model="manualForm.batchNo" /></el-form-item>
        <el-form-item label="工序"><el-input v-model="manualForm.processId" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveManual">创建</el-button>
      </template>
    </el-dialog>

    <!-- 免检申请 -->
    <el-dialog v-model="exemptVisible" title="免检申请（质量经理审批）" width="500px">
      <el-form label-width="100px">
        <el-form-item label="物料编码" required><el-input v-model="exemptForm.materialCode" /></el-form-item>
        <el-form-item label="物料名称"><el-input v-model="exemptForm.materialName" /></el-form-item>
        <el-form-item label="供应商 ID"><el-input v-model="exemptForm.supplierId" placeholder="空=不限供方" /></el-form-item>
        <el-form-item label="供应商名称"><el-input v-model="exemptForm.supplierName" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exemptVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveExempt">提交申请</el-button>
      </template>
    </el-dialog>

    <!-- 边界复核 -->
    <el-dialog v-model="reviewVisible" :title="`边界复核：${reviewRow.lotNo || ''}`" width="460px">
      <el-alert type="warning" :closable="false" style="margin-bottom: 10px;"
                title="不合格数恰好 = Ac（BR-4.12-17）：判定合格但放行前须质量工程师复核（L2）。" />
      <el-input v-model="reviewOpinion" type="textarea" :rows="3" placeholder="复核意见（退回时必填）" />
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="danger" :loading="busy" @click="submitReview(false)">退回重检</el-button>
        <el-button type="success" :loading="busy" @click="submitReview(true)">复核通过</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getLotBoardApi, getLotDetailApi, activateLotApi, inputLotApi, judgeLotApi,
  reviewLotApi, releaseLotApi, createManualLotApi, getStrictnessApi,
  getPatrolPlansApi, createPatrolPlanApi, togglePatrolPlanApi,
  getExemptsApi, applyExemptApi
} from '@/api/qms/inspection'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const roles = computed(() => userStore.userInfo?.roles || [])
const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))
const canInspect = computed(() => isAdmin.value || roles.value.includes('ROLE_INSPECTOR')
  || roles.value.includes('ROLE_QUALITY_ENG'))
const canReview = computed(() => isAdmin.value || roles.value.includes('ROLE_QUALITY_ENG'))
const canEdit = computed(() => isAdmin.value || roles.value.includes('ROLE_QUALITY_ENG'))

const TAB_MAP = {
  '/m/2.5.1': 'board', '/m/6.2.1': 'board', '/m/6.2.2': 'board', '/m/6.2.3': 'board',
  '/m/6.3.1': 'input', '/m/6.3.2': 'input', '/m/6.4.1': 'board'
}
const activeTab = ref(route.meta.tab || TAB_MAP[route.path] || 'board')
const title = computed(() => route.meta.title || '来料检验')
function onTabChange(name) {
  activeTab.value = name
}
watch(() => route.path, p => {
  if (TAB_MAP[p]) activeTab.value = TAB_MAP[p]
  if (route.meta.lotType) lotTypeFilter.value = route.meta.lotType
})

const loading = ref(false)
const busy = ref(false)
const boardRows = ref([])
const statusFilter = ref('')
const lotTypeFilter = ref(route.meta.lotType || '')
const currentLotId = ref('')
const detail = reactive({ lot: null, items: [] })
const abnormal = ref([])
const plans = ref([])
const exempts = ref([])
const strictnessRows = ref([])

const planVisible = ref(false)
const planForm = reactive({ name: '', itemCode: '', itemName: '', processName: '', intervalMinutes: 120, lotQty: 1 })
const manualVisible = ref(false)
const manualForm = reactive({ lotType: 'IPQC', itemCode: '', itemName: '', qty: 1, batchNo: '', processId: '' })
const exemptVisible = ref(false)
const exemptForm = reactive({ materialCode: '', materialName: '', supplierId: '', supplierName: '' })
const reviewVisible = ref(false)
const reviewRow = ref({})
const reviewOpinion = ref('')

const editable = computed(() => {
  const s = detail.lot?.status
  return canInspect.value && ['PENDING', 'INPUTTING'].includes(s)
})
const selectableLots = computed(() =>
  boardRows.value.filter(r => ['PENDING', 'INPUTTING', 'REVIEWING'].includes(r.status)))

function lotStatusName(s) {
  return { BLOCKED: '无标准阻断', PENDING: '待检', INPUTTING: '录入中/待确认',
           REVIEWING: '边界复核', RELEASED: '已放行', FROZEN: '不合格冻结',
           SKIPPED: '免检', CONCESSION: '让步接收', CANCELLED: '已作废' }[s] || s
}
function lotStatusTag(s) {
  return { BLOCKED: 'danger', PENDING: 'primary', INPUTTING: 'warning',
           REVIEWING: 'warning', RELEASED: 'success', FROZEN: 'danger',
           SKIPPED: 'info' }[s] || 'info'
}

async function loadBoard() {
  loading.value = true
  try {
    const params = { current: 1, size: 100 }
    if (statusFilter.value) params.status = statusFilter.value
    const res = await getLotBoardApi(params)
    let rows = res.data?.records || []
    if (lotTypeFilter.value) rows = rows.filter(r => r.lotType === lotTypeFilter.value)
    boardRows.value = rows
  } catch (e) { /* 拦截器提示 */ } finally { loading.value = false }
}

async function loadDetail() {
  if (!currentLotId.value) return
  try {
    const res = await getLotDetailApi(currentLotId.value)
    detail.lot = res.data?.lot || null
    detail.items = (res.data?.items || []).map(i => ({
      ...i,
      measuredValue: i.measuredValue ?? '',
      instrumentCode: i.instrumentCode || ''
    }))
    abnormal.value = []
  } catch (e) { /* ignored */ }
}

async function openInput(row) {
  currentLotId.value = row.id
  activeTab.value = 'input'
  await loadDetail()
}

async function saveInput() {
  if (!detail.lot) return
  busy.value = true
  try {
    const items = detail.items.map(i => ({
      itemId: i.id,
      measuredValue: i.measuredValue === '' || i.measuredValue == null ? null : Number(i.measuredValue),
      judge: i.judge || undefined,
      instrumentCode: i.instrumentCode || undefined,
      remark: i.remark || undefined
    }))
    const res = await inputLotApi(detail.lot.id, items, abnormal.value.length > 0)
    abnormal.value = res.data?.abnormal || []
    ElMessage.success(abnormal.value.length ? '已录入（数据异常待确认）' : '录入成功')
    await loadDetail()
    await loadBoard()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}

async function doJudge() {
  if (!detail.lot) return
  busy.value = true
  try {
    const res = await judgeLotApi(detail.lot.id)
    const v = res.data?.verdict
    if (v === 'FAIL') ElMessage.error('判定不合格：批次已冻结并生成处置待办')
    else if (v === 'BOUNDARY') ElMessage.warning('边界判定（=Ac）：已转质量工程师复核')
    else ElMessage.success('判定合格，待质检员确认放行')
    await loadDetail()
    await loadBoard()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}

async function doRelease(lot) {
  busy.value = true
  try {
    await ElMessageBox.confirm(`确认放行批次 ${lot.lotNo || detail.lot?.lotNo}？放行不可撤回。`, '合格放行', { type: 'warning' })
    await releaseLotApi(lot.id || detail.lot.id)
    ElMessage.success('已放行，可执行入库过账')
    await loadDetail()
    await loadBoard()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}

function openReview(row) {
  reviewRow.value = row
  reviewOpinion.value = ''
  reviewVisible.value = true
  currentLotId.value = row.id
  loadDetail()
}

async function submitReview(pass) {
  if (!pass && (reviewOpinion.value || '').trim().length < 2) {
    ElMessage.warning('退回须填写意见（≥2 字）')
    return
  }
  busy.value = true
  try {
    await reviewLotApi(reviewRow.value.id, pass, reviewOpinion.value)
    ElMessage.success(pass ? '复核通过，待确认放行' : '已退回重检')
    reviewVisible.value = false
    await loadDetail()
    await loadBoard()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}

async function doActivate(row) {
  busy.value = true
  try {
    await activateLotApi(row.id)
    ElMessage.success('已加载有效标准并转待检')
    await loadBoard()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}

async function loadPlans() {
  try { plans.value = (await getPatrolPlansApi()).data || [] } catch (e) { /* ignored */ }
}
function openPlan() {
  Object.assign(planForm, { name: '', itemCode: '', itemName: '', processName: '', intervalMinutes: 120, lotQty: 1 })
  planVisible.value = true
}
async function savePlan() {
  if (!planForm.name || !planForm.itemCode) { ElMessage.warning('名称与物料必填'); return }
  busy.value = true
  try {
    await createPatrolPlanApi({ ...planForm })
    ElMessage.success('计划已创建（到点自动生成 IPQC 批）')
    planVisible.value = false
    await loadPlans()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}
async function doTogglePlan(row) {
  try { await togglePatrolPlanApi(row.id); await loadPlans() } catch (e) { /* ignored */ }
}

async function saveManual() {
  if (!manualForm.itemCode || !manualForm.qty) { ElMessage.warning('物料与数量必填'); return }
  busy.value = true
  try {
    const res = await createManualLotApi({ ...manualForm })
    ElMessage.success(`已创建 ${res.data?.lotNo}`)
    manualVisible.value = false
    await loadBoard()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}

async function loadExempts() {
  try { exempts.value = (await getExemptsApi({})).data || [] } catch (e) { /* ignored */ }
  try { strictnessRows.value = (await getStrictnessApi({})).data || [] } catch (e) { /* ignored */ }
}
async function saveExempt() {
  if (!exemptForm.materialCode) { ElMessage.warning('物料编码必填'); return }
  busy.value = true
  try {
    await applyExemptApi({ ...exemptForm })
    ElMessage.success('已提交，待质量经理审批')
    exemptVisible.value = false
    await loadExempts()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}

function reload() { loadBoard(); loadPlans(); loadExempts(); if (currentLotId.value) loadDetail() }

onMounted(() => {
  if (route.meta.lotType) lotTypeFilter.value = route.meta.lotType
  reload()
})
</script>

<style scoped>
.qms-inspection { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>
