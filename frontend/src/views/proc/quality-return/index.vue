<template>
  <div class="rt-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="质量退货（2.6.1）：NCR 评审选退货自动带出（PO/收货单/数量/PO 单价）→ 采购经理审批 → 退货出库（红字凭证 RV）→ 30 天补发/退款跟踪"
      description="未入库质检退货仅关联 PO 与收货单（BR-4.2-32）；已入库手工退货须关联原入库单与单价；质量退货打绩效扣分标记（D4），MANUAL 手工退货不扣分。" />

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
        <el-select v-model="filters.sourceType" placeholder="来源" clearable style="width: 130px;" @change="load">
          <el-option label="质量退货(NCR)" value="NCR" />
          <el-option label="手工退货" value="MANUAL" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="退货单号 / 供应商 / NCR / PO" clearable
          style="width: 230px;" @keyup.enter="load" @clear="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <span class="spacer" />
        <el-button v-if="canOperate" type="success" @click="openCreate">手工发起退货</el-button>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading" @row-click="openDetail" row-style="cursor:pointer">
        <el-table-column prop="returnNo" label="退货单号" width="155">
          <template #default="{ row }"><b>{{ row.returnNo }}</b></template>
        </el-table-column>
        <el-table-column label="来源" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.sourceType === 'NCR' ? 'danger' : 'info'">
              {{ row.sourceType === 'NCR' ? '质量退货' : '手工' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ncrNo" label="NCR" width="150" show-overflow-tooltip />
        <el-table-column prop="poNo" label="PO" width="150" show-overflow-tooltip />
        <el-table-column prop="supplierName" label="供应商" width="130" show-overflow-tooltip />
        <el-table-column prop="totalQty" label="数量" width="80" align="right" />
        <el-table-column prop="totalAmt" label="金额" width="100" align="right" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{
              DRAFT: 'info', PENDING_APPROVE: 'warning', APPROVED: 'primary',
              REJECTED: 'danger', OUT_DONE: 'success', CANCELLED: 'info'
            }[row.status]">
              {{ { DRAFT: '草稿', PENDING_APPROVE: '审批中', APPROVED: '已审批',
                   REJECTED: '已驳回', OUT_DONE: '已出库', CANCELLED: '已作废' }[row.status] || row.status }}
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
          <el-descriptions-item label="来源">
            <el-tag size="small" :type="detail.return.sourceType === 'NCR' ? 'danger' : 'info'">
              {{ detail.return.sourceType === 'NCR' ? '质量退货' : '手工退货' }}
            </el-tag>
            <el-tag v-if="detail.return.perfFlag === '1'" size="small" type="warning" style="margin-left:6px;">
              绩效扣分标记
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="状态">{{ STATUS_TEXT[detail.return.status] }}</el-descriptions-item>
          <el-descriptions-item label="NCR">{{ detail.return.ncrNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="PO / GR">
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
          <el-descriptions-item label="退货原因" :span="2">{{ detail.return.returnReason || '—' }}</el-descriptions-item>
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
          <el-table-column prop="unitPrice" label="单价" width="80" align="right" />
          <el-table-column prop="amount" label="金额" width="100" align="right" />
        </el-table>

        <div v-if="canOperate || canPost" class="op-block" style="margin-top:12px;">
          <template v-if="['DRAFT', 'REJECTED'].includes(detail.return.status)">
            <el-button type="primary" size="small" @click="doSubmit">提交采购经理审批</el-button>
            <el-button size="small" @click="doCancel">作废</el-button>
          </template>
          <template v-else-if="detail.return.status === 'APPROVED'">
            <el-alert type="warning" :closable="false" style="margin-bottom:8px;"
              title="已审批：执行退货出库（库存扣减锁定优先 / 未入库置行驳回 + 红字凭证 + 30 天跟踪起算）" />
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

    <!-- 手工发起 -->
    <el-dialog v-model="createVisible" title="手工发起退货（已入库须关联原入库单与单价）" width="540px">
      <el-form label-width="110px" size="small">
        <el-form-item label="物料编码" required>
          <el-input v-model="createForm.itemCode" />
        </el-form-item>
        <el-form-item label="物料名称">
          <el-input v-model="createForm.itemName" />
        </el-form-item>
        <el-form-item label="批次">
          <el-input v-model="createForm.batchNo" />
        </el-form-item>
        <el-form-item label="退货数量" required>
          <el-input-number v-model="createForm.qty" :min="0.0001" :precision="4" />
        </el-form-item>
        <el-form-item label="退货原因" required>
          <el-input v-model="createForm.returnReason" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="原入库单号">
          <el-input v-model="createForm.originDocNo" placeholder="已入库退货必填（BR-4.2-33）" />
        </el-form-item>
        <el-form-item label="原入库单价">
          <el-input-number v-model="createForm.unitPrice" :min="0" :precision="4" />
        </el-form-item>
        <el-form-item label="供应商">
          <el-input v-model="createForm.supplierName" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">创建草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getReturnPageApi, getReturnDetailApi, createReturnApi,
  submitReturnApi, cancelReturnApi, postReturnApi, closeTrackApi
} from '@/api/proc/quality-return'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const myRoles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const hasRole = (...want) =>
  myRoles.value.some(r => r === 'ROLE_ADMIN' || want.includes(r))
// 发起/提交/作废/跟踪（Security：POST /api/proc/returns/** = ADMIN, QUALITY_ENG, QUALITY_MGR）
const canOperate = computed(() => hasRole('ROLE_QUALITY_ENG', 'ROLE_QUALITY_MGR'))
// 退货出库（红字凭证，仓库动作但 Security 仅放行 ADMIN——显隐与后端一致）
const canPost = computed(() => myRoles.value.includes('ROLE_ADMIN'))

const STATUS_TEXT = {
  DRAFT: '草稿', PENDING_APPROVE: '审批中', APPROVED: '已审批',
  REJECTED: '已驳回', OUT_DONE: '已出库', CANCELLED: '已作废'
}

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const filters = reactive({ status: '', sourceType: '', keyword: '', current: 1, size: 10 })

async function load() {
  loading.value = true
  try {
    const res = await getReturnPageApi({
      current: filters.current, size: filters.size,
      status: filters.status || undefined, sourceType: filters.sourceType || undefined,
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
  const res = await getReturnDetailApi(row.id)
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
    await submitReturnApi(detail.value.return.id)
    ElMessage.success('已提交采购经理审批')
    refresh()
  } catch { /* 拦截器已弹错 */ }
}

async function doCancel() {
  try {
    const { value } = await ElMessageBox.prompt('作废原因（至少 2 字）', '作废退货单',
      { inputPattern: /.{2,}/, inputErrorMessage: '至少 2 字' })
    await cancelReturnApi(detail.value.return.id, value)
    ElMessage.success('已作废')
    refresh()
  } catch { /* 取消或拦截器已处理 */ }
}

async function doPost() {
  try {
    await ElMessageBox.confirm('执行退货出库：库存扣减（锁定优先）+ 红字入库凭证 + 30 天跟踪起算。确认？',
      '退货出库', { type: 'warning' })
  } catch { return }
  try {
    const res = await postReturnApi(detail.value.return.id)
    ElMessage.success(`出库完成，红字凭证 ${(res.data && res.data.redDocNo) || ''}`)
    refresh()
  } catch { /* 拦截器已弹错（库存不足 422 等） */ }
}

async function doCloseTrack() {
  try {
    await closeTrackApi(detail.value.return.id, trackRemark.value)
    ElMessage.success('跟踪已闭环')
    refresh()
  } catch { /* 拦截器已弹错 */ }
}

// ---------- 手工发起 ----------
const createVisible = ref(false)
const createForm = reactive({
  itemCode: '', itemName: '', batchNo: '', qty: undefined,
  returnReason: '', originDocNo: '', unitPrice: undefined, supplierName: ''
})
function openCreate() {
  createForm.itemCode = ''
  createForm.itemName = ''
  createForm.batchNo = ''
  createForm.qty = undefined
  createForm.returnReason = ''
  createForm.originDocNo = ''
  createForm.unitPrice = undefined
  createForm.supplierName = ''
  createVisible.value = true
}
async function doCreate() {
  try {
    const res = await createReturnApi({ ...createForm })
    ElMessage.success('草稿已创建，请提交审批')
    createVisible.value = false
    load()
    if (res.data) openDetail(res.data)
  } catch { /* 拦截器已弹错 */ }
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
