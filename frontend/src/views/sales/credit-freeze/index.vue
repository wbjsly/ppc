<template>
  <div class="cf-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="信用冻结看板（3.3.2）"
      description="冻结单展示缺口金额、占用预占量与冻结时长；解冻回前一稳定状态（D5：审批前→待审批，已确认→已确认），不重跑审批、不释放预留；支持信用特批（上限与理由校验 BR-4.3-18）。"
    />

    <!-- 概览 -->
    <el-row :gutter="12" style="margin-bottom: 12px;">
      <el-col :span="6">
        <el-card shadow="never">
          <div class="kpi-name">冻结中</div>
          <div class="kpi-val" style="color: #f56c6c;">{{ frozenCount }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="kpi-name">累计缺口</div>
          <div class="kpi-val">¥ {{ fmt(totalGap) }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="kpi-name">本月已解冻</div>
          <div class="kpi-val" style="color: #67c23a;">{{ unfrozenCount }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="kpi-name">特批上限（参数）</div>
          <div class="kpi-val">¥ {{ fmt(specialLimit) }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">冻结单</span>
          <div class="header-actions">
            <el-select v-model="status" placeholder="状态" clearable style="width: 130px;" @change="load(1)">
              <el-option label="冻结中" value="FROZEN" />
              <el-option label="已解冻" value="UNFROZEN" />
            </el-select>
            <el-input v-model="keyword" placeholder="SO 号/客户" clearable
                      :prefix-icon="Search" style="width: 190px;" @keyup.enter="load(1)" />
            <el-button type="primary" @click="load(1)">查询</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="soNo" label="SO 编号" width="160" show-overflow-tooltip />
        <el-table-column prop="customerName" label="客户" min-width="140" show-overflow-tooltip />
        <el-table-column label="缺口金额" width="110" align="right">
          <template #default="{ row }"><b style="color: #f56c6c;">¥ {{ fmt(row.gapAmount) }}</b></template>
        </el-table-column>
        <el-table-column label="触发原因" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.freezeReason === 'AGING' ? 'warning' : 'danger'">
              {{ row.freezeReason === 'AGING' ? '账龄超比' : '额度不足' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="前一状态" width="110">
          <template #default="{ row }">{{ statusName(row.prevStatus) }}</template>
        </el-table-column>
        <el-table-column label="冻结时长" width="100">
          <template #default="{ row }">
            <span v-if="row.status === 'FROZEN'" style="color: #e6a23c;">{{ days(row.frozenAt) }} 天</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="解冻" width="150">
          <template #default="{ row }">
            <template v-if="row.status === 'UNFROZEN'">
              <el-tag type="success" size="small">{{ row.unfreezeNo }}</el-tag>
              <div class="tip">{{ methodName(row.unfreezeMethod) }} · {{ (row.unfrozenBy || '').replace('user-', '') }}</div>
            </template>
            <span v-else style="color: #909399;">未解冻</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'FROZEN'">
              <el-button link type="primary" @click="openSpecial(row)">信用特批</el-button>
              <el-button link type="warning" @click="manualUnfreeze(row)">人工解冻</el-button>
              <el-button link type="info" @click="$router.push('/m/3.3.3')">预收处理</el-button>
            </template>
            <el-button v-else link type="info" @click="showDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end;"
        v-model:current-page="current" :page-size="10" :total="total"
        layout="total, prev, pager, next" @current-change="load" />
    </el-card>

    <!-- 特批对话框 -->
    <el-dialog v-model="specialVisible" :title="`信用特批 — ${specialRow.soNo || ''}`" width="520px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 10px;"
        :title="`缺口 ¥ ${fmt(specialRow.gapAmount)}，特批上限 ¥ ${fmt(specialLimit)}`"
        description="特批通过即解冻；金额超上限或理由为空将被拒绝，审批记录永久留痕（BR-4.3-18）。" />
      <el-form label-width="90px">
        <el-form-item label="特批金额" required>
          <el-input-number v-model="specialAmount" :min="0" :precision="2" style="width: 220px;" />
        </el-form-item>
        <el-form-item label="特批理由" required>
          <el-input v-model="specialReason" type="textarea" :rows="3" maxlength="500"
                    placeholder="如：战略客户竞标支持 / 客户临时资金周转" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="specialVisible = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="submitSpecial">提交特批</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" title="冻结单详情" width="560px">
      <el-descriptions :column="2" size="small" border>
        <el-descriptions-item label="SO">{{ detail.soNo }}</el-descriptions-item>
        <el-descriptions-item label="客户">{{ detail.customerName }}</el-descriptions-item>
        <el-descriptions-item label="缺口">¥ {{ fmt(detail.gapAmount) }}</el-descriptions-item>
        <el-descriptions-item label="触发原因">{{ detail.freezeReason === 'AGING' ? '账龄超比' : '额度不足' }}</el-descriptions-item>
        <el-descriptions-item label="前一状态">{{ statusName(detail.prevStatus) }}</el-descriptions-item>
        <el-descriptions-item label="冻结时间">{{ fmtTime(detail.frozenAt) }}</el-descriptions-item>
        <el-descriptions-item label="解冻单号">{{ detail.unfreezeNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="解冻方式">{{ methodName(detail.unfreezeMethod) }}</el-descriptions-item>
        <el-descriptions-item label="解冻时间" :span="2">{{ fmtTime(detail.unfrozenAt) }}</el-descriptions-item>
        <el-descriptions-item label="解冻备注" :span="2">{{ detail.unfreezeRemark || '-' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { getFreezesApi, unfreezeApi, specialApproveApi } from '@/api/sd/credit'
import request from '@/utils/request'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const current = ref(1)
const status = ref('FROZEN')
const keyword = ref('')
const saving = ref(false)
const specialLimit = ref(500000)

const specialVisible = ref(false)
const specialRow = reactive({ id: '', soNo: '', gapAmount: 0 })
const specialAmount = ref(0)
const specialReason = ref('')

const detailVisible = ref(false)
const detail = reactive({})

onMounted(() => {
  load(1)
  loadParam()
})

async function loadParam() {
  try {
    const res = await request.get('/system/params')
    const p = (res.data || []).find(x => x.paramKey === 'CREDIT_SPECIAL_LIMIT')
    if (p) specialLimit.value = Number(p.paramValue)
  } catch (e) { /* 参数接口不可用时用默认 */ }
}

async function load(page) {
  if (page) current.value = page
  loading.value = true
  try {
    const res = await getFreezesApi({ current: current.value, size: 10, status: status.value || '', keyword: keyword.value })
    rows.value = res.data.records || []
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

const frozenCount = computed(() => rows.value.filter(r => r.status === 'FROZEN').length)
const unfrozenCount = computed(() => rows.value.filter(r => r.status === 'UNFROZEN').length)
const totalGap = computed(() => rows.value.filter(r => r.status === 'FROZEN')
  .reduce((s, r) => s + Number(r.gapAmount || 0), 0))

function openSpecial(row) {
  Object.assign(specialRow, { id: row.id, soNo: row.soNo, gapAmount: row.gapAmount })
  specialAmount.value = Number(row.gapAmount || 0)
  specialReason.value = ''
  specialVisible.value = true
}

async function submitSpecial() {
  if (!specialReason.value) {
    ElMessage.warning('特批理由必填')
    return
  }
  saving.value = true
  try {
    await specialApproveApi(specialRow.id, specialAmount.value, specialReason.value)
    ElMessage.success('特批通过，SO 已解冻（记录永久留痕）')
    specialVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function manualUnfreeze(row) {
  const { value } = await ElMessageBox.prompt(
    `人工解冻 ${row.soNo}（缺口 ¥ ${fmt(row.gapAmount)}）？将回到前一状态「${statusName(row.prevStatus)}」。备注必填。`,
    '人工解冻', { type: 'warning', inputPattern: /.{2,}/, inputErrorMessage: '备注至少 2 字' })
  await unfreezeApi(row.id, 'MANUAL', value)
  ElMessage.success('已解冻并生成确认单')
  load()
}

function showDetail(row) {
  Object.assign(detail, row)
  detailVisible.value = true
}

function days(t) {
  if (!t) return 0
  return Math.max(0, Math.floor((Date.now() - new Date(t).getTime()) / 86400000))
}
function statusName(s) {
  return { DRAFT: '待审批', PENDING: '待审批', CONFIRMED: '已确认', CREDIT_FREEZE: '信用冻结', CLOSED: '已关闭' }[s] || s || '-'
}
function methodName(m) {
  return { PREPAY: '预收到账', SPECIAL: '信用特批', MANUAL: '人工解冻' }[m] || m || '-'
}
function fmt(v) { return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '-' }
</script>

<style scoped>
.cf-page { padding: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.kpi-name { color: #909399; font-size: 12px; }
.kpi-val { font-size: 24px; font-weight: bold; margin-top: 4px; }
.tip { color: #909399; font-size: 12px; }
</style>
