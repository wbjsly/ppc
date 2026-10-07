<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.5.3 订单变更"
              description="支持数量调整、交期变更与行取消；变更重跑信用检查与 ATP 试算（超限重新冻结）；已发货/已开票行以 L1 阻断，变更仅作用于未执行部分（FR-4.3-4-7 / BR-4.3-30 / C-4.3-08）。" />

    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>可变更订单（已确认 CONFIRMED）</span>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>
      <el-table :data="rows" size="small" border highlight-current-row
                @row-click="openDetail" v-loading="loading">
        <el-table-column prop="soNo" label="订单号" width="160" />
        <el-table-column prop="customerName" label="客户" min-width="140" show-overflow-tooltip />
        <el-table-column prop="totalAmount" label="金额" width="120" align="right">
          <template #default="{ row }">¥{{ Number(row.totalAmount).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'CONFIRMED' ? 'success'
              : (row.status === 'CREDIT_FREEZE' ? 'danger' : 'warning')">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createDate" label="创建时间" width="160">
          <template #default="{ row }">{{ (row.createDate || '').replace('T', ' ') }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click.stop="openDetail(row)">
              发起变更
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 变更工作台 -->
    <el-card v-if="detail" shadow="never">
      <template #header>
        <div class="card-head">
          <span>变更工作台：{{ detail.so.soNo }}（{{ detail.so.customerName }}）</span>
          <div>
            <el-button size="small" @click="detail = null">收起</el-button>
            <el-button size="small" type="primary" :loading="submitting"
                       :disabled="!changedLines.length && !reason"
                       @click="submitChange">提交变更</el-button>
          </div>
        </div>
      </template>

      <el-input v-model="reason" type="textarea" :rows="2" class="mb12"
                placeholder="变更原因（必填，如：客户追加数量 / 交期提前）" />

      <el-table :data="detail.lines" size="small" border>
        <el-table-column prop="lineNo" label="#" width="45" />
        <el-table-column prop="itemCode" label="SKU" width="150" />
        <el-table-column prop="itemName" label="名称" min-width="110" show-overflow-tooltip />
        <el-table-column label="数量" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.qty" :min="0" :precision="3" size="small"
                             controls-position="right" style="width:115px"
                             :disabled="isExecuted(row)"
                             @change="() => markChanged(row, 'qty')" />
          </template>
        </el-table-column>
        <el-table-column label="计划发货" width="140">
          <template #default="{ row }">
            <el-date-picker v-model="row.planShipDate" type="date" value-format="YYYY-MM-DD"
                            size="small" style="width:130px" :disabled="isExecuted(row)"
                            @change="() => markChanged(row, 'date')" />
          </template>
        </el-table-column>
        <el-table-column label="已发/已开票" width="120" align="center">
          <template #default="{ row }">
            <span class="executed" v-if="isExecuted(row)">
              {{ row.shippedQty || 0 }} / {{ row.invoicedQty || 0 }}
            </span>
            <span v-else>0 / 0</span>
          </template>
        </el-table-column>
        <el-table-column label="预留量" width="90" align="right">
          <template #default="{ row }">{{ row.reservedQty || 0 }}</template>
        </el-table-column>
        <el-table-column label="行状态" width="110">
          <template #default="{ row }">
            <el-tag size="small"
                    :type="row.lineStatus === 'CANCELLED' ? 'info'
                      : (isExecuted(row) ? 'success' : '')">
              {{ row.lineStatus || 'OPEN' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="86">
          <template #default="{ row }">
            <el-button size="small" type="danger" link :disabled="isExecuted(row)"
                       @click="markCancel(row)">
              取消行
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-alert type="warning" :closable="false" show-icon class="mt12"
                v-if="hasExecuted"
                title="存在已执行行（已发货/已开票）：其数量、交期与价格变更将被 L1 阻断，变更仅作用于未执行部分" />
      <div class="chg-bar">
        <span>本次变更 {{ changedLines.length }} 行</span>
        <span v-if="cancelLines.length">其中取消 {{ cancelLines.length }} 行</span>
      </div>
    </el-card>

    <!-- 变更结果 -->
    <el-dialog v-model="resultVisible" title="变更结果" width="520px">
      <template v-if="result">
        <el-alert :type="result.frozen ? 'error' : 'success'" :closable="false" class="mb12">
          <template #title>
            {{ result.frozen ? '变更后信用超限，订单已重新冻结（PREV_STATUS=' +
              (result.so?.prevStatus || 'CONFIRMED') + '）' : '变更完成，订单回到已确认状态' }}
          </template>
        </el-alert>
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="信用重跑结论">{{ result.creditRerun }}</el-descriptions-item>
          <el-descriptions-item label="当前状态">{{ result.status }}</el-descriptions-item>
        </el-descriptions>
      </template>
      <template #footer>
        <el-button type="primary" @click="resultVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getSoPageApi, getSoDetailApi, changeSoApi } from '@/api/sd/so'

const rows = ref([])
const loading = ref(false)
const detail = ref(null)
const reason = ref('')
const submitting = ref(false)
const resultVisible = ref(false)
const result = ref(null)
/** 行变更登记：id -> {qty?, date?, cancel?} */
const edits = ref({})

const changedLines = computed(() => Object.values(edits.value))
const cancelLines = computed(() =>
  Object.values(edits.value).filter(e => e.cancel))
const hasExecuted = computed(() =>
  detail.value && detail.value.lines.some(isExecuted))

function isExecuted(row) {
  return Number(row.shippedQty || 0) > 0 || Number(row.invoicedQty || 0) > 0
}

function markChanged(row, kind) {
  const e = edits.value[row.id] || { id: row.id }
  if (kind === 'qty') {
    const orig = detail.value._orig?.[row.id]
    if (orig && Number(orig.qty) !== Number(row.qty)) e.qty = row.qty
    else delete e.qty
  } else if (kind === 'date') {
    const orig = detail.value._orig?.[row.id]
    if (orig && orig.planShipDate !== row.planShipDate) e.date = row.planShipDate
    else delete e.date
  }
  if (Object.keys(e).filter(k => k !== 'id').length) edits.value[row.id] = e
  else delete edits.value[row.id]
}

function markCancel(row) {
  if (isExecuted(row)) {
    return ElMessage.error(`已执行行不可变更（行 ${row.lineNo} 已发/已开票），变更仅作用于未执行部分（C-4.3-08）`)
  }
  edits.value[row.id] = { id: row.id, cancel: true }
  ElMessage.info(`行 ${row.lineNo} 已标记取消（提交后释放预留）`)
}

async function load() {
  loading.value = true
  try {
    const res = await getSoPageApi({ current: 1, size: 50, status: 'CONFIRMED' })
    rows.value = res.data.records || []
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  try {
    const res = await getSoDetailApi(row.id)
    detail.value = res.data
    // 快照原始值（变更对比基准）
    detail.value._orig = {}
    for (const l of detail.value.lines) {
      detail.value._orig[l.id] = { qty: l.qty, planShipDate: l.planShipDate }
    }
    edits.value = {}
    reason.value = ''
  } catch (e) {
    ElMessage.error(e?.message || '加载详情失败')
  }
}

async function submitChange() {
  if (!reason.value) return ElMessage.error('变更原因必填')
  const lines = []
  for (const e of Object.values(edits.value)) {
    const item = { id: e.id }
    if (e.cancel) item.cancel = true
    if (e.qty !== undefined) item.qty = e.qty
    if (e.date !== undefined) item.planShipDate = e.date
    lines.push(item)
  }
  if (!lines.length) return ElMessage.error('无变更内容')
  submitting.value = true
  try {
    const res = await changeSoApi(detail.value.so.id, lines, reason.value)
    result.value = res.data
    resultVisible.value = true
    await load()
    await openDetail({ id: detail.value.so.id })
  } catch (e) {
    ElMessage.error(e?.message || '变更失败')
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
.executed { color: #e6a23c; font-weight: 600; }
.chg-bar { display: flex; gap: 20px; justify-content: flex-end; margin-top: 10px; font-size: 13px; color: #909399; }
</style>
