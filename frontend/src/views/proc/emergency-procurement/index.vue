<template>
  <div class="emergency-procurement">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">紧急采购</span>
          <el-radio-group v-model="tab">
            <el-radio-button value="ea">紧急申请</el-radio-button>
            <el-radio-button value="channel">通道台账</el-radio-button>
          </el-radio-group>
        </div>
      </template>

      <!-- ① 紧急申请 -->
      <template v-if="tab === 'ea'">
        <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
          title="紧急采购（S-4.2-04 / BR-4.2-11）：仅对 已批准/待询价 的 PR 发起，采购总监特批放行（L2）；放行后须在 EMERGENCY_FILL_DAYS（默认 5 个工作日）内补齐比价资料。"
          description="逾期未补齐（L1321）：申请转入逾期例外 + 申请人通道自动阻断，经总监复核恢复并关闭例外。附件上传为桩。" />
        <div class="toolbar">
          <el-select v-model="listQuery.status" placeholder="状态" clearable style="width: 160px;" @change="loadList(1)">
            <el-option v-for="(n, v) in statusNames" :key="v" :label="n" :value="v" />
          </el-select>
          <el-input v-model="listQuery.keyword" placeholder="EA 单号/申请人" clearable style="width: 180px;" @keyup.enter="loadList(1)" />
          <el-button type="primary" @click="loadList(1)">查询</el-button>
          <el-button v-if="isAdmin" type="danger" :icon="Plus" @click="openCreate">发起紧急申请</el-button>
        </div>

        <el-table :data="rows" v-loading="loading" stripe>
          <el-table-column prop="eaNo" label="EA 单号" width="160">
            <template #default="{ row }"><b>{{ row.eaNo }}</b></template>
          </el-table-column>
          <el-table-column prop="prNo" label="关联 PR" width="150" />
          <el-table-column label="事由" width="110">
            <template #default="{ row }">{{ reasonName(row.reasonType) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="补齐截止" width="160">
            <template #default="{ row }">
              {{ row.clearanceDueDate || '—' }}
              <el-tag v-if="row.overdueDays" type="danger" size="small" style="margin-left:4px;">
                逾期{{ row.overdueDays }}日
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="标记" width="150">
            <template #default="{ row }">
              <el-tag v-if="row.remindFlag === '1'" type="warning" size="small">提醒</el-tag>
              <el-tag v-if="row.escalateFlag === '1'" type="danger" size="small" style="margin-left:2px;">升级</el-tag>
              <el-tag v-if="row.exceptionFlag === '1'" type="danger" size="small" style="margin-left:2px;">例外</el-tag>
              <span v-if="row.remindFlag !== '1' && row.escalateFlag !== '1' && row.exceptionFlag !== '1'"
                    style="color:#c0c4cc;">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="applicant" label="申请人" width="110" />
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination layout="total, prev, pager, next" style="margin-top: 12px; justify-content: flex-end;"
          :total="total" :page-size="listQuery.size" :current-change="loadList" />
      </template>

      <!-- ② 通道台账 -->
      <template v-else>
        <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
          title="紧急通道台账（L1321）：无台账记录 = 已开通（默认）；逾期自动阻断，总监复核恢复并关闭全部逾期例外。" />
        <div class="toolbar">
          <el-select v-model="chQuery.status" placeholder="状态" clearable style="width: 140px;" @change="loadChannels(1)">
            <el-option label="已开通" value="OPEN" />
            <el-option label="已阻断" value="BLOCKED" />
          </el-select>
          <el-input v-model="chQuery.keyword" placeholder="账号" clearable style="width: 180px;" @keyup.enter="loadChannels(1)" />
          <el-button type="primary" @click="loadChannels(1)">查询</el-button>
        </div>
        <el-table :data="chRows" v-loading="loading" stripe>
          <el-table-column prop="account" label="账号" width="160" />
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-tag :type="row.status === 'BLOCKED' ? 'danger' : 'success'" size="small">
                {{ row.status === 'BLOCKED' ? '已阻断' : (row.implicit ? '已开通（默认）' : '已开通') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="阻断原因" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ row.blockReason || '—' }}</template>
          </el-table-column>
          <el-table-column label="阻断时间" width="150">
            <template #default="{ row }">{{ (row.blockDate || '').replace('T', ' ').slice(0, 16) || '—' }}</template>
          </el-table-column>
          <el-table-column prop="overdueCount" label="关联逾期" width="90" align="center" />
          <el-table-column label="复核留痕" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <template v-if="row.reviewBy">
                {{ row.reviewBy }}（{{ (row.reviewDate || '').replace('T', ' ').slice(0, 16) }}）：{{ row.reviewNote }}
              </template>
              <span v-else style="color:#c0c4cc;">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button v-if="isAdmin && row.status === 'BLOCKED'" link type="warning"
                         @click="openRestore(row)">复核恢复</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination layout="total, prev, pager, next" style="margin-top: 12px; justify-content: flex-end;"
          :total="chTotal" :page-size="chQuery.size" :current-change="loadChannels" />
      </template>
    </el-card>

    <!-- 发起对话框 -->
    <el-dialog v-model="createVisible" title="发起紧急采购申请" width="640px">
      <el-alert v-if="createMsg" type="error" show-icon :closable="false" :title="createMsg" style="margin-bottom: 10px;" />
      <el-form label-width="110px">
        <el-form-item label="关联 PR" required>
          <el-select v-model="form.prId" filterable placeholder="选择 已批准/待询价 的 PR" style="width: 100%;">
            <el-option v-for="c in candidates" :key="c.prId"
                       :label="`${c.prNo}（${statusPr(c.status)}${c.hasOpenEa ? '，已有未关闭EA' : ''}）`"
                       :value="c.prId" :disabled="c.hasOpenEa" />
          </el-select>
        </el-form-item>
        <el-form-item label="紧急事由" required>
          <el-radio-group v-model="form.reasonType">
            <el-radio value="STOP_LINE">产线停线</el-radio>
            <el-radio value="CUSTOMER_RUSH">客户紧急订单</el-radio>
            <el-radio value="OTHER">其他</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="事由说明" required>
          <el-input v-model="form.reasonDesc" type="textarea" :rows="2" maxlength="255" placeholder="≥2 字，说明紧急性" />
        </el-form-item>
        <el-form-item label="期望到货日">
          <el-date-picker v-model="form.expectArriveDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">提交（进入特批）</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="dtVisible" :title="`紧急申请：${dt.ea.eaNo || ''}`" size="720px">
      <el-descriptions :column="3" border size="small" style="margin-bottom: 10px;">
        <el-descriptions-item label="状态">
          <el-tag :type="statusTag(dt.ea.status)" size="small">{{ statusName(dt.ea.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="关联 PR">{{ dt.pr?.prNo || '—' }}</el-descriptions-item>
        <el-descriptions-item label="申请人">{{ dt.ea.applicant }}</el-descriptions-item>
        <el-descriptions-item label="紧急事由">{{ reasonName(dt.ea.reasonType) }}</el-descriptions-item>
        <el-descriptions-item label="期望到货" :span="2">{{ dt.ea.expectArriveDate || '—' }}</el-descriptions-item>
        <el-descriptions-item label="事由说明" :span="3">{{ dt.ea.reasonDesc }}</el-descriptions-item>
        <el-descriptions-item label="特批人">{{ dt.ea.specialApproveBy || '—' }}</el-descriptions-item>
        <el-descriptions-item label="特批时间" :span="2">{{ (dt.ea.specialApproveDate || '').replace('T', ' ').slice(0, 16) || '—' }}</el-descriptions-item>
        <el-descriptions-item label="特批原因" :span="3">{{ dt.ea.specialReason || '—' }}</el-descriptions-item>
        <el-descriptions-item label="补齐截止日" :span="2">
          {{ dt.ea.clearanceDueDate || '—' }}
          <span v-if="dt.ea.overdueDays" style="color:#f56c6c;">（逾期 {{ dt.ea.overdueDays }} 个工作日）</span>
        </el-descriptions-item>
        <el-descriptions-item label="驳回原因">{{ dt.ea.rejectReason || '—' }}</el-descriptions-item>
        <el-descriptions-item label="补齐" :span="2">
          <template v-if="dt.ea.fillDate">
            {{ dt.ea.quoteCount }} 家报价 · {{ dt.ea.compareDocNo }}（{{ (dt.ea.fillDate || '').replace('T', ' ').slice(0, 16) }}）
          </template>
          <template v-else>未补齐（附件上传为桩）</template>
        </el-descriptions-item>
        <el-descriptions-item label="关闭原因">{{ dt.ea.closeReason || '—' }}</el-descriptions-item>
      </el-descriptions>

      <div class="toolbar" v-if="isAdmin">
        <template v-if="dt.ea.status === 'PENDING_SPECIAL_APPROVAL'">
          <el-button type="primary" @click="doApprove">特批通过（采购总监）</el-button>
          <el-button type="danger" plain @click="doReject">驳回</el-button>
        </template>
        <template v-else-if="['APPROVED_EMERGENCY', 'FILLING'].includes(dt.ea.status)">
          <el-button type="success" @click="openFill">登记比价补齐</el-button>
        </template>
        <el-button v-if="!['COMPLETED', 'CLOSED', 'REJECTED'].includes(dt.ea.status)"
                   type="info" plain @click="doClose">人工关闭</el-button>
      </div>
      <div class="tip" v-if="dt.ea.status === 'OVERDUE_EXCEPTION'">
        已逾期例外：须先在「通道台账」经总监复核恢复后处理（L1321）。
      </div>
    </el-drawer>

    <!-- 特批/驳回原因弹窗（共用简单 prompt 弹窗） -->
    <el-dialog v-model="reasonVisible" :title="reasonTitle" width="480px">
      <el-input v-model="reasonText" type="textarea" :rows="3" maxlength="255"
                :placeholder="reasonPlaceholder" />
      <template #footer>
        <el-button @click="reasonVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitReason">确定</el-button>
      </template>
    </el-dialog>

    <!-- 补齐登记弹窗 -->
    <el-dialog v-model="fillVisible" title="比价资料补齐登记（附件上传为桩）" width="520px">
      <el-form label-width="120px">
        <el-form-item label="实际报价家数" required>
          <el-input-number v-model="fillForm.quoteCount" :min="1" :precision="0" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="比价资料编号" required>
          <el-input v-model="fillForm.compareDocNo" maxlength="64" placeholder="如 BJ-2026-001" />
        </el-form-item>
        <el-form-item label="补充说明" required>
          <el-input v-model="fillForm.fillNote" type="textarea" :rows="2" maxlength="500" placeholder="≥2 字" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="fillVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitFill">提交补齐</el-button>
      </template>
    </el-dialog>

    <!-- 复核恢复弹窗 -->
    <el-dialog v-model="restoreVisible" :title="`复核恢复通道：${restoreAccount}`" width="480px">
      <el-alert type="warning" :closable="false" style="margin-bottom: 10px;"
                title="恢复将同时关闭该账号全部「逾期例外」申请（L1321 恢复路径）" />
      <el-input v-model="restoreNote" type="textarea" :rows="3" maxlength="255" placeholder="复核说明（≥2 字，必填）" />
      <template #footer>
        <el-button @click="restoreVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitRestore">确认恢复</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/user'
import {
  getEmergencyPageApi, getEmergencyDetailApi, getPrCandidatesApi, createEmergencyApi,
  approveEmergencyApi, rejectEmergencyApi, fillEmergencyApi, closeEmergencyApi,
  getChannelsApi, restoreChannelApi
} from '@/api/proc/emergency'

const userStore = useUserStore()
const isAdmin = computed(() => (userStore.userInfo?.roles || []).includes('ROLE_ADMIN'))
const tab = ref('ea')
const loading = ref(false)
const saving = ref(false)

const statusNames = {
  PENDING_SPECIAL_APPROVAL: '特批中', APPROVED_EMERGENCY: '已特批放行', FILLING: '补齐中',
  COMPLETED: '已补齐', OVERDUE_EXCEPTION: '逾期例外', REJECTED: '已驳回', CLOSED: '已关闭'
}
const prStatusNames = { APPROVED: '已批准', PENDING_RFQ: '待询价' }
function statusName(s) { return statusNames[s] || s }
function statusPr(s) { return prStatusNames[s] || s }
function statusTag(s) {
  return { PENDING_SPECIAL_APPROVAL: 'warning', APPROVED_EMERGENCY: 'success', FILLING: 'primary',
    COMPLETED: 'success', OVERDUE_EXCEPTION: 'danger', REJECTED: 'danger', CLOSED: 'info' }[s]
}
function reasonName(t) {
  return { STOP_LINE: '产线停线', CUSTOMER_RUSH: '客户紧急订单', OTHER: '其他' }[t] || t
}

// ---------- 申请列表 ----------
const listQuery = ref({ status: '', keyword: '', current: 1, size: 10 })
const rows = ref([])
const total = ref(0)

async function loadList(page) {
  if (page) listQuery.value.current = page
  loading.value = true
  try {
    const res = await getEmergencyPageApi(listQuery.value)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

// ---------- 通道台账 ----------
const chQuery = ref({ status: '', keyword: '', current: 1, size: 10 })
const chRows = ref([])
const chTotal = ref(0)

async function loadChannels(page) {
  if (page) chQuery.value.current = page
  loading.value = true
  try {
    const res = await getChannelsApi(chQuery.value)
    chRows.value = res.data.records
    chTotal.value = res.data.total
  } finally {
    loading.value = false
  }
}

// ---------- 发起 ----------
const createVisible = ref(false)
const createMsg = ref('')
const candidates = ref([])
const form = ref({})

async function openCreate() {
  form.value = { prId: '', reasonType: 'STOP_LINE', reasonDesc: '', expectArriveDate: '' }
  createMsg.value = ''
  createVisible.value = true
  const res = await getPrCandidatesApi({})
  candidates.value = res.data.candidates
}

async function submitCreate() {
  createMsg.value = ''
  saving.value = true
  try {
    const res = await createEmergencyApi(form.value)
    ElMessage.success(`已发起 ${res.data.ea.eaNo}，进入特批（2.1.3 详情内操作）`)
    createVisible.value = false
    loadList()
  } catch (e) {
    if (e?.message) createMsg.value = e.message
  } finally {
    saving.value = false
  }
}

// ---------- 详情与动作 ----------
const dtVisible = ref(false)
const dt = ref({ ea: {}, pr: null })

async function openDetail(row) {
  const res = await getEmergencyDetailApi(row.id)
  dt.value = res.data
  dtVisible.value = true
}

// 共用原因弹窗
const reasonVisible = ref(false)
const reasonTitle = ref('')
const reasonText = ref('')
const reasonPlaceholder = ref('')
const reasonAction = ref(null)

function askReason(title, placeholder, action) {
  reasonTitle.value = title
  reasonPlaceholder.value = placeholder
  reasonText.value = ''
  reasonAction.value = action
  reasonVisible.value = true
}
async function submitReason() {
  if (!reasonText.value || reasonText.value.trim().length < 2) {
    ElMessage.warning('原因必填（≥2 字）')
    return
  }
  saving.value = true
  try {
    await reasonAction.value(reasonText.value.trim())
    reasonVisible.value = false
  } finally {
    saving.value = false
  }
}

function refresh() {
  openDetail({ id: dt.value.ea.id })
  loadList()
}

function doApprove() {
  askReason('特批通过（采购总监，L2）', '特批原因（≥2 字）', async (reason) => {
    try {
      await approveEmergencyApi(dt.value.ea.id, reason)
      ElMessage.success('特批通过，已生成放行与补齐截止日')
      refresh()
    } catch (e) {
      ElMessage.error(e?.message || '特批失败')
    }
  })
}
function doReject() {
  askReason('特批驳回（退回后可重新发起）', '驳回原因（≥2 字）', async (reason) => {
    try {
      await rejectEmergencyApi(dt.value.ea.id, reason)
      ElMessage.success('已驳回')
      refresh()
    } catch (e) {
      ElMessage.error(e?.message || '驳回失败')
    }
  })
}
function doClose() {
  askReason('人工关闭（原因必填）', '关闭原因（≥2 字）', async (reason) => {
    try {
      await closeEmergencyApi(dt.value.ea.id, reason)
      ElMessage.success('已关闭')
      refresh()
    } catch (e) {
      ElMessage.error(e?.message || '关闭失败')
    }
  })
}

const fillVisible = ref(false)
const fillForm = ref({})
function openFill() {
  fillForm.value = { quoteCount: 1, compareDocNo: '', fillNote: '' }
  fillVisible.value = true
}
async function submitFill() {
  saving.value = true
  try {
    await fillEmergencyApi(dt.value.ea.id, fillForm.value)
    ElMessage.success('补齐完成')
    fillVisible.value = false
    refresh()
  } catch (e) {
    ElMessage.error(e?.message || '补齐失败')
  } finally {
    saving.value = false
  }
}

// ---------- 复核恢复 ----------
const restoreVisible = ref(false)
const restoreAccount = ref('')
const restoreNote = ref('')

function openRestore(row) {
  restoreAccount.value = row.account
  restoreNote.value = ''
  restoreVisible.value = true
}
async function submitRestore() {
  if (!restoreNote.value || restoreNote.value.trim().length < 2) {
    ElMessage.warning('复核说明必填（≥2 字）')
    return
  }
  saving.value = true
  try {
    const res = await restoreChannelApi(restoreAccount.value, restoreNote.value.trim())
    ElMessage.success(`已恢复开通，关闭逾期例外 ${res.data.closedOverdue} 单`)
    restoreVisible.value = false
    loadChannels()
    loadList()
  } catch (e) {
    ElMessage.error(e?.message || '恢复失败')
  } finally {
    saving.value = false
  }
}

onMounted(() => { loadList(1); loadChannels(1) })
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; flex-wrap: wrap; }
.tip { font-size: 12px; color: #909399; }
</style>
