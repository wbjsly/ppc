<template>
  <div class="bin-assignment-page">
    <el-tabs v-model="activeTab">
      <!-- ============ Tab A：待分配（过账前，SOP 1.4） ============ -->
      <el-tab-pane label="待分配 · 过账前" name="pending">
        <div class="toolbar">
          <el-input v-model="pendingKeyword" placeholder="单号 / 物料 / 批次 / PO" clearable
                    style="width: 240px;" @keyup.enter="loadPending" @clear="loadPending" />
          <el-button type="primary" plain @click="loadPending">查询</el-button>
          <el-button type="success" :loading="bulkLoading" @click="recommendAll">一键推荐全部并确认</el-button>
          <span class="tip">强前置：GR 行须有 CONFIRMED 分配方可过账（C-4.4-11）</span>
        </div>
        <el-table :data="pendingRows" v-loading="pendingLoading" border stripe
                  :row-class-name="rowClass" @row-dblclick="openCandidates">
          <el-table-column prop="GR_NO" label="收货单号" width="130" />
          <el-table-column prop="LINE_NO" label="行" width="45" align="center" />
          <el-table-column prop="PO_NO" label="来源 PO" width="120" show-overflow-tooltip />
          <el-table-column prop="ITEM_CODE" label="物料" width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.ITEM_CODE }} {{ row.ITEM_NAME }}</template>
          </el-table-column>
          <el-table-column prop="BATCH_NO" label="批次" width="120" />
          <el-table-column prop="QTY" label="核销量" width="80" align="right" />
          <el-table-column prop="QC_STATUS" label="检验" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="qcTag(row.QC_STATUS)">{{ qcText(row.QC_STATUS) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="分配状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="allocTag(row.allocationStatus)">
                {{ allocText(row.allocationStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="binCode" label="分配仓位" width="130">
            <template #default="{ row }">
              <span v-if="row.binCode">{{ row.binCode }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="openCandidates(row)">查看建议</el-button>
              <el-button size="small" type="primary" plain
                         :disabled="row.allocationStatus === 'CONFIRMED'"
                         @click="openAssign(row)">指定/改派</el-button>
              <el-button size="small" type="success"
                         :disabled="row.allocationStatus !== 'RECOMMENDED'"
                         @click="confirmRow(row)">确认分配</el-button>
              <el-button size="small" @click="showHistory(row.GR_NO)">历史</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂无待分配行（须已登记、检验放行且核销量>0）" />
          </template>
        </el-table>
        <el-pagination style="margin-top: 10px;" layout="total, prev, pager, next"
                       :total="pendingTotal" :page-size="pendingSize"
                       v-model:current-page="pendingPage" @current-change="loadPending" />
      </el-tab-pane>

      <!-- ============ Tab B：未分配上架 ============ -->
      <el-tab-pane label="未分配 · 上架" name="unassigned">
        <div class="toolbar">
          <el-input v-model="unassignedKeyword" placeholder="物料 / 批次" clearable
                    style="width: 240px;" @keyup.enter="loadUnassigned" @clear="loadUnassigned" />
          <el-button type="primary" plain @click="loadUnassigned">查询</el-button>
          <span class="tip">BIN=空 的库存行（存量回填 / 未前置分配链路），推荐 → 确认上架</span>
        </div>
        <el-table :data="unassignedRows" v-loading="unassignedLoading" border stripe>
          <el-table-column prop="warehouseCode" label="仓库" width="100" />
          <el-table-column prop="itemCode" label="物料" width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.itemCode }} {{ row.itemName }}</template>
          </el-table-column>
          <el-table-column prop="batchNo" label="批次" width="130" />
          <el-table-column prop="qty" label="在手" width="80" align="right" />
          <el-table-column prop="availableQty" label="可用" width="80" align="right" />
          <el-table-column prop="qcQty" label="QC 锁定" width="90" align="right" />
          <el-table-column prop="inboundDate" label="入库日期" width="110" />
          <el-table-column label="上架状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="allocTag(row.allocationStatus)">
                {{ allocText(row.allocationStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="目标仓位" width="140">
            <template #default="{ row }">
              <span v-if="row.binCode">{{ row.binCode }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="260" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" @click="recommendPutaway(row)">推荐上架</el-button>
              <el-button size="small" type="success"
                         :disabled="row.allocationStatus !== 'RECOMMENDED'"
                         @click="confirmPutaway(row)">确认上架</el-button>
              <el-button size="small" @click="showHistory(row.id)">历史</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="没有未分配（BIN=空）的库存行" />
          </template>
        </el-table>
        <el-pagination style="margin-top: 10px;" layout="total, prev, pager, next"
                       :total="unassignedTotal" :page-size="unassignedSize"
                       v-model:current-page="unassignedPage" @current-change="loadUnassigned" />
      </el-tab-pane>
    </el-tabs>

    <!-- ============ 候选建议弹窗（Top3 + 理由 + 合规/容量徽标） ============ -->
    <el-dialog v-model="candVisible" :title="candTitle" width="760px">
      <el-table :data="candRows" border size="small" v-loading="candLoading">
        <el-table-column type="index" label="#" width="45" />
        <el-table-column prop="binCode" label="仓位" width="140" />
        <el-table-column prop="zoneName" label="区域" width="110" />
        <el-table-column label="入选理由" min-width="200">
          <template #default="{ row }">
            <el-tag v-for="r in row.reasons || []" :key="r" size="small" type="info"
                    style="margin-right: 4px;">{{ r }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="徽标" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.sameItem" size="small" type="success">同物料</el-tag>
            <el-tag size="small" :type="row.capacityLeft == null ? 'info' : 'warning'">
              {{ row.capacityLeft == null ? '容量不限' : `余 ${row.capacityLeft} 托` }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="pickCandidate(row)">选用</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="无可用仓位（挂起）——请检查仓位属性/托位容量或扩仓位后重新推荐" />
        </template>
      </el-table>
      <div v-if="candContext.mode === 'assign'" class="assign-bar">
        <span>或手工指定：</span>
        <el-select v-model="manualBin" filterable allow-create default-first-option
                   placeholder="仅可输入合法仓位（服务端合规复检，违规 422）" style="width: 260px;">
          <el-option v-for="c in candRows" :key="c.binCode" :label="c.binCode" :value="c.binCode" />
        </el-select>
        <el-button type="primary" :disabled="!manualBin" @click="submitAssign">确认指定</el-button>
      </div>
      <template #footer>
        <el-button @click="candVisible = false">关闭</el-button>
        <el-button v-if="candContext.mode !== 'assign'" type="success"
                   :disabled="!candRows.length" :loading="acting" @click="recommendFromDialog">
          推荐首选并落台账
        </el-button>
      </template>
    </el-dialog>

    <!-- ============ 台账历史弹窗 ============ -->
    <el-dialog v-model="historyVisible" title="分配台账历史（含被替代记录）" width="820px">
      <el-table :data="historyRows" border size="small" v-loading="historyLoading">
        <el-table-column prop="sourceType" label="类型" width="70" />
        <el-table-column prop="sourceDocNo" label="来源" width="130" show-overflow-tooltip />
        <el-table-column prop="sourceLineNo" label="行" width="45" align="center" />
        <el-table-column prop="itemCode" label="物料" width="120" show-overflow-tooltip />
        <el-table-column prop="binCode" label="仓位" width="130">
          <template #default="{ row }">{{ row.binCode || '（未分配）' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="allocTag(row.status)">{{ allocText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="被替代" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.supersededBy" size="small" type="info">已替代</el-tag>
            <span v-else class="muted">生效中</span>
          </template>
        </el-table-column>
        <el-table-column prop="createBy" label="操作人" width="90" />
        <el-table-column prop="createDate" label="时间" width="155" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getPendingGrLinesApi, getCandidatesApi, recommendApi, recommendAllApi,
  confirmAssignApi, assignApi, getUnassignedApi, getPutawayCandidatesApi,
  recommendPutawayApi, getHistoryApi
} from '@/api/inv/bin-assignment'

const activeTab = ref('pending')

// ---- Tab A ----
const pendingRows = ref([])
const pendingTotal = ref(0)
const pendingPage = ref(1)
const pendingSize = 20
const pendingKeyword = ref('')
const pendingLoading = ref(false)
const bulkLoading = ref(false)

async function loadPending() {
  pendingLoading.value = true
  try {
    const res = await getPendingGrLinesApi({
      keyword: pendingKeyword.value, current: pendingPage.value, size: pendingSize
    })
    pendingRows.value = res.data.rows || []
    pendingTotal.value = Number(res.data.total) || 0
  } finally {
    pendingLoading.value = false
  }
}

function rowClass({ row }) {
  return row.suspended ? 'row-suspended' : ''
}

async function recommendAll() {
  try {
    await ElMessageBox.confirm(
      '将对当前全部待分配行执行「推荐→确认」，无可用仓位的行将挂起并通知仓库主管。继续？',
      '一键推荐全部并确认', { confirmButtonText: '执行', cancelButtonText: '取消' })
  } catch { return }
  bulkLoading.value = true
  try {
    const res = await recommendAllApi()
    ElMessage.success(`已确认 ${res.data.confirmed} 行，挂起 ${res.data.suspended} 行`)
    await loadPending()
  } finally {
    bulkLoading.value = false
  }
}

async function confirmRow(row) {
  if (!row.recordId) return
  try {
    await confirmAssignApi(row.recordId)
    ElMessage.success('分配已确认（可过账）')
    await loadPending()
  } finally { /* 错误由 request 拦截器提示 */ }
}

// ---- 候选弹窗 ----
const candVisible = ref(false)
const candLoading = ref(false)
const candRows = ref([])
const candContext = ref({ mode: 'recommend', grNo: '', lineNo: null, stockId: '' })
const manualBin = ref('')
const acting = ref(false)

const candTitle = computed(() => {
  const c = candContext.value
  if (c.mode === 'putaway') return `上架建议 Top3（${c.stockId ? '未分配行' : ''}）`
  if (c.mode === 'assign') return `指定/改派 · ${c.grNo} 行 ${c.lineNo}（仅合法候选）`
  return `仓位分配建议 Top3 · ${c.grNo} 行 ${c.lineNo}`
})

async function loadCandidates() {
  candLoading.value = true
  try {
    const c = candContext.value
    const res = c.mode === 'putaway'
      ? await getPutawayCandidatesApi(c.stockId)
      : await getCandidatesApi(c.grNo, c.lineNo)
    candRows.value = res.data || []
  } finally {
    candLoading.value = false
  }
}

function openCandidates(row) {
  candContext.value = { mode: 'recommend', grNo: row.GR_NO, lineNo: row.LINE_NO, stockId: '' }
  manualBin.value = ''
  candVisible.value = true
  loadCandidates()
}

function openAssign(row) {
  candContext.value = { mode: 'assign', grNo: row.GR_NO, lineNo: row.LINE_NO, stockId: '' }
  manualBin.value = ''
  candVisible.value = true
  loadCandidates()
}

async function recommendFromDialog() {
  const c = candContext.value
  acting.value = true
  try {
    const res = c.mode === 'putaway'
      ? await recommendPutawayApi(c.stockId)
      : await recommendApi(c.grNo, c.lineNo)
    if (res.data.suspended) {
      ElMessage.warning('无可用仓位，已挂起并通知仓库主管')
    } else {
      ElMessage.success(`已推荐：${res.data.record.binCode}（待确认）`)
    }
    candVisible.value = false
    if (activeTab.value === 'pending') await loadPending()
    else await loadUnassigned()
  } finally {
    acting.value = false
  }
}

function pickCandidate(row) {
  if (candContext.value.mode !== 'assign') return
  manualBin.value = row.binCode
}

async function submitAssign() {
  const c = candContext.value
  try {
    await assignApi(c.grNo, c.lineNo, manualBin.value)
    ElMessage.success(`已确认分配：${manualBin.value}`)
    candVisible.value = false
    await loadPending()
  } catch (e) {
    // 违规 422 由拦截器提示（含合法仓位清单）
    ElMessage.error(e?.message || '指定失败')
  }
}

async function recommendPutaway(row) {
  candContext.value = { mode: 'putaway', grNo: '', lineNo: null, stockId: row.id }
  manualBin.value = ''
  candVisible.value = true
  await loadCandidates()
}

async function confirmPutaway(row) {
  if (!row.recordId) return
  try {
    await confirmAssignApi(row.recordId)
    ElMessage.success(`上架完成：${row.binCode || ''}（同位行自动合并）`)
    await loadUnassigned()
  } finally { /* 错误由 request 拦截器提示 */ }
}

// ---- 历史 ----
const historyVisible = ref(false)
const historyLoading = ref(false)
const historyRows = ref([])

async function showHistory(sourceDocNo) {
  historyVisible.value = true
  historyLoading.value = true
  try {
    const res = await getHistoryApi(sourceDocNo)
    historyRows.value = res.data || []
  } finally {
    historyLoading.value = false
  }
}

// ---- Tab B ----
const unassignedRows = ref([])
const unassignedTotal = ref(0)
const unassignedPage = ref(1)
const unassignedSize = 20
const unassignedKeyword = ref('')
const unassignedLoading = ref(false)

async function loadUnassigned() {
  unassignedLoading.value = true
  try {
    const res = await getUnassignedApi({
      keyword: unassignedKeyword.value, current: unassignedPage.value, size: unassignedSize
    })
    unassignedRows.value = res.data.rows || []
    unassignedTotal.value = Number(res.data.total) || 0
  } finally {
    unassignedLoading.value = false
  }
}

// ---- 展示辅助 ----
function qcTag(v) {
  return v === 'RELEASED' || v === 'SKIPPED' ? 'success' : v === 'CONCESSION' ? 'warning' : 'info'
}
function qcText(v) {
  return { RELEASED: '已放行', SKIPPED: '免检', CONCESSION: '让步', PENDING: '待检', NONE: '未检验' }[v] || v
}
function allocTag(v) {
  return { CONFIRMED: 'success', RECOMMENDED: 'primary', SUSPENDED: 'danger', PENDING: 'info' }[v] || 'info'
}
function allocText(v) {
  return { CONFIRMED: '已确认', RECOMMENDED: '已推荐', SUSPENDED: '无可用位', PENDING: '待分配' }[v] || v
}

onMounted(() => {
  loadPending()
  loadUnassigned()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; }
.tip { color: #909399; font-size: 12px; }
.muted { color: #c0c4cc; }
.assign-bar { display: flex; gap: 10px; align-items: center; margin-top: 12px; }
:deep(.row-suspended) { background: #fef0f0 !important; }
</style>
