<template>
  <div class="cost-element">
    <el-card shadow="never">
      <div class="head">
        <span class="title">{{ isMaterial ? '5.11.1 直接材料归集' : '5.11.2 直接人工归集' }}</span>
        <div class="filters">
          <el-input v-model="keyword" placeholder="工单号/物料" clearable style="width: 180px"
            @keyup.enter="load" />
          <el-date-picker v-model="period" type="month" placeholder="归集期次"
            value-format="YYYYMM" style="width: 130px" />
          <el-button type="primary" @click="load">查询</el-button>
        </div>
      </div>

      <el-table :data="rows" border stripe @row-click="openDetail" v-loading="loading">
        <el-table-column prop="summary.moNo" label="工单号" width="140" />
        <el-table-column prop="summary.period" label="期次" width="80" />
        <el-table-column :label="isMaterial ? '直接材料' : '直接人工'" width="130" align="right">
          <template #default="{ row }">
            {{ fmt(isMaterial ? row.summary.materialCost : row.summary.laborCost) }}
          </template>
        </el-table-column>
        <el-table-column label="总成本" width="120" align="right">
          <template #default="{ row }">{{ fmt(row.summary.totalCost) }}</template>
        </el-table-column>
        <el-table-column label="合格产出" width="90" align="right">
          <template #default="{ row }">{{ fmt(row.summary.goodQty) }}</template>
        </el-table-column>
        <el-table-column label="单位成本" width="110" align="right">
          <template #default="{ row }">{{ fmt(row.summary.unitCost) }}</template>
        </el-table-column>
        <el-table-column label="差异率" width="90" align="right">
          <template #default="{ row }">
            <el-tag :type="varianceTag(row.summary)" size="small">
              {{ pct(row.summary.varRate) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="差异报告" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.summary.reportStatus === 'RAISED'" type="danger" size="small">待审阅</el-tag>
            <el-tag v-else-if="row.summary.reportStatus === 'REVIEWED'" type="success" size="small">已审阅</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="summary.mark" label="缺素提示" min-width="150" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click.stop="openDetail(row)">明细</el-button>
            <el-button size="small" type="warning" :disabled="!canWrite" :loading="rerunning"
              @click.stop="rerun(row)">重新归集</el-button>
            <el-button v-if="row.summary.reportStatus === 'RAISED' && canReview"
              size="small" type="success" @click.stop="review(row)">审阅</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!rows.length && !loading" class="empty">暂无归集数据——工单完工后自动生成，或联系生产成本会计重跑</div>
    </el-card>

    <el-drawer v-model="drawer" :title="detailTitle" size="60%">
      <template v-if="detail">
        <el-descriptions :column="3" border size="small" class="desc">
          <el-descriptions-item label="总成本">{{ fmt(detail.summary?.totalCost) }}</el-descriptions-item>
          <el-descriptions-item label="料">{{ fmt(detail.summary?.materialCost) }}</el-descriptions-item>
          <el-descriptions-item label="工">{{ fmt(detail.summary?.laborCost) }}</el-descriptions-item>
          <el-descriptions-item label="费">{{ fmt(detail.summary?.overheadCost) }}</el-descriptions-item>
          <el-descriptions-item label="单位成本">{{ fmt(detail.summary?.unitCost) }}</el-descriptions-item>
          <el-descriptions-item label="标准总成本">{{ fmt(detail.summary?.stdTotalCost) }}</el-descriptions-item>
          <el-descriptions-item label="总差异">{{ fmt(detail.summary?.varTotal) }}</el-descriptions-item>
          <el-descriptions-item label="差异率">{{ pct(detail.summary?.varRate) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag v-if="detail.summary?.reportStatus === 'RAISED'" type="danger" size="small">差异待审阅</el-tag>
            <el-tag v-else-if="detail.summary?.reportStatus === 'REVIEWED'" type="success" size="small">差异已审阅</el-tag>
            <span v-else>正常</span>
          </el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="detail.summary?.mark" :title="detail.summary.mark" type="warning"
          :closable="false" style="margin-top: 12px" />
        <el-table :data="elementLines" border stripe size="small" style="margin-top: 12px">
          <el-table-column prop="dimKey" :label="isMaterial ? '物料编码' : '报工单号'" width="150" />
          <el-table-column prop="dimName" label="名称" min-width="140" />
          <el-table-column :label="isMaterial ? '投料数量' : '工时'" width="110" align="right">
            <template #default="{ row }">{{ fmt(row.qty) }}</template>
          </el-table-column>
          <el-table-column label="单价/费率" width="110" align="right">
            <template #default="{ row }">{{ fmt(row.rate) }}</template>
          </el-table-column>
          <el-table-column label="金额" width="120" align="right">
            <template #default="{ row }">{{ fmt(row.amount) }}</template>
          </el-table-column>
          <el-table-column prop="mark" label="标记" width="110" />
        </el-table>
        <div class="tip">口径：材料按物料标准成本（缺价取最近采购价）；人工按已确认报工 × 标准工资率
          （STD_LABOR_RATE）。重跑为重建式，不影响已生成完工凭证。</div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { listCostApi, costDetailApi, rerunCostApi, reviewVarianceApi } from '@/api/mrp/cost'

const route = useRoute()
const userStore = useUserStore()
const isMaterial = route.meta.element !== 'LABOR'

const rows = ref([])
const loading = ref(false)
const keyword = ref('')
const period = ref('')
const drawer = ref(false)
const detail = ref(null)
const rerunning = ref(false)

const roles = computed(() => userStore.userInfo?.roles || [])
const canWrite = computed(() =>
  roles.value.includes('ROLE_COST_ACCOUNTANT') || roles.value.includes('ROLE_ADMIN'))
const canReview = computed(() =>
  roles.value.includes('ROLE_PLAN_MGR') || roles.value.includes('ROLE_ADMIN'))

const detailTitle = computed(() =>
  detail.value?.summary ? `归集明细 - ${detail.value.summary.moNo}` : '归集明细')
const elementLines = computed(() => (detail.value?.lines || [])
  .filter(l => l.costType === (isMaterial ? 'MATERIAL' : 'LABOR')))

function fmt(v) {
  if (v === null || v === undefined) return '—'
  return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })
}
function pct(v) {
  if (v === null || v === undefined) return '—'
  return (Number(v) * 100).toFixed(2) + '%'
}
function varianceTag(s) {
  return Number(s?.varRate) > 0.05 ? 'danger' : 'success'
}

async function load() {
  loading.value = true
  try {
    const params = {}
    if (keyword.value) params.keyword = keyword.value
    if (period.value) params.period = period.value
    rows.value = (await listCostApi(params)).data || []
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  const res = await costDetailApi(row.summary.moId)
  detail.value = res.data
  drawer.value = true
}

async function rerun(row) {
  rerunning.value = true
  try {
    await rerunCostApi(row.summary.moId)
    ElMessage.success('归集已重建')
    await load()
  } finally {
    rerunning.value = false
  }
}

async function review(row) {
  try {
    await reviewVarianceApi(row.summary.moId)
    ElMessage.success('差异报告已审阅')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '审阅失败')
  }
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-size: 16px; font-weight: 600; }
.filters { display: flex; gap: 8px; }
.empty { text-align: center; color: #909399; padding: 32px 0; }
.desc { margin-bottom: 4px; }
.tip { margin-top: 12px; color: #909399; font-size: 12px; line-height: 1.6; }
:deep(.el-table__row) { cursor: pointer; }
</style>
