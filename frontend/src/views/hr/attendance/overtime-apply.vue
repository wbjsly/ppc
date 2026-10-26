<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">加班申请（10.4.1）</span>
        <div>
          <el-select v-model="statusFilter" placeholder="状态过滤" clearable style="width:130px;margin-right:8px" @change="load">
            <el-option label="GM 特批待办" value="GM_SPECIAL" />
            <el-option label="待审批" value="DRAFT" />
            <el-option label="已审批" value="APPROVED" />
            <el-option label="已驳回" value="REJECTED" />
          </el-select>
          <el-button type="primary" @click="openSubmit">提交加班申请</el-button>
          <el-button @click="confirmScanNow">确认扫描（MIN 认定）</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <!-- 申请清单 -->
      <el-table :data="applies" height="200" style="margin-bottom:12px">
        <el-table-column prop="otNo" label="单号" width="160" />
        <el-table-column prop="empName" label="姓名" width="100" />
        <el-table-column prop="otDate" label="加班日" width="110" />
        <el-table-column label="时段" width="130">
          <template #default="{ row }">{{ row.startTime }}-{{ row.endTime }}</template>
        </el-table-column>
        <el-table-column prop="hours" label="时长" width="70" />
        <el-table-column prop="reason" label="事由" min-width="150" />
        <el-table-column label="补单" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.isEmergency === '1'" type="warning" size="small">补单</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="特批" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.isSpecial === '1'" type="danger" size="small">GM 特批</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ otStatus(row.status) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT' || row.status === 'GM_SPECIAL'" link type="success" size="small"
                       @click="approve(row, true)">通过</el-button>
            <el-button v-if="row.status === 'DRAFT' || row.status === 'GM_SPECIAL'" link type="danger" size="small"
                       @click="approve(row, false)">驳回</el-button>
            <el-button v-if="row.status === 'DRAFT' || row.status === 'GM_SPECIAL'" link type="primary" size="small"
                       @click="viewApproval(row)">审批视图</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 确认分流（含试算器） -->
      <el-table :data="confirms" height="240">
        <el-table-column prop="confirmNo" label="确认单" width="160" />
        <el-table-column prop="empNo" label="工号" width="110" />
        <el-table-column prop="otDate" label="加班日" width="110" />
        <el-table-column prop="appliedHours" label="申请" width="60" />
        <el-table-column prop="punchedHours" label="打卡" width="60" />
        <el-table-column prop="validHours" label="有效" width="60" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ { WEEKDAY: '平日1.5x', WEEKEND: '周末2x', HOLIDAY: '法定假3x' }[row.otType] }}</template>
        </el-table-column>
        <el-table-column label="超时" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.excessFlag === '1'" type="warning" size="small">待追认</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="分流" width="110">
          <template #default="{ row }">
            <span v-if="row.splitChoice === 'WAGE'">工资 ¥{{ row.wageAmount }}</span>
            <span v-else-if="row.splitChoice === 'COMP_TIME'">调休</span>
            <span v-else class="muted">待分流</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <template v-if="row.status === 'EXCESS_PENDING'">
              <el-button link type="warning" size="small" @click="resolveExcess(row)">按打卡追认</el-button>
              <el-button link type="primary" size="small" @click="resolveExcess(row, false)">按申请认定</el-button>
            </template>
            <template v-else-if="row.status === 'PENDING'">
              <el-button link type="primary" size="small" @click="trial(row)">试算</el-button>
              <el-button link type="success" size="small" @click="split(row, 'WAGE')">工资</el-button>
              <el-button v-if="row.otType === 'WEEKEND'" link type="info" size="small"
                         @click="split(row, 'COMP_TIME')">调休</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 提交申请 -->
    <el-dialog v-model="submitVisible" title="提交加班申请" width="480px">
      <el-alert type="info" :closable="false" style="margin-bottom:12px"
                title="须事前申请；紧急加班 1 工作日内补单（附主管书面说明）；月累计超 36 小时硬阻断，可勾选总经理特批放行" />
      <el-form label-width="90px">
        <el-form-item label="加班日期"><el-date-picker v-model="form.otDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="时段">
          <el-time-picker v-model="form.startTime" format="HH:mm" value-format="HH:mm" style="width:47%" placeholder="开始" />
          <span style="margin:0 4px">-</span>
          <el-time-picker v-model="form.endTime" format="HH:mm" value-format="HH:mm" style="width:47%" placeholder="结束" />
        </el-form-item>
        <el-form-item label="时长(h)"><el-input-number v-model="form.hours" :min="0.5" :step="0.5" /></el-form-item>
        <el-form-item label="事由"><el-input v-model="form.reason" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="紧急补单">
          <el-switch v-model="form.isEmergency" @change="v => form.isEmergency = v ? '1' : '0'" />
          <span v-if="form.isEmergency === '1'" style="margin-left:8px;color:#e6a23c">补单须主管书面说明</span>
        </el-form-item>
        <el-form-item v-if="form.isEmergency === '1'" label="书面说明">
          <el-input v-model="form.emergencyNote" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="总经理特批">
          <el-switch v-model="form.isSpecial" @change="v => form.isSpecial = v ? '1' : '0'" />
          <span v-if="form.isSpecial === '1'" style="margin-left:8px;color:#f56c6c">月累计超 36 小时须 ROLE_GM 审批（BR-4.7-04）</span>
        </el-form-item>
        <el-form-item v-if="form.isSpecial === '1'" label="超限原因">
          <el-input v-model="form.specialReason" type="textarea" :rows="2" placeholder="必填：项目攻坚/生产旺季等超限理由" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitVisible=false">取消</el-button>
        <el-button type="primary" @click="doSubmit">提交</el-button>
      </template>
    </el-dialog>

    <!-- 试算结果 -->
    <el-dialog v-model="trialVisible" title="分流试算（不落库）" width="420px">
      <el-descriptions v-if="trialData" :column="1" border>
        <el-descriptions-item label="有效时长">{{ trialData.validHours }} 小时</el-descriptions-item>
        <el-descriptions-item label="倍数">{{ trialData.multiplier }}x</el-descriptions-item>
        <el-descriptions-item label="2x 工资估算">
          {{ trialData.wageEstimate != null ? '¥' + trialData.wageEstimate : (trialData.wageNote || '—') }}
        </el-descriptions-item>
        <el-descriptions-item label="等值调休">
          {{ trialData.compTimeHours ? trialData.compTimeHours + ' 小时（有效期至 ' + trialData.compTimeExpireAt + '）' : (trialData.compTimeNote || '—') }}
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
    <!-- 审批超时催办（FR-4.7-6-2） -->
    <el-card shadow="never" style="margin-top:12px" v-if="reminders.length">
      <div class="title" style="margin-bottom:8px">审批超时催办（近 30 天）</div>
      <el-table :data="reminders" height="160">
        <el-table-column prop="remindDate" label="催办日" width="110" />
        <el-table-column prop="empName" label="催办对象（审批主管）" width="160" />
        <el-table-column prop="detail" label="内容" min-width="240" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  otSubmit, otApprove, otApprovalView, otApplyList, otConfirmScan,
  otResolveExcess, otSplit, otTrial, otConfirmList, otApprovalReminders
} from '@/api/hr/attendance'

const applies = ref([])
const confirms = ref([])
const reminders = ref([])
const statusFilter = ref('')
const submitVisible = ref(false)
const form = ref({ otDate: '', startTime: '', endTime: '', hours: 4, reason: '', isEmergency: '0', emergencyNote: '', isSpecial: '0', specialReason: '' })
const trialVisible = ref(false)
const trialData = ref(null)

const otStatus = s => ({ DRAFT: '待审批', APPROVING: '审批中', GM_SPECIAL: 'GM 特批待审', APPROVED: '已审批', REJECTED: '已驳回', CONFIRMED: '已确认' }[s] || s)

async function load() {
  const [a, c, r] = await Promise.all([
    otApplyList(statusFilter.value ? { status: statusFilter.value } : {}),
    otConfirmList({}),
    otApprovalReminders().catch(() => ({ data: [] }))
  ])
  applies.value = a.data || []
  confirms.value = c.data || []
  reminders.value = r.data || []
}

function openSubmit() {
  form.value = { otDate: '', startTime: '', endTime: '', hours: 4, reason: '', isEmergency: '0', emergencyNote: '', isSpecial: '0', specialReason: '' }
  submitVisible.value = true
}
async function doSubmit() {
  if (!form.value.otDate || !form.value.reason) return ElMessage.warning('日期与事由必填')
  if (form.value.isSpecial === '1' && !form.value.specialReason) return ElMessage.warning('特批须填超限原因')
  try {
    await otSubmit(form.value)
    ElMessage.success('申请已提交'); submitVisible.value = false; load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function approve(row, ok) {
  try {
    await otApprove(row.id, { approve: ok, opinion: ok ? '同意' : '不批准' })
    ElMessage.success(ok ? '已审批通过' : '已驳回'); load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function viewApproval(row) {
  const { data } = await otApprovalView(row.id)
  const extra = row.isSpecial === '1' && row.specialReason ? `；特批原因：${row.specialReason}` : ''
  ElMessage.info(`当月累计 ${data?.monthHours}h，部门预算占用 ${data?.budgetUsagePct ?? '—'}%${extra}`)
}
async function confirmScanNow() {
  const out = await otConfirmScan()
  ElMessage.success(`确认扫描完成：${out.data?.created} 单`); load()
}
async function resolveExcess(row, approveExcess = true) {
  await otResolveExcess(row.id, { approveExcess })
  ElMessage.success(approveExcess ? '已按打卡追认' : '已按申请认定'); load()
}
async function trial(row) {
  const { data } = await otTrial(row.id)
  trialData.value = data; trialVisible.value = true
}
async function split(row, choice) {
  try {
    await otSplit(row.id, { choice })
    ElMessage.success(choice === 'WAGE' ? '已分流工资（金额固化）' : '已分流调休（批次生成）')
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
.muted { color: #999; }
</style>
