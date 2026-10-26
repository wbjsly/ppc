<template>
  <div>
    <el-page-header content="报工录入（5.7.1）— 扫/选派工单 → 开工 → 投料 → 产出（批量/逐件）→ 工时 → 提交；首件/IPQC 未决将被阻断"
                    style="margin-bottom: 8px" />

    <!-- 扫码输入（D11 输入源无关：扫码枪键盘模拟走同一通道） -->
    <div class="toolbar">
      <el-input ref="scanRef" v-model="scanBuf" placeholder="扫码区：扫派工单号定位（扫码枪/手工均可）"
                clearable style="width: 320px" size="small" @keyup.enter="onScan" />
      <el-button size="small" type="primary" @click="onScan">定位</el-button>
      <el-input v-model="dispatchKeyword" placeholder="或按单号/工单号搜索" clearable style="width: 180px"
                size="small" @keyup.enter="loadDispatches" @clear="loadDispatches" />
      <el-button size="small" @click="loadDispatches">查询</el-button>
      <span class="tip">录入通道输入源无关：手工点击与扫码枪输入走同一累加/填充逻辑（design D11）</span>
    </div>

    <!-- 派工单选择（开工准入：终态阻断/超期黄条/人单提示） -->
    <el-table :data="dispatches" v-loading="dLoading" border stripe size="small" max-height="200"
              highlight-current-row @current-change="onPick">
      <el-table-column prop="dispatchNo" label="派工单号" width="160" />
      <el-table-column prop="moNo" label="工单号" width="150" />
      <el-table-column prop="opSeq" label="序" width="50" align="center" />
      <el-table-column prop="opName" label="工序" width="120" show-overflow-tooltip />
      <el-table-column prop="wcCode" label="工作中心" width="120" />
      <el-table-column label="计划开工" width="110" align="center">
        <template #default="{ row }">
          <span :style="row.planStart && row.planStart < today ? 'color:#E6A23C' : ''">
            {{ row.planStart || '-' }}{{ row.planStart && row.planStart < today ? '（超期）' : '' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'RELEASED' ? 'success' : 'info'">
            {{ row.status === 'RELEASED' ? '已下发' : row.status }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!dLoading && dispatches.length === 0" description="无可用派工单"
              :image-size="50" />

    <!-- 作业区 -->
    <el-card v-if="rpt" shadow="never" style="margin-top: 12px">
      <template #header>
        <div class="card-head">
          <strong>{{ rpt.rptNo }}</strong>
          <el-tag size="small" :type="rpt.status === 'OPEN' ? 'warning' : 'success'">
            {{ rpt.status === 'OPEN' ? '进行中' : rpt.status === 'CONFIRMED' ? '已确认' : '已冲销' }}
          </el-tag>
          <span class="tip">{{ rpt.moNo }} / 工序{{ rpt.opSeq }} {{ rpt.opName }} / {{ rpt.wcCode }}</span>
          <span v-if="rpt.proxyFlag === '1'" class="tip" style="color:#E6A23C">
            代报（原派工人 {{ rpt.dispatchOwner }}）
          </span>
          <span v-if="rpt.overdueFlag === '1'" class="tip" style="color:#E6A23C">超期开工</span>
        </div>
      </template>

      <el-tabs v-model="tab">
        <!-- 投料 -->
        <el-tab-pane label="投料" name="feed">
          <div class="toolbar">
            <el-input v-model="feedItem" placeholder="物料编码（扫码/手输）" style="width: 160px" size="small" />
            <el-input v-model="feedBatch" placeholder="批次（可空）" style="width: 130px" size="small" />
            <el-input v-model.number="feedQty" placeholder="数量" style="width: 90px" size="small" />
            <el-button size="small" type="primary" :disabled="rpt.status !== 'OPEN'" @click="doFeed">
              投料（即扣库存）
            </el-button>
            <span class="tip">保存即扣 AVAILABLE_QTY；不足 422 护栏；投料缺记录不阻断提交（完工倒冲补足 D13）</span>
          </div>
          <el-table :data="feeds" border stripe size="small">
            <el-table-column prop="itemCode" label="物料" width="140" />
            <el-table-column prop="batchCode" label="批次" width="130">
              <template #default="{ row }">{{ row.batchCode || '-' }}</template>
            </el-table-column>
            <el-table-column prop="qty" label="数量" width="90" align="right" />
            <el-table-column label="类型" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.flowType === 'BFLUSH' ? 'info' : 'success'">
                  {{ row.flowType === 'BFLUSH' ? '倒冲补投' : '手工' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 产出（批量/逐件） -->
        <el-tab-pane label="产出" name="output">
          <div class="toolbar">
            <el-radio-group v-model="mode" size="small" :disabled="rpt.status !== 'OPEN'">
              <el-radio-button value="BATCH">批量</el-radio-button>
              <el-radio-button value="PIECE">逐件</el-radio-button>
            </el-radio-group>
            <template v-if="mode === 'BATCH'">
              <el-input v-model.number="batchGood" placeholder="合格数" style="width: 90px" size="small" />
              <el-input v-model.number="batchNg" placeholder="不合格数" style="width: 90px" size="small" />
              <el-button size="small" type="primary" :disabled="rpt.status !== 'OPEN'" @click="applyBatch">
                填入
              </el-button>
            </template>
            <template v-else>
              <el-button size="small" type="success" :disabled="rpt.status !== 'OPEN'" @click="pieceGood++">
                合格 +1（{{ pieceGood }}）
              </el-button>
              <el-button size="small" type="danger" :disabled="rpt.status !== 'OPEN'" @click="pieceNg++">
                不合格 +1（{{ pieceNg }}）
              </el-button>
              <el-button size="small" :disabled="rpt.status !== 'OPEN' || (pieceGood + pieceNg) === 0"
                        @click="pieceGood = 0; pieceNg = 0">
                重置
              </el-button>
            </template>
            <el-select v-model="ngDispose" placeholder="不合格处置" size="small" style="width: 130px"
                       :disabled="rpt.status !== 'OPEN' || curNg === 0">
              <el-option label="报废（小额域内）" value="SCRAP" />
              <el-option label="返工（必建 NCR）" value="REWORK" />
              <el-option label="让步接收（必建 NCR）" value="CONCESSION" />
            </el-select>
            <span class="tip">实时：投入 {{ curGood + curNg }} / 合格 {{ curGood }} / 良率 {{ yieldPct }}%</span>
          </div>
          <el-alert v-if="overprodHint" :title="overprodHint" :type="overprodTipType" show-icon
                    :closable="false" style="margin-bottom: 6px" />
          <el-alert v-if="rpt.overprodFlag === '1' && rpt.prodConfirmFlag !== '1'"
                    title="超产已挂起：待计划主管审批通过后方可提交"
                    type="warning" show-icon :closable="false" style="margin-bottom: 6px" />
        </el-tab-pane>

        <!-- 工时 -->
        <el-tab-pane label="工时" name="hours">
          <div class="toolbar">
            <span>准备工时</span>
            <el-input v-model.number="setupHours" style="width: 80px" size="small" />
            <span>加工工时</span>
            <el-input v-model.number="runHours" style="width: 80px" size="small" />
            <span>设备运行</span>
            <el-input v-model.number="machineHours" style="width: 80px" size="small" />
            <el-button size="small" type="primary" :disabled="rpt.status !== 'OPEN'" @click="doHours">
              保存工时
            </el-button>
            <span class="tip">偏离快照标准工时 &gt; WORKHOUR_DEVIATION_ALERT 生成主管待办（FR-4.5-5-6）</span>
          </div>
          <el-alert v-if="rpt.hourAlertFlag === '1' && !rpt.hourConfirmBy"
                    title="工时异常待主管确认后方可提交（待办已推送）"
                    type="warning" show-icon :closable="false" />
        </el-tab-pane>
      </el-tabs>

      <div class="toolbar" style="margin-top: 8px">
        <el-button type="primary" :loading="submitting" :disabled="rpt.status !== 'OPEN'"
                   @click="doConfirm">
          提交报工
        </el-button>
        <el-button :disabled="rpt.status !== 'OPEN'" @click="doDiscard">放弃草稿</el-button>
        <span class="tip">提交后只读（BR-4.5-25 仅可冲销）；首件/IPQC/超产/工时/冻结前置校验</span>
      </div>
    </el-card>

    <!-- 当班记录 -->
    <div class="toolbar" style="margin-top: 16px">
      <strong>我的报工记录</strong>
      <el-button size="small" @click="loadMine">刷新</el-button>
    </div>
    <el-table :data="mine" border stripe size="small" max-height="220">
      <el-table-column prop="rptNo" label="报工单号" width="150" />
      <el-table-column prop="moNo" label="工单" width="140" />
      <el-table-column prop="opSeq" label="序" width="50" align="center" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'CONFIRMED' ? 'success'
            : row.status === 'REVERSED' ? 'info' : 'warning'">
            {{ row.status === 'CONFIRMED' ? '已确认' : row.status === 'REVERSED' ? '已冲销' : '进行中' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createDate" label="时间" width="160" />
    </el-table>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  openReportingApi, confirmReportingApi, discardReportingApi,
  addFeedApi, getFeedsApi, saveOutputApi, saveHoursApi, getReportingListApi,
  getReportingDetailApi, getOutputApi
} from '@/api/mrp/reporting'
import { getDispatchListApi } from '@/api/mrp/dispatch'

const today = new Date().toISOString().slice(0, 10)

// 扫码通道（D11：扫码枪=键盘模拟，enter 触发与手工定位同一逻辑）
const scanBuf = ref('')
const scanRef = ref(null)
const dispatchKeyword = ref('')
const dispatches = ref([])
const dLoading = ref(false)

const rpt = ref(null)
const tab = ref('feed')
const mode = ref('BATCH')
// 模式切换双向同步累计值（spec「切换模式不丢数」——批量已填 8/2 切逐件从 8/2 起算）
// 注意：回调触发时 mode 已是新值，须显式按 oldMode 取源（computed 依赖 mode 会读到新值）
watch(mode, (newMode, oldMode) => {
  const srcGood = oldMode === 'BATCH' ? (Number(batchGood.value) || 0) : pieceGood.value
  const srcNg = oldMode === 'BATCH' ? (Number(batchNg.value) || 0) : pieceNg.value
  if (newMode === 'PIECE') {
    pieceGood.value = srcGood
    pieceNg.value = srcNg
  } else {
    batchGood.value = srcGood
    batchNg.value = srcNg
  }
})
const feeds = ref([])
const submitting = ref(false)
const mine = ref([])

// 投料表单
const feedItem = ref('')
const feedBatch = ref('')
const feedQty = ref(null)

// 产出
const batchGood = ref(null)
const batchNg = ref(null)
const pieceGood = ref(0)
const pieceNg = ref(0)
const good = ref(0)
const ng = ref(0)
const ngDispose = ref('SCRAP')
const overprodHint = ref('')
const overprodTipType = ref('warning')

// 工时
const setupHours = ref(null)
const runHours = ref(null)
const machineHours = ref(null)

const yieldPct = computed(() => {
  const total = curGood.value + curNg.value
  return total > 0 ? ((curGood.value / total) * 100).toFixed(1) : '-'
})
// 当前模式下的累计值（批量读 batch 变量、逐件读 piece 变量——实时统计随模式取数）
const curGood = computed(() => (mode.value === 'BATCH'
  ? Number(batchGood.value) || 0 : pieceGood.value))
const curNg = computed(() => (mode.value === 'BATCH'
  ? Number(batchNg.value) || 0 : pieceNg.value))

async function loadDispatches() {
  dLoading.value = true
  try {
    const res = await getDispatchListApi('RELEASED', dispatchKeyword.value)
    dispatches.value = (res.data || []).filter(d => d.status === 'RELEASED')
  } finally {
    dLoading.value = false
  }
}

function onScan() {
  const code = (scanBuf.value || '').trim()
  if (!code) return
  dispatchKeyword.value = code
  loadDispatches()
  // 扫码命中唯一行时自动选中开工
  if (dispatches.value.length === 1) {
    onPick(dispatches.value[0])
  }
  scanBuf.value = ''
}

async function onPick(row) {
  if (!row) return
  // 防重：已有进行中的报工单 → 复用不重复开（工位机重复扫码场景）
  if (rpt.value && rpt.value.status === 'OPEN') {
    if (rpt.value.dispatchId === row.id) {
      ElMessage.info('该派工单已有进行中的报工单：' + rpt.value.rptNo)
      return
    }
    ElMessage.warning('已有进行中的报工单 ' + rpt.value.rptNo + '，请先提交或放弃')
    return
  }
  try {
    const res = await openReportingApi(row.id, mode.value)
    rpt.value = res.data
    resetForms()
    await loadFeeds()
    ElMessage.success('已开工：' + rpt.value.rptNo)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message || '开工失败')
  }
}

function resetForms() {
  good.value = 0; ng.value = 0
  batchGood.value = null; batchNg.value = null
  pieceGood.value = 0; pieceNg.value = 0
  overprodHint.value = ''
  setupHours.value = null; runHours.value = null; machineHours.value = null
}

async function loadFeeds() {
  if (!rpt.value) return
  const res = await getFeedsApi(rpt.value.id)
  feeds.value = res.data || []
}

async function doFeed() {
  try {
    await addFeedApi({
      rptId: rpt.value.id, itemCode: feedItem.value,
      batchCode: feedBatch.value || null, qty: feedQty.value
    })
    ElMessage.success('投料成功（库存即扣）')
    feedItem.value = ''; feedBatch.value = ''; feedQty.value = null
    await loadFeeds()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '投料失败')
  }
}

function applyBatch() {
  good.value = Number(batchGood.value) || 0
  ng.value = Number(batchNg.value) || 0
  evalOverprod()
}

async function saveOutput(ack) {
  try {
    const res = await saveOutputApi({
      rptId: rpt.value.id, goodQty: good.value, ngQty: ng.value,
      ngDispose: ng.value > 0 ? ngDispose.value : null, overprodAck: ack
    })
    overprodHint.value = ''
    return res.data
  } catch (e) {
    const msg = e?.response?.data?.message || e.message
    if (msg && msg.includes('二次确认')) {
      // 阈值内超产：弹确认后带 ack 重提
      overprodHint.value = msg
      overprodTipType.value = 'warning'
      return 'NEED_ACK'
    }
    if (msg && msg.includes('超产')) {
      overprodHint.value = msg
      overprodTipType.value = 'error'
      ElMessage.warning(msg)
      return 'HUNG'
    }
    ElMessage.error(msg || '产出保存失败')
    throw e
  }
}

function evalOverprod() {
  overprodHint.value = ''
}

async function onPieceSave() {
  good.value = pieceGood.value
  ng.value = pieceNg.value
  return saveOutput(false)
}

// 逐件累加实时落库太重——累计在本地，提交前统一保存（同一数据结构，spec「切换模式不丢数」）
async function ensureOutputSaved() {
  good.value = mode.value === 'BATCH' ? (Number(batchGood.value) || 0) : pieceGood.value
  ng.value = mode.value === 'BATCH' ? (Number(batchNg.value) || 0) : pieceNg.value
  if (good.value === 0 && ng.value === 0) return 'EMPTY'
  const r = await saveOutput(false)
  if (r === 'NEED_ACK') {
    const ok = window.confirm(overprodHint.value + '\n确认超产并提交？')
    if (!ok) return 'ABORT'
    await saveOutput(true)
  }
  return r
}

async function doHours() {
  try {
    rpt.value = (await saveHoursApi({
      rptId: rpt.value.id, setupHours: setupHours.value,
      runHours: runHours.value, machineHours: machineHours.value
    })).data
    ElMessage.success('工时已保存')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '工时保存失败')
  }
}

async function doConfirm() {
  submitting.value = true
  try {
    const out = await ensureOutputSaved()
    if (out === 'ABORT') return
    rpt.value = (await confirmReportingApi(rpt.value.id)).data
    ElMessage.success('报工已提交（工序已完成）')
    await loadMine()
  } catch (e) {
    // 前置校验失败（首件/IPQC/超产/工时/冻结）原样提示
    ElMessage.error(e?.response?.data?.message || '提交失败')
  } finally {
    submitting.value = false
  }
}

async function doDiscard() {
  try {
    await discardReportingApi(rpt.value.id)
    ElMessage.success('草稿已放弃')
    rpt.value = null
    await loadMine()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '放弃失败')
  }
}

async function loadMine() {
  const res = await getReportingListApi(null, null, null)
  mine.value = (res.data || []).slice(0, 20)
  // 刷新恢复：存在 OPEN 报工单则自动续接作业区（工位机刷新场景）
  if (!rpt.value) {
    const open = (res.data || []).find(r => r.status === 'OPEN')
    if (open) {
      const d = await getReportingDetailApi(open.id)
      rpt.value = d.data
      await loadFeeds()
      // 产出回填（覆盖式保存的既有值）
      const o = await getOutputApi(open.id)
      if (o.data) {
        pieceGood.value = Number(o.data.goodQty) || 0
        pieceNg.value = Number(o.data.ngQty) || 0
        batchGood.value = pieceGood.value
        batchNg.value = pieceNg.value
        if (o.data.ngDispose) ngDispose.value = o.data.ngDispose
      }
    }
  }
}

onMounted(() => {
  loadDispatches()
  loadMine()
  // 扫码枪自动聚焦（工位机场景）
  scanRef.value?.focus?.()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 8px; }
.tip { color: #909399; font-size: 12px; }
.card-head { display: flex; gap: 10px; align-items: center; }
</style>
