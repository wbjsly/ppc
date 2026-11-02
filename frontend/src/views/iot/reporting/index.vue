<template>
  <div>
    <PdaShell :title="'移动报工 · ' + tabTitle" :scan="tab === 'output'" @scan="onScan" @sync="doSync">
      <!-- 13.1.1 工单接收 -->
      <template v-if="tab === 'orders'">
        <el-table :data="orders" size="small" v-loading="loading">
          <el-table-column prop="moNo" label="工单" width="130" />
          <el-table-column prop="productName" label="产品" show-overflow-tooltip />
          <el-table-column prop="planQty" label="计划" width="70" />
          <el-table-column prop="opSeq" label="工序" width="55" />
          <el-table-column label="首件" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="faType(row.firstArticleStatus)">
                {{ faText(row.firstArticleStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click="selectOrder(row)">开始领料</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-alert v-if="escalated > 0" type="warning" :closable="false" style="margin-top:8px"
                  :title="`已升级 ${escalated} 个离线超 72h 终端待办`" />
      </template>

      <!-- 扫码领料校验（选中工单后显示） -->
      <template v-if="tab === 'output' && current">
        <el-card shadow="never" style="margin-bottom:10px">
          <div style="font-size:13px;color:#606266">
            {{ current.moNo }} · {{ current.productName }} · 工序 {{ current.opSeq }}
            <el-tag size="small" style="margin-left:6px" :type="faType(current.firstArticleStatus)">
              首件：{{ faText(current.firstArticleStatus) }}
            </el-tag>
          </div>
        </el-card>

        <!-- 领料校验结果 -->
        <el-alert v-if="verifyResult" type="success" :closable="false" style="margin-bottom:8px"
                  :title="`领料校验通过：${verifyResult.itemCode}`"
                  :description="verifyResult.warning || verifyResult.overReason || ''" />
        <el-alert v-if="verifyError" type="error" :closable="false" style="margin-bottom:8px"
                  :title="verifyError" />

        <!-- 首件提交（无记录时强制） -->
        <el-card v-if="faState === 'NONE' || faState === 'PENDING'" shadow="never" style="margin-bottom:10px">
          <template #header>
            <span style="font-weight:600">首件报工（BR-4.11-1-3 强制）</span>
          </template>
          <el-input v-model="ctqJson" type="textarea" :rows="2"
                    placeholder='CTQ 数据 JSON，如 {"dimension":"42.0mm"}' />
          <div style="margin-top:8px;display:flex;gap:8px">
            <el-input v-model="photoUrl" placeholder="首件照片 URL" style="flex:1" />
            <el-button size="small" @click="submitFa">提交首件</el-button>
          </div>
          <div v-if="faOffline" style="margin-top:6px">
            <el-switch v-model="faOfflineFlag" active-text="离线条件放行（BR-4.11-10，联网须复核）" />
          </div>
          <div v-if="faState === 'PENDING'" style="margin-top:8px;color:#909399;font-size:12px">
            待 IPQC 确认 —— 确认通过后方可批量报工
          </div>
        </el-card>

        <!-- 批量报工录入 -->
        <el-form label-width="90px" size="small">
          <el-form-item label="合格产出">
            <el-input-number v-model="goodQty" :min="0" :precision="0" style="width:160px" />
          </el-form-item>
          <el-form-item label="不良产出">
            <el-input-number v-model="ngQty" :min="0" :precision="0" style="width:160px" />
          </el-form-item>
          <el-form-item label="幂等键">
            <el-input v-model="idemKey" placeholder="工单+工序+时间戳+设备（防重复过账）" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="doReport">提交报工</el-button>
          </el-form-item>
        </el-form>

        <el-alert v-if="reportResult" :type="reportResult.type" :closable="false"
                  :title="reportResult.title" :description="reportResult.desc" />
      </template>

      <!-- 13.1.3 质检确认（IPQC 首件队列） -->
      <template v-if="tab === 'qc'">
        <el-table :data="faQueue" size="small" v-loading="loading">
          <el-table-column prop="moNo" label="工单" width="130" />
          <el-table-column prop="opSeq" label="工序" width="55" />
          <el-table-column prop="ctqJson" label="CTQ 数据" show-overflow-tooltip />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="faType(row.status)">{{ faText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <template v-if="row.status === 'PENDING'">
                <el-button size="small" type="success" link @click="confirmFa(row, true)">通过</el-button>
                <el-button size="small" type="danger" link @click="confirmFa(row, false)">驳回</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <!-- 13.1.4 报工审核（车间主任超产/异常待审） -->
      <template v-if="tab === 'review'">
        <el-table :data="reviewList" size="small" v-loading="loading">
          <el-table-column prop="TITLE" label="待审" show-overflow-tooltip />
          <el-table-column prop="ASSIGNEE" label="处理人" width="110" />
          <el-table-column prop="DUE_AT" label="超时计时" width="150" />
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button size="small" type="success" link @click="review(row, true)">通过</el-button>
              <el-button size="small" type="danger" link @click="review(row, false)">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </PdaShell>
  </div>
</template>

<script setup>
/**
 * 13.1 移动报工四叶（add-iot-mobile）：
 * 13.1.1 工单接收 / 13.1.2 工序报工（首件+领料校验+批量录入）/
 * 13.1.3 质检确认 / 13.1.4 报工审核。套 PdaShell 仿真壳。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PdaShell from '../_pda/PdaShell.vue'
import {
  myOrdersApi, scanPushEscalationsApi, verifyMaterialApi, submitFirstArticleApi,
  firstArticleOfApi, confirmFirstArticleApi, reportOutputApi, reviewReportApi,
} from '@/api/iot/iot'
import request from '@/utils/request'

const route = useRoute()
const tab = computed(() => route.meta.tab || 'orders')
const tabTitle = computed(() => route.meta.title || '移动报工')

const loading = ref(false)
const orders = ref([])
const current = ref(null)
const verifyResult = ref(null)
const verifyError = ref('')
const ctqJson = ref('')
const photoUrl = ref('')
const faOfflineFlag = ref(true)
const faOffline = computed(() => faState.value === 'NONE')
const goodQty = ref(0)
const ngQty = ref(0)
const idemKey = ref('')
const submitting = ref(false)
const reportResult = ref(null)
const faState = ref('NONE')
const faId = ref(null)
const faQueue = ref([])
const reviewList = ref([])
const escalated = ref(0)

function faType(s) {
  return { NONE: 'info', PENDING: 'warning', CONFIRMED: 'success', REJECTED: 'danger' }[s] || 'info'
}
function faText(s) {
  return { NONE: '未提交', PENDING: '待确认', CONFIRMED: '已通过', REJECTED: '已驳回' }[s] || s
}

async function loadOrders() {
  loading.value = true
  try {
    const r = await myOrdersApi()
    orders.value = r.data || []
  } finally {
    loading.value = false
  }
}

function selectOrder(row) {
  current.value = row
  faState.value = row.firstArticleStatus
  idemKey.value = `${row.moNo}-${row.opSeq}-${Date.now()}-PDA`
}

async function onScan(code) {
  // 扫码即领料校验（13.1.2 FR-4.11-1-2 四校验）
  if (!current.value) {
    ElMessage.warning('请先在工单列表选择工单')
    return
  }
  verifyResult.value = null
  verifyError.value = ''
  try {
    const r = await verifyMaterialApi({
      dispatchId: current.value.dispatchId,
      scanCode: code,
      qty: goodQty.value > 0 ? goodQty.value : 10,
      reason: null,
    })
    verifyResult.value = r.data
  } catch (e) {
    verifyError.value = e?.response?.data?.message || e.message || '校验失败'
  }
}

async function submitFa() {
  try {
    const r = await submitFirstArticleApi({
      dispatchId: current.value.dispatchId,
      ctqJson: ctqJson.value,
      photoUrl: photoUrl.value,
      offline: faOfflineFlag.value,
    })
    faState.value = r.data.status
    faId.value = r.data.id
    ElMessage.success('首件已提交，待 IPQC 确认')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '提交失败')
  }
}

async function doReport() {
  submitting.value = true
  try {
    const r = await reportOutputApi({
      dispatchId: current.value.dispatchId,
      goodQty: goodQty.value,
      ngQty: ngQty.value,
      idempotentKey: idemKey.value,
      deviceCode: 'PDA-WEB',
      overprodAck: false,
    })
    const d = r.data
    if (d.duplicate) {
      reportResult.value = { type: 'warning', title: '重复提交已去重', desc: d.message }
    } else if (d.status === 'PENDING_REVIEW') {
      reportResult.value = { type: 'warning', title: '超产待车间主任审核', desc: '审核通过后过账' }
    } else if (d.status === 'CONFIRMED') {
      reportResult.value = {
        type: d.qualityAlert ? 'warning' : 'success',
        title: '报工已确认',
        desc: d.qualityAlert ? '连续 5 不良质量告警已生成' : '',
      }
    }
    faState.value = 'CONFIRMED'
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '报工失败')
  } finally {
    submitting.value = false
  }
}

async function confirmFa(row, pass) {
  try {
    await confirmFirstArticleApi(row.id, { pass, remark: pass ? 'IPQC 确认通过' : '首件不合格' })
    ElMessage.success(pass ? '已通过，解锁批量报工' : '已驳回')
    loadFaQueue()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

async function loadFaQueue() {
  // 质检确认队列：取 PENDING 首件（经待办/直接查询近似——列表页由后端首件接口提供）
  loading.value = true
  try {
    const r = await request.get('/iot/reporting/my-orders')
    faQueue.value = (r.data || [])
      .filter(o => o.firstArticleStatus === 'PENDING')
      .map(o => ({ moNo: o.moNo, opSeq: o.opSeq, ctqJson: '（见报工端提交）', id: o.dispatchId, status: 'PENDING', dispatchId: o.dispatchId }))
  } finally {
    loading.value = false
  }
}

async function loadReview() {
  loading.value = true
  try {
    const r = await request.get('/iot/todos', { params: { todoType: 'REPORT', status: 'PENDING' } })
    reviewList.value = r.data || []
  } catch (e) {
    reviewList.value = []
  } finally {
    loading.value = false
  }
}

async function review(row, pass) {
  try {
    await reviewReportApi(row.REF_ID || row.refId, { pass, opinion: pass ? '同意' : '驳回' })
    ElMessage.success('已处理')
    loadReview()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

async function doSync() {
  ElMessage.info('联网状态下队列已实时同步')
}

onMounted(async () => {
  idemKey.value = `PDA-${Date.now()}`
  await loadOrders()
  // 72h 离线升级扫描（进入页面触发一次）
  try {
    const r = await scanPushEscalationsApi()
    escalated.value = r.data?.escalated || 0
  } catch (e) { /* 静默 */ }
  if (tab.value === 'qc') loadFaQueue()
  if (tab.value === 'review') loadReview()
})
</script>
