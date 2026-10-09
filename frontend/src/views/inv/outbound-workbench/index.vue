<template>
  <div class="page">
    <el-alert :title="`${meta.name}（${meta.code}）`" type="info" :closable="false" style="margin-bottom: 8px">
      <template #default>
        {{ meta.desc }}
        <span v-if="typeDisabled">　业务类型已停用，暂不可发起过账；历史单据仍可查询。</span>
      </template>
    </el-alert>

    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item label="关键字">
        <el-input v-model="query.keyword" :placeholder="meta.placeholder" clearable style="width: 240px"
          @keyup.enter="reload" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="reload">查询</el-button>
      </el-form-item>
      <el-form-item v-if="typeCode === 'MATERIAL_OUT'">
        <el-button type="success" @click="$router.push('/inv/material-issue')">新建领料单</el-button>
      </el-form-item>
      <el-form-item v-if="typeCode === 'SCRAP_OUT'">
        <el-button type="success" @click="$router.push('/inv/scrap-order')">新建报废单</el-button>
      </el-form-item>
      <span v-if="asOf" class="as-of">数据截至 {{ asOf }}</span>
    </el-form>

    <el-tabs v-model="queueTab" @tab-change="onQueueChange">
      <el-tab-pane :label="`待处理（${meta.aLabel}）`" name="A" />
      <el-tab-pane :label="`已处理（${meta.bLabel}）`" name="B" />
    </el-tabs>

    <el-table :data="rows" v-loading="loading" border stripe>
      <el-table-column prop="docNo" :label="meta.docLabel" width="160" />
      <el-table-column :label="meta.sub1Label" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.sub1 || '-' }}</template>
      </el-table-column>
      <el-table-column :label="meta.sub2Label" width="120">
        <template #default="{ row }">{{ row.sub2 || '-' }}</template>
      </el-table-column>
      <el-table-column label="物料" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.itemSummary || '-' }}</template>
      </el-table-column>
      <el-table-column prop="qty" label="数量" width="90" align="right">
        <template #default="{ row }">{{ fmtNum(row.qty) }}</template>
      </el-table-column>
      <el-table-column label="金额" width="100" align="right">
        <template #default="{ row }">{{ row.amount == null ? '-' : fmtNum(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="标记" width="130">
        <template #default="{ row }">
          <el-tag v-if="row.note" type="warning" size="small">{{ row.note }}</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="createDate" label="创建时间" width="155">
        <template #default="{ row }">{{ fmtTime(row.createDate) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          <el-button v-if="queueTab === 'A' && canOperate" link type="primary" size="small"
            :loading="acting" @click="doPost(row)">过账</el-button>
          <el-button v-if="typeCode === 'SALES_OUT' && row.status === 'POSTED' && canOperate"
            link type="success" size="small" :loading="acting" @click="doConfirm(row)">发货确认</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && rows.length === 0" description="暂无任务数据" />
    <el-pagination v-if="total > 0" class="pager" background layout="total, prev, pager, next"
      :total="total" :page-size="query.size" :current-page="query.current" @current-change="onPage" />

    <!-- 详情抽屉：单据信息 + 该单流水（BIZ_DOC_NO 下钻） -->
    <el-drawer v-model="detailVisible" :title="`详情：${current.docNo || ''}`" size="640px">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item :label="meta.docLabel">{{ current.docNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusText(current.status) }}</el-descriptions-item>
        <el-descriptions-item :label="meta.sub1Label">{{ current.sub1 || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="meta.sub2Label">{{ current.sub2 || '-' }}</el-descriptions-item>
        <el-descriptions-item label="物料">{{ current.itemSummary || '-' }}</el-descriptions-item>
        <el-descriptions-item label="标记">{{ current.note || '-' }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ fmtNum(current.qty) }}</el-descriptions-item>
        <el-descriptions-item label="金额">{{ current.amount == null ? '-' : fmtNum(current.amount) }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">出入库流水</el-divider>
      <el-table :data="flows" border size="small" v-loading="flowLoading" max-height="360">
        <el-table-column prop="txnNo" label="流水号" width="150" />
        <el-table-column label="方向" width="60" align="center">
          <template #default="{ row }">
            <el-tag :type="row.direction === 'IN' ? 'success' : 'warning'" size="small">
              {{ row.direction === 'IN' ? '入' : '出' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="typeCode" label="类型" width="130" />
        <el-table-column prop="itemCode" label="物料" width="110" />
        <el-table-column prop="batchNo" label="批次" width="100" />
        <el-table-column prop="binCode" label="仓位" width="110">
          <template #default="{ row }">{{ row.binCode || '未分配' }}</template>
        </el-table-column>
        <el-table-column prop="qty" label="数量" width="70" align="right" />
        <el-table-column label="前后余额" width="110" align="right">
          <template #default="{ row }">{{ row.beforeQty }} → {{ row.afterQty }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!flowLoading && flows.length === 0" description="该单暂无流水" :image-size="60" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getOutboundQueueApi, postOutboundApi, confirmOutboundApi } from '@/api/inv/outbound-workbench'
import { getTransactionsByDocApi } from '@/api/inv/transaction'
import { useUserStore } from '@/store/user'

const route = useRoute()
const userStore = useUserStore()

// 类型路由参数（spec outbound-workbench：四路由共用一个组件，镜像入库侧）
const TYPE_BY_PATH = {
  '/m/4.5.1': 'SALES_OUT',
  '/m/4.5.2': 'MATERIAL_OUT',
  '/m/4.5.3': 'TRANSFER_OUT',
  '/m/4.5.4': 'SCRAP_OUT'
}
const typeCode = computed(() => TYPE_BY_PATH[route.path] || 'SALES_OUT')

const META = {
  SALES_OUT: {
    name: '销售出库', code: 'SALES_OUT', docLabel: '发货单号',
    sub1Label: '客户', sub2Label: '仓库', aLabel: '待过账', bLabel: '待发货确认',
    placeholder: '发货单/客户/仓库/物料', desc: '队列A=待过账（与 3.7.x 发货页同一后端动作）；队列B=已出库待发货确认（回写 SO 与应收事件由销售域承载）。'
  },
  MATERIAL_OUT: {
    name: '领料出库', code: 'MATERIAL_OUT', docLabel: '领料单号',
    sub1Label: '领料类型', sub2Label: '工单号', aLabel: '待过账', bLabel: '已过账',
    placeholder: '领料单/工单/物料', desc: '队列A=待过账（创建领料单请点右上「新建领料单」）；过账经通用引擎 FIFO 配批扣减。'
  },
  TRANSFER_OUT: {
    name: '调拨出库', code: 'TRANSFER_OUT', docLabel: '调拨单号',
    sub1Label: '调出 → 调入', sub2Label: '法人', aLabel: '待出库过账', bLabel: '在途（出库完成）',
    placeholder: '调拨单/仓库/物料', desc: '本页仅出库段过账；调拨单创建与全生命周期在 4.12.1 仓间调拨，在途跟踪在 4.12.2。'
  },
  SCRAP_OUT: {
    name: '报废出库', code: 'SCRAP_OUT', docLabel: '报废单号',
    sub1Label: '原因 / NCR', sub2Label: '仓库', aLabel: '已批待过账', bLabel: '已过账待核销',
    placeholder: '报废单/NCR/仓库/物料', desc: '队列A=已批准待过账（呆滞须先完成三方会签 C-4.4-14）；处置核销在报废单页办理。'
  }
}
const meta = computed(() => META[typeCode.value] || META.SALES_OUT)

// 流水抽屉 bizDocType（与引擎 Request 的 bizDocType 一致）
const BIZ_TYPE = {
  SALES_OUT: 'SHIPMENT',
  MATERIAL_OUT: 'MATERIAL_ISSUE',
  TRANSFER_OUT: 'TRANSFER',
  SCRAP_OUT: 'SCRAP'
}

const loading = ref(false)
const acting = ref(false)
const rows = ref([])
const total = ref(0)
const asOf = ref('')
const queueTab = ref('A')
const typeEnabled = ref(null)
const query = ref({ keyword: '', current: 1, size: 20 })

const detailVisible = ref(false)
const flowLoading = ref(false)
const flows = ref([])
const current = ref({})

const canOperate = computed(() => {
  const roles = userStore.userInfo?.roles || []
  return roles.includes('ROLE_ADMIN') || roles.includes('ROLE_WAREHOUSE')
})
const typeDisabled = computed(() => typeEnabled.value === false || typeEnabled.value === 0)

function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}
function fmtNum(v) {
  if (v == null) return '-'
  const n = Number(v)
  return Number.isInteger(n) ? String(n) : n.toFixed(2)
}
function statusText(s) {
  const M = {
    DRAFT: '草稿', POSTED: '已过账', CONFIRMED: '已确认', OUT_POSTED: '在途',
    APPROVED: '已批准', DISPOSED: '已核销', CANCELLED: '已作废', SIGNED: '已签收'
  }
  return M[s] || s || '-'
}
function statusTag(s) {
  return { DRAFT: 'info', POSTED: 'warning', CONFIRMED: 'success', OUT_POSTED: 'warning',
    APPROVED: 'success', DISPOSED: 'success', CANCELLED: 'danger', SIGNED: 'success' }[s] || 'info'
}

async function loadData() {
  loading.value = true
  try {
    const params = {
      queue: queueTab.value,
      current: query.value.current,
      size: query.value.size
    }
    if (query.value.keyword) params.keyword = query.value.keyword
    const res = await getOutboundQueueApi(typeCode.value, params)
    rows.value = res.data.rows || []
    total.value = Number(res.data.total || 0)
    asOf.value = fmtTime(res.data.asOf)
  } finally {
    loading.value = false
  }
}

function reload() {
  query.value.current = 1
  loadData()
}
function onQueueChange() {
  query.value.current = 1
  loadData()
}
function onPage(p) {
  query.value.current = p
  loadData()
}

/** 过账：与域页面同一后端动作（双入口同结果，spec outbound-workbench） */
async function doPost(row) {
  await ElMessageBox.confirm(
    `确认对 ${row.docNo} 执行出库过账？（与域页面同一动作，过账不可撤销）`,
    '过账确认', { type: 'warning' })
  acting.value = true
  try {
    const res = await postOutboundApi(typeCode.value, row.docId)
    if (res.code === 200) {
      ElMessage.success('过账成功，已生成出库流水' + (typeCode.value === 'SCRAP_OUT' ? '与报废凭证' : ''))
      loadData()
    } else {
      ElMessage.error(res.message || '过账失败')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '过账失败')
  } finally {
    acting.value = false
  }
}

/** 发货确认（仅 SALES_OUT 队列B；POSTED → CONFIRMED） */
async function doConfirm(row) {
  await ElMessageBox.confirm(
    `确认发货完成？（${row.docNo}，确认后回写 SO 已发量与应收事件）`,
    '发货确认', { type: 'warning' })
  acting.value = true
  try {
    const res = await confirmOutboundApi(row.docId)
    if (res.code === 200) {
      ElMessage.success('发货已确认')
      loadData()
    } else {
      ElMessage.error(res.message || '确认失败')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '确认失败')
  } finally {
    acting.value = false
  }
}

async function openDetail(row) {
  current.value = row
  detailVisible.value = true
  flowLoading.value = true
  flows.value = []
  try {
    const res = await getTransactionsByDocApi(BIZ_TYPE[typeCode.value], row.docNo)
    flows.value = res.data || []
  } finally {
    flowLoading.value = false
  }
}

watch(() => route.path, () => {
  queueTab.value = 'A'
  query.value = { keyword: '', current: 1, size: 20 }
  loadData()
})

onMounted(loadData)
</script>

<style scoped>
.page { padding: 4px; }
.filter-bar { margin-bottom: 4px; }
.as-of { color: #909399; font-size: 12px; line-height: 32px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
