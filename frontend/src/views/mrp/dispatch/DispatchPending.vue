<template>
  <div>
    <el-page-header content="智能派工（5.6.1）— 待派列表按优先级排序，勾选生成工序级派工单；齐套<100% 阻断可走缺料派工申请（L2 计划主管签）"
                    style="margin-bottom: 8px" />

    <!-- 待派工列表 -->
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="工单号（模糊）" clearable style="width: 180px"
                size="small" @keyup.enter="load" @clear="load" />
      <el-button size="small" type="primary" @click="load">查询</el-button>
      <el-button size="small" type="success" :disabled="selected.length === 0" @click="doGenerate(selected)">
        生成派工单（{{ selected.length }}）
      </el-button>
      <span class="tip">勾选多行=批量（工单级操作语义 D7）；wcCode 空 = 工序快照默认工作中心；齐套不足将被阻断</span>
    </div>

    <el-table :data="pendingRows" v-loading="loading" border stripe size="small"
              row-key="moId-opSeq" max-height="320" @selection-change="onSelect">
      <el-table-column type="selection" width="40" :selectable="() => true" />
      <el-table-column prop="moNo" label="工单号" width="150" />
      <el-table-column prop="productCode" label="产成品" width="130" show-overflow-tooltip />
      <el-table-column prop="opSeq" label="序" width="50" align="center" />
      <el-table-column prop="opName" label="工序" width="130" show-overflow-tooltip />
      <el-table-column label="优先级" width="80" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.priority <= 1 ? 'danger' : row.priority === 2 ? 'warning' : 'info'">
            {{ row.priority }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="计划开工" width="110" align="center">
        <template #default="{ row }">{{ row.planStartDate || '-' }}</template>
      </el-table-column>
      <el-table-column label="缺料" width="90" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.shortageFlag === '1'" type="danger" size="small">缺料待料</el-tag>
          <el-tag v-else type="success" size="small">齐套</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="doGenerate([row])">生成</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && pendingRows.length === 0" description="无待派工序（全部已派或无 Released 工单）"
              :image-size="50" />

    <!-- 已生成派工单 -->
    <div class="toolbar" style="margin-top: 16px">
      <strong>已生成派工单</strong>
      <el-select v-model="statusFilter" size="small" clearable placeholder="状态" style="width: 150px"
                 @change="loadDispatches">
        <el-option label="待确认" value="PENDING_CONFIRM" />
        <el-option label="已确认（已下发）" value="RELEASED" />
        <el-option label="已取消" value="CANCELLED" />
      </el-select>
      <el-input v-model="dispatchKeyword" placeholder="单号/工单/WC" clearable style="width: 160px"
                size="small" @keyup.enter="loadDispatches" @clear="loadDispatches" />
      <el-button size="small" type="primary" @click="loadDispatches">查询</el-button>
      <el-button size="small" :disabled="!rescheduleWc" @click="doReschedule">
        重新排程{{ rescheduleWc ? `（${rescheduleWc}）` : '' }}
      </el-button>
      <span class="tip">点选下方派工单行选择重排工作中心；已确认占用固定，低优先级后移（FR-4.5-4-7）</span>
    </div>

    <el-table :data="dispatches" v-loading="dLoading" border stripe size="small" max-height="300"
              highlight-current-row @current-change="onPickDispatch">
      <el-table-column prop="dispatchNo" label="派工单号" width="160" />
      <el-table-column prop="moNo" label="工单号" width="150" />
      <el-table-column prop="opSeq" label="序" width="50" align="center" />
      <el-table-column prop="wcCode" label="工作中心" width="130" />
      <el-table-column label="计划时段" width="180" align="center">
        <template #default="{ row }">{{ row.planStart }} ~ {{ row.planEnd }}</template>
      </el-table-column>
      <el-table-column prop="durationHours" label="工时" width="70" align="right" />
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'RELEASED' ? 'success'
            : row.status === 'PENDING_CONFIRM' ? 'warning' : 'info'">
            {{ statusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="标记" width="150">
        <template #default="{ row }">
          <el-tag v-if="row.riskFlag === '1'" type="warning" size="small">交期风险</el-tag>
          <el-tag v-if="row.shortageFirstFlag === '1'" type="danger" size="small">缺料先行</el-tag>
          <el-tag v-if="row.outsourceFlag === '1'" type="info" size="small">外协</el-tag>
          <span v-if="row.riskFlag !== '1' && row.shortageFirstFlag !== '1' && row.outsourceFlag !== '1'">-</span>
        </template>
      </el-table-column>
      <el-table-column label="外协回联" width="130" align="center">
        <template #default="{ row }">
          <span v-if="row.outsourceFlag !== '1'">-</span>
          <el-tag v-else-if="row.receiptFlag === '1'" type="success" size="small">已收货</el-tag>
          <el-tag v-else-if="row.poNo" type="primary" size="small">已下单</el-tag>
          <el-tag v-else-if="row.prNo" size="small">待转单</el-tag>
          <el-tag v-else type="info" size="small">未生 PR</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" align="center">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING_CONFIRM'">
            <el-button size="small" type="success" link @click="doConfirm(row)">确认</el-button>
            <el-button size="small" type="danger" link @click="doCancel(row)">取消</el-button>
          </template>
          <span v-else>-</span>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!dLoading && dispatches.length === 0" description="尚无派工单" :image-size="50" />

    <!-- 缺料阻断弹窗 -->
    <el-dialog v-model="blockVisible" title="齐套不足，禁止正常派工（C-4.5-11）" width="560px">
      <el-alert type="error" :closable="false" style="margin-bottom: 10px">
        <template #title>{{ blockMessage }}</template>
      </el-alert>
      <p class="tip">
        处置方式：① 等待到货后重试（5.5 调度器自动恢复齐套）；② 确需先行开工 → 提交缺料派工申请，
        由计划主管审批（C-4.5-12，通过后生成「缺料先行」派工单）。
      </p>
      <template #footer>
        <el-button @click="blockVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="!blockTarget" @click="applyShortage">缺料派工申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onActivated } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getPendingApi, getDispatchListApi, generateDispatchApi,
  confirmDispatchApi, cancelDispatchApi, rescheduleApi, applyShortageApi
} from '@/api/mrp/dispatch'

const loading = ref(false)
const dLoading = ref(false)
const pendingRows = ref([])
const dispatches = ref([])
const keyword = ref('')
const statusFilter = ref('')
const dispatchKeyword = ref('')
const selected = ref([])
const rescheduleWc = ref('')
const blockVisible = ref(false)
const blockMessage = ref('')
const blockTarget = ref(null) // 被阻断的行（缺料申请用）

async function load() {
  loading.value = true
  try {
    const res = await getPendingApi(keyword.value || undefined)
    pendingRows.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function loadDispatches() {
  dLoading.value = true
  try {
    const res = await getDispatchListApi(statusFilter.value || undefined, dispatchKeyword.value || undefined)
    dispatches.value = res.data || []
  } finally {
    dLoading.value = false
  }
}

function onSelect(sel) {
  selected.value = sel
}

function onPickDispatch(row) {
  rescheduleWc.value = row ? row.wcCode : ''
}

async function doGenerate(rows) {
  const payload = rows.map(r => ({ moId: r.moId, opSeq: r.opSeq, wcCode: null }))
  try {
    const res = await generateDispatchApi(payload)
    const made = res.data || []
    ElMessage.success(`已生成 ${made.length} 张派工单（待确认）`)
    blockTarget.value = null
    await Promise.all([load(), loadDispatches()])
  } catch (e) {
    const msg = (e && (e.msg || e.message)) || '生成失败'
    if (msg.includes('齐套') || msg.includes('C-4.5-11') || msg.includes('缺料')) {
      blockMessage.value = msg
      blockTarget.value = rows.length === 1 ? rows[0] : null // 批量阻断时逐单申请（取第一行会误伤）
      blockVisible.value = true
    } else {
      ElMessage.error(msg)
    }
  }
}

async function applyShortage() {
  const t = blockTarget.value
  if (!t) return
  try {
    await applyShortageApi(t.moId, t.opSeq)
    blockVisible.value = false
    ElMessage.success('缺料派工申请已提交（计划主管审批），结果将站内通知')
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '申请失败')
  }
}

async function doConfirm(row) {
  try {
    await ElMessageBox.confirm(`确认派工单 ${row.dispatchNo}？确认后触发物料预扣。`, '确认派工', { type: 'warning' })
  } catch { return }
  try {
    await confirmDispatchApi(row.id)
    ElMessage.success('已确认（下发车间），物料预扣已生效')
    await loadDispatches()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '确认失败')
  }
}

async function doCancel(row) {
  let reason
  try {
    const r = await ElMessageBox.prompt('取消原因（必填）', `取消 ${row.dispatchNo}`, {
      inputValidator: v => (v && v.trim().length >= 2) || '至少 2 字'
    })
    reason = r.value
  } catch { return }
  try {
    await cancelDispatchApi(row.id, reason)
    ElMessage.success('已取消（工序回待派；末张取消释放预扣）')
    await Promise.all([load(), loadDispatches()])
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '取消失败')
  }
}

async function doReschedule() {
  if (!rescheduleWc.value) return
  try {
    const res = await rescheduleApi({ wcCode: rescheduleWc.value })
    ElMessage.success(`重排完成：${(res.data || []).length} 张（低优先级后移，留痕）`)
    await loadDispatches()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '重排失败')
  }
}

function statusText(s) {
  return { PENDING_CONFIRM: '待确认', RELEASED: '已确认', CANCELLED: '已取消' }[s] || s
}

onMounted(load)
onActivated(() => { load(); loadDispatches() })
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
</style>
