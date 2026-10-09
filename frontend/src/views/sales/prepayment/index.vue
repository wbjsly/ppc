<template>
  <div class="pp-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="预收处理（3.3.3）"
      description="冻结后预收目标 = 缺口金额；通知销售与客户 → 到账登记（销售/客服）→ 财务确认（仅 FINANCE_MGR/ADMIN）：到账 ≥ 缺口自动解冻并生成《SO 解冻确认单》，不足保持冻结并展示尚差（BR-4.3-17）。"
    />

    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">预收款通知单</span>
          <div class="header-actions">
            <el-select v-model="status" placeholder="状态" clearable style="width: 150px;" @change="load(1)">
              <el-option label="待通知" value="PENDING" />
              <el-option label="已通知" value="NOTIFIED" />
              <el-option label="已登记待确认" value="REGISTERED" />
              <el-option label="已结清" value="SETTLED" />
            </el-select>
            <el-input v-model="keyword" placeholder="通知号/SO/客户" clearable
                      :prefix-icon="Search" style="width: 200px;" @keyup.enter="load(1)" />
            <el-button type="primary" @click="load(1)">查询</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="noticeNo" label="通知单号" width="155" />
        <el-table-column prop="soNo" label="SO 编号" width="155" show-overflow-tooltip />
        <el-table-column prop="customerName" label="客户" min-width="130" show-overflow-tooltip />
        <el-table-column label="预收目标（缺口）" width="130" align="right">
          <template #default="{ row }"><b>¥ {{ fmt(row.gapAmount) }}</b></template>
        </el-table-column>
        <el-table-column label="已到账" width="110" align="right">
          <template #default="{ row }">¥ {{ fmt(row.receivedAmount) }}</template>
        </el-table-column>
        <el-table-column label="尚差" width="110" align="right">
          <template #default="{ row }">
            <span :style="{ color: Number(row.remainAmount) > 0 ? '#f56c6c' : '#67c23a' }">
              ¥ {{ fmt(row.remainAmount == null ? row.gapAmount : row.remainAmount) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="通知" width="120">
          <template #default="{ row }">
            <span class="tip">销售 {{ fmtTime(row.notifySalesAt) || '未发' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="stType(row.status)">{{ stName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="财务确认" width="140">
          <template #default="{ row }">
            <span v-if="row.confirmBy">{{ (row.confirmBy || '').replace('user-', '') }} {{ fmtTime(row.confirmAt) }}</span>
            <span v-else style="color: #909399;">未确认</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING'" link type="primary"
                       @click="doNotify(row)">通知</el-button>
            <el-button v-if="row.status === 'NOTIFIED' || row.status === 'REGISTERED'"
                       link type="warning" @click="openRegister(row)">登记到账</el-button>
            <el-button v-if="row.status === 'REGISTERED'" link type="success"
                       @click="doConfirm(row)">财务确认</el-button>
            <el-button v-if="row.status === 'SETTLED'" link type="info"
                       @click="showSettled(row)">解冻结果</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end;"
        v-model:current-page="current" :page-size="10" :total="total"
        layout="total, prev, pager, next" @current-change="load" />
    </el-card>

    <!-- 登记到账 -->
    <el-dialog v-model="registerVisible" :title="`到账登记 — ${registerRow.noticeNo || ''}`" width="480px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px;"
        :title="`预收目标 ¥ ${fmt(registerRow.gapAmount)}，已登记 ¥ ${fmt(registerRow.receivedAmount)}`"
        description="登记后由财务确认；到账 ≥ 缺口才自动解冻。" />
      <el-form label-width="90px">
        <el-form-item label="到账金额" required>
          <el-input-number v-model="receiveAmount" :min="0.01" :precision="2" style="width: 220px;" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="receiveRemark" maxlength="500" placeholder="如：银行转账回单号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitRegister">登记</el-button>
      </template>
    </el-dialog>

    <!-- 解冻结果 -->
    <el-dialog v-model="settledVisible" title="解冻结果" width="480px">
      <el-result icon="success" title="已结清并解冻"
                 :sub-title="settledMessage || '到账足额，SO 已回到前一稳定状态。'">
        <template #extra>
          <el-button type="primary" @click="settledVisible = false">知道了</el-button>
        </template>
      </el-result>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { getNoticesApi, notifyNoticeApi, registerReceivedApi, confirmReceivedApi } from '@/api/sd/credit'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const current = ref(1)
const status = ref('')
const keyword = ref('')
const saving = ref(false)

const registerVisible = ref(false)
const registerRow = reactive({ id: '', noticeNo: '', gapAmount: 0, receivedAmount: 0 })
const receiveAmount = ref(0)
const receiveRemark = ref('')

const settledVisible = ref(false)
const settledMessage = ref('')

onMounted(() => load(1))

async function load(page) {
  if (page) current.value = page
  loading.value = true
  try {
    const res = await getNoticesApi({ current: current.value, size: 10, status: status.value || '', keyword: keyword.value })
    rows.value = res.data.records || []
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

async function doNotify(row) {
  await notifyNoticeApi(row.id)
  ElMessage.success('已通知销售员与客户（留痕时间戳）')
  load()
}

function openRegister(row) {
  Object.assign(registerRow, {
    id: row.id, noticeNo: row.noticeNo,
    gapAmount: Number(row.gapAmount || 0), receivedAmount: Number(row.receivedAmount || 0)
  })
  const remain = Number(row.remainAmount == null ? row.gapAmount : row.remainAmount)
  receiveAmount.value = remain > 0 ? remain : 0
  receiveRemark.value = ''
  registerVisible.value = true
}

async function submitRegister() {
  saving.value = true
  try {
    await registerReceivedApi(registerRow.id, receiveAmount.value, receiveRemark.value)
    ElMessage.success('到账已登记，待财务确认')
    registerVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function doConfirm(row) {
  await ElMessageBox.confirm(
    `确认 ${row.noticeNo} 到账？到账 ≥ 缺口 ¥ ${fmt(row.gapAmount)} 将自动解冻 SO 并生成《SO 解冻确认单》；不足则保持冻结。`,
    '财务确认到账', { type: 'warning' })
  const res = await confirmReceivedApi(row.id)
  const d = res.data || {}
  if (d.ok) {
    ElMessage.success(d.message || '已自动解冻')
  } else {
    ElMessageBox.alert(d.message || '到账不足，保持冻结', '到账不足', { type: 'warning' })
  }
  load()
}

function showSettled(row) {
  settledMessage.value = `通知单 ${row.noticeNo} 已结清：到账 ¥ ${fmt(row.receivedAmount)}，财务 ${row.confirmBy || ''} 确认。`
  settledVisible.value = true
}

function stName(s) {
  return { PENDING: '待通知', NOTIFIED: '已通知', REGISTERED: '待确认', SETTLED: '已结清' }[s] || s
}
function stType(s) {
  return { PENDING: 'info', NOTIFIED: 'primary', REGISTERED: 'warning', SETTLED: 'success' }[s] || 'info'
}
function fmt(v) { return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(5, 16) : '' }
</script>

<style scoped>
.pp-page { padding: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
</style>
