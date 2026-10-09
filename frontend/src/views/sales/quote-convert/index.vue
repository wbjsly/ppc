<template>
  <div class="qcv-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="报价转化（3.2.3）"
      description="一键将已发布报价转为 SO：校验有效期、客户信用状态与占用；转化回写报价关联 SO，成功后商机自动推进「合同签订」。过期报价请先开新版本更新。"
    />

    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">可转化报价</span>
          <div class="header-actions">
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px;" @change="loadData(1)">
              <el-option label="已发布（可转化）" value="PUBLISHED" />
              <el-option label="已转化" value="CONVERTED" />
              <el-option label="旧版本" value="SUPERSEDED" />
              <el-option label="审批中" value="PENDING" />
            </el-select>
            <el-input v-model="query.keyword" placeholder="编号/客户" clearable
                      :prefix-icon="Search" style="width: 190px;" @keyup.enter="loadData(1)" />
            <el-button type="primary" @click="loadData(1)">查询</el-button>
            <el-button :icon="Refresh" @click="loadData()">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="报价编号" width="155" show-overflow-tooltip>
          <template #default="{ row }">{{ row.quoteNo || row.draftNo }}</template>
        </el-table-column>
        <el-table-column prop="oppNo" label="商机" width="150" show-overflow-tooltip />
        <el-table-column prop="customerName" label="客户" min-width="140" show-overflow-tooltip />
        <el-table-column label="金额" width="120" align="right">
          <template #default="{ row }">¥ {{ fmt(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column prop="versionNo" label="版本" width="66" align="center" />
        <el-table-column label="有效期" width="150">
          <template #default="{ row }">
            <template v-if="row.validTo">
              {{ row.validFrom }} ~ {{ row.validTo }}
              <el-tag v-if="expired(row)" type="danger" size="small" style="margin-left: 4px;">过期</el-tag>
            </template>
            <span v-else style="color: #909399;">未发布</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="94">
          <template #default="{ row }">
            <el-tag size="small" :type="stType(row.status)">{{ stName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="关联 SO" width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.soNo || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PUBLISHED' && !expired(row)" type="primary" size="small"
                       @click="doConvert(row)">转化 SO</el-button>
            <el-button v-else-if="row.status === 'PUBLISHED' && expired(row)" type="warning" size="small"
                       @click="checkRow(row)">查看原因</el-button>
            <el-button v-if="row.status === 'PUBLISHED'" link type="info"
                       @click="$router.push('/m/3.2.1')">更新报价</el-button>
            <el-button link type="primary" @click="openDetail(row)">明细</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end;"
        v-model:current-page="query.current" :page-size="10" :total="total"
        layout="total, prev, pager, next" @current-change="loadData" />
    </el-card>

    <!-- 明细 -->
    <el-dialog v-model="detailVisible" :title="`报价明细 — ${detail.quoteNo || detail.draftNo || ''}`" width="780px">
      <el-descriptions :column="3" size="small" border style="margin-bottom: 10px;">
        <el-descriptions-item label="商机">{{ detail.oppNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="客户">{{ detail.customerCode }} {{ detail.customerName }}</el-descriptions-item>
        <el-descriptions-item label="金额">¥ {{ fmt(detail.totalAmount) }}</el-descriptions-item>
        <el-descriptions-item label="有效期">{{ detail.validFrom || '-' }} ~ {{ detail.validTo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="版本">v{{ detail.versionNo || 1 }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ stName(detail.status) }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.soNo" label="关联 SO" :span="3">
          <b>{{ detail.soNo }}</b>
        </el-descriptions-item>
      </el-descriptions>
      <el-table :data="detailLines" size="small" border v-loading="linesLoading">
        <el-table-column prop="lineNo" label="#" width="46" />
        <el-table-column prop="itemCode" label="SKU" width="150" />
        <el-table-column prop="itemName" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="qty" label="数量" width="80" align="right" />
        <el-table-column label="单价" width="90" align="right">
          <template #default="{ row }">{{ row.unitPrice }}</template>
        </el-table-column>
        <el-table-column label="金额" width="100" align="right">
          <template #default="{ row }">{{ fmt(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="价源" width="90">
          <template #default="{ row }">{{ srcName(row.priceSource) }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { getQuotesApi, getQuoteApi, getQuoteLinesApi, convertCheckApi, convertQuoteApi } from '@/api/sd/quote'

const router = useRouter()

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ current: 1, keyword: '', status: 'PUBLISHED' })

const detailVisible = ref(false)
const detail = reactive({ id: '', quoteNo: '', draftNo: '', oppNo: '', customerCode: '', customerName: '',
  totalAmount: null, validFrom: '', validTo: '', versionNo: 1, status: '', soNo: '' })
const detailLines = ref([])
const linesLoading = ref(false)

onMounted(() => loadData(1))

async function loadData(page) {
  if (page) query.current = page
  loading.value = true
  try {
    const res = await getQuotesApi({
      current: query.current, size: 10,
      keyword: query.keyword, status: query.status || ''
    })
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function expired(row) {
  return row.status === 'PUBLISHED' && row.validTo && row.validTo < new Date().toISOString().slice(0, 10)
}

async function checkRow(row) {
  const res = await convertCheckApi(row.id)
  const d = res.data || {}
  if (d.ok) {
    ElMessage.success('校验通过，可转化')
  } else {
    ElMessageBox.alert(d.reason || '不可转化', '转化预检', { type: 'warning' })
  }
}

async function doConvert(row) {
  // 先服务端预检（有效期/信用/占用），通过再确认执行
  const chk = await convertCheckApi(row.id)
  if (!chk.data.ok) {
    ElMessageBox.alert(chk.data.reason || '不可转化', '转化预检', { type: 'warning' })
    return
  }
  await ElMessageBox.confirm(
    `将报价「${row.quoteNo}」（¥ ${fmt(row.totalAmount)}）转化为销售订单？` +
    '转化后报价状态回写、旧版本不可再转，关联商机将自动推进至「合同签订」。',
    '转化 SO', { type: 'warning' })
  const res = await convertQuoteApi(row.id)
  ElMessage.success(`转化成功：SO ${res.data.soNo}，商机已推进「合同签订」`)
  loadData()
}

async function openDetail(row) {
  const res = await getQuoteApi(row.id)
  Object.assign(detail, res.data)
  detailVisible.value = true
  linesLoading.value = true
  try {
    const lr = await getQuoteLinesApi(row.id)
    detailLines.value = lr.data || []
  } finally {
    linesLoading.value = false
  }
}

// ---------- 辅助 ----------
function stName(s) {
  return { DRAFT: '草稿', PENDING: '审批中', REJECTED: '已驳回', PUBLISHED: '已发布',
    SUPERSEDED: '旧版本', CONVERTED: '已转化' }[s] || s
}
function stType(s) {
  return { DRAFT: 'info', PENDING: 'warning', REJECTED: 'danger', PUBLISHED: 'success',
    SUPERSEDED: 'info', CONVERTED: 'primary' }[s] || 'info'
}
function srcName(s) {
  return { EXCLUSIVE: '客户专属', LADDER: '量价阶梯', TIME: '时间促销' }[s] || s || '-'
}
function fmt(v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<style scoped>
.qcv-page { padding: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
