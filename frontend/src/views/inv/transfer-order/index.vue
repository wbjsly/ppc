<template>
  <div class="page">
    <el-tabs v-model="tab">
      <!-- ============ Tab 1：调拨单 ============ -->
      <el-tab-pane label="调拨单" name="orders">
        <el-form :inline="true" class="filter-bar" @submit.prevent>
          <el-form-item label="状态">
            <el-select v-model="query.status" clearable placeholder="全部" style="width: 160px">
              <el-option label="草稿" value="DRAFT" />
              <el-option label="在途（出库完成）" value="OUT_POSTED" />
              <el-option label="入库完成" value="IN_POSTED" />
              <el-option label="已关闭" value="CLOSED" />
              <el-option label="已作废" value="CANCELLED" />
            </el-select>
          </el-form-item>
          <el-form-item label="关键字">
            <el-input v-model="query.keyword" placeholder="调拨单/仓库" clearable style="width: 200px"
              @keyup.enter="reload" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="reload">查询</el-button>
            <el-button type="success" @click="openCreate">新建调拨单</el-button>
          </el-form-item>
        </el-form>

        <el-table :data="rows" v-loading="loading" border stripe>
          <el-table-column prop="transferNo" label="调拨单号" width="150" />
          <el-table-column label="调出 → 调入" width="180">
            <template #default="{ row }">{{ row.outWhCode }} → {{ row.inWhCode }}</template>
          </el-table-column>
          <el-table-column label="法人" width="110">
            <template #default="{ row }">
              <el-tag :type="row.crossLe === '1' ? 'warning' : 'info'" size="small">
                {{ row.crossLe === '1' ? '跨法人' : '同法人' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="totalQty" label="数量" width="90" align="right" />
          <el-table-column prop="totalAmount" label="金额" width="100" align="right" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="outPostAt" label="出库时间" width="155">
            <template #default="{ row }">{{ fmtTime(row.outPostAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="260" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
              <el-button v-if="row.status === 'DRAFT' && canOperate" link type="primary" size="small"
                :loading="acting" @click="doPostOut(row)">出库过账</el-button>
              <el-button v-if="row.status === 'IN_POSTED' && canOperate" link type="success" size="small"
                :loading="acting" @click="doClose(row)">关闭</el-button>
              <el-button v-if="row.status === 'DRAFT' && canOperate" link type="danger" size="small"
                @click="doCancel(row)">作废</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && rows.length === 0" description="暂无调拨单" />
        <el-pagination v-if="total > 0" class="pager" background layout="total, prev, pager, next"
          :total="total" :page-size="query.size" :current-page="query.current" @current-change="onPage" />
      </el-tab-pane>

      <!-- ============ Tab 2：内部往来（F2） ============ -->
      <el-tab-pane label="内部往来" name="ledger">
        <el-alert type="info" :closable="false" style="margin-bottom: 8px"
          title="口径：余额 = 该法人对方向 ISSUED 未核销内部发票合计；入库过账自动配对核销（F2/偏差 D3：非独立对账单实体）" />
        <el-table :data="ledgerRows" v-loading="ledgerLoading" border stripe>
          <el-table-column prop="outLe" label="调出法人" width="130" />
          <el-table-column prop="inLe" label="调入法人" width="130" />
          <el-table-column label="方向" width="140">
            <template #default="{ row }">
              <el-tag size="small" :type="row.direction === 'OUT' ? 'primary' : 'success'">
                {{ row.direction === 'OUT' ? '内部销售（应收）' : '内部采购（应付）' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="issuedCount" label="未核销张数" width="110" align="center" />
          <el-table-column prop="issuedAmount" label="未核销余额（含税）" width="160" align="right" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openLedgerDetail(row)">明细下钻</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!ledgerLoading && ledgerRows.length === 0" description="无未核销内部发票（全部已配对）" />
      </el-tab-pane>
    </el-tabs>

    <!-- ============ 新建调拨单对话框 ============ -->
    <el-dialog v-model="createVisible" title="新建调拨单（行内部转移价必填）" width="880px" :close-on-click-modal="false">
      <el-form :model="form" label-width="90px">
        <el-row :gutter="16">
          <el-col :span="10">
            <el-form-item label="调出仓" required>
              <el-select v-model="form.outWhCode" placeholder="选择调出仓" style="width: 100%">
                <el-option v-for="w in warehouses" :key="w.whCode" :label="`${w.whName}（${w.whCode}）`"
                  :value="w.whCode" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="10">
            <el-form-item label="调入仓" required>
              <el-select v-model="form.inWhCode" placeholder="选择调入仓" style="width: 100%">
                <el-option v-for="w in warehouses" :key="w.whCode" :label="`${w.whName}（${w.whCode}）`"
                  :value="w.whCode" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="4">
            <el-form-item label="备注">
              <el-input v-model="form.remark" maxlength="100" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <el-table :data="form.lines" border size="small">
        <el-table-column label="物料" min-width="220">
          <template #default="{ row }">
            <el-select v-model="row.itemCode" filterable placeholder="选择物料" style="width: 100%"
              @change="onItemChange(row)">
              <el-option v-for="it in itemOptions" :key="it.code" :label="`${it.name}（${it.code}）`"
                :value="it.code" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="批次（可空=FIFO分配）" width="160">
          <template #default="{ row }">
            <el-input v-model="row.batchNo" placeholder="空=过账时分配" />
          </template>
        </el-table-column>
        <el-table-column label="数量" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.qty" :min="0.0001" :precision="4" controls-position="right"
              style="width: 110px" />
          </template>
        </el-table-column>
        <el-table-column label="内部转移价（必填）" width="170">
          <template #default="{ row }">
            <el-input-number v-model="row.internalPrice" :min="0.01" :precision="4" controls-position="right"
              style="width: 150px" />
          </template>
        </el-table-column>
        <el-table-column label="行金额" width="110" align="right">
          <template #default="{ row }">{{ lineAmount(row) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" @click="form.lines.splice($index, 1)"
              :disabled="form.lines.length <= 1">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 8px">
        <el-button size="small" @click="addLine">+ 加行</el-button>
        <span class="as-of" style="margin-left: 12px">合计：数量 {{ totalQty }} ／ 金额 {{ totalAmt }}</span>
      </div>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- ============ 详情抽屉 ============ -->
    <el-drawer v-model="detailVisible" :title="`调拨单：${current.transferNo || ''}`" size="680px">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="状态">{{ statusText(current.status) }}</el-descriptions-item>
        <el-descriptions-item label="法人">
          {{ current.outLeCode }} → {{ current.inLeCode }}
          （{{ current.crossLe === '1' ? '跨法人' : '同法人' }}）
        </el-descriptions-item>
        <el-descriptions-item label="调出仓">{{ current.outWhCode }}</el-descriptions-item>
        <el-descriptions-item label="调入仓">{{ current.inWhCode }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ current.totalQty }}</el-descriptions-item>
        <el-descriptions-item label="金额">{{ current.totalAmount }}</el-descriptions-item>
        <el-descriptions-item label="出库时间">{{ fmtTime(current.outPostAt) }}</el-descriptions-item>
        <el-descriptions-item label="入库时间">{{ fmtTime(current.inPostAt) }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">调拨行</el-divider>
      <el-table :data="detailLines" border size="small" max-height="220">
        <el-table-column prop="lineNo" label="#" width="45" />
        <el-table-column prop="itemCode" label="物料" width="130" />
        <el-table-column prop="batchNo" label="批次" width="120">
          <template #default="{ row }">{{ row.batchNo || '（分配）' }}</template>
        </el-table-column>
        <el-table-column prop="qty" label="数量" width="80" align="right" />
        <el-table-column prop="internalPrice" label="转移价" width="90" align="right" />
        <el-table-column prop="lineAmount" label="金额" width="100" align="right" />
      </el-table>

      <template v-if="detailAllocs.length">
        <el-divider content-position="left">出库分配明细（入库段按批入账）</el-divider>
        <el-table :data="detailAllocs" border size="small" max-height="180">
          <el-table-column prop="lineNo" label="#" width="45" />
          <el-table-column prop="itemCode" label="物料" width="130" />
          <el-table-column prop="batchNo" label="批次" width="120" />
          <el-table-column prop="binCode" label="仓位" width="130">
            <template #default="{ row }">{{ row.binCode || '未分配' }}</template>
          </el-table-column>
          <el-table-column prop="qty" label="数量" width="80" align="right" />
        </el-table>
      </template>

      <el-divider content-position="left">出入库流水</el-divider>
      <el-table :data="flows" border size="small" v-loading="flowLoading" max-height="260">
        <el-table-column prop="txnNo" label="流水号" width="150" />
        <el-table-column label="方向" width="60" align="center">
          <template #default="{ row }">
            <el-tag :type="row.direction === 'IN' ? 'success' : 'warning'" size="small">
              {{ row.direction === 'IN' ? '入' : '出' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="itemCode" label="物料" width="110" />
        <el-table-column prop="batchNo" label="批次" width="100" />
        <el-table-column prop="binCode" label="仓位" width="120">
          <template #default="{ row }">{{ row.binCode || '未分配' }}</template>
        </el-table-column>
        <el-table-column prop="qty" label="数量" width="70" align="right" />
      </el-table>
      <el-empty v-if="!flowLoading && flows.length === 0" description="暂无流水" :image-size="60" />
    </el-drawer>

    <!-- ============ 内部往来明细 ============ -->
    <el-dialog v-model="ledgerDetailVisible" title="内部往来明细（逐单核销留痕）" width="820px">
      <el-table :data="ledgerDetail" border size="small" v-loading="ledgerDetailLoading" max-height="420">
        <el-table-column prop="intInvNo" label="内部发票号" width="150" />
        <el-table-column label="方向" width="80">
          <template #default="{ row }">{{ row.direction === 'OUT' ? '销售票' : '采购票' }}</template>
        </el-table-column>
        <el-table-column prop="transferNo" label="调拨单号" width="150" />
        <el-table-column prop="issuerLeCode" label="开票法人" width="110" />
        <el-table-column prop="netAmount" label="不含税" width="100" align="right" />
        <el-table-column prop="taxAmount" label="税额" width="90" align="right" />
        <el-table-column prop="totalAmount" label="含税" width="100" align="right" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'SETTLED' ? 'success' : 'warning'" size="small">
              {{ row.status === 'SETTLED' ? '已核销' : '未核销' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="ledgerDetailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createTransferApi, cancelTransferApi, postTransferOutApi, closeTransferApi,
  getTransferApi, getTransfersApi, getInternalLedgerApi, getInternalLedgerDetailApi
} from '@/api/inv/transfer'
import { getTransactionsByDocApi } from '@/api/inv/transaction'
import { getEnabledWarehousesApi } from '@/api/inv/warehouse'
import { getItemOptionsApi } from '@/api/mdm/item'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const tab = ref('orders')
const loading = ref(false)
const acting = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ status: '', keyword: '', current: 1, size: 20 })

const warehouses = ref([])
const itemOptions = ref([])

// 创建表单
const createVisible = ref(false)
const form = ref({ outWhCode: '', inWhCode: '', remark: '', lines: [] })

// 详情
const detailVisible = ref(false)
const current = ref({})
const detailLines = ref([])
const detailAllocs = ref([])
const flowLoading = ref(false)
const flows = ref([])

// 内部往来
const ledgerLoading = ref(false)
const ledgerRows = ref([])
const ledgerDetailVisible = ref(false)
const ledgerDetailLoading = ref(false)
const ledgerDetail = ref([])

const canOperate = computed(() => {
  const roles = userStore.userInfo?.roles || []
  return roles.includes('ROLE_ADMIN') || roles.includes('ROLE_WAREHOUSE')
})
const totalQty = computed(() =>
  form.value.lines.reduce((s, l) => s + (Number(l.qty) || 0), 0).toFixed(4).replace(/\.?0+$/, ''))
const totalAmt = computed(() =>
  form.value.lines.reduce((s, l) => s + (Number(l.qty) || 0) * (Number(l.internalPrice) || 0), 0).toFixed(2))

function lineAmount(row) {
  return ((Number(row.qty) || 0) * (Number(row.internalPrice) || 0)).toFixed(2)
}
function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}
function statusText(s) {
  return { DRAFT: '草稿', OUT_POSTED: '在途', IN_POSTED: '入库完成', CLOSED: '已关闭', CANCELLED: '已作废' }[s] || s || '-'
}
function statusTag(s) {
  return { DRAFT: 'info', OUT_POSTED: 'warning', IN_POSTED: 'primary', CLOSED: 'success', CANCELLED: 'danger' }[s] || 'info'
}
function errMsg(e, fallback) {
  return e?.response?.data?.message || e?.message || fallback
}

async function loadData() {
  loading.value = true
  try {
    const params = { current: query.value.current, size: query.value.size }
    if (query.value.status) params.status = query.value.status
    if (query.value.keyword) params.keyword = query.value.keyword
    const res = await getTransfersApi(params)
    rows.value = res.data.rows || []
    total.value = Number(res.data.total || 0)
  } finally {
    loading.value = false
  }
}
function reload() {
  query.value.current = 1
  loadData()
}
function onPage(p) {
  query.value.current = p
  loadData()
}

async function loadRefs() {
  const [wh, items] = await Promise.all([getEnabledWarehousesApi(), getItemOptionsApi()])
  warehouses.value = wh.data || []
  itemOptions.value = items.data || []
}

function openCreate() {
  form.value = { outWhCode: '', inWhCode: '', remark: '', lines: [blankLine()] }
  createVisible.value = true
}
function blankLine() {
  return { itemCode: '', batchNo: '', qty: 1, internalPrice: null }
}
function addLine() {
  form.value.lines.push(blankLine())
}
function onItemChange(row) {
  const it = itemOptions.value.find(o => o.code === row.itemCode)
  row.itemName = it ? it.name : ''
}
async function doCreate() {
  const f = form.value
  if (!f.outWhCode || !f.inWhCode) return ElMessage.warning('请选择调出仓与调入仓')
  if (f.outWhCode === f.inWhCode) return ElMessage.warning('调出仓与调入仓不可相同')
  if (!f.lines.length) return ElMessage.warning('请添加调拨行')
  for (const l of f.lines) {
    if (!l.itemCode) return ElMessage.warning('行物料必填')
    if (!(l.qty > 0)) return ElMessage.warning('行数量须大于 0')
    if (!(l.internalPrice > 0)) return ElMessage.warning('行内部转移价必填且大于 0')
  }
  acting.value = true
  try {
    const res = await createTransferApi({ head: f, lines: f.lines })
    if (res.code === 200) {
      ElMessage.success(`调拨单 ${res.data.order.transferNo} 已创建（草稿）`)
      createVisible.value = false
      reload()
    } else {
      ElMessage.error(res.message || '创建失败')
    }
  } catch (e) {
    ElMessage.error(errMsg(e, '创建失败'))
  } finally {
    acting.value = false
  }
}

async function doPostOut(row) {
  await ElMessageBox.confirm(
    `确认对 ${row.transferNo} 执行出库过账？${row.crossLe === '1' ? '（跨法人：将生成成对内部凭证与内部销售发票）' : ''}`,
    '出库过账', { type: 'warning' })
  acting.value = true
  try {
    const res = await postTransferOutApi(row.docId || row.id)
    if (res.code === 200) {
      ElMessage.success('出库过账完成，已进入在途（4.12.2 可跟踪）')
      reload()
    } else {
      ElMessage.error(res.message || '过账失败')
    }
  } catch (e) {
    ElMessage.error(errMsg(e, '过账失败'))
  } finally {
    acting.value = false
  }
}

async function doClose(row) {
  await ElMessageBox.confirm(`确认关闭 ${row.transferNo}？（关闭后只读）`, '关闭调拨单', { type: 'warning' })
  acting.value = true
  try {
    const res = await closeTransferApi(row.id)
    if (res.code === 200) {
      ElMessage.success('已关闭')
      reload()
    } else {
      ElMessage.error(res.message || '关闭失败')
    }
  } catch (e) {
    ElMessage.error(errMsg(e, '关闭失败'))
  } finally {
    acting.value = false
  }
}

async function doCancel(row) {
  const { value } = await ElMessageBox.prompt('作废原因（必填）', '作废调拨单', { inputPattern: /\S+/, inputErrorMessage: '原因必填' })
  try {
    await cancelTransferApi(row.id, value)
    ElMessage.success('已作废')
    reload()
  } catch (e) {
    ElMessage.error(errMsg(e, '作废失败'))
  }
}

async function openDetail(row) {
  current.value = row
  detailVisible.value = true
  detailLines.value = []
  detailAllocs.value = []
  flows.value = []
  flowLoading.value = true
  try {
    const res = await getTransferApi(row.id)
    detailLines.value = res.data.lines || []
    detailAllocs.value = res.data.allocs || []
    const fr = await getTransactionsByDocApi('TRANSFER', row.transferNo)
    flows.value = fr.data || []
  } finally {
    flowLoading.value = false
  }
}

async function loadLedger() {
  ledgerLoading.value = true
  try {
    const res = await getInternalLedgerApi()
    ledgerRows.value = res.data.rows || []
  } finally {
    ledgerLoading.value = false
  }
}
async function openLedgerDetail(row) {
  ledgerDetailVisible.value = true
  ledgerDetailLoading.value = true
  ledgerDetail.value = []
  try {
    const res = await getInternalLedgerDetailApi({ outLe: row.outLe, inLe: row.inLe, direction: row.direction })
    ledgerDetail.value = res.data.rows || []
  } finally {
    ledgerDetailLoading.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadRefs(), loadData(), loadLedger()])
})
</script>

<style scoped>
.page { padding: 4px; }
.filter-bar { margin-bottom: 4px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
