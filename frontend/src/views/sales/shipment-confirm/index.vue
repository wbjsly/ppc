<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.7.4 发货确认与签收"
              description="过账后的发货单在此确认（记录物流单号与发货时间，物流异常通知销售）→ 签收推进「已签收」；超时未签收可手工触发预警；拒收自动转退货申请草稿。拣货作业由库存域拣货复核（4.7）承载，本页为销售侧策略与确认。" />

    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>发货单工作台</span>
          <div>
            <el-select v-model="filterStatus" size="small" style="width:160px" @change="load">
              <el-option label="待确认 POSTED" value="POSTED" />
              <el-option label="已发货 CONFIRMED" value="CONFIRMED" />
              <el-option label="已签收 SIGNED" value="SIGNED" />
              <el-option label="拒收 REJECTED" value="REJECTED" />
              <el-option label="全部" value="" />
            </el-select>
            <el-button size="small" style="margin-left:8px" @click="load">刷新</el-button>
            <el-button size="small" type="warning" style="margin-left:8px"
                       @click="doSweep">触发超时预警扫描</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="shipNo" label="发货单号" width="170" />
        <el-table-column prop="customerName" label="客户" min-width="140" show-overflow-tooltip />
        <el-table-column prop="totalQty" label="数量" width="90" align="right" />
        <el-table-column prop="totalAmt" label="金额" width="110" align="right">
          <template #default="{ row }">¥{{ Number(row.totalAmt).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="105">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="物流" width="160" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.logisticsCo || '—' }} {{ row.logisticsNo || '' }}
          </template>
        </el-table-column>
        <el-table-column label="签收时限" width="150">
          <template #default="{ row }">
            <span v-if="row.signDueDate" :class="overdue(row) ? 'overdue' : ''">
              {{ row.signDueDate }}
              <el-tag v-if="row.signWarned === '1'" size="small" type="danger">已预警</el-tag>
              <el-tag v-else-if="overdue(row)" size="small" type="warning">临期/超期</el-tag>
            </span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="270">
          <template #default="{ row }">
            <template v-if="row.status === 'POSTED'">
              <el-button size="small" type="primary" @click="openConfirm(row)">发货确认</el-button>
            </template>
            <template v-else-if="row.status === 'CONFIRMED'">
              <el-button size="small" type="success" @click="doSign(row)">签收</el-button>
              <el-button size="small" type="danger" link @click="openReject(row)">拒收</el-button>
            </template>
            <el-button size="small" link @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 发货确认对话框 -->
    <el-dialog v-model="confirmDialog" :title="`发货确认 ${current?.shipNo}`" width="560px">
      <el-form label-width="100px" size="small">
        <el-form-item label="物流公司">
          <el-input v-model="confirmForm.logisticsCo" placeholder="如 顺丰速运" />
        </el-form-item>
        <el-form-item label="物流单号">
          <el-input v-model="confirmForm.logisticsNo" placeholder="运单号（可空）" />
        </el-form-item>
        <el-form-item label="物流异常">
          <el-input v-model="confirmForm.exceptNote" type="textarea" :rows="2"
                    placeholder="异常信息（非空将记录并通知销售，FR-4.3-6-6）" />
        </el-form-item>
      </el-form>
      <p class="hint">确认后回写 SO 行已发量与状态（部分发货/已发货），并设定签收时限（SIGN_TIMEOUT_DAYS）。</p>
      <template #footer>
        <el-button @click="confirmDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doConfirm">确认发货</el-button>
      </template>
    </el-dialog>

    <!-- 拒收对话框 -->
    <el-dialog v-model="rejectDialog" :title="`拒收 ${current?.shipNo}`" width="480px">
      <el-alert type="warning" :closable="false" show-icon class="mb12"
                title="拒收将生成退货申请草稿并关联原发货单（实物回补在退货判定流程完成）" />
      <el-input v-model="rejectReason" type="textarea" :rows="3"
                placeholder="拒收原因（必填，如：外箱破损 / 规格不符）" />
      <template #footer>
        <el-button @click="rejectDialog = false">取消</el-button>
        <el-button type="danger" :disabled="!rejectReason" :loading="submitting"
                   @click="doReject">确认拒收</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" :title="`发货单 ${detail?.shipment?.shipNo}`" width="840px">
      <template v-if="detail">
        <el-alert type="info" :closable="false" show-icon class="mb12"
                  :title="detail.pickingNote" />
        <el-descriptions :column="3" size="small" border class="mb12">
          <el-descriptions-item label="状态">{{ statusName(detail.shipment.status) }}</el-descriptions-item>
          <el-descriptions-item label="发货时间">{{ (detail.shipment.shipAt || '').replace('T', ' ') || '—' }}</el-descriptions-item>
          <el-descriptions-item label="签收时间">{{ (detail.shipment.signAt || '').replace('T', ' ') || '—' }}</el-descriptions-item>
          <el-descriptions-item label="物流">{{ detail.shipment.logisticsCo || '—' }} {{ detail.shipment.logisticsNo || '' }}</el-descriptions-item>
          <el-descriptions-item label="过账人/时间">
            {{ detail.shipment.postBy || '—' }} {{ (detail.shipment.postAt || '').replace('T', ' ') || '' }}
          </el-descriptions-item>
          <el-descriptions-item label="异常/拒收">
            {{ detail.shipment.exceptNote || detail.shipment.rejectReason || '—' }}
          </el-descriptions-item>
        </el-descriptions>
        <el-table :data="detail.lines" size="small" border>
          <el-table-column label="来源" width="180">
            <template #default="{ row }">{{ row.soNo }} 行{{ row.soLineNo }}</template>
          </el-table-column>
          <el-table-column prop="itemCode" label="SKU" width="140" />
          <el-table-column prop="qty" label="数量" width="90" align="right" />
          <el-table-column label="出库批次" min-width="200">
            <template #default="{ row }">
              <template v-if="row.batchAlloc">
                <el-tag v-for="(a, i) in parseAlloc(row.batchAlloc)" :key="i" size="small"
                        type="info" class="mr4">{{ a.batchNo || '(空批)' }} × {{ a.qty }}</el-tag>
              </template>
              <span v-else class="muted">未过账</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getShipmentsApi, getShipmentDetailApi, confirmShipmentApi,
  signShipmentApi, rejectShipmentApi, sweepSignTimeoutApi
} from '@/api/sd/shipment'

const rows = ref([])
const loading = ref(false)
const filterStatus = ref('POSTED')
const current = ref(null)
const confirmDialog = ref(false)
const rejectDialog = ref(false)
const detailVisible = ref(false)
const submitting = ref(false)
const rejectReason = ref('')
const detail = ref(null)
const confirmForm = ref({ logisticsCo: '', logisticsNo: '', exceptNote: '' })

function statusName(s) {
  return { DRAFT: '草稿', POSTED: '待确认', CONFIRMED: '已发货', SIGNED: '已签收',
    REJECTED: '拒收', CANCELLED: '已取消' }[s] || s
}
function statusTag(s) {
  return { DRAFT: 'info', POSTED: 'warning', CONFIRMED: 'success',
    SIGNED: 'success', REJECTED: 'danger' }[s] || ''
}
function overdue(row) {
  return row.status === 'CONFIRMED' && row.signDueDate &&
    row.signDueDate < new Date().toISOString().slice(0, 10)
}
function parseAlloc(json) {
  try { return JSON.parse(json) } catch (e) { return [] }
}

async function load() {
  loading.value = true
  try {
    const res = await getShipmentsApi({
      current: 1, size: 50, status: filterStatus.value || undefined
    })
    rows.value = res.data.records || []
  } finally {
    loading.value = false
  }
}

function openConfirm(row) {
  current.value = row
  confirmForm.value = { logisticsCo: '', logisticsNo: '', exceptNote: '' }
  confirmDialog.value = true
}

async function doConfirm() {
  submitting.value = true
  try {
    await confirmShipmentApi(current.value.id, confirmForm.value)
    ElMessage.success('发货确认完成，SO 行已回写')
    confirmDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '确认失败')
  } finally {
    submitting.value = false
  }
}

async function doSign(row) {
  try {
    await signShipmentApi(row.id)
    ElMessage.success('已登记签收')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '签收失败')
  }
}

function openReject(row) {
  current.value = row
  rejectReason.value = ''
  rejectDialog.value = true
}

async function doReject() {
  submitting.value = true
  try {
    await rejectShipmentApi(current.value.id, rejectReason.value)
    ElMessage.success('拒收已登记，退货申请草稿已生成')
    rejectDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '拒收失败')
  } finally {
    submitting.value = false
  }
}

async function doSweep() {
  try {
    const res = await sweepSignTimeoutApi()
    ElMessage.success(`扫描完成：${res.data} 条超时未签收已预警`)
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '扫描失败')
  }
}

async function openDetail(row) {
  const res = await getShipmentDetailApi(row.id)
  detail.value = res.data
  detailVisible.value = true
}

onMounted(load)
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.mb12 { margin-bottom: 12px; }
.hint { font-size: 12px; color: #909399; margin-top: 4px; }
.overdue { color: #f56c6c; font-weight: 600; }
.muted { color: #c0c4cc; }
.mr4 { margin-right: 4px; }
</style>
