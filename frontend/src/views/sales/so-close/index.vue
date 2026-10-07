<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.5.4 订单关闭"
              description="手动关闭必填原因并经销售经理确认；关闭时未执行余量显式处理留痕、释放未消耗预留（ATP 回升）；关闭后仅可查询，重启须新建 SO 并关联原单号（业务逻辑 6）。" />

    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>可关闭订单</span>
          <div>
            <el-select v-model="filterStatus" size="small" style="width:170px" @change="load">
              <el-option label="未关闭（活跃）" value="" />
              <el-option label="草稿 DRAFT" value="DRAFT" />
              <el-option label="审批中 PENDING" value="PENDING" />
              <el-option label="已确认 CONFIRMED" value="CONFIRMED" />
              <el-option label="信用冻结" value="CREDIT_FREEZE" />
              <el-option label="已关闭 CLOSED" value="CLOSED" />
            </el-select>
            <el-button size="small" style="margin-left:8px" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="soNo" label="订单号" width="160" />
        <el-table-column prop="customerName" label="客户" min-width="140" show-overflow-tooltip />
        <el-table-column prop="totalAmount" label="金额" width="120" align="right">
          <template #default="{ row }">¥{{ Number(row.totalAmount).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'CLOSED' ? 'info'
              : (row.status === 'CREDIT_FREEZE' ? 'danger' : 'success')">
              {{ statusName(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="关闭信息" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.status === 'CLOSED'">
              {{ row.closeReason }}（{{ row.closedBy }} {{ (row.closedAt || '').replace('T', ' ') }}）
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <template v-if="row.status !== 'CLOSED' && row.status !== 'CANCELLED'">
              <el-button size="small" type="danger" @click="openClose(row)">关闭</el-button>
              <el-button size="small" @click="openDetail(row)">余量</el-button>
            </template>
            <template v-else-if="row.status === 'CLOSED'">
              <el-button size="small" link @click="tryReopen(row)">尝试重启</el-button>
              <el-button size="small" link @click="openDetail(row)">详情</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 关闭对话框（余量显式处理 + 经理确认） -->
    <el-dialog v-model="closeDialog" :title="`关闭订单 ${current?.soNo}`" width="640px">
      <template v-if="detail">
        <h4>未执行余量（关闭时显式处理并留痕）</h4>
        <el-table :data="remainLines" size="small" border class="mb12">
          <el-table-column prop="lineNo" label="#" width="45" />
          <el-table-column prop="itemCode" label="SKU" width="150" />
          <el-table-column label="数量" width="90" align="right" prop="qty" />
          <el-table-column label="已发" width="90" align="right">
            <template #default="{ row }">{{ row.shippedQty || 0 }}</template>
          </el-table-column>
          <el-table-column label="剩余未发" width="100" align="right">
            <template #default="{ row }">
              <b class="remain">{{ remain(row) }}</b>
            </template>
          </el-table-column>
          <el-table-column label="预留" width="80" align="right">
            <template #default="{ row }">{{ row.reservedQty || 0 }}</template>
          </el-table-column>
        </el-table>

        <el-alert type="warning" :closable="false" show-icon class="mb12"
                  :title="`关闭将释放 ${totalReserved} 条未消耗预留（ATP 回升），剩余余量作废留痕`" />

        <el-input v-model="closeReason" type="textarea" :rows="3"
                  placeholder="关闭原因（必填，如：客户取消 / 转框架合同执行 / 尾量放弃）" />
        <p class="hint">手动关闭需销售经理确认——当前操作人即确认人（需 SALES_MGR / ADMIN 角色）。</p>
      </template>
      <template #footer>
        <el-button @click="closeDialog = false">取消</el-button>
        <el-button type="danger" :disabled="!closeReason" :loading="closing"
                   @click="doClose">确认关闭</el-button>
      </template>
    </el-dialog>

    <!-- 详情（版本与变更台账） -->
    <el-dialog v-model="detailDialog" :title="`订单 ${current?.soNo} 台账`" width="860px">
      <template v-if="detail">
        <el-descriptions :column="3" size="small" border class="mb12">
          <el-descriptions-item label="状态">{{ statusName(detail.so.status) }}</el-descriptions-item>
          <el-descriptions-item label="金额">¥{{ Number(detail.so.totalAmount).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="关闭原因">{{ detail.so.closeReason || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="detail.lines" size="small" border>
          <el-table-column prop="lineNo" label="#" width="45" />
          <el-table-column prop="itemCode" label="SKU" width="150" />
          <el-table-column prop="qty" label="数量" width="90" align="right" />
          <el-table-column prop="shippedQty" label="已发" width="90" align="right" />
          <el-table-column prop="reservedQty" label="预留" width="90" align="right" />
          <el-table-column label="剩余未发" width="100" align="right">
            <template #default="{ row }"><b>{{ remain(row) }}</b></template>
          </el-table-column>
          <el-table-column prop="lineStatus" label="行状态" width="110" />
        </el-table>
      </template>
      <template #footer>
        <el-button @click="detailDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSoPageApi, getSoDetailApi, closeSoApi } from '@/api/sd/so'
import request from '@/utils/request'

const rows = ref([])
const loading = ref(false)
const filterStatus = ref('')
const current = ref(null)
const detail = ref(null)
const closeDialog = ref(false)
const detailDialog = ref(false)
const closeReason = ref('')
const closing = ref(false)

const remainLines = computed(() =>
  detail.value ? detail.value.lines.filter(l =>
    l.lineStatus !== 'CANCELLED' && remain(l) > 0) : [])
const totalReserved = computed(() =>
  remainLines.value.filter(l => Number(l.reservedQty || 0) > 0).length)

function remain(row) {
  return Number(row.qty || 0) - Number(row.shippedQty || 0)
}
function statusName(s) {
  return {
    DRAFT: '草稿', PENDING: '审批中', CONFIRMED: '已确认', PARTIAL_SHIPPED: '部分发货',
    SHIPPED: '已发货', SIGNED: '已签收', INVOICED: '已开票', CLOSED: '已关闭',
    CREDIT_FREEZE: '信用冻结(挂起)', CHANGING: '变更中(挂起)', CANCELLED: '已取消'
  }[s] || s
}

async function load() {
  loading.value = true
  try {
    const res = await getSoPageApi({
      current: 1, size: 50,
      status: filterStatus.value || undefined
    })
    rows.value = res.data.records || []
  } finally {
    loading.value = false
  }
}

async function openClose(row) {
  current.value = row
  const res = await getSoDetailApi(row.id)
  detail.value = res.data
  closeReason.value = ''
  closeDialog.value = true
}

async function openDetail(row) {
  current.value = row
  const res = await getSoDetailApi(row.id)
  detail.value = res.data
  detailDialog.value = true
}

async function doClose() {
  if (!closeReason.value) return ElMessage.error('关闭原因必填')
  closing.value = true
  try {
    await closeSoApi(current.value.id, closeReason.value)
    ElMessage.success('订单已关闭，未消耗预留已释放（ATP 回升）')
    closeDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '关闭失败')
  } finally {
    closing.value = false
  }
}

/** 关闭单不可重启（业务逻辑 6 场景） */
async function tryReopen(row) {
  try {
    await request({ url: `/sd/so/${row.id}/reopen`, method: 'post' })
    ElMessage.success('意外成功（不应出现）')
  } catch (e) {
    ElMessageBox.alert(
      e?.message || `已关闭订单不可重启，请新建 SO 并关联原单号 ${row.soNo}`,
      '不可重启', { confirmButtonText: '知道了' }
    )
  }
}

onMounted(load)
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.mb12 { margin-bottom: 12px; }
.muted { color: #c0c4cc; }
.remain { color: #e6a23c; }
.hint { font-size: 12px; color: #909399; margin-top: 8px; }
h4 { margin: 4px 0 8px; font-size: 14px; }
</style>
