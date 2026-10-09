<template>
  <div class="opp-fu-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="商机跟进（3.1.3）"
      description="5 阶段推进每次须审批（销售经理节点，通过前停在原阶段）；阶段概率/下一步行动/日期必填；同阶段停留超 30 天标超期；跟进记录仅追加；丢失归档须原因分类+说明。"
    />

    <el-row :gutter="12">
      <!-- ============ 左：商机列表 ============ -->
      <el-col :span="9">
        <el-card>
          <template #header>
            <div class="card-header">
              <span style="font-weight: bold;">商机（在跟优先）</span>
              <el-input v-model="keyword" placeholder="编号/名称/客户" clearable size="small"
                        :prefix-icon="Search" style="width: 180px;" @keyup.enter="loadList(1)" />
            </div>
          </template>
          <el-table :data="rows" v-loading="loading" stripe height="560"
                    highlight-current-row @current-change="selectOpp">
            <el-table-column prop="oppNo" label="编号" width="140" show-overflow-tooltip />
            <el-table-column prop="oppName" label="名称" min-width="120" show-overflow-tooltip />
            <el-table-column label="阶段" width="96">
              <template #default="{ row }">
                <el-tag size="small" :type="stageTagType(row.stage)">{{ stageName(row.stage) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="statusType(row.status)">{{ statusName(row.status) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination small style="margin-top: 10px; justify-content: flex-end;"
            v-model:current-page="pageNo" :page-size="10" :total="total"
            layout="prev, pager, next" @current-change="loadList" />
        </el-card>
      </el-col>

      <!-- ============ 右：详情 / 推进 / 跟进 ============ -->
      <el-col :span="15">
        <el-empty v-if="!current" description="左侧选择一个商机" />
        <template v-else>
          <!-- 详情卡片 -->
          <el-card style="margin-bottom: 12px;">
            <template #header>
              <div class="card-header">
                <span style="font-weight: bold;">
                  {{ current.oppNo }} {{ current.oppName }}
                  <el-tag size="small" :type="stageTagType(current.stage)" style="margin-left: 6px;">
                    {{ stageName(current.stage) }}
                  </el-tag>
                  <el-tag v-if="current.stageOverdueLive === '1'" type="warning" size="small" style="margin-left: 6px;">
                    停留 {{ current.stageDays }} 天·超期
                  </el-tag>
                  <el-tag v-if="current.stagePending" type="warning" size="small" style="margin-left: 6px;">
                    阶段审批中
                  </el-tag>
                </span>
                <div class="header-actions">
                  <el-button size="small" type="primary" :disabled="current.status !== 'OPEN'"
                             @click="openAdvance">推进阶段</el-button>
                  <el-button size="small" type="danger" :disabled="current.status !== 'OPEN' || current.stagePending"
                             @click="openLost">丢失归档</el-button>
                  <el-button size="small" type="success" @click="tryCreateQuote">创建报价</el-button>
                </div>
              </div>
            </template>
            <el-descriptions :column="3" size="small" border>
              <el-descriptions-item label="客户">{{ current.customerCode }} {{ current.customerName }}</el-descriptions-item>
              <el-descriptions-item label="预期金额">{{ current.expectAmount == null ? '-' : '¥ ' + fmtAmount(current.expectAmount) }}</el-descriptions-item>
              <el-descriptions-item label="预计成交">{{ current.expectCloseDate || '-' }}</el-descriptions-item>
              <el-descriptions-item label="阶段概率">{{ current.stageProbability == null ? '-' : current.stageProbability + '%' }}</el-descriptions-item>
              <el-descriptions-item label="下一步" :span="2">{{ current.nextAction || '-' }}（{{ current.nextActionDate || '-' }}）</el-descriptions-item>
              <el-descriptions-item label="负责人">{{ current.ownerName || current.ownerId || '-' }}</el-descriptions-item>
              <el-descriptions-item label="竞争分析" :span="2">{{ current.competition || '-' }}</el-descriptions-item>
              <el-descriptions-item label="需求摘要" :span="3">{{ current.demandSummary || '-' }}</el-descriptions-item>
              <el-descriptions-item v-if="current.status === 'LOST'" label="丢失原因" :span="3">
                <el-tag type="danger" size="small">{{ lossName(current.lossCategory) }}</el-tag>
                {{ current.lossRemark }}
              </el-descriptions-item>
            </el-descriptions>
          </el-card>

          <!-- 阶段推进日志 -->
          <el-card style="margin-bottom: 12px;">
            <template #header><span style="font-weight: bold;">阶段推进与审批</span></template>
            <el-table :data="logs" size="small" stripe>
              <el-table-column label="推进" min-width="200">
                <template #default="{ row }">{{ stageName(row.fromStage) }} → {{ stageName(row.toStage) }}</template>
              </el-table-column>
              <el-table-column label="概率" width="70">
                <template #default="{ row }">{{ row.probability == null ? '-' : row.probability + '%' }}</template>
              </el-table-column>
              <el-table-column prop="nextAction" label="下一步" min-width="140" show-overflow-tooltip />
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="logTagType(row.status)">{{ logName(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="审批意见" min-width="130">
                <template #default="{ row }">{{ row.opinion || '-' }}</template>
              </el-table-column>
              <el-table-column label="时间" width="150">
                <template #default="{ row }">{{ fmtTime(row.decideAt || row.applyAt) }}</template>
              </el-table-column>
            </el-table>
            <div v-if="current.stagePending" class="form-tip" style="margin-top: 8px;">
              审批签署请到「审批中心」（/api/qms/approvals 待办）以销售经理身份处理；通过后阶段自动跃迁。
            </div>
          </el-card>

          <!-- 跟进记录 -->
          <el-card>
            <template #header><span style="font-weight: bold;">跟进记录（仅追加，不可改删）</span></template>
            <el-form inline style="margin-bottom: 8px;">
              <el-form-item label="方式">
                <el-select v-model="fuForm.followType" style="width: 130px;">
                  <el-option label="电话" value="CALL" />
                  <el-option label="拜访" value="VISIT" />
                  <el-option label="演示" value="DEMO" />
                  <el-option label="方案发送" value="PROPOSAL" />
                  <el-option label="微信" value="WECHAT" />
                  <el-option label="其他" value="OTHER" />
                </el-select>
              </el-form-item>
              <el-form-item label="内容">
                <el-input v-model="fuForm.content" style="width: 320px;" maxlength="2000" placeholder="跟进内容" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" size="small" @click="addFollowup">追加</el-button>
              </el-form-item>
            </el-form>
            <el-timeline>
              <el-timeline-item v-for="f in followups" :key="f.id"
                :timestamp="fmtTime(f.followAt)" placement="top"
                :type="f.followType === 'SYSTEM' ? 'warning' : 'primary'">
                <div>
                  <el-tag size="small" :type="f.followType === 'SYSTEM' ? 'warning' : 'info'" style="margin-right: 6px;">
                    {{ fuTypeName(f.followType) }}
                  </el-tag>
                  <span>{{ f.content }}</span>
                  <div class="form-tip">{{ f.operatorName || f.operatorId }}</div>
                </div>
              </el-timeline-item>
            </el-timeline>
            <el-empty v-if="!followups.length" description="暂无跟进记录" :image-size="60" />
          </el-card>
        </template>
      </el-col>
    </el-row>

    <!-- ============ 推进阶段对话框 ============ -->
    <el-dialog v-model="advVisible" title="推进阶段" width="560px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px;"
        :title="`当前「${stageName(current?.stage)}」→ 目标「${stageName(advForm.toStage)}」`"
        description="提交后生成销售经理审批任务；审批通过前商机停在原阶段。" />
      <el-form :model="advForm" label-width="120px">
        <el-form-item label="阶段概率 %" required>
          <el-input-number v-model="advForm.probability" :min="0" :max="100" />
        </el-form-item>
        <el-form-item label="下一步行动" required>
          <el-input v-model="advForm.nextAction" maxlength="500" placeholder="如：安排样机演示" />
        </el-form-item>
        <el-form-item label="预计完成日期" required>
          <el-date-picker v-model="advForm.nextActionDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="advVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitAdvance">提交审批</el-button>
      </template>
    </el-dialog>

    <!-- ============ 丢失归档对话框 ============ -->
    <el-dialog v-model="lostVisible" title="丢失归档" width="520px">
      <el-form :model="lostForm" label-width="110px">
        <el-form-item label="丢失原因" required>
          <el-select v-model="lostForm.category" style="width: 100%;">
            <el-option label="价格" value="PRICE" />
            <el-option label="功能" value="FUNCTION" />
            <el-option label="竞品" value="COMPETITOR" />
            <el-option label="需求变更" value="DEMAND_CHANGE" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明" required>
          <el-input v-model="lostForm.remark" type="textarea" :rows="3" maxlength="500" placeholder="归档后只读，进入漏斗统计分母" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="lostVisible = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="submitLost">确认归档</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import {
  getOpportunitiesApi, getOpportunityApi, advanceStageApi, markLostApi,
  getStageLogsApi, getOppFollowupsApi, addOppFollowupApi, checkQuoteApi
} from '@/api/crm/opportunity'

const route = useRoute()
const router = useRouter()

const STAGES = [
  { value: 'REQUIREMENT', label: '需求确认' },
  { value: 'DEMO', label: '方案演示' },
  { value: 'NEGOTIATION', label: '商务谈判' },
  { value: 'QUOTE', label: '报价' },
  { value: 'CONTRACT', label: '合同签订' }
]
const NEXT = { REQUIREMENT: 'DEMO', DEMO: 'NEGOTIATION', NEGOTIATION: 'QUOTE', QUOTE: 'CONTRACT' }

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const pageNo = ref(1)
const keyword = ref('')
const current = ref(null)
const logs = ref([])
const followups = ref([])
const saving = ref(false)
const fuForm = reactive({ followType: 'CALL', content: '' })

onMounted(async () => {
  await loadList(1)
  if (route.query.oppId) {
    const res = await getOpportunityApi(route.query.oppId)
    selectOpp(res.data)
  }
})

async function loadList(page) {
  if (page) pageNo.value = page
  loading.value = true
  try {
    const res = await getOpportunitiesApi({ current: pageNo.value, size: 10, keyword: keyword.value })
    rows.value = res.data.records
    total.value = res.data.total
    // 保持选中态
    if (current.value) {
      const still = rows.value.find(r => r.id === current.value.id)
      if (still) current.value = still
    }
  } finally {
    loading.value = false
  }
}

async function selectOpp(row) {
  if (!row) return
  const res = await getOpportunityApi(row.id)
  current.value = res.data
  await Promise.all([loadLogs(), loadFollowups()])
}

async function loadLogs() {
  const res = await getStageLogsApi(current.value.id)
  logs.value = res.data || []
}
async function loadFollowups() {
  const res = await getOppFollowupsApi(current.value.id)
  followups.value = res.data || []
}

// ---------- 阶段推进 ----------
const advVisible = ref(false)
const advForm = reactive({ toStage: '', probability: null, nextAction: '', nextActionDate: '' })

function openAdvance() {
  const target = NEXT[current.value.stage]
  if (!target) {
    ElMessage.warning('已是最后阶段（合同签订），不可继续推进')
    return
  }
  advForm.toStage = target
  advForm.probability = null
  advForm.nextAction = ''
  advForm.nextActionDate = ''
  advVisible.value = true
}

async function submitAdvance() {
  if (advForm.probability == null || !advForm.nextAction || !advForm.nextActionDate) {
    ElMessage.warning('阶段概率 / 下一步行动 / 预计完成日期必填')
    return
  }
  saving.value = true
  try {
    await advanceStageApi(current.value.id, { ...advForm })
    ElMessage.success('已提交销售经理审批，通过前商机停在原阶段')
    advVisible.value = false
    await selectOpp({ id: current.value.id })
    loadList()
  } finally {
    saving.value = false
  }
}

// ---------- 丢失归档 ----------
const lostVisible = ref(false)
const lostForm = reactive({ category: '', remark: '' })

function openLost() {
  lostForm.category = ''
  lostForm.remark = ''
  lostVisible.value = true
}

async function submitLost() {
  if (!lostForm.category || !lostForm.remark) {
    ElMessage.warning('丢失原因分类与说明必填')
    return
  }
  saving.value = true
  try {
    await markLostApi(current.value.id, { ...lostForm })
    ElMessage.success('已归档（只读，计入漏斗统计）')
    lostVisible.value = false
    await selectOpp({ id: current.value.id })
    loadList()
  } finally {
    saving.value = false
  }
}

// ---------- 跟进 ----------
async function addFollowup() {
  if (!fuForm.content) {
    ElMessage.warning('跟进内容必填')
    return
  }
  await addOppFollowupApi(current.value.id, { ...fuForm })
  fuForm.content = ''
  ElMessage.success('跟进已追加')
  await loadFollowups()
}

// ---------- 一键创建报价（3.1.2 同闸口） ----------
async function tryCreateQuote() {
  const res = await checkQuoteApi(current.value.id)
  const d = res.data || {}
  if (!d.ok) {
    ElMessage.error(d.reason || '不可引用')
    await loadFollowups() // 原因已回写追踪记录
    return
  }
  await ElMessageBox.confirm(
    `校验通过：${d.prefill.oppNo} ${d.prefill.oppName}。跳转报价创建页（3.2.1）并带出客户与需求？`,
    '创建报价', { type: 'info' })
  jumpToQuote(d.prefill)
}

function jumpToQuote(prefill) {
  // 3.2.1 报价创建页随报价管理批次交付；未就位时给出明确提示而非空白路由
  const resolved = router.resolve({ path: '/m/3.2.1', query: { ...prefill } })
  if (resolved.matched.length && resolved.matched[0].path) {
    router.push(resolved)
  } else {
    ElMessage.warning('报价创建页（3.2.1）将在报价管理批次交付，预填数据已校验通过')
  }
}

// ---------- 展示辅助 ----------
function stageName(s) { return (STAGES.find(x => x.value === s) || {}).label || s || '-' }
function stageTagType(s) {
  return { REQUIREMENT: 'info', DEMO: '', NEGOTIATION: 'warning', QUOTE: 'primary', CONTRACT: 'success' }[s] || 'info'
}
function statusName(s) { return { OPEN: '跟进中', WON: '已赢单', LOST: '已丢失', CLOSED: '已关闭' }[s] || s }
function statusType(s) { return { OPEN: '', WON: 'success', LOST: 'danger', CLOSED: 'info' }[s] || 'info' }
function logName(s) { return { PENDING: '审批中', APPROVED: '已通过', REJECTED: '已驳回' }[s] || s }
function logTagType(s) { return { PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger' }[s] || 'info' }
function lossName(c) {
  return { PRICE: '价格', FUNCTION: '功能', COMPETITOR: '竞品', DEMAND_CHANGE: '需求变更', OTHER: '其他' }[c] || c
}
function fuTypeName(t) {
  return { CALL: '电话', VISIT: '拜访', DEMO: '演示', PROPOSAL: '方案发送', WECHAT: '微信', SYSTEM: '系统', OTHER: '其他' }[t] || t
}
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '-' }
function fmtAmount(v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<style scoped>
.opp-fu-page { padding: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; }
.form-tip { color: #909399; font-size: 12px; }
</style>
