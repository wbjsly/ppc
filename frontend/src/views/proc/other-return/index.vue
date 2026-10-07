<template>
  <div class="rt-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="其他退货（2.6.2）：非质量退货——供应商发错货 / 多发货 / 到货后发现质量问题，采购员按 PO 发起（自动带出原入库凭证与单价）→ 采购经理审批 → 退货出库（红字凭证 RV）→ 30 天补发/退款跟踪"
      description="BR-4.2-32：已入库退货必须关联原 PO 与原入库单，金额由系统按原入库单价自动计算（单价只读）；BR-4.2-34：非质量退货不打绩效扣分（PERF=0）。" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 150px;" @change="load">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="审批中" value="PENDING_APPROVE" />
          <el-option label="已审批" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
          <el-option label="已出库" value="OUT_DONE" />
          <el-option label="已作废" value="CANCELLED" />
        </el-select>
        <el-select v-model="filters.reasonType" placeholder="退货原因" clearable style="width: 170px;" @change="load">
          <el-option label="供应商发错货" value="WRONG_ITEM" />
          <el-option label="多发货" value="OVER_SHIP" />
          <el-option label="到货后发现质量问题" value="QUALITY_FOUND" />
          <el-option label="其他" value="OTHER" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="退货单号 / 供应商 / PO" clearable
          style="width: 220px;" @keyup.enter="load" @clear="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <span class="spacer" />
        <el-button v-if="canOperate" type="success" @click="openCreate">发起其他退货</el-button>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading" @row-click="openDetail" row-style="cursor:pointer">
        <el-table-column prop="returnNo" label="退货单号" width="155">
          <template #default="{ row }"><b>{{ row.returnNo }}</b></template>
        </el-table-column>
        <el-table-column label="退货原因" width="150" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="(row.reasonType && { WRONG_ITEM: 'danger', OVER_SHIP: 'warning', QUALITY_FOUND: 'danger', OTHER: 'info' }[row.reasonType]) || 'info'">
              {{ REASON_TEXT[row.reasonType] || '其他' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="poNo" label="原 PO" width="150" show-overflow-tooltip />
        <el-table-column prop="originDocNo" label="原入库单" width="150" show-overflow-tooltip />
        <el-table-column prop="supplierName" label="供应商" width="130" show-overflow-tooltip />
        <el-table-column prop="totalQty" label="数量" width="80" align="right" />
        <el-table-column prop="totalAmt" label="金额" width="100" align="right" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{
              DRAFT: 'info', PENDING_APPROVE: 'warning', APPROVED: 'primary',
              REJECTED: 'danger', OUT_DONE: 'success', CANCELLED: 'info'
            }[row.status]">
              {{ STATUS_TEXT[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="redDocNo" label="红字凭证" width="150">
          <template #default="{ row }">
            <span v-if="row.redDocNo" style="color:#67C23A;">{{ row.redDocNo }}</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="30 天跟踪" width="150">
          <template #default="{ row }">
            <template v-if="row.trackStatus">
              <el-tag size="small" :type="{ OPEN: 'primary', OVERDUE: 'danger', CLOSED: 'success' }[row.trackStatus]">
                {{ { OPEN: '跟踪中', OVERDUE: '逾期', CLOSED: '已闭环' }[row.trackStatus] }}
              </el-tag>
              <span v-if="row.trackOverdue" style="color:#F56C6C;font-size:12px;margin-left:4px;">
                {{ row.trackDueDate }}
              </span>
            </template>
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

    <!-- 详情 -->
    <el-drawer v-model="drawer"
      :title="detail.return ? `${detail.return.returnNo} · ${STATUS_TEXT[detail.return.status]}` : '退货详情'"
      size="720px" destroy-on-close>
      <div v-if="detail.return">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="退货原因">
            <el-tag size="small">{{ REASON_TEXT[detail.return.reasonType] || '其他' }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="状态">{{ STATUS_TEXT[detail.return.status] }}</el-descriptions-item>
          <el-descriptions-item label="原 PO / GR">
            {{ detail.return.poNo || '—' }} / {{ detail.return.grNo || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="原入库单">{{ detail.return.originDocNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ detail.return.supplierName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="数量 / 金额">
            {{ detail.return.totalQty }} / {{ detail.return.totalAmt }}
          </el-descriptions-item>
          <el-descriptions-item label="红字凭证">
            <span v-if="detail.return.redDocNo" style="color:#67C23A;font-weight:700;">
              {{ detail.return.redDocNo }}
            </span>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="绩效扣分">
            <el-tag size="small" type="info">不扣分（非质量退货）</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="补充说明" :span="2">{{ detail.return.returnReason || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.return.rejectReason" label="驳回原因" :span="2">
            <span style="color:#F56C6C;">{{ detail.return.rejectReason }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.return.trackStatus" label="跟踪" :span="2">
            {{ { OPEN: '跟踪中', OVERDUE: '已逾期', CLOSED: '已闭环' }[detail.return.trackStatus] }}
            · 到期 {{ detail.return.trackDueDate }}
            <template v-if="detail.return.trackClosedDate">· 闭环于 {{ detail.return.trackClosedDate }}</template>
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="sec">退货行</h4>
        <el-table :data="detail.lines || []" size="mini" border>
          <el-table-column prop="lineNo" label="#" width="45" />
          <el-table-column prop="itemCode" label="物料" width="140" />
          <el-table-column prop="itemName" label="名称" min-width="120" show-overflow-tooltip />
          <el-table-column prop="batchNo" label="批次" width="110" />
          <el-table-column prop="qty" label="数量" width="80" align="right" />
          <el-table-column prop="unitPrice" label="单价（原入库价）" width="120" align="right" />
          <el-table-column prop="amount" label="金额" width="100" align="right" />
        </el-table>

        <div v-if="canOperate || canPost" class="op-block" style="margin-top:12px;">
          <template v-if="['DRAFT', 'REJECTED'].includes(detail.return.status)">
            <el-button type="primary" size="small" @click="doSubmit">提交采购经理审批</el-button>
            <el-button size="small" @click="doCancel">作废</el-button>
          </template>
          <template v-else-if="detail.return.status === 'APPROVED'">
            <el-alert type="warning" :closable="false" style="margin-bottom:8px;"
              title="已审批：执行退货出库（库存扣减 + 红字凭证 + 30 天跟踪起算）" />
            <el-button v-if="canPost" type="danger" size="small" @click="doPost">退货出库</el-button>
          </template>
          <template v-else-if="detail.return.status === 'OUT_DONE' && detail.return.trackStatus !== 'CLOSED'">
            <el-input v-model="trackRemark" placeholder="补发/退款完成说明" style="width:320px;" />
            <el-button type="success" size="small" style="margin-left:8px;" @click="doCloseTrack">
              跟踪闭环
            </el-button>
          </template>
          <template v-else-if="detail.return.status === 'PENDING_APPROVE'">
            <el-alert type="info" :closable="false"
              title="审批中：在质量审批待办由采购经理（ROLE_PM）签署" />
          </template>
          <template v-else>
            <el-alert type="success" :closable="false" title="已归档（只读）" />
          </template>
        </div>
      </div>
    </el-drawer>

    <!-- 发起其他退货（PO 联动带出，单价只读，金额后端自动计算） -->
    <el-dialog v-model="createVisible" title="发起其他退货（按 PO 自动带出原入库凭证与单价）" width="560px">
      <el-form label-width="120px" size="small">
        <el-form-item label="原 PO" required>
          <el-select v-model="createForm.poId" filterable placeholder="选择已入库的采购订单"
            style="width: 100%;" @change="onPoChange">
            <el-option v-for="po in poList" :key="po.id"
              :label="`${po.poNo} · ${po.supplierName || ''}`" :value="po.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="可退入库行" required>
          <el-select v-model="createForm.itemCode" filterable placeholder="选择要退的物料批次（先选 PO）"
            style="width: 100%;" :disabled="!returnables.length" @change="onItemChange">
            <el-option v-for="r in returnables" :key="r.itemCode + '|' + r.batchNo"
              :label="`${r.itemCode} ${r.itemName || ''} · 批次 ${r.batchNo || '—'} · 可退 ${r.returnableQty}`"
              :value="r.itemCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="原入库凭证">
          <el-input :model-value="picked.originDocNo || '—'" disabled />
        </el-form-item>
        <el-form-item label="原入库单价">
          <el-input :model-value="picked.unitPrice != null ? `${picked.unitPrice}（只读，系统按此计价）` : '—'" disabled />
        </el-form-item>
        <el-form-item label="可退库存量">
          <el-input :model-value="picked.returnableQty != null ? String(picked.returnableQty) : '—'" disabled />
        </el-form-item>
        <el-form-item label="退货数量" required>
          <el-input-number v-model="createForm.qty" :min="0.0001" :max="picked.returnableQty || undefined"
            :precision="4" :disabled="!picked.itemCode" />
        </el-form-item>
        <el-form-item label="退货原因" required>
          <el-select v-model="createForm.reasonType" style="width: 100%;">
            <el-option label="供应商发错货" value="WRONG_ITEM" />
            <el-option label="多发货" value="OVER_SHIP" />
            <el-option label="到货后发现质量问题" value="QUALITY_FOUND" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="补充说明">
          <el-input v-model="createForm.note" type="textarea" :rows="2" placeholder="可选：具体情况说明" />
        </el-form-item>
        <el-alert type="info" :closable="false"
          :title="`金额将按 数量 × 原入库单价 自动计算${picked.unitPrice && createForm.qty ? `：${(createForm.qty * Number(picked.unitPrice)).toFixed(2)}` : ''}`" />
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="doCreate">创建草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getOtherReturnPageApi, getOtherReturnDetailApi, getPoReturnablesApi,
  createOtherReturnApi, submitOtherReturnApi, cancelOtherReturnApi,
  postOtherReturnApi, closeOtherTrackApi
} from '@/api/proc/other-return'
import { getPoPageApi } from '@/api/proc/purchase-order'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const myRoles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const hasRole = (...want) =>
  myRoles.value.some(r => r === 'ROLE_ADMIN' || want.includes(r))
// 发起/提交/作废/跟踪（Security：POST /api/proc/returns 创建 = ADMIN, PM）
const canOperate = computed(() => hasRole('ROLE_PM'))
// 退货出库（红字凭证）：与后端 returns/** 规则一致
const canPost = computed(() => myRoles.value.includes('ROLE_ADMIN'))

const STATUS_TEXT = {
  DRAFT: '草稿', PENDING_APPROVE: '审批中', APPROVED: '已审批',
  REJECTED: '已驳回', OUT_DONE: '已出库', CANCELLED: '已作废'
}
const REASON_TEXT = {
  WRONG_ITEM: '供应商发错货', OVER_SHIP: '多发货',
  QUALITY_FOUND: '到货后发现质量问题', OTHER: '其他'
}

const loading = ref(false)
const rows = ref([])
const total = ref(0)
// 来源固定 MANUAL（spec other-return：本页仅非质量退货）
const filters = reactive({ status: '', reasonType: '', keyword: '', current: 1, size: 10 })

async function load() {
  loading.value = true
  try {
    const res = await getOtherReturnPageApi({
      current: filters.current, size: filters.size,
      status: filters.status || undefined, sourceType: 'MANUAL',
      keyword: filters.keyword || undefined
    })
    rows.value = (res.data && res.data.records) || []
    total.value = (res.data && res.data.total) || 0
  } finally {
    loading.value = false
  }
}

// ---------- 详情 ----------
const drawer = ref(false)
const detail = ref({})
const trackRemark = ref('')

async function openDetail(row) {
  const res = await getOtherReturnDetailApi(row.id)
  detail.value = res.data || {}
  trackRemark.value = ''
  drawer.value = true
}
function refresh() {
  openDetail({ id: detail.value.return.id })
  load()
}

async function doSubmit() {
  try {
    await submitOtherReturnApi(detail.value.return.id)
    ElMessage.success('已提交采购经理审批')
    refresh()
  } catch { /* 拦截器已弹错 */ }
}

async function doCancel() {
  try {
    const { value } = await ElMessageBox.prompt('作废原因（至少 2 字）', '作废退货单',
      { inputPattern: /.{2,}/, inputErrorMessage: '至少 2 字' })
    await cancelOtherReturnApi(detail.value.return.id, value)
    ElMessage.success('已作废')
    refresh()
  } catch { /* 取消或拦截器已处理 */ }
}

async function doPost() {
  try {
    await ElMessageBox.confirm('执行退货出库：库存扣减 + 红字入库凭证 + 30 天跟踪起算。确认？',
      '退货出库', { type: 'warning' })
  } catch { return }
  try {
    const res = await postOtherReturnApi(detail.value.return.id)
    ElMessage.success(`出库完成，红字凭证 ${(res.data && res.data.redDocNo) || ''}`)
    refresh()
  } catch { /* 拦截器已弹错 */ }
}

async function doCloseTrack() {
  try {
    await closeOtherTrackApi(detail.value.return.id, trackRemark.value)
    ElMessage.success('跟踪已闭环')
    refresh()
  } catch { /* 拦截器已弹错 */ }
}

// ---------- 发起（PO 联动带出） ----------
const createVisible = ref(false)
const creating = ref(false)
const poList = ref([])
const returnables = ref([])
const createForm = reactive({ poId: '', itemCode: '', qty: undefined, reasonType: '', note: '' })
const picked = computed(() =>
  returnables.value.find(r => r.itemCode === createForm.itemCode) || {})

async function openCreate() {
  createForm.poId = ''
  createForm.itemCode = ''
  createForm.qty = undefined
  createForm.reasonType = ''
  createForm.note = ''
  returnables.value = []
  createVisible.value = true
  if (!poList.value.length) {
    const res = await getPoPageApi({ current: 1, size: 100 })
    poList.value = (res.data && res.data.records) || []
  }
}

async function onPoChange(poId) {
  createForm.itemCode = ''
  createForm.qty = undefined
  returnables.value = []
  if (!poId) return
  try {
    const res = await getPoReturnablesApi(poId)
    returnables.value = res.data || []
  } catch { /* 422 无已入库记录，拦截器已弹错 */ }
}

function onItemChange() {
  createForm.qty = undefined
}

async function doCreate() {
  if (!createForm.poId || !createForm.itemCode || !createForm.qty || !createForm.reasonType) {
    ElMessage.warning('PO / 退货行 / 数量 / 退货原因为必填')
    return
  }
  creating.value = true
  try {
    const res = await createOtherReturnApi({
      poId: createForm.poId,
      itemCode: createForm.itemCode,
      batchNo: picked.value.batchNo || '',
      itemName: picked.value.itemName || '',
      qty: createForm.qty,
      reasonType: createForm.reasonType,
      note: createForm.note
      // 单价/金额/入库凭证不传：后端反查（D3）
    })
    ElMessage.success('草稿已创建，请提交审批')
    createVisible.value = false
    load()
    if (res.data) openDetail(res.data)
  } catch { /* 拦截器已弹错 */ } finally {
    creating.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.rt-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.spacer { flex: 1; }
.sec { margin: 16px 0 8px; font-size: 14px; color: #303133; }
.op-block { background: #fafafa; border: 1px solid #ebeef5; border-radius: 4px; padding: 12px; }
</style>
