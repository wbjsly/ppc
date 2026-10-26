<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>{{ type === 'DISPATCH' ? '外派管理（10.8.1）' : '借调管理（10.8.2）' }}—— 主体三要素 / 双人复核（C-4.7-11）/ 分摊凭证</span>
          <div>
            <el-button size="small" @click="scanRemind">提醒扫描</el-button>
            <el-button type="primary" size="small" @click="openSubmit">发起{{ type === 'DISPATCH' ? '外派' : '借调' }}</el-button>
          </div>
        </div>
      </template>
      <el-alert :closable="false" type="info" show-icon class="mb8"
        :title="type === 'DISPATCH'
          ? '外派=主体永久转移：生效写回法人主体/合同/缴纳地 + 社保减员增员双单；补贴按属地×职级标准带出（可改，差异审批可见）'
          : '借调=劳动关系不变：主档零改动，单据落用工主体/成本中心/分摊比例（双方确认后锁定）；协议编号必填，到期前 15 天提醒，逾期未返岗 L1 阻断分摊'" />

      <el-tabs v-model="tab">
        <el-tab-pane :label="type === 'DISPATCH' ? '外派单据' : '借调单据'" name="list">
          <el-table :data="rows" size="small" border>
            <el-table-column prop="secNo" label="单号" width="160" />
            <el-table-column prop="empName" label="员工" width="90" />
            <el-table-column label="主体" width="150">
              <template #default="{ row }">{{ leName(row.srcLe) }} → {{ leName(row.targetLe) }}</template>
            </el-table-column>
            <el-table-column label="起止" width="180">
              <template #default="{ row }">{{ row.startDate }} ~ {{ row.endDate }}</template>
            </el-table-column>
            <el-table-column prop="costSharePct" label="分摊%" width="70" />
            <el-table-column prop="agreementNo" label="协议" width="110" />
            <el-table-column label="补贴(标准)" width="110">
              <template #default="{ row }">
                <span v-if="row.allowanceMonthly != null">
                  {{ row.allowanceMonthly }}
                  <el-tag v-if="row.allowanceStdMonthly != null && Number(row.allowanceMonthly) !== Number(row.allowanceStdMonthly)"
                          type="warning" size="small">差异</el-tag>
                </span>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" min-width="330">
              <template #default="{ row }">
                <template v-if="row.status === 'DRAFT'">
                  <el-button link type="primary" size="small" @click="confirmOut(row)">调出确认</el-button>
                  <el-button link type="primary" size="small" @click="confirmIn(row)">调入确认</el-button>
                </template>
                <el-button v-if="row.status === 'CONFIRMED_IN'" link type="primary" size="small"
                           @click="hrReview(row)">HR 审核</el-button>
                <el-button v-if="row.status === 'REVIEWING'" link type="primary" size="small"
                           @click="openReview(row)">复核签署</el-button>
                <el-button v-if="row.status === 'REVIEW_DIFF'" link type="danger" size="small"
                           @click="adjudicate(row)">差异裁决</el-button>
                <el-button v-if="row.status === 'APPROVING'" link type="success" size="small"
                           @click="approve(row, true)">审批通过</el-button>
                <el-button v-if="row.status === 'APPROVING'" link type="danger" size="small"
                           @click="approve(row, false)">驳回</el-button>
                <el-button v-if="row.status === 'APPROVED'" link type="warning" size="small"
                           @click="doEffect(row)">生效执行</el-button>
                <template v-if="row.status === 'EFFECTED' && row.chgType === 'SECOND'">
                  <el-button link type="success" size="small" @click="doReturn(row)">返岗</el-button>
                  <el-button link type="warning" size="small" @click="openExtend(row)">延期</el-button>
                </template>
                <el-button v-if="row.status === 'EXTENDING'" link type="primary" size="small"
                           @click="approveExtend(row, true)">批延期</el-button>
                <el-button link type="info" size="small" @click="openDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane v-if="type === 'SECOND'" label="分摊预览" name="alloc">
          <div class="toolbar" style="margin-bottom:12px">
            <el-input v-model="allocYm" placeholder="核算月 如 202610" style="width:130px" @change="loadAlloc" />
            <el-button @click="loadAlloc">查询</el-button>
            <el-button type="primary" @click="genAlloc">生成当月分摊凭证（幂等）</el-button>
          </div>
          <el-table :data="allocRows" size="small" border>
            <el-table-column prop="secNo" label="单号" width="160" />
            <el-table-column prop="empName" label="员工" width="90" />
            <el-table-column prop="sharePct" label="分摊%" width="70" />
            <el-table-column label="在途天数" width="90">
              <template #default="{ row }">{{ row.inDays }}/{{ row.monthDays }}</template>
            </el-table-column>
            <el-table-column prop="monthCost" label="月人力成本" width="110" />
            <el-table-column prop="allocAmount" label="分摊额" width="100" />
            <el-table-column prop="anchor" label="凭证锚点" width="180" />
            <el-table-column prop="estimateNote" label="备注" min-width="140" />
          </el-table>
        </el-tab-pane>

        <el-tab-pane v-if="type === 'DISPATCH'" label="补贴标准" name="std">
          <el-table :data="stdRows" size="small" border>
            <el-table-column prop="place" label="城市/外派地" width="140" />
            <el-table-column prop="gradeId" label="职级" width="140" />
            <el-table-column prop="monthly" label="月补贴标准" width="120" />
          </el-table>
          <div class="muted" style="margin-top:8px">标准由 HR 维护（当前经社保政策页/DB 维护；DEMO市×GRADE-DEMO 3000 为演示占位行）</div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 发起 -->
    <el-dialog v-model="submitVisible" :title="`发起${type === 'DISPATCH' ? '外派' : '借调'}`" width="620px">
      <el-alert v-if="submitWarnings.length" type="warning" :closable="false" class="mb8"
        :title="submitWarnings.join('；')" />
      <el-form label-width="110px">
        <el-form-item label="员工">
          <el-select v-model="form.empId" filterable style="width:100%">
            <el-option v-for="e in emps" :key="e.id" :label="`${e.empNo} ${e.name}`" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="用工/新主体">
          <el-select v-model="form.targetLe" style="width:100%">
            <el-option v-for="le in les" :key="le.leCode" :label="`${le.leCode} ${le.leName}`" :value="le.leCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="起止日期">
          <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" style="width:150px" />
          <span style="margin:0 6px">~</span>
          <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" style="width:150px" />
        </el-form-item>
        <el-form-item label="变更缴纳地">
          <el-switch v-model="form.placeChange" active-value="1" inactive-value="0" />
          <el-input v-if="form.placeChange === '1'" v-model="form.targetPlace" placeholder="新缴纳地（须已维护属地政策）"
                    style="width:220px;margin-left:8px" />
        </el-form-item>
        <el-form-item label="用工成本中心"><el-input v-model="form.costCenter" /></el-form-item>
        <el-form-item v-if="type === 'SECOND'" label="分摊比例%">
          <el-input-number v-model="form.costSharePct" :min="0" :max="100" />
          <span class="muted" style="margin-left:8px">双方确认后锁定（凭证计算因子）</span>
        </el-form-item>
        <template v-if="type === 'SECOND'">
          <el-form-item label="三方协议编号">
            <el-input v-model="form.agreementNo" placeholder="必填（BR-4.7-19 备案）" style="width:200px" />
            <el-date-picker v-model="form.agreementDate" type="date" value-format="YYYY-MM-DD"
                            placeholder="签署日期" style="width:140px;margin-left:8px" />
          </el-form-item>
          <el-form-item label="协议扫描件">
            <input type="file" @change="onFileChange" />
            <span v-if="uploadName" class="muted">已选：{{ uploadName }}</span>
          </el-form-item>
        </template>
        <el-form-item label="补贴标准">
          <span v-if="stdPreview != null">属地×职级标准 {{ stdPreview }}/月（自动带出，可改）</span>
          <span v-else class="muted">未命中标准，请手工填写或先维护标准</span>
          <el-input-number v-model="form.allowanceMonthly" :min="0" :step="100" style="margin-left:12px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitVisible = false">取消</el-button>
        <el-button type="primary" @click="doSubmit">提交</el-button>
      </template>
    </el-dialog>

    <!-- 双人复核 -->
    <el-dialog v-model="reviewVisible" :title="`双人复核 - ${reviewRow?.secNo || ''}`" width="520px">
      <el-alert :closable="false" type="warning" show-icon class="mb8"
        title="C-4.7-11 五要素独立确认——两人全一致才流转；不一致挂起升级 HR 经理裁决" />
      <el-form label-width="110px">
        <el-form-item label="用工主体"><el-input v-model="reviewForm.targetLe" /></el-form-item>
        <el-form-item label="分摊比例%"><el-input v-model="reviewForm.costSharePct" /></el-form-item>
        <el-form-item label="社保缴纳地"><el-input v-model="reviewForm.salaryPlace" /></el-form-item>
        <el-form-item label="成本中心"><el-input v-model="reviewForm.costCenter" /></el-form-item>
        <el-form-item label="生效日期"><el-input v-model="reviewForm.startDate" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" @click="doReview">签署</el-button>
      </template>
    </el-dialog>

    <!-- 详情（含协议扫描件） -->
    <el-dialog v-model="detailVisible" :title="`详情 - ${detailData?.secondment?.secNo || ''}`" width="640px">
      <template v-if="detailData">
        <el-descriptions :column="2" border size="small" class="mb8">
          <el-descriptions-item label="员工">{{ detailData.secondment.empName }}</el-descriptions-item>
          <el-descriptions-item label="用工主体">{{ detailData.targetLeName }}</el-descriptions-item>
          <el-descriptions-item label="起止">{{ detailData.secondment.startDate }} ~ {{ detailData.secondment.endDate }}</el-descriptions-item>
          <el-descriptions-item label="分摊%">{{ detailData.secondment.costSharePct ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="协议">{{ detailData.secondment.agreementNo ?? '—' }} {{ detailData.secondment.agreementDate ?? '' }}</el-descriptions-item>
          <el-descriptions-item label="锁定">{{ detailData.secondment.shareLocked === '1' ? '比例已锁定' : '未锁定' }}</el-descriptions-item>
          <el-descriptions-item label="累计延期" :span="2">{{ detailData.secondment.extendMonthsTotal }} 个月</el-descriptions-item>
          <el-descriptions-item label="轨迹" :span="2">
            <div class="muted" style="white-space:pre-wrap">{{ detailData.secondment.hrOpinion || '—' }}</div>
          </el-descriptions-item>
        </el-descriptions>
        <div class="sec">协议扫描件</div>
        <div v-for="a in detailData.attachments" :key="a.id" class="mb8">
          <el-link :href="attachmentDownloadUrl(a.id)" type="primary" target="_blank">{{ a.fileName }}</el-link>
          <span class="muted">（{{ Math.round((a.fileSize || 0) / 1024) }} KB，{{ a.createBy }}）</span>
        </div>
        <div v-if="!detailData.attachments?.length" class="muted mb8">未上传</div>
        <div>上传新件：<input type="file" @change="e => onDetailUpload(e, detailData.secondment.id)" /></div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import {
  secSubmit, secList, secDetail, secConfirmOut, secConfirmIn, secHrReview, secReviewSign,
  secAdjudicate, secApprove, secEffect, secReturn, secExtend, secApproveExtend,
  secRemindScan, secAllowanceStd, secAllocPreview, secAllocGenerate,
  uploadAttachment, attachmentDownloadUrl
} from '@/api/hr/secondment'
import { empList } from '@/api/hr/employee-archive'

const route = useRoute()
const type = route.meta.secType || 'SECOND'
const tab = ref('list')
const rows = ref([])
const emps = ref([])
const les = ref([])
const stdRows = ref([])
const allocRows = ref([])
const allocYm = ref(new Date(Date.now() - 32 * 86400000).toISOString().slice(0, 7).replace('-', ''))
const submitVisible = ref(false)
const submitWarnings = ref([])
const reviewVisible = ref(false)
const reviewRow = ref(null)
const detailVisible = ref(false)
const detailData = ref(null)
const stdPreview = ref(null)
const uploadFile = ref(null)
const uploadName = ref('')
const form = ref({})
const reviewForm = ref({})

const STATUS_NAMES = {
  DRAFT: '待调出确认', CONFIRMED_OUT: '待调入确认', CONFIRMED_IN: '待 HR 审核',
  HR_REVIEWED: '复核中', REVIEWING: '双人复核中', REVIEW_DIFF: '差异挂起', APPROVING: '审批中',
  APPROVED: '待生效', EFFECTED: '已生效', EXTENDING: '延期审批中', RETURNING: '返岗办理中',
  RETURNED: '已返岗', EXPIRED_ALERT: '过期告警', REJECTED: '已驳回', VOID: '已作废'
}
const statusName = s => STATUS_NAMES[s] || s
const statusTag = s => ({
  DRAFT: 'info', CONFIRMED_OUT: 'info', CONFIRMED_IN: 'info', HR_REVIEWED: 'info',
  REVIEWING: 'warning', REVIEW_DIFF: 'danger', APPROVING: 'warning', APPROVED: 'warning',
  EFFECTED: 'success', EXTENDING: 'warning', RETURNING: 'warning', RETURNED: 'success',
  EXPIRED_ALERT: 'danger', REJECTED: 'info', VOID: 'info'
}[s] || 'info')

async function load() {
  rows.value = (await secList({ chgType: type })).data || []
}
async function loadAlloc() {
  allocRows.value = (await secAllocPreview({ ym: allocYm.value })).data || []
}
async function loadLes() {
  const { data } = await request({
    url: '/api/mdm/legal-entities', method: 'get', params: { current: 1, size: 100 }
  })
  les.value = data?.records || data || []
}
async function loadStd() {
  stdRows.value = (await secAllowanceStd({})).data || []
}
async function loadEmps() {
  const { data } = await empList({})
  emps.value = (data || []).map(r => r.employee).filter(e => e && e.empStatus !== 'RESIGNED')
}
const leName = code => les.value.find(l => l.leCode === code)?.leName || code || '—'

// 缴纳地变化 → 按标准表带出建议补贴（FR-4.7-7-3）
watch(() => form.value.targetPlace, async (place) => {
  stdPreview.value = null
  if (!place) return
  const { data } = await secAllowanceStd({ place })
  const hit = (data || [])[0]
  stdPreview.value = hit ? Number(hit.monthly) : null
  if (hit) form.value.allowanceMonthly = Number(hit.monthly)
})

function openSubmit() {
  form.value = { empId: '', targetLe: '', startDate: '', endDate: '', placeChange: '0', targetPlace: '',
    costCenter: '', costSharePct: type === 'SECOND' ? 100 : null, agreementNo: '', agreementDate: '',
    allowanceMonthly: null }
  submitWarnings.value = []
  uploadFile.value = null; uploadName.value = ''
  stdPreview.value = null
  submitVisible.value = true
  if (!emps.value.length) loadEmps()
  if (!les.value.length) loadLes()
}
function onFileChange(e) {
  uploadFile.value = e.target.files?.[0] || null
  uploadName.value = uploadFile.value?.name || ''
}
async function doSubmit() {
  if (!form.value.empId || !form.value.targetLe || !form.value.startDate || !form.value.endDate) {
    return ElMessage.warning('员工/主体/起止必填')
  }
  try {
    const { data } = await secSubmit(form.value)
    const s = data.secondment
    if (uploadFile.value) {
      await uploadAttachment(uploadFile.value, 'SECONDTM_AGREEMENT', s.id)
    }
    submitWarnings.value = data.warnings || []
    if (submitWarnings.value.length) {
      await ElMessageBox.alert(submitWarnings.value.join('；'), '提交成功（L4 警示）', { type: 'warning' })
    } else {
      ElMessage.success('已提交')
    }
    submitVisible.value = false
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

async function confirmOut(row) {
  const { value } = await ElMessageBox.prompt('调出主体确认意见（agree=false 请输入 REJECT）', '调出确认', {
    inputValidator: v => (v && v.trim()) || '意见必填'
  })
  const agree = value.trim() !== 'REJECT'
  try { await secConfirmOut(row.id, { opinion: value, agree }); ElMessage.success('已确认'); load() }
  catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function confirmIn(row) {
  const { value } = await ElMessageBox.prompt('调入主体确认意见（agree=false 请输入 REJECT；确认后分摊比例锁定）', '调入确认', {
    inputValidator: v => (v && v.trim()) || '意见必填'
  })
  const agree = value.trim() !== 'REJECT'
  try { await secConfirmIn(row.id, { opinion: value, agree }); ElMessage.success('已确认（比例锁定）'); load() }
  catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function hrReview(row) {
  const { value } = await ElMessageBox.prompt('HR 合规审核意见', 'HR 审核', {
    inputValidator: v => (v && v.trim()) || '意见必填'
  })
  try {
    const { data } = await secHrReview(row.id, { opinion: value })
    if (data.warnings?.length) {
      await ElMessageBox.alert(data.warnings.join('；'), '通过（L4 警示）', { type: 'warning' })
    } else {
      ElMessage.success('进入双人复核')
    }
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
function openReview(row) {
  reviewRow.value = row
  reviewForm.value = { targetLe: row.targetLe, costSharePct: row.costSharePct,
    salaryPlace: row.targetPlace, costCenter: row.costCenter, startDate: row.startDate }
  reviewVisible.value = true
}
async function doReview() {
  try {
    const { data } = await secReviewSign(reviewRow.value.id, reviewForm.value)
    if (data.status === 'REVIEW_DIFF') ElMessage.warning('复核不一致，已挂起升级裁决')
    else ElMessage.success('已签署，流转审批')
    reviewVisible.value = false
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function adjudicate(row) {
  const { value: decision } = await ElMessageBox.confirm('裁决通过并流转？', '差异裁决',
    { confirmButtonText: '通过', cancelButtonText: '驳回' }).then(() => 'APPROVE').catch(() => 'REJECT')
  const { value: note } = await ElMessageBox.prompt('裁决说明', '说明', { inputValidator: v => (v && v.trim()) || '必填' })
  try { await secAdjudicate(row.id, { decision, note }); ElMessage.success('已裁决'); load() }
  catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function approve(row, flag) {
  const { value } = await ElMessageBox.prompt(flag ? '审批意见' : '驳回意见', flag ? '审批通过' : '驳回', {
    inputValidator: v => (v && v.trim()) || '意见必填'
  })
  try { await secApprove(row.id, { approve: flag, opinion: value }); ElMessage.success('已处理'); load() }
  catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function doEffect(row) {
  try { await secEffect(row.id); ElMessage.success('已生效（分叉联动执行）'); load() }
  catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function doReturn(row) {
  const { value } = await ElMessageBox.prompt('返岗确认说明', '返岗确认', { inputValidator: v => (v && v.trim()) || '必填' })
  try { await secReturn(row.id, { note: value }); ElMessage.success('已返岗（在途关闭）'); load() }
  catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function openExtend(row) {
  const { value: months } = await ElMessageBox.prompt('延期月数（累计 >24 月将被拒绝，BR-4.7-05）', '延期申请', {
    inputPattern: /^\d{1,2}$/, inputErrorMessage: '1-99 整数'
  })
  const { value: reason } = await ElMessageBox.prompt('延期原因', '说明', { inputValidator: v => (v && v.trim()) || '必填' })
  try { await secExtend(row.id, { months: Number(months), reason }); ElMessage.success('已提交延期（L2 待批）'); load() }
  catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function approveExtend(row, flag) {
  const { value } = await ElMessageBox.prompt('审批说明', '延期审批', { inputValidator: v => (v && v.trim()) || '必填' })
  try { await secApproveExtend(row.id, { approve: flag, note: value }); ElMessage.success('已处理'); load() }
  catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function scanRemind() {
  const { data } = await secRemindScan()
  ElMessage.success(`提醒 ${data.reminded} 条，过期告警 ${data.expiredAlerted} 条`)
  load()
}
async function genAlloc() {
  const { data } = await secAllocGenerate({ ym: allocYm.value })
  ElMessage.success(`生成 ${data.generated} 单（过期跳过 ${data.skippedExpired}，失败 ${data.failed}）`)
  loadAlloc()
}
async function openDetail(row) {
  const { data } = await secDetail(row.id)
  detailData.value = data
  detailVisible.value = true
}
async function onDetailUpload(e, secId) {
  const file = e.target.files?.[0]
  if (!file) return
  try {
    await uploadAttachment(file, 'SECONDTM_AGREEMENT', secId)
    ElMessage.success('已上传')
    const { data } = await secDetail(secId)
    detailData.value = data
  } catch (err) { ElMessage.error(err?.response?.data?.msg || err?.message) }
}

onMounted(() => { load(); loadLes(); loadStd() })
</script>

<style scoped>
.page { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.mb8 { margin-bottom: 8px; }
.sec { font-weight: 600; margin: 10px 0 6px; }
.muted { color: #909399; font-size: 12px; }
.toolbar { display: flex; gap: 8px; }
</style>
