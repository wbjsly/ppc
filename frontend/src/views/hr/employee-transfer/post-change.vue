<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>调岗管理（10.7.3）—— 双确认 / 双人复核（C-4.7-03）/ 生效联动</span>
          <el-button type="primary" size="small" @click="openSubmit">发起调岗</el-button>
        </div>
      </template>
      <el-alert :closable="false" type="info" show-icon class="mb8"
        title="流程：发起 → 调出确认 → 调入确认 → HR 审核 → 双人复核（HR 经理+另一 HR 专员独立确认五要素，系统比对一致才流转）→ 审批 → 生效（主档+账号角色联动）。薪酬超职级带宽 → 自动追加 HR 经理 L2 复核节点；HR 审核环节可勾选变更社保缴纳地（生效时更新主档并开社保转移单）" />

      <el-table :data="rows" size="small" border>
        <el-table-column prop="trfNo" label="单号" width="120" />
        <el-table-column prop="empName" label="员工" width="90" />
        <el-table-column label="调岗" min-width="170">
          <template #default="{ row }">{{ posName(row.fromPosId) }} → {{ posName(row.toPosId) }}</template>
        </el-table-column>
        <el-table-column label="带宽" width="90">
          <template #default="{ row }">
            <el-tooltip v-if="row.bandExceeded !== null && row.bandExceeded !== undefined" :content="row.bandNote" placement="top">
              <el-tag size="small" :type="row.bandExceeded === '1' ? 'danger' : 'warning'">
                {{ row.bandExceeded === '1' ? '超上限' : '低于下限' }}
              </el-tag>
            </el-tooltip>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="缴纳地变更" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.placeChange === '1'" size="small" type="warning">{{ row.newPlace }}</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="effectiveDate" label="生效日" width="100" />
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">{{ statusName(row.status) }}</template>
        </el-table-column>
        <el-table-column label="复核" width="150">
          <template #default="{ row }">
            <template v-if="row.review1By && row.review2By">
              <el-tag size="small" :type="row.status === 'REVIEW_DIFF' ? 'danger' : 'success'">
                {{ row.status === 'REVIEW_DIFF' ? '差异挂起' : '一致' }}
              </el-tag>
            </template>
            <span v-else-if="row.review1By" class="muted">第一复核已签，待第二复核</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="300">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT'" link type="primary" size="small" @click="confirmOut(row)">调出确认</el-button>
            <el-button v-if="row.status === 'DEPT_OUT_CONFIRMED'" link type="primary" size="small" @click="confirmIn(row)">调入确认</el-button>
            <el-button v-if="row.status === 'DEPT_IN_CONFIRMED'" link type="primary" size="small" @click="hrReview(row)">HR 审核</el-button>
            <el-button v-if="row.status === 'REVIEWING'" link type="primary" size="small" @click="openReview(row)">复核签署</el-button>
            <el-button v-if="row.status === 'REVIEW_DIFF'" link type="danger" size="small" @click="adjudicate(row)">差异裁决</el-button>
            <el-button v-if="editableStates.includes(row.status)" link type="warning" size="small" @click="openPlace(row)">缴纳地</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 发起调岗 -->
    <el-dialog v-model="submitVisible" title="发起调岗" width="520px">
      <el-form label-width="100px">
        <el-form-item label="员工">
          <el-select v-model="form.empId" filterable style="width:100%">
            <el-option v-for="e in emps" :key="e.id" :label="`${e.empNo} ${e.name}`" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="调入岗位">
          <el-select v-model="form.toPosId" style="width:100%">
            <el-option v-for="p in positions" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="拟生效日"><el-date-picker v-model="form.effectiveDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="薪酬建议">
          <el-input v-model="form.salaryAdvice" />
          <div class="muted">超调入岗位职级带宽（SAL_MIN~SAL_MAX）将标记并自动追加 HR 经理 L2 复核节点</div>
        </el-form-item>
        <el-form-item label="原因"><el-input v-model="form.reason" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitVisible = false">取消</el-button>
        <el-button type="primary" @click="doSubmit">提交</el-button>
      </template>
    </el-dialog>

    <!-- 双人复核签署 -->
    <el-dialog v-model="reviewVisible" :title="`双人复核签署 - ${reviewRow?.trfNo || ''}`" width="520px">
      <el-alert :closable="false" type="warning" show-icon class="mb8"
        title="逐项独立确认五要素——两人结果全一致才流转；任一不一致挂起升级裁决" />
      <el-form label-width="90px">
        <el-form-item label="岗位"><el-input v-model="reviewForm.position" :placeholder="posName(reviewRow?.toPosId)" /></el-form-item>
        <el-form-item label="薪酬"><el-input v-model="reviewForm.salary" /></el-form-item>
        <el-form-item label="汇报线"><el-input v-model="reviewForm.reportLine" /></el-form-item>
        <el-form-item label="成本中心"><el-input v-model="reviewForm.costCenter" /></el-form-item>
        <el-form-item label="生效日期"><el-input v-model="reviewForm.effectiveDate" :placeholder="reviewRow?.effectiveDate" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" @click="doReview">签署</el-button>
      </template>
    </el-dialog>
    <!-- 缴纳地变更（FR-4.7-4-8，HR 审核环节） -->
    <el-dialog v-model="placeVisible" :title="`社保缴纳地变更 - ${placeRow?.trfNo || ''}`" width="420px">
      <el-alert :closable="false" type="warning" show-icon class="mb8"
        title="勾选后：调岗生效时更新主档社保缴纳地 + 自动开立社保转移单 + 转移提醒；生效前可随时取消" />
      <el-form label-width="100px">
        <el-form-item label="变更缴纳地">
          <el-switch v-model="placeForm.placeChange" active-value="1" inactive-value="0" />
        </el-form-item>
        <el-form-item v-if="placeForm.placeChange === '1'" label="新缴纳地">
          <el-select v-model="placeForm.newPlace" filterable placeholder="选择城市（须已维护社保政策）" style="width:100%">
            <el-option v-for="c in placeCities" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="placeVisible = false">取消</el-button>
        <el-button type="primary" @click="doPlace">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { trfList, postSubmit, postConfirmOut, postConfirmIn, postHrReview,
  postReviewSign, postAdjudicate, empList, posList, postPlaceChange } from '@/api/hr/employee-archive'
import { policyList } from '@/api/hr/payroll'

const rows = ref([])
const emps = ref([])
const positions = ref([])
const submitVisible = ref(false)
const reviewVisible = ref(false)
const reviewRow = ref(null)
const form = ref({})
const reviewForm = ref({})
// 缴纳地变更（FR-4.7-4-8）
const placeVisible = ref(false)
const placeRow = ref(null)
const placeForm = ref({ placeChange: '0', newPlace: '' })
const placeCities = ref([])
const editableStates = ['DRAFT', 'DEPT_OUT_CONFIRMED', 'DEPT_IN_CONFIRMED', 'REVIEWING']

const STATUS_NAMES = { DRAFT: '待调出确认', DEPT_OUT_CONFIRMED: '待调入确认', DEPT_IN_CONFIRMED: '待 HR 审核',
  HR_REVIEWED: '复核中', REVIEWING: '双人复核中', REVIEW_DIFF: '差异挂起', APPROVING: '审批中',
  EFFECTED: '已生效', REJECTED: '驳回' }
const statusName = (s) => STATUS_NAMES[s] || s
const posName = (id) => positions.value.find(p => p.id === id)?.name || '—'

async function loadAll() {
  try { rows.value = (await trfList({ type: 'POST' })).data || [] } catch (e) { /* ignore */ }
  try {
    const { data } = await empList({})
    emps.value = (data || []).map(r => r.employee)
  } catch (e) { /* ignore */ }
  try { positions.value = (await posList({})).data || [] } catch (e) { /* ignore */ }
}

function openSubmit() {
  form.value = { effectiveDate: new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10) }
  submitVisible.value = true
}

async function doSubmit() {
  try {
    await postSubmit(form.value)
    ElMessage.success('调岗申请已提交')
    submitVisible.value = false
    loadAll()
  } catch (e) { /* ignore */ }
}

async function confirmOut(row) {
  const { value } = await ElMessageBox.prompt('调出确认意见', '调出部门确认', {
    inputValidator: (v) => (v && v.trim()) || '意见必填'
  })
  try { await postConfirmOut(row.id, value); ElMessage.success('已确认'); loadAll() } catch (e) { /* ignore */ }
}
async function confirmIn(row) {
  const { value } = await ElMessageBox.prompt('调入确认意见', '调入部门确认', {
    inputValidator: (v) => (v && v.trim()) || '意见必填'
  })
  try { await postConfirmIn(row.id, value); ElMessage.success('已确认'); loadAll() } catch (e) { /* ignore */ }
}
async function hrReview(row) {
  const { value } = await ElMessageBox.prompt('HR 审核意见', 'HR 审核 → 双人复核', {
    inputValidator: (v) => (v && v.trim()) || '意见必填'
  })
  try { await postHrReview(row.id, value); ElMessage.success('进入双人复核'); loadAll() } catch (e) { /* ignore */ }
}

function openReview(row) {
  reviewRow.value = row
  reviewForm.value = { position: posName(row.toPosId), salary: row.salaryAdvice || '',
    reportLine: '', costCenter: '', effectiveDate: row.effectiveDate }
  reviewVisible.value = true
}

async function doReview() {
  try {
    const { data } = await postReviewSign(reviewRow.value.id, reviewForm.value)
    if (data.status === 'REVIEW_DIFF') {
      ElMessage.warning('两人复核结果不一致，已挂起升级裁决')
    } else {
      ElMessage.success('复核已签署')
    }
    reviewVisible.value = false
    loadAll()
  } catch (e) { /* ignore */ }
}

async function adjudicate(row) {
  const { value: decision } = await ElMessageBox.confirm('裁决：通过并流转审批？', '复核差异裁决',
    { confirmButtonText: '通过（APPROVE）', cancelButtonText: '驳回' }).then(() => 'APPROVE').catch(() => 'REJECT')
  if (decision === 'REJECT') {
    try { await postAdjudicate(row.id, { decision: 'REJECT', note: '驳回' }); loadAll() } catch (e) { /* ignore */ }
    return
  }
  const { value: note } = await ElMessageBox.prompt('裁决说明', '说明', {
    inputValidator: (v) => (v && v.trim()) || '必填'
  })
  try { await postAdjudicate(row.id, { decision: 'APPROVE', note }); ElMessage.success('已裁决，流转审批'); loadAll() } catch (e) { /* ignore */ }
}

async function openPlace(row) {
  placeRow.value = row
  placeForm.value = { placeChange: row.placeChange || '0', newPlace: row.newPlace || '' }
  placeVisible.value = true
  if (!placeCities.value.length) {
    try {
      const { data } = await policyList({})
      placeCities.value = [...new Set((data || []).map(p => p.city))]
    } catch (e) { /* ignore */ }
  }
}
async function doPlace() {
  try {
    await postPlaceChange(placeRow.value.id, placeForm.value.placeChange, placeForm.value.newPlace)
    ElMessage.success('已保存（生效时联动）')
    placeVisible.value = false
    loadAll()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

onMounted(loadAll)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.mb8 { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
</style>
