<template>
  <div class="page-wrap">
    <!-- 待确认列表 + 超时倒计时（规格 R2/R3） -->
    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>交期确认 · 待确认作业台（12.5.2）—— 48h 催办 / 72h 升级 / 连 3 张锁定</span>
          <div>
            <el-select v-model="query.status" placeholder="确认状态" clearable size="small"
                       style="width:140px" @change="load">
              <el-option label="已推送（待确认）" value="PUSHED" />
              <el-option label="变更待处理" value="CHANGE_PENDING" />
              <el-option label="已升级" value="ESCATED" />
              <el-option label="已确认" value="CONFIRMED" />
            </el-select>
            <el-input v-model="query.keyword" placeholder="PO 单号" clearable size="small"
                      style="width:150px;margin-left:8px" @keyup.enter="load" />
            <el-button size="small" style="margin-left:8px" @click="load">查询</el-button>
            <el-button size="small" @click="$router.push('/m/12.5.1')">推送侧 12.5.1</el-button>
          </div>
        </div>
      </template>
      <el-table :data="rows" size="small" border v-loading="loading"
                @expand-change="loadTimeline">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="tl-box">
              <el-timeline v-if="timelineCache[row.id] && timelineCache[row.id].length">
                <el-timeline-item v-for="t in timelineCache[row.id]" :key="t.id"
                  :timestamp="t.actionAt" :type="tlType(t.actionType)">
                  【{{ actLabel(t.actionType) }}】{{ t.source }}
                  <span v-if="t.operatorName"> · {{ t.operatorName }}</span>
                  <span v-if="t.promiseDate"> · 承诺 {{ t.promiseDate }}</span>
                  <span v-if="t.qtySuggested != null" class="hint">
                    · 数量建议 {{ t.qtySuggested }}{{ t.qtyReason ? '（' + t.qtyReason + '）' : '' }}</span>
                  <div v-if="t.detail" class="hint">{{ t.detail }}</div>
                </el-timeline-item>
              </el-timeline>
              <el-empty v-else description="暂无协同动作" :image-size="40" />
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="poNo" label="PO 单号" width="145" />
        <el-table-column prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
        <el-table-column label="确认状态" width="105" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="cType(row.confirmStatus)">{{ cLabel(row.confirmStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="promiseDate" label="承诺交期" width="105" />
        <el-table-column label="超时倒计时" width="150">
          <template #default="{ row }">
            <template v-if="row.confirmStatus === 'CONFIRMED'">—</template>
            <template v-else-if="row.overdueHours > 0">
              <span class="overdue">已超催办时限 {{ row.overdueHours }}h</span>
              <div class="hint">距升级 {{ row.escalateInHours ?? 0 }}h</div>
            </template>
            <template v-else>
              <span>剩 {{ row.escalateInHours ?? 0 }}h 确认</span>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="锁定" width="70" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.locked || row.coopLock === '1'" type="danger" size="small">LOCKED</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <template v-if="canPm && row.confirmStatus !== 'CONFIRMED' && !(row.locked || row.coopLock === '1')">
              <el-button size="mini" type="primary" link @click="openConfirm(row)">代录确认</el-button>
              <el-button v-if="row.confirmStatus === 'CHANGE_PENDING'" size="mini" type="warning" link
                         @click="openAccept(row)">接受改期</el-button>
              <el-button v-if="row.confirmStatus === 'CHANGE_PENDING'" size="mini" link
                         @click="openNegotiate(row)">发起协商</el-button>
              <el-button size="mini" link @click="doRemind(row)">催办</el-button>
            </template>
            <span v-else class="hint">已确认或锁定中</span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="mt12" layout="total, prev, pager, next" :total="total"
                     :page-size="query.size" :current-page="query.current" @current-change="onPage" />
    </el-card>

    <!-- 协同异常工单（spec 增补：锁定→OPEN→工单解锁 DONE L5） -->
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>协同异常工单 —— 连续 3 张超时锁定自动生成；解锁须经工单（L5 留痕）</span>
          <el-select v-model="ticketQuery.status" placeholder="状态" clearable size="small"
                     style="width:120px" @change="loadTickets">
            <el-option label="待处理 OPEN" value="OPEN" />
            <el-option label="已处理 DONE" value="DONE" />
          </el-select>
        </div>
      </template>
      <el-table :data="tickets" size="small" border>
        <el-table-column prop="ticketNo" label="工单号" width="160" />
        <el-table-column prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
        <el-table-column prop="lockPoNos" label="连续超时 PO" min-width="200" show-overflow-tooltip />
        <el-table-column prop="lockAt" label="锁定时间" width="160" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'OPEN' ? 'danger' : 'success'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="解锁留痕（L5）" min-width="200">
          <template #default="{ row }">
            <template v-if="row.status === 'DONE'">
              {{ row.unlockBy }} · {{ row.unlockAt }}
              <div class="hint">{{ row.unlockReason }}</div>
            </template>
            <span v-else class="hint">待处理</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button v-if="canAdmin && row.status === 'OPEN'" size="mini" type="warning"
                       @click="openResolve(row)">工单解锁</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 代录确认对话框（默认接受原交期 / 新承诺交期 / 结构化数量建议） -->
    <el-dialog v-model="confirmDlg.visible" title="代录交期确认（来源 OFFLINE）" width="520px">
      <el-form label-width="120px">
        <el-form-item label="承诺交期">
          <el-date-picker v-model="confirmDlg.promiseDate" type="date" value-format="YYYY-MM-DD"
                          placeholder="留空 = 默认接受原交期" style="width:100%" />
        </el-form-item>
        <el-form-item label="建议数量">
          <el-input-number v-model="confirmDlg.qtySuggested" :min="0" :precision="3"
                           placeholder="可选：结构化数量变更建议" style="width:100%" />
        </el-form-item>
        <el-form-item label="建议原因">
          <el-input v-model="confirmDlg.qtyReason" placeholder="如：产能不足" />
        </el-form-item>
      </el-form>
      <div class="hint">数量建议不会直接写回 PO 数量（须经协商或另行处理，spec 增补）。</div>
      <template #footer>
        <el-button @click="confirmDlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="confirmDlg.saving" @click="submitConfirm">确认</el-button>
      </template>
    </el-dialog>

    <!-- 接受改期 -->
    <el-dialog v-model="acceptDlg.visible" title="接受改期（写回 PO 承诺交期）" width="460px">
      <el-form label-width="110px">
        <el-form-item label="最终承诺交期" required>
          <el-date-picker v-model="acceptDlg.promiseDate" type="date" value-format="YYYY-MM-DD"
                          style="width:100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="acceptDlg.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="acceptDlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="acceptDlg.saving" @click="submitAccept">接受并确认</el-button>
      </template>
    </el-dialog>

    <!-- 发起协商 -->
    <el-dialog v-model="negDlg.visible" title="发起协商（留痕时间线，PO 保持未确认）" width="460px">
      <el-form label-width="90px">
        <el-form-item label="协商说明" required>
          <el-input v-model="negDlg.note" type="textarea" :rows="3"
                    placeholder="如：请评估提前至 11-05 的可行性" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="negDlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="negDlg.saving" @click="submitNegotiate">发起协商</el-button>
      </template>
    </el-dialog>

    <!-- 工单解锁（L5 留痕，原因必填） -->
    <el-dialog v-model="resolveDlg.visible" title="工单处理解锁（解锁人/时间/原因 L5 留痕）" width="480px">
      <el-alert type="warning" :closable="false" show-icon class="mb12"
                :title="`工单 ${resolveDlg.ticketNo}｜${resolveDlg.supplierName}`"
                :description="'涉及连续超时 PO：' + resolveDlg.lockPoNos" />
      <el-form label-width="90px">
        <el-form-item label="解锁原因" required>
          <el-input v-model="resolveDlg.reason" type="textarea" :rows="3"
                    placeholder="如：继续合作，观察下批交付（必填，L5 留痕）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveDlg.visible = false">取消</el-button>
        <el-button type="warning" :loading="resolveDlg.saving" @click="submitResolve">确认解锁</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, reactive, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getPoCoopPageApi, getPoCoopTimelineApi, remindPoApi,
  offlineConfirmPoApi, acceptPoChangeApi,
  getPoTicketsApi, resolvePoTicketApi, negotiatePoApi
} from '@/api/proc/po-collaboration-console'

const CONFIRM = {
  PUSHED: ['待确认', 'info'], CONFIRMED: ['已确认', 'success'],
  CHANGE_PENDING: ['变更待处理', 'warning'], ESCATED: ['已升级', 'danger']
}
const ACT = {
  PUSHED: '推送', REMIND: '催办', ESCALATE: '升级', CONFIRM: '确认',
  CHANGE_REQUEST: '改期申请', CHANGE_ACCEPTED: '接受改期', NEGOTIATE: '发起协商',
  LOCK: '锁定', UNLOCK: '解锁'
}
const route = useRoute()
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ status: '', keyword: '', current: 1, size: 20 })
const timelineCache = ref({})
const tickets = ref([])
const ticketQuery = reactive({ status: 'OPEN' })

const userStore = useUserStore()
const roles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const canPm = computed(() => roles.value.includes('ROLE_ADMIN') || roles.value.includes('ROLE_PM'))
const canAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))

function cLabel(s) { return (CONFIRM[s] || [s, 'info'])[0] }
function cType(s) { return (CONFIRM[s] || [s, 'info'])[1] }
function actLabel(a) { return ACT[a] || a }
function tlType(a) {
  return { PUSHED: 'primary', REMIND: 'warning', ESCALATE: 'danger', CONFIRM: 'success',
    CHANGE_ACCEPTED: 'success', LOCK: 'danger', UNLOCK: 'success', NEGOTIATE: 'info' }[a] || 'info'
}

async function load() {
  loading.value = true
  try {
    const p = { current: query.current, size: query.size }
    if (query.status) p.status = query.status
    if (query.keyword) p.keyword = query.keyword
    const d = (await getPoCoopPageApi(p)).data
    rows.value = d.records || []
    total.value = Number(d.total || 0)
  } finally {
    loading.value = false
  }
}

async function loadTimeline(row) {
  if (timelineCache.value[row.id]) return
  const d = (await getPoCoopTimelineApi(row.id)).data
  timelineCache.value[row.id] = d || []
}

async function loadTickets() {
  const p = { current: 1, size: 50 }
  if (ticketQuery.status) p.status = ticketQuery.status
  tickets.value = ((await getPoTicketsApi(p)).data.records) || []
}

function onPage(c) { query.current = c; load() }

// ---------- 确认四分支 ----------
const confirmDlg = reactive({ visible: false, saving: false, poId: '', promiseDate: '', qtySuggested: undefined, qtyReason: '' })
function openConfirm(row) {
  Object.assign(confirmDlg, { visible: true, poId: row.id, promiseDate: '', qtySuggested: undefined, qtyReason: '' })
}
async function submitConfirm() {
  confirmDlg.saving = true
  try {
    const data = { source: 'OFFLINE' }
    if (confirmDlg.promiseDate) data.promiseDate = confirmDlg.promiseDate
    if (confirmDlg.qtySuggested != null) data.qtySuggested = String(confirmDlg.qtySuggested)
    if (confirmDlg.qtyReason) data.qtyReason = confirmDlg.qtyReason
    const r = (await offlineConfirmPoApi(confirmDlg.poId, data)).data
    ElMessage.success(`已代录确认：${r.status}${r.promiseDate ? '，承诺 ' + r.promiseDate : ''}`)
    confirmDlg.visible = false
    load()
  } finally {
    confirmDlg.saving = false
  }
}

const acceptDlg = reactive({ visible: false, saving: false, poId: '', promiseDate: '', remark: '' })
function openAccept(row) { Object.assign(acceptDlg, { visible: true, poId: row.id, promiseDate: '', remark: '' }) }
async function submitAccept() {
  if (!acceptDlg.promiseDate) { ElMessage.warning('请选择最终承诺交期'); return }
  acceptDlg.saving = true
  try {
    const r = (await acceptPoChangeApi(acceptDlg.poId, {
      promiseDate: acceptDlg.promiseDate, remark: acceptDlg.remark
    })).data
    ElMessage.success(`已接受改期并确认：承诺 ${r.promiseDate}`)
    acceptDlg.visible = false
    load()
  } finally {
    acceptDlg.saving = false
  }
}

const negDlg = reactive({ visible: false, saving: false, poId: '', note: '' })
function openNegotiate(row) { Object.assign(negDlg, { visible: true, poId: row.id, note: '' }) }
async function submitNegotiate() {
  if (!negDlg.note) { ElMessage.warning('请填写协商说明'); return }
  negDlg.saving = true
  try {
    await negotiatePoApi(negDlg.poId, { note: negDlg.note })
    ElMessage.success('协商已发起并留痕（时间线可见）')
    negDlg.visible = false
    delete timelineCache.value[negDlg.poId]
    load()
  } finally {
    negDlg.saving = false
  }
}

async function doRemind(row) {
  await remindPoApi(row.id)
  ElMessage.success('催办已补发')
  delete timelineCache.value[row.id]
  load()
}

// ---------- 工单解锁（L5） ----------
const resolveDlg = reactive({
  visible: false, saving: false, id: '', ticketNo: '', supplierName: '', lockPoNos: '', reason: ''
})
function openResolve(row) {
  Object.assign(resolveDlg, {
    visible: true, id: row.id, ticketNo: row.ticketNo,
    supplierName: row.supplierName, lockPoNos: row.lockPoNos, reason: ''
  })
}
async function submitResolve() {
  if (!resolveDlg.reason.trim()) { ElMessage.warning('解锁原因必填（L5 留痕）'); return }
  resolveDlg.saving = true
  try {
    const r = (await resolvePoTicketApi(resolveDlg.id, resolveDlg.reason.trim())).data
    ElMessage.success(`工单 ${r.ticketNo} 已解锁（DONE）`)
    resolveDlg.visible = false
    loadTickets()
    load()
  } finally {
    resolveDlg.saving = false
  }
}

onMounted(async () => {
  await load()
  loadTickets()
  if (route.query.poId) {
    const row = rows.value.find(r => r.id === route.query.poId)
    if (row) loadTimeline(row)
  }
})
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
.hint { color: #909399; font-size: 12px; line-height: 1.7; }
.overdue { color: #F56C6C; font-weight: 600; }
.tl-box { padding: 4px 16px; }
</style>
