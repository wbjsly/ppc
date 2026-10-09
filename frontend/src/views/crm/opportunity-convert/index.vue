<template>
  <div class="opp-cvt-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="商机转化（3.1.2）"
      description="本页只提供可转化商机列表、卡控校验与跳转，不承载报价表单（BR-4.3-07）。点击「创建报价」时校验商机状态与客户状态：不通过 L1 阻断并把不可引用原因回写追踪记录；通过则带出商机与客户跳转 3.2.1。"
    />

    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">可转化商机</span>
          <div class="header-actions">
            <el-input v-model="query.keyword" placeholder="编号/名称/客户" clearable
                      :prefix-icon="Search" style="width: 200px;" @keyup.enter="loadData(1)" />
            <el-select v-model="query.stage" placeholder="阶段" clearable style="width: 130px;">
              <el-option v-for="s in STAGES" :key="s.value" :label="s.label" :value="s.value" />
            </el-select>
            <el-button type="primary" @click="loadData(1)">查询</el-button>
            <el-button :icon="Refresh" @click="loadData(1)">刷新闸口状态</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="oppNo" label="商机编号" width="150" />
        <el-table-column prop="oppName" label="商机名称" min-width="150" show-overflow-tooltip />
        <el-table-column label="客户" width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.customerCode }} {{ row.customerName }}</template>
        </el-table-column>
        <el-table-column label="预期金额" width="120" align="right">
          <template #default="{ row }">{{ row.expectAmount == null ? '-' : '¥ ' + fmtAmount(row.expectAmount) }}</template>
        </el-table-column>
        <el-table-column label="阶段" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="stageTagType(row.stage)">{{ stageName(row.stage) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="闸口状态" min-width="200">
          <template #default="{ row }">
            <el-tag v-if="gateOk(row)" type="success" size="small">可转化</el-tag>
            <template v-else>
              <el-tooltip :content="row.quoteBlockReason || gateHint(row)" placement="top">
                <el-tag type="danger" size="small">{{ gateHint(row) }}</el-tag>
              </el-tooltip>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" size="small" @click="createQuote(row)">创建报价</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end;"
        v-model:current-page="query.current" v-model:page-size="query.size"
        :total="total" :page-sizes="[10, 20, 50]"
        layout="total, prev, pager, next" @current-change="loadData" />
    </el-card>

    <!-- 校验结果（失败时展示阻断原因） -->
    <el-dialog v-model="blockVisible" title="不可引用" width="480px">
      <el-result icon="error" :title="blockReason" sub-title="原因已回写商机追踪记录；达到报价阶段或解决客户状态后可再次校验。">
        <template #extra>
          <el-button type="primary" @click="blockVisible = false">知道了</el-button>
        </template>
      </el-result>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { getOpportunitiesApi, checkQuoteApi } from '@/api/crm/opportunity'

const router = useRouter()

const STAGES = [
  { value: 'REQUIREMENT', label: '需求确认' },
  { value: 'DEMO', label: '方案演示' },
  { value: 'NEGOTIATION', label: '商务谈判' },
  { value: 'QUOTE', label: '报价' },
  { value: 'CONTRACT', label: '合同签订' }
]
const STAGE_ORDER = { REQUIREMENT: 0, DEMO: 1, NEGOTIATION: 2, QUOTE: 3, CONTRACT: 4 }

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ current: 1, size: 10, keyword: '', stage: '' })
const blockVisible = ref(false)
const blockReason = ref('')

onMounted(() => loadData(1))

async function loadData(page) {
  if (page) query.value.current = page
  loading.value = true
  try {
    // 默认展示在跟商机（丢失/关闭的也允许查询，闸口会阻断）
    const res = await getOpportunitiesApi({ ...query.value, status: query.value.stage ? '' : 'OPEN' })
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

/** 前端预判（与服务端口径一致，服务端校验为准） */
function gateOk(row) {
  return row.status === 'OPEN' && STAGE_ORDER[row.stage] >= STAGE_ORDER.QUOTE
}
function gateHint(row) {
  if (row.status === 'LOST') return '已丢失归档'
  if (row.status === 'CLOSED') return '已关闭'
  return '未达报价阶段'
}

async function createQuote(row) {
  const res = await checkQuoteApi(row.id) // 校验 + 不通过时回写追踪记录
  const d = res.data || {}
  if (!d.ok) {
    blockReason.value = d.reason || '不可引用'
    blockVisible.value = true
    // 刷新列表以展示回写后的 quoteBlockReason
    loadData()
    return
  }
  await ElMessageBox.confirm(
    `校验通过：${d.prefill.oppNo} ${d.prefill.oppName}（${d.prefill.customerCode} ${d.prefill.customerName}）。跳转报价创建页（3.2.1）并带出商机与需求明细？`,
    '创建报价', { type: 'success' })
  const resolved = router.resolve({ path: '/m/3.2.1', query: { ...d.prefill } })
  if (resolved.matched.length && resolved.matched[0].path) {
    router.push(resolved)
  } else {
    ElMessage.warning('报价创建页（3.2.1）将在报价管理批次交付，预填数据已校验通过')
  }
}

function stageName(s) { return (STAGES.find(x => x.value === s) || {}).label || s }
function stageTagType(s) {
  return { REQUIREMENT: 'info', DEMO: '', NEGOTIATION: 'warning', QUOTE: 'primary', CONTRACT: 'success' }[s] || 'info'
}
function statusName(s) { return { OPEN: '跟进中', WON: '已赢单', LOST: '已丢失', CLOSED: '已关闭' }[s] || s }
function statusType(s) { return { OPEN: '', WON: 'success', LOST: 'danger', CLOSED: 'info' }[s] || 'info' }
function fmtAmount(v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<style scoped>
.opp-cvt-page { padding: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
