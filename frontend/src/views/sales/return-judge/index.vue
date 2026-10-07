<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.10.2 退货判定工作台"
              description="判定确认责任方（我方质量/客户原因/物流破损）、每行核定可退数量与超期标记，记录判定人/时间/依据；超期（超过 RETURN_PERIOD_DAYS）自动标记并附说明；判定驳回注明原因退回。退款类提交后走 L2 审批（销售经理 + 财务），驳回退回调整（spec 12.3/12.4）。" />

    <el-row :gutter="12">
      <!-- 队列 -->
      <el-col :span="9">
        <el-card shadow="never" style="height:100%">
          <template #header>
            <div class="card-head">
              <b>退货队列</b>
              <div>
                <el-select v-model="filterStatus" size="small" style="width:170px" @change="load">
                  <el-option label="待判定 DRAFT" value="DRAFT" />
                  <el-option label="已判定 JUDGED" value="JUDGED" />
                  <el-option label="审批中 APPROVING" value="APPROVING" />
                  <el-option label="已驳回 REJECTED" value="REJECTED" />
                  <el-option label="全部" value="" />
                </el-select>
                <el-button size="small" style="margin-left:8px" @click="load">刷新</el-button>
              </div>
            </div>
          </template>
          <div v-for="r in list" :key="r.id" class="queue-item"
               :class="{ active: current && current.id === r.id }" @click="open(r)">
            <div class="q-head">
              <b>{{ r.returnNo }}</b>
              <el-tag size="small" :type="stTag(r.status)">{{ stName(r.status) }}</el-tag>
              <el-tag v-if="r.overdue === '1'" size="small" type="danger">超期</el-tag>
              <el-tag size="small" :type="r.invoiceFlag === 'INVOICED' ? 'warning' : 'info'">
                {{ r.invoiceFlag === 'INVOICED' ? '已开票' : '未开票' }}
              </el-tag>
            </div>
            <div class="q-meta">
              {{ r.customerName }} ｜ {{ r.soNo }} ｜
              {{ r.handleType === 'REFUND' ? '退款' : '换货' }} ｜ ¥{{ fmt(r.totalAmt) }}
            </div>
            <div class="q-meta">原因：{{ r.reasonType }} ｜ {{ (r.applyAt || '').slice(0, 10) }}</div>
          </div>
          <el-empty v-if="!list.length" description="队列为空" :image-size="60" />
        </el-card>
      </el-col>

      <!-- 判定表单 / 审批 -->
      <el-col :span="15">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <b>{{ current ? `判定：${current.returnNo}` : '判定工作台' }}</b>
              <div v-if="current">
                <el-button size="small" type="warning" :disabled="!canJudge"
                           @click="rejectVisible = true">判定驳回</el-button>
                <el-button size="small" type="primary" :disabled="!canJudge"
                           @click="doJudge">提交判定</el-button>
                <el-button size="small" type="success" :disabled="current.status !== 'JUDGED'"
                           @click="doSubmit">提交审批</el-button>
              </div>
            </div>
          </template>

          <el-empty v-if="!current" description="从左侧选择退货单" :image-size="70" />

          <template v-else>
            <el-descriptions :column="3" size="small" border class="mb12">
              <el-descriptions-item label="客户">{{ current.customerName }}</el-descriptions-item>
              <el-descriptions-item label="原 SO">{{ current.soNo }}</el-descriptions-item>
              <el-descriptions-item label="金额">¥{{ fmt(current.totalAmt) }}</el-descriptions-item>
              <el-descriptions-item label="原因分类">{{ current.reasonType }}</el-descriptions-item>
              <el-descriptions-item label="期望处理">
                {{ current.handleType === 'REFUND' ? '退款' : '换货' }}
              </el-descriptions-item>
              <el-descriptions-item label="开票分流">
                <el-tag size="small" :type="current.invoiceFlag === 'INVOICED' ? 'danger' : 'info'">
                  {{ current.invoiceFlag === 'INVOICED' ? '已开票（须红字）' : '未开票' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="退货原因" :span="2">{{ current.returnReason }}</el-descriptions-item>
              <el-descriptions-item label="凭证说明">{{ current.remark }}</el-descriptions-item>
            </el-descriptions>

            <el-alert v-if="current.overdue === '1'" type="warning" :closable="false" show-icon
                      class="mb12"
                      :title="`超期退货（超过约定退货期）：${current.overdueNote || current.judgeNote || ''}`" />

            <!-- 判定区 -->
            <template v-if="canJudge">
              <el-form label-width="96px" size="small">
                <el-form-item label="责任方" required>
                  <el-radio-group v-model="judgeForm.liability">
                    <el-radio value="OUR_QUALITY">我方质量</el-radio>
                    <el-radio value="CUSTOMER">客户原因</el-radio>
                    <el-radio value="LOGISTICS">物流破损</el-radio>
                  </el-radio-group>
                </el-form-item>
                <el-form-item label="判定处理" required>
                  <el-radio-group v-model="judgeForm.handle">
                    <el-radio value="REFUND">退款</el-radio>
                    <el-radio value="EXCHANGE">换货</el-radio>
                  </el-radio-group>
                  <span class="hint">（可修正申请的期望处理方式，退款/换货互斥 BR-4.3-70）</span>
                </el-form-item>
                <el-form-item label="判定依据" required>
                  <el-input v-model="judgeForm.basis" type="textarea" :rows="2"
                            placeholder="必填：检验结论/责任认定/处理意见（记录判定人、时间、依据）" />
                </el-form-item>
              </el-form>

              <el-table :data="lines" size="small" border class="mb12">
                <el-table-column prop="lineNo" label="#" width="42" />
                <el-table-column prop="itemCode" label="物料" width="120" />
                <el-table-column prop="itemName" label="名称" min-width="120" show-overflow-tooltip />
                <el-table-column label="申请数量" width="95" align="right">
                  <template #default="{ row }">{{ Number(row.qty) }}</template>
                </el-table-column>
                <el-table-column label="核定可退" width="130">
                  <template #default="{ row }">
                    <el-input-number v-model="judgeForm.qtyMap[row.id]" :min="0"
                                     :max="Number(row.qty)" :precision="3" size="small"
                                     style="width:100%" />
                  </template>
                </el-table-column>
                <el-table-column label="单价" width="90" align="right">
                  <template #default="{ row }">¥{{ fmt(row.unitPrice) }}</template>
                </el-table-column>
              </el-table>
            </template>

            <!-- 判定结果 / 审批留痕 -->
            <template v-else>
              <el-descriptions :column="3" size="small" border class="mb12">
                <el-descriptions-item label="责任方">
                  {{ liabilityName(current.liability) }}
                </el-descriptions-item>
                <el-descriptions-item label="判定处理">
                  {{ current.judgeHandle === 'REFUND' ? '退款' : '换货' }}
                </el-descriptions-item>
                <el-descriptions-item label="判定人">{{ current.judgeBy || '—' }}</el-descriptions-item>
                <el-descriptions-item label="判定依据" :span="3">
                  {{ current.judgeNote || '—' }}
                </el-descriptions-item>
              </el-descriptions>
              <el-table :data="lines" size="small" border class="mb12">
                <el-table-column prop="lineNo" label="#" width="42" />
                <el-table-column prop="itemCode" label="物料" width="120" />
                <el-table-column label="申请/核定" width="110" align="right">
                  <template #default="{ row }">
                    {{ Number(row.qty) }} / <b>{{ row.judgeQty == null ? '—' : Number(row.judgeQty) }}</b>
                  </template>
                </el-table-column>
                <el-table-column label="行状态" width="100">
                  <template #default="{ row }">
                    <el-tag size="small">{{ row.lineStatus }}</el-tag>
                  </template>
                </el-table-column>
              </el-table>

              <!-- 审批操作 -->
              <div v-if="current.status === 'APPROVING' || current.status === 'APPROVED'
                        || current.status === 'REJECTED'" class="mb12">
                <h4>审批</h4>
                <div v-for="t in myTasks" :key="t.taskId" class="todo-item">
                  <div class="todo-meta">
                    [{{ t.nodeName }}] {{ t.title }}
                    <el-tag size="small" :type="current.overdue === '1' ? 'danger' : 'primary'">
                      {{ current.overdue === '1' ? '超期单' : '常规' }}
                    </el-tag>
                  </div>
                  <div class="todo-op">
                    <el-input v-model="opinions[t.taskId]" size="small" style="flex:1"
                              placeholder="审批意见必填（≥2 字）" />
                    <el-button size="small" type="success" @click="doPass(t)">通过</el-button>
                    <el-button size="small" type="danger" @click="doReject(t)">驳回</el-button>
                  </div>
                </div>
                <el-alert v-if="!myTasks.length && current.status === 'APPROVING'"
                          type="info" :closable="false" show-icon
                          title="等待对应角色审批（退款 L2：销售经理 → 财务）" />
                <el-alert v-if="current.status === 'REJECTED'" type="error" :closable="false"
                          show-icon title="审批已驳回 —— 退回发起人调整后重新判定与提交" />
                <el-alert v-if="current.status === 'APPROVED'" type="success" :closable="false"
                          show-icon title="审批通过 —— 转 3.10.3 退款换货执行" />
              </div>
            </template>

            <h4>操作留痕</h4>
            <el-timeline>
              <el-timeline-item v-for="l in judgeLogs" :key="l.id" :type="logType(l.opType)">
                [{{ opName(l.opType) }}] {{ l.basis }}
                <div class="meta">{{ l.opBy }} ｜ {{ (l.opAt || '').replace('T', ' ').slice(0, 16) }}</div>
              </el-timeline-item>
            </el-timeline>
            <el-empty v-if="!judgeLogs.length" description="尚无留痕" :image-size="50" />
          </template>
        </el-card>
      </el-col>
    </el-row>

    <!-- 判定驳回 -->
    <el-dialog v-model="rejectVisible" title="判定驳回" width="460px">
      <el-input v-model="rejectReason" type="textarea" :rows="3"
                placeholder="必填：驳回原因（≥2 字，退回申请方调整）" />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" @click="doJudgeReject">驳回退回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getReturnsApi, getReturnDetailApi, judgeReturnApi, judgeRejectApi, submitReturnApi
} from '@/api/sd/return'
import {
  getQmsApprovalTodoApi, passQmsApprovalApi, rejectQmsApprovalApi
} from '@/api/qms/approval'

const list = ref([])
const filterStatus = ref('DRAFT')
const current = ref(null)
const lines = ref([])
const judgeLogs = ref([])
const opinions = ref({})
const allTodos = ref([])
const rejectVisible = ref(false)
const rejectReason = ref('')

const judgeForm = reactive({
  liability: 'OUR_QUALITY',
  handle: 'REFUND',
  basis: '',
  qtyMap: {}
})

const canJudge = computed(() => current.value
  && (current.value.status === 'DRAFT' || current.value.status === 'REJECTED'))

const myTasks = computed(() => allTodos.value.filter(t =>
  t.bizType === 'SdReturn' && current.value && t.bizId === current.value.id))

function fmt(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function stName(s) {
  return ({ DRAFT: '待判定', JUDGED: '已判定', APPROVING: '审批中', APPROVED: '已通过',
    REJECTED: '已驳回', DONE: '已完成', CANCELLED: '已撤销' })[s] || s
}
function stTag(s) {
  return ({ DRAFT: 'warning', JUDGED: 'primary', APPROVING: 'warning', APPROVED: 'success',
    REJECTED: 'danger', DONE: '', CANCELLED: 'info' })[s] || 'info'
}
function liabilityName(v) {
  return ({ OUR_QUALITY: '我方质量', CUSTOMER: '客户原因', LOGISTICS: '物流破损' })[v] || v || '—'
}
function opName(v) {
  return ({ JUDGE: '判定', JUDGE_REJECT: '判定驳回', SUBMIT: '提交审批',
    STOCK_IN: '实物入库', REFUND: '退款执行', EXCHANGE: '换货执行' })[v] || v
}
function logType(v) {
  return ({ JUDGE_REJECT: 'danger', REFUND: 'success', EXCHANGE: 'primary',
    STOCK_IN: 'warning' })[v] || 'primary'
}

async function load() {
  const res = await getReturnsApi({
    current: 1, size: 100, status: filterStatus.value || undefined
  })
  list.value = res.data.records || res.data || []
}

async function open(r) {
  const res = await getReturnDetailApi(r.id)
  current.value = res.data.return
  lines.value = res.data.lines || []
  judgeLogs.value = res.data.judgeLogs || []
  judgeForm.liability = 'OUR_QUALITY'
  judgeForm.handle = current.value.handleType || 'REFUND'
  judgeForm.basis = ''
  judgeForm.qtyMap = {}
  for (const l of lines.value) {
    judgeForm.qtyMap[l.id] = l.judgeQty == null ? Number(l.qty) : Number(l.judgeQty)
  }
  loadTodo()
}

async function loadTodo() {
  try {
    const res = await getQmsApprovalTodoApi()
    allTodos.value = res.data || []
  } catch (e) {
    allTodos.value = []
  }
}

async function doJudge() {
  if (!judgeForm.liability) return ElMessage.warning('责任方必填')
  if (!judgeForm.basis || judgeForm.basis.trim().length < 2) {
    return ElMessage.warning('判定依据必填（spec 12.3）')
  }
  const jl = lines.value.map(l => ({
    lineId: l.id, judgeQty: Number(judgeForm.qtyMap[l.id] || 0)
  }))
  if (!jl.some(x => x.judgeQty > 0)) return ElMessage.warning('至少一行核定可退数量 > 0')
  await judgeReturnApi(current.value.id, {
    liability: judgeForm.liability, handle: judgeForm.handle,
    basis: judgeForm.basis.trim(), lines: jl
  })
  ElMessage.success('判定已记录（判定人/时间/依据留痕）')
  open({ id: current.value.id })
  load()
}

async function doJudgeReject() {
  if (!rejectReason.value || rejectReason.value.trim().length < 2) {
    return ElMessage.warning('驳回原因必填（≥2 字）')
  }
  await judgeRejectApi(current.value.id, rejectReason.value.trim())
  ElMessage.success('已判定驳回，单据退回')
  rejectVisible.value = false
  rejectReason.value = ''
  open({ id: current.value.id })
  load()
}

async function doSubmit() {
  await submitReturnApi(current.value.id)
  ElMessage.success('已提交审批（退款：销售经理+财务 L2 / 换货：销售经理）')
  open({ id: current.value.id })
  load()
}

async function doPass(t) {
  const op = (opinions.value[t.taskId] || '').trim()
  if (op.length < 2) return ElMessage.warning('审批意见必填（≥2 字）')
  await passQmsApprovalApi(t.taskId, op)
  ElMessage.success('已通过')
  opinions.value[t.taskId] = ''
  open({ id: current.value.id })
  loadTodo()
  load()
}

async function doReject(t) {
  const op = (opinions.value[t.taskId] || '').trim()
  if (op.length < 2) return ElMessage.warning('驳回意见必填（≥2 字）')
  await rejectQmsApprovalApi(t.taskId, op)
  ElMessage.success('已驳回，单据退回发起人')
  opinions.value[t.taskId] = ''
  open({ id: current.value.id })
  loadTodo()
  load()
}

onMounted(load)
</script>

<style scoped>
.page-wrap { padding: 12px; }
.mb12 { margin-bottom: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.queue-item { border: 1px solid #ebeef5; border-radius: 4px; padding: 8px 10px;
  margin-bottom: 8px; cursor: pointer; }
.queue-item:hover { border-color: #c6e2ff; }
.queue-item.active { border-color: #409eff; background: #f5faff; }
.q-head { display: flex; gap: 6px; align-items: center; margin-bottom: 4px; }
.q-meta { font-size: 12px; color: #909399; }
.todo-item { border: 1px solid #ebeef5; border-radius: 4px; padding: 10px; margin-bottom: 8px; }
.todo-meta { font-size: 12px; color: #606266; margin-bottom: 6px; }
.todo-op { display: flex; gap: 8px; }
.meta { font-size: 12px; color: #909399; }
.hint { font-size: 12px; color: #909399; margin-left: 8px; }
</style>
