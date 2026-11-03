<template>
  <div class="opp-board">
    <PageInfo title="商机推进（11.1.3）" description="CRM 管理视角看板：页顶漏斗/转化率/赢率自动统计（BR-4.8-09），待推进清单含超期与停留 30 天预警；推进/跟进/丢失等操作跳 3.1.3 商机跟进页执行，本页不重复承载操作表单。" style="margin-bottom: 12px;" />

    <!-- ============ 页顶漏斗统计 ============ -->
    <el-row :gutter="12" style="margin-bottom: 12px;">
      <el-col :span="4" v-for="b in funnel" :key="b.stage">
        <el-card shadow="never" class="funnel-card">
          <div class="funnel-name">{{ b.stageName }}</div>
          <div class="funnel-count">{{ b.count }}</div>
          <div class="funnel-amount">¥ {{ fmtAmount(b.amount) }}</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never" class="funnel-card highlight">
          <div class="funnel-name">赢率</div>
          <div class="funnel-count">{{ stats.winRate || 0 }}%</div>
          <div class="funnel-amount">赢 {{ stats.won || 0 }} / 负 {{ stats.lost || 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never" class="funnel-card highlight">
          <div class="funnel-name">转化率</div>
          <div class="funnel-count">{{ stats.conversionRate || 0 }}%</div>
          <div class="funnel-amount">线索 {{ stats.leadConverted || 0 }}/{{ stats.leadTotal || 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never" class="funnel-card highlight">
          <div class="funnel-name">平均销售周期</div>
          <div class="funnel-count">{{ stats.avgCycleDays || 0 }}</div>
          <div class="funnel-amount">天（已终结商机）</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ============ 待推进清单 ============ -->
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">待推进清单</span>
          <div class="header-actions">
            <el-input v-model="query.keyword" placeholder="编号/名称/客户" clearable
                      :prefix-icon="Search" style="width: 200px;" @keyup.enter="loadData(1)" />
            <el-select v-model="query.stage" placeholder="阶段" clearable style="width: 130px;">
              <el-option v-for="s in STAGES" :key="s.value" :label="s.label" :value="s.value" />
            </el-select>
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px;">
              <el-option label="跟进中" value="OPEN" />
              <el-option label="已赢单" value="WON" />
              <el-option label="已丢失" value="LOST" />
            </el-select>
            <el-button type="primary" @click="loadData(1)">查询</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="oppNo" label="商机编号" width="150" />
        <el-table-column prop="oppName" label="商机名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="customerName" label="客户" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.customerCode }} {{ row.customerName }}</template>
        </el-table-column>
        <el-table-column prop="expectAmount" label="预期金额" width="120" align="right">
          <template #default="{ row }">{{ row.expectAmount == null ? '-' : '¥ ' + fmtAmount(row.expectAmount) }}</template>
        </el-table-column>
        <el-table-column label="阶段" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="stageTagType(row.stage)">{{ stageName(row.stage) }}</el-tag>
            <el-tag v-if="row.stageOverdue === '1'" size="small" type="danger" style="margin-left: 4px;">超期</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="停留" width="90" align="center">
          <template #default="{ row }">
            <span :class="{ 'stale-warn': stayDays(row) >= 30 }">{{ stayDays(row) }} 天</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ownerName" label="负责人" width="100" />
        <el-table-column label="下一步" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.nextAction || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="goFollowup(row)">跟进操作</el-button>
          </template>
        </el-table-column>
      </el-table>

            <AppPagination
        style="margin-top: 12px; justify-content: flex-end;"
        :total="total"
        v-model:page-size="query.size"
        @current-change="(p) => loadData(p)"
      />
    </el-card>
  </div>
</template>

<script setup>
import AppPagination from '@/components/AppPagination.vue'
import PageInfo from '@/components/PageInfo.vue'
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { getOppStatsApi, getOpportunitiesApi } from '@/api/crm/opportunity'

const router = useRouter()

const STAGES = [
  { value: 'REQUIREMENT', label: '需求确认' },
  { value: 'DEMO', label: '方案演示' },
  { value: 'NEGOTIATION', label: '商务谈判' },
  { value: 'QUOTE', label: '报价' },
  { value: 'CONTRACT', label: '合同签订' }
]

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const stats = ref({})
const funnel = ref([])
const query = ref({ current: 1, size: 20, keyword: '', stage: '', status: 'OPEN' })

onMounted(() => {
  loadData(1)
  loadStats()
})

async function loadData(page) {
  if (page) query.value.current = page
  loading.value = true
  try {
    const res = await getOpportunitiesApi(query.value)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  const res = await getOppStatsApi()
  stats.value = res.data || {}
  funnel.value = stats.value.funnel || []
}

/** 操作跳 3.1.3 商机跟进页（本页不承载操作表单） */
function goFollowup(row) {
  router.push({ path: '/m/3.1.3', query: { id: row.id } })
}

/** 同阶段停留天数（stageEnteredAt 起算，30 天预警） */
function stayDays(row) {
  if (!row.stageEnteredAt) return 0
  const start = new Date(String(row.stageEnteredAt).replace('T', ' ').slice(0, 19)).getTime()
  if (Number.isNaN(start)) return 0
  return Math.max(0, Math.floor((Date.now() - start) / 86400000))
}

// ---------- 展示辅助 ----------
function stageName(s) {
  return (STAGES.find(x => x.value === s) || {}).label || s
}
function stageTagType(s) {
  return { REQUIREMENT: 'info', DEMO: '', NEGOTIATION: 'warning', QUOTE: 'primary', CONTRACT: 'success' }[s] || 'info'
}
function statusName(s) {
  return { OPEN: '跟进中', WON: '已赢单', LOST: '已丢失', CLOSED: '已关闭' }[s] || s
}
function statusType(s) {
  return { OPEN: '', WON: 'success', LOST: 'danger', CLOSED: 'info' }[s] || 'info'
}
function fmtAmount(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<style scoped>
.opp-board { padding: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.funnel-card { text-align: center; }
.funnel-card .funnel-name { color: #909399; font-size: 12px; }
.funnel-card .funnel-count { font-size: 24px; font-weight: bold; color: #303133; margin: 4px 0; }
.funnel-card .funnel-amount { color: #909399; font-size: 12px; }
.funnel-card.highlight .funnel-count { color: #f56c6c; }
.stale-warn { color: #e6a23c; font-weight: bold; }
</style>
