<template>
  <div class="page">
    <!-- 类型信息条 -->
    <el-alert v-if="typeInfo" :title="`${typeInfo.name}（${typeInfo.code}）`" type="info" :closable="false"
      style="margin-bottom: 8px">
      <template #default>
        <span v-if="isTypeDisabled">该业务类型已停用，暂不可发起过账；历史单据仍可查询。</span>
        <span v-else-if="isUnbuilt">待对应业务域（工单 5.4 / 调拨）上线后自动接入——类型已注册、接口契约就位。</span>
        <span v-else>来源单据视图 + 过账双入口（与采购收货页同后端动作）+ 入库确认。</span>
      </template>
    </el-alert>

    <!-- 4.4.3 退货镜像说明 -->
    <el-alert v-if="isReturnMirror" title="退货流程在销售退货（3.10）页面办理，本页为库存域镜像查询与流水"
      type="warning" :closable="false" style="margin-bottom: 8px" show-icon />

      <el-form :inline="true" class="filter-bar" @submit.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" :placeholder="keywordPlaceholder" clearable style="width: 220px" />
        </el-form-item>
        <el-form-item v-if="isPurchase" label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 150px">
            <el-option label="待过账" value="CREATED" />
            <el-option label="已过账" value="POSTED" />
            <el-option label="已确认" value="CONFIRMED" />
            <el-option label="已作废" value="CANCELLED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData">查询</el-button>
        </el-form-item>
        <span v-if="asOf" class="as-of">数据截至 {{ asOf }}</span>
      </el-form>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="docNo" :label="isPurchase ? '收货单号' : '退货单号'" width="150" />
        <el-table-column prop="sourceDocNo" :label="isPurchase ? '采购订单' : '销售订单'" width="140">
          <template #default="{ row }">{{ row.sourceDocNo || '-' }}</template>
        </el-table-column>
        <el-table-column :label="isPurchase ? '供应商' : '客户'" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.supplierName || row.customerName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="batchNo" label="批次" width="130">
          <template #default="{ row }">{{ row.batchNo || '-' }}</template>
        </el-table-column>
        <el-table-column v-if="isPurchase" label="检验状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.lotStatus" :type="lotTag(row.lotStatus)" size="small">{{ lotText(row.lotStatus) }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!isPurchase" prop="totalQty" label="数量" width="90" align="right" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="isPurchase" prop="postingDocNo" label="入库凭证" width="130">
          <template #default="{ row }">{{ row.postingDocNo || '-' }}</template>
        </el-table-column>
        <el-table-column v-if="isPurchase" prop="confirmBy" label="确认人" width="100">
          <template #default="{ row }">{{ row.confirmBy || '-' }}</template>
        </el-table-column>
        <el-table-column prop="createDate" label="创建时间" width="155">
          <template #default="{ row }">{{ fmtTime(row.createDate) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <template v-if="isPurchase">
              <el-button v-if="row.status === 'CREATED' && canOperate" link type="primary" size="small"
                :loading="acting" @click="doPost(row)">过账</el-button>
              <el-button v-if="row.status === 'POSTED' && canOperate" link type="success" size="small"
                :loading="acting" @click="doConfirm(row)">入库确认</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <!-- 空列表统一空态（未建域类型骨架 4.4.2/4.4.4；有数据类型为正常空页） -->
      <el-empty v-if="!loading && rows.length === 0"
        :description="emptyDesc" />

      <el-pagination v-if="total > 0" class="pager" background layout="total, prev, pager, next"
        :total="total" :page-size="query.size" :current-page="query.current" @current-change="onPage" />

    <!-- 详情抽屉：单据信息 + 该单流水 -->
    <el-drawer v-model="detailVisible" :title="`详情：${current.docNo || ''}`" size="620px">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="单号">{{ current.docNo }}</el-descriptions-item>
        <el-descriptions-item label="来源单">{{ current.sourceDocNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="批次">{{ current.batchNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusText(current.status) }}</el-descriptions-item>
        <el-descriptions-item v-if="isPurchase" label="检验状态">
          {{ current.lotStatus ? lotText(current.lotStatus) : '-' }}
        </el-descriptions-item>
        <el-descriptions-item v-if="isPurchase" label="入库凭证">{{ current.postingDocNo || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="isPurchase" label="确认人">{{ current.confirmBy || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="isPurchase" label="确认时间">{{ fmtTime(current.confirmAt) }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">出入库流水</el-divider>
      <el-table :data="flows" border size="small" v-loading="flowLoading" max-height="360">
        <el-table-column prop="txnNo" label="流水号" width="150" />
        <el-table-column label="方向" width="70" align="center">
          <template #default="{ row }">
            <el-tag :type="row.direction === 'IN' ? 'success' : 'warning'" size="small">
              {{ row.direction === 'IN' ? '入' : '出' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="typeCode" label="类型" width="140" />
        <el-table-column prop="itemCode" label="物料" width="120" />
        <el-table-column prop="batchNo" label="批次" width="110" />
        <el-table-column prop="qty" label="数量" width="80" align="right" />
        <el-table-column label="前后余额" width="120" align="right">
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
import { getWorkbenchTasksApi } from '@/api/inv/workbench'
import { getTransactionsByDocApi } from '@/api/inv/transaction'
import { postingGrApi, confirmGrApi } from '@/api/proc/goods-receipt'
import { useUserStore } from '@/store/user'

const route = useRoute()
const userStore = useUserStore()

// 类型路由参数（spec inbound-workbench：同组件 type 参数化）
const TYPE_BY_PATH = {
  '/m/4.4.1': 'PURCHASE_IN',
  '/m/4.4.2': 'WIP_IN',
  '/m/4.4.3': 'SALES_RETURN_IN',
  '/m/4.4.4': 'TRANSFER_IN'
}
const typeCode = computed(() => TYPE_BY_PATH[route.path] || 'PURCHASE_IN')
const isPurchase = computed(() => typeCode.value === 'PURCHASE_IN')
const isReturnMirror = computed(() => typeCode.value === 'SALES_RETURN_IN')
// 未建域空态：WIP_IN / TRANSFER_IN /（数据源恒空的类型由接口返回空，页面按 total=0 + 骨架文案渲染）
const UNBUILT = ['WIP_IN', 'TRANSFER_IN', 'SCRAP_OUT', 'ADJUST_IN', 'ADJUST_OUT']
const isUnbuilt = computed(() => UNBUILT.includes(typeCode.value))

const loading = ref(false)
const acting = ref(false)
const rows = ref([])
const total = ref(0)
const asOf = ref('')
const typeInfo = ref(null)
const typeEnabled = ref(null)
const query = ref({ keyword: '', status: '', current: 1, size: 20 })

const detailVisible = ref(false)
const flowLoading = ref(false)
const flows = ref([])
const current = ref({})

const canOperate = computed(() => {
  const roles = userStore.userInfo?.roles || []
  return roles.includes('ROLE_ADMIN') || roles.includes('ROLE_WAREHOUSE')
})

const keywordPlaceholder = computed(() =>
  isPurchase.value ? '收货单/PO/批次/供应商' : '退货单/SO/客户')

const isTypeDisabled = computed(() => typeEnabled.value === false || typeEnabled.value === 0)

const emptyDesc = computed(() => {
  if (isTypeDisabled.value) return '业务类型已停用，暂无任务'
  if (isUnbuilt.value) {
    return `暂无${typeInfo.value ? typeInfo.value.name : ''}数据 —— 待对应业务域上线后自动接入，类型与接口契约已就位`
  }
  return '暂无任务数据'
})

function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}

function statusText(s) {
  const GR = { CREATED: '待过账', POSTED: '已过账', CONFIRMED: '已确认', CANCELLED: '已作废' }
  if (GR[s]) return GR[s]
  return s || '-'
}

function statusTag(s) {
  return { CREATED: 'info', POSTED: 'warning', CONFIRMED: 'success', CANCELLED: 'danger' }[s] || 'info'
}

function lotText(s) {
  return { PENDING: '待检', RELEASED: '已放行', SKIPPED: '免检', REJECTED: '不合格' }[s] || s
}

function lotTag(s) {
  return { PENDING: 'warning', RELEASED: 'success', SKIPPED: 'info', REJECTED: 'danger' }[s] || 'info'
}

async function loadData() {
  loading.value = true
  try {
    const params = {
      type: typeCode.value,
      current: query.value.current,
      size: query.value.size
    }
    if (query.value.keyword) params.keyword = query.value.keyword
    if (query.value.status && isPurchase.value) params.status = query.value.status
    const res = await getWorkbenchTasksApi(params)
    typeInfo.value = res.data.type
    typeEnabled.value = res.data.type?.enabled
    rows.value = res.data.rows || []
    total.value = Number(res.data.total || 0)
    asOf.value = fmtTime(res.data.asOf)
  } finally {
    loading.value = false
  }
}

function onPage(p) {
  query.value.current = p
  loadData()
}

/** 过账：与采购收货页同一后端动作（双入口同权限，spec 场景） */
async function doPost(row) {
  await ElMessageBox.confirm(
    `确认对收货单 ${row.docNo} 执行入库过账？（与收货页 2.4.4 同一动作）`,
    '过账确认', { type: 'warning' })
  acting.value = true
  try {
    const res = await postingGrApi(row.id, {})
    if (res.code === 200) {
      ElMessage.success('过账成功，已生成入库凭证与流水')
      loadData()
    } else {
      ElMessage.error(res.message || '过账失败')
    }
  } finally {
    acting.value = false
  }
}

/** 入库确认（FR-4.4-1-7，不可逆） */
async function doConfirm(row) {
  await ElMessageBox.confirm(
    `确认入库完成？（${row.docNo}，确认后不可逆，纠错走收货调整单）`,
    '入库确认', { type: 'warning' })
  acting.value = true
  try {
    const res = await confirmGrApi(row.id)
    if (res.code === 200) {
      ElMessage.success('已确认，确认人与时间已留痕')
      loadData()
    } else {
      ElMessage.error(res.message || '确认失败')
    }
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
    const bizType = isPurchase.value ? 'GR' : 'RETURN'
    const res = await getTransactionsByDocApi(bizType, row.docNo)
    flows.value = res.data || []
  } finally {
    flowLoading.value = false
  }
}

// 路由切换（4.4.1↔4.4.3 同组件不同参数）时重载
watch(() => route.path, () => {
  query.value.current = 1
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
