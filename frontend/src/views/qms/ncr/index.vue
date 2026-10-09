<template>
  <div class="ncr-page">
    <el-alert type="error" :closable="false" style="margin-bottom: 12px;"
      title="不合格品 NCR（2.5.3 / 6.5）：判不合格同事务生成并冻结，评审须选定处置，处置凭证齐全 → CAPA 有效 + 复检放行方可关闭"
      description="两阶段冻结：未过账冻 GR 行（阻断入库），已入库冻库存（AVAILABLE → QC）；评审时限 Critical 4h / Major 24h / Minor 48h，超期升级质量总监并冻结供应商绩效发布（BR-4.2-27）。" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 150px;" @change="load">
          <el-option v-for="(label, val) in STATUS_TEXT" :key="val" :label="label" :value="val" />
        </el-select>
        <el-select v-model="filters.severity" placeholder="严重度" clearable style="width: 130px;" @change="load">
          <el-option label="Critical" value="CRITICAL" />
          <el-option label="Major" value="MAJOR" />
          <el-option label="Minor" value="MINOR" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="NCR 号 / 批号 / 物料 / 供应商" clearable
          style="width: 240px;" @keyup.enter="load" @clear="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <span class="spacer" />
        <el-tag v-if="scopeText" type="warning" size="small">{{ scopeText }}</el-tag>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading" @row-click="openDetail" row-style="cursor:pointer">
        <el-table-column prop="ncrNo" label="NCR 号" width="150">
          <template #default="{ row }"><b>{{ row.ncrNo }}</b></template>
        </el-table-column>
        <el-table-column prop="lotNo" label="检验批" width="140" show-overflow-tooltip />
        <el-table-column prop="itemCode" label="物料" width="130" />
        <el-table-column prop="itemName" label="名称" min-width="120" show-overflow-tooltip />
        <el-table-column prop="supplierName" label="供应商" width="130" show-overflow-tooltip />
        <el-table-column prop="qty" label="数量" width="80" align="right" />
        <el-table-column label="严重度" width="95" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{ CRITICAL: 'danger', MAJOR: 'warning', MINOR: 'info' }[row.severity]">
              {{ row.severity }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ STATUS_TEXT[row.status] || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="处置" width="90" align="center">
          <template #default="{ row }">{{ DISP_TEXT[row.disposition] || '—' }}</template>
        </el-table-column>
        <el-table-column label="冻结" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.frozenFlag === '1'" type="danger" size="small">
              冻结·{{ SCOPE_TEXT[row.freezeScope] || row.freezeScope }}
            </el-tag>
            <el-tag v-else type="success" size="small">已解冻</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="评审时限" width="150">
          <template #default="{ row }">
            <template v-if="row.status === 'CREATED' && row.reviewDueTime">
              <span :style="{ color: row.reviewOverdue ? '#F56C6C' : '' , fontWeight: row.reviewOverdue ? 700 : 400 }">
                {{ reviewCountdown(row) }}
              </span>
              <el-tag v-if="row.reviewEscalatedFlag === '1'" type="danger" size="small" style="margin-left:4px;">已升级</el-tag>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="超期" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.overdueFlag === '1'" type="danger" size="small">30天+</el-tag>
            <el-tag v-else-if="row.perfFrozenFlag === '1'" type="danger" size="small">绩效冻结</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top: 10px; justify-content: flex-end;"
        layout="total, prev, pager, next" :total="total"
        v-model:current-page="filters.current" :page-size="filters.size" @current-change="load" />
    </el-card>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawer" :title="detail.ncr ? `${detail.ncr.ncrNo} · ${STATUS_TEXT[detail.ncr.status] || detail.ncr.status}` : 'NCR 详情'"
      size="760px" destroy-on-close>
      <div v-if="detail.ncr" class="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="检验批">{{ detail.ncr.lotNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="严重度">
            <el-tag size="small" :type="{ CRITICAL: 'danger', MAJOR: 'warning', MINOR: 'info' }[detail.ncr.severity]">
              {{ detail.ncr.severity }}
            </el-tag>
            <span v-if="detail.ncr.ctqFlag === '1'" style="margin-left:6px;color:#F56C6C;">CTQ</span>
            <span v-if="detail.ncr.regulatoryFlag === '1'" style="margin-left:6px;color:#F56C6C;">安全/法规</span>
          </el-descriptions-item>
          <el-descriptions-item label="物料">{{ detail.ncr.itemCode }} {{ detail.ncr.itemName }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ detail.ncr.supplierName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="不合格数量">{{ detail.ncr.qty }}</el-descriptions-item>
          <el-descriptions-item label="不合格项">{{ detail.ncr.defectItem || '—' }}</el-descriptions-item>
          <el-descriptions-item label="冻结">
            <el-tag v-if="detail.ncr.frozenFlag === '1'" type="danger" size="small">
              冻结中 · {{ SCOPE_TEXT[detail.ncr.freezeScope] || detail.ncr.freezeScope }}
            </el-tag>
            <el-tag v-else type="success" size="small">已解冻</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="处置">{{ DISP_TEXT[detail.ncr.disposition] || '未评审' }}</el-descriptions-item>
          <el-descriptions-item label="评审时限" :span="2">
            <span :style="{ color: reviewOverdueNow() ? '#F56C6C' : '' }">{{ detail.ncr.reviewDueTime || '—' }}</span>
            <span v-if="detail.ncr.reviewBy" style="margin-left:10px;">
              评审人 {{ detail.ncr.reviewBy }} @ {{ detail.ncr.reviewTime }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="评审意见" :span="2">{{ detail.ncr.reviewOpinion || '—' }}</el-descriptions-item>
          <el-descriptions-item label="不合格描述" :span="2">{{ detail.ncr.defectDesc || '—' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 检验项 -->
        <h4 class="sec">检验项（{{ (detail.items || []).length }}）</h4>
        <el-table :data="detail.items || []" size="mini" border max-height="200">
          <el-table-column prop="sequenceNo" label="#" width="45" />
          <el-table-column prop="characteristicName" label="特性" width="120" />
          <el-table-column label="规格" width="150">
            <template #default="{ row }">
              <span v-if="row.specType === 'NUMERIC'">[{{ row.lowerLimit ?? '−∞' }}, {{ row.upperLimit ?? '+∞' }}]</span>
              <span v-else>定性</span>
              <el-tag v-if="row.ctqFlag === '1'" size="small" type="danger" style="margin-left:4px;">CTQ</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="measuredValue" label="实测" width="80" />
          <el-table-column label="判定" width="80" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="{ PASS: 'success', FAIL: 'danger', PENDING: 'info' }[row.judge]">
                {{ { PASS: '合格', FAIL: '不合格', PENDING: '待检' }[row.judge] || row.judge }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="100" />
        </el-table>

        <!-- 操作区（按角色显隐） -->
        <h4 class="sec">处置操作</h4>
        <el-alert v-if="detail.ncr.status === 'CREATED'" type="warning" :closable="false" style="margin-bottom:8px;"
          :title="`评审必须选定处置方式（剩余 ${reviewCountdown(detail.ncr)}）`" />

        <div v-if="canReview && detail.ncr.status === 'CREATED'" class="op-block">
          <el-radio-group v-model="reviewForm.disposition">
            <el-radio-button value="RETURN">退货</el-radio-button>
            <el-radio-button value="SORT">挑选</el-radio-button>
            <el-radio-button value="REWORK">返工</el-radio-button>
            <el-radio-button value="CONCESSION"
              :disabled="detail.ncr.regulatoryFlag === '1'"
              :title="detail.ncr.regulatoryFlag === '1' ? '安全/法规 CTQ 禁止让步（BR-4.12-26）' : ''">
              让步接收
            </el-radio-button>
            <el-radio-button value="SCRAP"
              :disabled="detail.ncr.regulatoryFlag === '1'"
              :title="detail.ncr.regulatoryFlag === '1' ? '安全/法规 CTQ 仅可退货或返工（BR-4.12-26）' : ''">
              报废
            </el-radio-button>
          </el-radio-group>
          <div v-if="detail.ncr.regulatoryFlag === '1'" style="color:#F56C6C;font-size:12px;margin:6px 0;">
            安全/法规 CTQ 不合格：仅可退货或返工，禁止让步接收与报废（BR-4.12-26）
          </div>
          <el-input v-model="reviewForm.opinion" type="textarea" :rows="2" placeholder="评审意见（必填，至少 2 字）"
            style="margin-top:8px;" />
          <el-button type="primary" size="small" style="margin-top:8px;" @click="doReview">提交评审</el-button>
          <el-button size="small" @click="showCancel = true">作废</el-button>
        </div>

        <div v-if="canDispose && ['SORTING', 'REWORKING'].includes(detail.ncr.status)" class="op-block">
          <el-input v-model="planForm.plan" type="textarea" :rows="2"
            :placeholder="detail.ncr.disposition === 'SORT' ? '挑选方案（范围/责任方/隔离区）' : '返工方案（方式/责任方/完成时间）'" />
          <el-button type="primary" size="small" style="margin-top:8px;" @click="doPlan">保存方案</el-button>
          <el-divider style="margin:10px 0;" />
          <el-input v-model="planForm.result" type="textarea" :rows="2"
            placeholder="执行结果凭证（必填：挑选合格/隔离/报废数量，或返工批号/完成时间）" />
          <el-button type="success" size="small" style="margin-top:8px;" @click="doConfirm">
            确认处置完成（生成复检批）
          </el-button>
        </div>

        <el-alert v-if="detail.ncr.status === 'RETURNING'" type="info" :closable="false" style="margin-bottom:8px;"
          title="退货处置：待 2.6.1 质量退货自动带出退货单（PO/收货单/数量/PO 单价），审批后仓库出库" />

        <el-alert v-if="detail.ncr.status === 'CONCESSION'" type="info" :closable="false" style="margin-bottom:8px;"
          title="让步接收处置：在 2.5.2 发起申请，双签（质量经理 + 技术负责人）批准后方可确认处置" />

        <el-alert v-if="detail.ncr.status === 'SCRAPPING'" type="warning" :closable="false" style="margin-bottom:8px;"
          title="报废处置：先在 4.5.4 报废出库创建报废单（原因=质量、关联本 NCR）完成会签过账，再回填报废单号确认处置" />
        <div v-if="canDispose && detail.ncr.status === 'SCRAPPING'" class="op-block">
          <el-input v-model="planForm.result" type="textarea" :rows="2"
            placeholder="报废执行凭证（必填：4.5.4 关联报废单号）" />
          <el-button type="success" size="small" style="margin-top:8px;" @click="doConfirm">
            确认处置完成（凭报废单号）
          </el-button>
        </div>

        <div v-if="canDispose && detail.ncr.status === 'DISPOSED'" class="op-block">
          <el-alert type="warning" :closable="false" style="margin-bottom:8px;"
            title="关闭校验：CAPA 有效性须验证通过（VALID + CLOSED）；挑选/返工须复检批已放行" />
          <el-input v-model="closeForm.opinion" type="textarea" :rows="2" placeholder="关闭意见（复检结论等）" />
          <el-button type="success" size="small" style="margin-top:8px;" @click="doClose">关闭 NCR（解冻归档）</el-button>
        </div>

        <!-- CAPA / 复检批 -->
        <template v-if="detail.capa">
          <h4 class="sec">关联 CAPA</h4>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="编号">{{ detail.capa.capaNo }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ detail.capa.status }}</el-descriptions-item>
            <el-descriptions-item label="有效性">
              <el-tag size="small" :type="{ VALID: 'success', INVALID: 'danger', PARTIAL: 'warning' }[detail.capa.verifyResult] || 'info'">
                {{ detail.capa.verifyResult }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </template>

        <template v-if="(detail.recheckLots || []).length">
          <h4 class="sec">复检批</h4>
          <el-table :data="detail.recheckLots" size="mini" border>
            <el-table-column prop="lotNo" label="批号" width="150" />
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'RELEASED' ? 'success' : 'primary'">
                  {{ { PENDING: '待检', INPUTTING: '待确认', RELEASED: '已放行', BLOCKED: '无标准' }[row.status] || row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="releasedDate" label="放行时间" min-width="150" />
          </el-table>
        </template>

        <!-- 日志时间线 -->
        <h4 class="sec">操作与升级日志</h4>
        <el-timeline style="padding-left: 4px;">
          <el-timeline-item v-for="l in (detail.logs || [])" :key="l.id"
            :timestamp="l.createDate" placement="top"
            :type="LOG_TONE[l.action] || 'primary'">
            <b>{{ LOG_TEXT[l.action] || l.action }}</b>
            <span v-if="l.notifyRole" style="color:#E6A23C;margin-left:6px;">→ {{ ROLE_TEXT[l.notifyRole] || l.notifyRole }}</span>
            <span v-if="l.ccRole" style="color:#909399;margin-left:4px;">抄送 {{ ROLE_TEXT[l.ccRole] || l.ccRole }}</span>
            <div style="color:#606266;font-size:12px;">{{ l.detail }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <!-- 作废原因 -->
    <el-dialog v-model="showCancel" title="作废 NCR（须填原因）" width="440px">
      <el-input v-model="cancelReason" type="textarea" :rows="3" placeholder="作废原因（至少 2 字）" />
      <template #footer>
        <el-button @click="showCancel = false">取消</el-button>
        <el-button type="danger" @click="doCancel">确认作废</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getNcrPageApi, getNcrDetailApi, reviewNcrApi, planNcrApi,
  confirmNcrApi, closeNcrApi, cancelNcrApi
} from '@/api/qms/ncr'

const route = useRoute()
const userStore = useUserStore()

const STATUS_TEXT = {
  CREATED: '待评审', REVIEWING: '评审中', RETURNING: '退货中', SORTING: '挑选中',
  REWORKING: '返工中', CONCESSION: '让步中', DISPOSED: '已处置', CLOSED: '已关闭', CANCELLED: '已作废'
}
const DISP_TEXT = { RETURN: '退货', SORT: '挑选', REWORK: '返工', CONCESSION: '让步接收', SCRAP: '报废' }
const SCOPE_TEXT = { GR_LINE: 'GR 行', STOCK: '库存', BOTH: '双冻结', NONE: '无对象' }
const ROLE_TEXT = {
  ROLE_QUALITY_MGR: '质量经理', ROLE_QUALITY_DIRECTOR: '质量总监', ROLE_PM: '采购经理',
  ROLE_TECH_OWNER: '技术负责人', ROLE_QUALITY_ENG: '质量工程师'
}
const LOG_TEXT = {
  CREATE: 'NCR 生成', CAPA: 'CAPA 自动立项', REVIEW: '评审', DISPOSE: '方案录入', CONFIRM: '处置确认',
  RECHECK: '复检批生成', CLOSE: '关闭', CANCEL: '作废', UNFREEZE: '解冻',
  ESCALATE_MGR: '超时升级', ESCALATE_DIRECTOR: '超期升级', REMIND: '超期提醒'
}
const LOG_TONE = {
  CREATE: 'primary', CAPA: 'primary', ESCALATE_MGR: 'warning', ESCALATE_DIRECTOR: 'danger',
  REMIND: 'warning', CLOSE: 'success', CANCEL: 'info', UNFREEZE: 'success'
}

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const tab = route.meta.tab || 'all'
const filters = reactive({ status: '', severity: '', keyword: '', current: 1, size: 10 })

const scopeText = computed(() => ({
  isolate: '6.5.1 标识隔离：默认只看冻结中的批次',
  dispose: '6.5.2 处置跟踪：默认只看进入处置流的单据'
}[tab] || ''))

if (tab === 'isolate') filters.status = 'CREATED'
if (tab === 'dispose') filters.status = 'SORTING'

const myRoles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const hasRole = (...want) =>
  myRoles.value.some(r => r === 'ROLE_ADMIN' || want.includes(r))
const canReview = computed(() => hasRole('ROLE_QUALITY_ENG', 'ROLE_QUALITY_MGR'))
const canDispose = computed(() => hasRole('ROLE_QUALITY_ENG', 'ROLE_QUALITY_MGR', 'ROLE_QUALITY_DIRECTOR'))

async function load() {
  loading.value = true
  try {
    const res = await getNcrPageApi({
      current: filters.current, size: filters.size,
      status: filters.status || undefined, severity: filters.severity || undefined,
      keyword: filters.keyword || undefined
    })
    rows.value = (res.data && res.data.records) || []
    total.value = (res.data && res.data.total) || 0
  } finally {
    loading.value = false
  }
}

function statusType(s) {
  return {
    CREATED: 'danger', RETURNING: 'warning', SORTING: 'warning', REWORKING: 'warning',
    CONCESSION: 'warning', DISPOSED: 'primary', CLOSED: 'success', CANCELLED: 'info'
  }[s] || 'info'
}

function reviewCountdown(row) {
  if (!row.reviewDueTime) return '—'
  const diff = new Date(row.reviewDueTime.replace(' ', 'T')) - Date.now()
  const h = Math.floor(Math.abs(diff) / 3600000)
  const m = Math.floor((Math.abs(diff) % 3600000) / 60000)
  const text = `${h}h${m}m`
  return diff < 0 ? `超时 ${text}` : `剩 ${text}`
}

function reviewOverdueNow() {
  const n = detail.value.ncr
  return n && n.status === 'CREATED' && n.reviewDueTime &&
    new Date(n.reviewDueTime.replace(' ', 'T')) < Date.now()
}

// ---------- 详情 ----------
const drawer = ref(false)
const detail = ref({})
const reviewForm = reactive({ disposition: '', opinion: '' })
const planForm = reactive({ plan: '', result: '' })
const closeForm = reactive({ opinion: '' })
const showCancel = ref(false)
const cancelReason = ref('')

async function openDetail(row) {
  const res = await getNcrDetailApi(row.id)
  detail.value = res.data || {}
  reviewForm.disposition = ''
  reviewForm.opinion = ''
  planForm.plan = (res.data.ncr && res.data.ncr.disposePlan) || ''
  planForm.result = ''
  closeForm.opinion = ''
  cancelReason.value = ''
  drawer.value = true
}

async function doReview() {
  if (!reviewForm.disposition) return ElMessage.warning('请选择处置方式')
  try {
    await reviewNcrApi(detail.value.ncr.id, reviewForm.disposition, reviewForm.opinion)
    ElMessage.success('评审完成，已进入处置流')
    openDetail(detail.value.ncr)
    load()
  } catch { /* 拦截器已统一弹错 */ }
}

async function doPlan() {
  try {
    await planNcrApi(detail.value.ncr.id, planForm.plan)
    ElMessage.success('方案已保存')
    openDetail(detail.value.ncr)
  } catch { /* 拦截器已统一弹错 */ }
}

async function doConfirm() {
  try {
    const isScrap = detail.value.ncr.disposition === 'SCRAP'
    await ElMessageBox.confirm(
      isScrap ? '确认报废处置完成？将凭报废单号归档本 NCR 处置。' : '确认处置执行完成？挑选/返工将同事务生成复检批。',
      '处置确认', { type: 'warning' })
  } catch { return }
  try {
    await confirmNcrApi(detail.value.ncr.id, planForm.result)
    ElMessage.success(detail.value.ncr.disposition === 'SCRAP' ? '处置已确认' : '处置已确认，复检批已生成')
    openDetail(detail.value.ncr)
    load()
  } catch { /* 拦截器已统一弹错 */ }
}

async function doClose() {
  try {
    await ElMessageBox.confirm('关闭后 NCR 只读归档、库存解冻。确认关闭？', '关闭 NCR', { type: 'warning' })
  } catch { return }
  try {
    await closeNcrApi(detail.value.ncr.id, closeForm.opinion)
    ElMessage.success('NCR 已关闭并解冻')
    openDetail(detail.value.ncr)
    load()
  } catch { /* 拦截器已统一弹错 */ }
}

async function doCancel() {
  try {
    await cancelNcrApi(detail.value.ncr.id, cancelReason.value)
    ElMessage.success('已作废并解冻')
    showCancel.value = false
    openDetail(detail.value.ncr)
    load()
  } catch { /* 拦截器已统一弹错 */ }
}

onMounted(async () => {
  if (!userStore.userInfo) {
    try { await userStore.getUserInfo() } catch { /* ignore */ }
  }
  load()
})
</script>

<style scoped>
.ncr-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.spacer { flex: 1; }
.sec { margin: 16px 0 8px; font-size: 14px; color: #303133; }
.op-block { background: #fafafa; border: 1px solid #ebeef5; border-radius: 4px; padding: 12px; }
</style>
