<template>
  <div class="page-wrap">
    <!-- PO 推送列表（推送侧，规格 R1） -->
    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>订单下达 · PO 推送列表（12.5.1）—— 审批通过即推送，版本变更再推送</span>
          <div>
            <el-select v-model="query.status" placeholder="确认状态" clearable size="small"
                       style="width:140px" @change="load">
              <el-option label="已推送" value="PUSHED" />
              <el-option label="已确认" value="CONFIRMED" />
              <el-option label="变更待处理" value="CHANGE_PENDING" />
              <el-option label="已升级" value="ESCATED" />
            </el-select>
            <el-input v-model="query.keyword" placeholder="PO 单号" clearable size="small"
                      style="width:150px;margin-left:8px" @keyup.enter="load" />
            <el-button size="small" style="margin-left:8px" @click="load">查询</el-button>
          </div>
        </div>
      </template>
      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="poNo" label="PO 单号" width="150" />
        <el-table-column prop="supplierName" label="供应商" min-width="140" show-overflow-tooltip />
        <el-table-column label="版本" width="70" align="center">
          <template #default="{ row }">v{{ row.currVersion || 1 }}</template>
        </el-table-column>
        <el-table-column prop="poStatus" label="订单状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small">{{ row.poStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="确认状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="confirmType(row.confirmStatus)">{{ confirmLabel(row.confirmStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="pushedAt" label="推送时间" width="160" />
        <el-table-column label="推送结果" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="pushFailures.has(row.poNo)" size="small" type="danger">异常</el-tag>
            <el-tag v-else size="small" type="success">已入队</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button size="mini" text type="primary"
                       @click="$router.push({ path: '/m/12.5.2', query: { poId: row.id } })">处理确认</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="mt12" layout="total, prev, pager, next" :total="total"
                     :page-size="query.size" :current-page="query.current" @current-change="onPage" />
      <div class="hint mt12">
        推送由 PO 审批通过自动触发（事务性发件箱 <code>PROC.PO_PUSHED</code>，幂等键 PO编号#PUSH:v版本，
        发布失败回滚 PO 状态）；确认侧动作（催办/确认/协商/工单）→ 12.5.2。
      </div>
    </el-card>

    <!-- 推送台账（outbox 事件明细，design D4） -->
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>推送台账 —— outbox <code>PROC.PO_PUSHED</code> 事件明细</span>
          <div>
            <el-input v-model="ledgerQuery.poNo" placeholder="PO 单号（幂等键模糊）" clearable
                      size="small" style="width:200px" @keyup.enter="loadLedger" />
            <el-button size="small" style="margin-left:8px" @click="loadLedger">查询</el-button>
          </div>
        </div>
      </template>
      <el-table :data="ledger" size="small" border v-loading="ledgerLoading">
        <el-table-column prop="idempotencyKey" label="幂等键" width="220" show-overflow-tooltip />
        <el-table-column prop="occurredAt" label="发生时间" width="165" />
        <el-table-column label="投递状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'DELIVERED' ? 'success'
              : (row.status === 'DEAD' ? 'danger' : 'warning')">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="consumerNote" label="消费备注" min-width="200" show-overflow-tooltip />
        <el-table-column prop="payload" label="载荷摘要" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ summarize(row.payload) }}</template>
        </el-table-column>
      </el-table>
      <el-pagination class="mt12" layout="total, prev, pager, next" :total="ledgerTotal"
                     :page-size="ledgerQuery.size" :current-page="ledgerQuery.current"
                     @current-change="onLedgerPage" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, reactive } from 'vue'
import { getPoCoopPageApi, getPushLedgerApi } from '@/api/proc/po-collaboration-console'

const CONFIRM = {
  PUSHED: ['已推送', 'info'], CONFIRMED: ['已确认', 'success'],
  CHANGE_PENDING: ['变更待处理', 'warning'], ESCATED: ['已升级', 'danger']
}
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ status: '', keyword: '', current: 1, size: 20 })
const ledger = ref([])
const ledgerTotal = ref(0)
const ledgerLoading = ref(false)
const ledgerQuery = reactive({ poNo: '', current: 1, size: 20 })
const pushFailures = ref(new Set())

function confirmLabel(s) { return (CONFIRM[s] || [s, 'info'])[0] }
function confirmType(s) { return (CONFIRM[s] || [s, 'info'])[1] }
function summarize(payload) {
  if (!payload) return '—'
  try {
    const p = JSON.parse(payload)
    return `${p.poNo || ''}${p.versionTag ? ' ' + p.versionTag : ''}${p.supplierName ? '｜' + p.supplierName : ''}`
  } catch { return String(payload).slice(0, 100) }
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
    // 推送失败可见性：outbox 非 DELIVERED 的幂等键 → 对应 PO 标注异常
    markPushFailures()
  } finally {
    loading.value = false
  }
}

async function markPushFailures() {
  const d = (await getPushLedgerApi({ current: 1, size: 100 })).data
  const failed = new Set()
  for (const e of d.records || []) {
    if (e.status !== 'DELIVERED' && e.idempotencyKey) {
      failed.add(String(e.idempotencyKey).split('#')[0])
    }
  }
  pushFailures.value = failed
}

async function loadLedger() {
  ledgerLoading.value = true
  try {
    const p = { current: ledgerQuery.current, size: ledgerQuery.size }
    if (ledgerQuery.poNo) p.poNo = ledgerQuery.poNo
    const d = (await getPushLedgerApi(p)).data
    ledger.value = d.records || []
    ledgerTotal.value = Number(d.total || 0)
  } finally {
    ledgerLoading.value = false
  }
}

function onPage(c) { query.current = c; load() }
function onLedgerPage(c) { ledgerQuery.current = c; loadLedger() }

onMounted(() => { load(); loadLedger() })
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
.hint { color: #909399; font-size: 12px; line-height: 1.7; }
</style>
