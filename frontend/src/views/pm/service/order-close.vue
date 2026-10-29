<template>
  <div class="order-close">
    <el-card style="margin-bottom: 12px;">
      <div class="hint">
        工单闭环（FR-4.8-9-3/4/5）：处理记录全程仅可追加（BR-4.8-35），提交关闭申请前须填根因分类
        （设计缺陷自动抄送研发负责人）。质量类投诉（产品质量/制造·设计缺陷）必须关联 NCR，
        未关联不可标记已根治（BR-4.8-36）。客户确认双通道：内部代录 + 免密链接（token 一次性）；
        5 工作日未确认 → 2 次提醒后默认关闭。回访 ≤2 分自动生成改进任务（10 工作日闭环，逾期升级销售经理）。
      </div>
    </el-card>

    <!-- 工单选择 -->
    <el-card style="margin-bottom: 12px;">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <b>选择工单</b>
          <div>
            <el-select v-model="filters.status" clearable placeholder="状态" style="width: 150px; margin-right: 8px;">
              <el-option value="DISPATCHED" label="已派单" />
              <el-option value="PROCESSING" label="处理中" />
              <el-option value="PENDING_CONFIRM" label="待客户确认" />
              <el-option value="CLOSED" label="已关闭" />
            </el-select>
            <el-input v-model="filters.keyword" clearable placeholder="单号/客户/描述"
              style="width: 180px; margin-right: 8px;" @keyup.enter="loadOrders" />
            <el-button @click="loadOrders">查询</el-button>
          </div>
        </div>
      </template>
      <el-table :data="orders" size="small" border highlight-current-row @current-change="onSelect">
        <el-table-column prop="orderNo" label="工单号" width="150" />
        <el-table-column prop="customerName" label="客户" width="130" />
        <el-table-column label="等级" width="70">
          <template #default="{ row }">L{{ row.level }}</template>
        </el-table-column>
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ statusName(row.status) }}</el-tag>
            <el-tag v-if="row.suspendedFlag === '1'" size="small" type="warning">挂起</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="materialCost" label="服务成本" width="90" />
        <el-table-column prop="engineerName" label="工程师" width="90" />
      </el-table>
    </el-card>

    <!-- 工单处理详情 -->
    <el-row :gutter="12" v-if="current">
      <el-col :span="14">
        <el-card style="margin-bottom: 12px;">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <b>{{ current.orderNo }} 处理</b>
              <div>
                <el-button size="small" type="primary" @click="doAccept"
                  v-if="!current.respondedAt">受理</el-button>
                <el-button size="small" @click="suspendVisible = true"
                  v-if="current.suspendedFlag !== '1' && current.status !== 'CLOSED'">挂起</el-button>
                <el-button size="small" @click="doResume" v-if="current.suspendedFlag === '1'">恢复</el-button>
              </div>
            </div>
          </template>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="状态">{{ statusName(current.status) }}</el-descriptions-item>
            <el-descriptions-item label="SLA 解决截止">
              {{ (current.resolveDeadline || '').replace('T', ' ') }}
            </el-descriptions-item>
            <el-descriptions-item label="根因">
              {{ rootCauseName(current.rootCause) || '未填写（不可提交关闭）' }}
            </el-descriptions-item>
            <el-descriptions-item label="NCR 关联">
              <el-tag v-if="current.ncrNo" type="success" size="small">{{ current.ncrNo }}</el-tag>
              <el-tag v-else-if="isQuality" type="danger" size="small">必填（BR-4.8-36）</el-tag>
              <span v-else>—</span>
            </el-descriptions-item>
            <el-descriptions-item label="已根治">
              <el-tag v-if="current.rootCured === '1'" type="success" size="small">是</el-tag>
              <span v-else>否</span>
            </el-descriptions-item>
            <el-descriptions-item label="驳回次数">{{ current.rejectCount }}</el-descriptions-item>
          </el-descriptions>

          <el-divider content-position="left">追加处理记录</el-divider>
          <el-input v-model="newRecord" type="textarea" :rows="2"
            placeholder="处理过程记录（仅可追加，不可修改历史）" />
          <el-button style="margin-top: 8px;" type="primary" size="small"
            :disabled="!newRecord.trim()" @click="doAppendRecord">追加记录</el-button>

          <el-divider content-position="left">动作</el-divider>
          <div style="display: flex; gap: 8px; flex-wrap: wrap;">
            <el-button size="small" @click="rootCauseVisible = true"
              :disabled="current.status === 'CLOSED'">保存根因</el-button>
            <el-button size="small" @click="ncrVisible = true"
              :disabled="current.status === 'CLOSED'">关联 NCR</el-button>
            <el-button size="small" type="warning" @click="doMarkCured"
              :disabled="current.status === 'CLOSED' || current.rootCured === '1'">标记已根治</el-button>
            <el-button size="small" type="success" @click="doSubmitConfirm"
              :disabled="current.status !== 'PROCESSING' && current.status !== 'DISPATCHED'">提交客户确认</el-button>
            <el-button size="small" @click="doAfterSaleIssue"
              :disabled="current.status === 'CLOSED'">售后领料</el-button>
          </div>
        </el-card>

        <el-card>
          <template #header><b>处理记录时间线（仅追加）</b></template>
          <el-timeline>
            <el-timeline-item v-for="r in records" :key="r.id"
              :timestamp="(r.createDate || '').replace('T', ' ')" placement="top">
              <b>{{ actionName(r.action) }}</b>（{{ r.createBy }}）
              <div style="color: #606266; font-size: 12px;">{{ r.content }}</div>
            </el-timeline-item>
          </el-timeline>
        </el-card>
      </el-col>

      <el-col :span="10">
        <!-- 客户确认 -->
        <el-card style="margin-bottom: 12px;" v-if="current.status === 'PENDING_CONFIRM'">
          <template #header><b>客户确认</b></template>
          <div style="margin-bottom: 10px;">
            <el-button type="primary" size="small" @click="agentConfirmVisible = true">代录确认/驳回</el-button>
            <el-button size="small" @click="doGenerateLink">生成确认链接</el-button>
          </div>
          <div v-if="confirmLink" class="link-box">
            客户确认链接（一次性，7 天有效）：<br />
            <code>{{ confirmLink }}</code>
          </div>
        </el-card>

        <!-- 回访 -->
        <el-card style="margin-bottom: 12px;" v-if="current.status === 'CLOSED' && !currentSurvey">
          <template #header><b>满意度回访</b></template>
          <el-form label-width="90px" size="small">
            <el-form-item label="评分" required>
              <el-rate v-model="surveyForm.score" show-text :texts="['1', '2', '3', '4', '5']" />
              <div class="field-hint">≤2 分将自动生成改进任务（BR-4.8-06）</div>
            </el-form-item>
            <el-form-item label="改进建议">
              <el-input v-model="surveyForm.suggestion" type="textarea" :rows="2" />
            </el-form-item>
          </el-form>
          <el-button type="primary" size="small" @click="doSaveSurvey">提交回访</el-button>
          <el-button size="small" @click="doUnreached">3 次无法联系（未回访归档）</el-button>
        </el-card>

        <el-card style="margin-bottom: 12px;" v-if="currentSurvey">
          <template #header><b>回访结果</b></template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="评分">
              <el-tag v-if="currentSurvey.unreachedFlag === '1'" type="info">未回访</el-tag>
              <span v-else>{{ currentSurvey.score }} 分</span>
            </el-descriptions-item>
            <el-descriptions-item label="建议">{{ currentSurvey.suggestion || '—' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <!-- 改进任务（本工单） -->
        <el-card style="margin-bottom: 12px;" v-if="improveTasks.length">
          <template #header><b>低分改进任务</b></template>
          <el-table :data="improveTasks" size="small" border>
            <el-table-column prop="content" label="内容" show-overflow-tooltip />
            <el-table-column label="期限" width="110">
              <template #default="{ row }">{{ (row.dueDate || '').slice(0, 10) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'CLOSED' ? 'success' : 'warning'">
                  {{ row.status === 'PENDING' ? '待处理' : row.status === 'PROCESSING' ? '处理中' : '已闭环' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button v-if="row.status !== 'CLOSED'" link type="primary" size="small"
                  @click="closeImprove(row)">闭环</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- 售后领料（本工单） -->
        <el-card style="margin-bottom: 12px;">
          <template #header><b>售后领料（AFTER_SALE）</b></template>
          <el-table :data="issues" size="small" border>
            <el-table-column prop="issueNo" label="领料单号" width="150" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">{{ row.status }}</template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button v-if="row.status === 'DRAFT'" link type="primary" size="small"
                  @click="doPostIssue(row)">过账</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="field-hint" style="margin-top: 6px;">
            服务成本合计：{{ current.materialCost }}（过账后自动累加）
          </div>
        </el-card>

        <!-- 回访统计（三维汇总） -->
        <el-card>
          <template #header><b>回访统计（客户健康度口径）</b></template>
          <el-tabs size="small">
            <el-tab-pane label="按客户">
              <el-table :data="stats.byCustomer" size="small" border max-height="220">
                <el-table-column prop="customerName" label="客户" />
                <el-table-column prop="orderCount" label="工单数" width="70" />
                <el-table-column prop="avgScore" label="均分" width="60" />
                <el-table-column prop="lowScoreCount" label="低分" width="60" />
                <el-table-column prop="duplicateCount" label="重复投诉" width="80" />
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="按工程师">
              <el-table :data="stats.byEngineer" size="small" border max-height="220">
                <el-table-column prop="engineerName" label="工程师" />
                <el-table-column prop="orderCount" label="工单数" width="70" />
                <el-table-column prop="avgScore" label="均分" width="60" />
                <el-table-column prop="lowScoreCount" label="低分" width="60" />
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="按类型">
              <el-table :data="stats.byType" size="small" border max-height="220">
                <el-table-column label="投诉类型" prop="complaintType" />
                <el-table-column prop="orderCount" label="工单数" width="70" />
                <el-table-column prop="avgScore" label="均分" width="60" />
                <el-table-column prop="unreachedCount" label="未回访" width="70" />
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </el-card>
      </el-col>
    </el-row>

    <!-- 根因对话框 -->
    <el-dialog v-model="rootCauseVisible" title="保存根因分类" width="420px">
      <el-radio-group v-model="rootCauseForm">
        <el-radio value="DESIGN">设计缺陷（自动抄送研发负责人）</el-radio>
        <el-radio value="MANUFACTURE">制造缺陷</el-radio>
        <el-radio value="OPERATION">操作不当</el-radio>
        <el-radio value="EXTERNAL">外部原因</el-radio>
      </el-radio-group>
      <template #footer>
        <el-button @click="rootCauseVisible = false">取消</el-button>
        <el-button type="primary" @click="doSaveRootCause">保存</el-button>
      </template>
    </el-dialog>

    <!-- NCR 关联对话框 -->
    <el-dialog v-model="ncrVisible" title="关联质量域 NCR（BR-4.8-36）" width="460px">
      <el-input v-model="ncrForm" placeholder="NCR 单号（须存在，如 NCR20261026-000001）" />
      <div class="field-hint" style="margin-top: 6px;">
        也可在质量域「不合格管理」新建 NCR 后回来填写；质量类投诉未关联不可标已根治。
      </div>
      <template #footer>
        <el-button @click="ncrVisible = false">取消</el-button>
        <el-button type="primary" @click="doLinkNcr">关联</el-button>
      </template>
    </el-dialog>

    <!-- 代录确认对话框 -->
    <el-dialog v-model="agentConfirmVisible" title="代录客户确认（confirmed_via=AGENT）" width="420px">
      <el-radio-group v-model="agentResult">
        <el-radio value="RESOLVED">已解决（关闭工单）</el-radio>
        <el-radio value="REJECTED">驳回（退回处理，重新计时）</el-radio>
      </el-radio-group>
      <el-input v-model="agentNote" type="textarea" :rows="2" style="margin-top: 10px;"
        placeholder="代录备注（客户原话/沟通记录）" />
      <template #footer>
        <el-button @click="agentConfirmVisible = false">取消</el-button>
        <el-button type="primary" @click="doAgentConfirm">确认</el-button>
      </template>
    </el-dialog>

    <!-- 挂起对话框 -->
    <el-dialog v-model="suspendVisible" title="挂起「待客户信息」（SLA 暂停计时）" width="420px">
      <el-input v-model="suspendReason" type="textarea" :rows="2" placeholder="挂起原因（默认：待客户信息）" />
      <template #footer>
        <el-button @click="suspendVisible = false">取消</el-button>
        <el-button type="primary" @click="doSuspend">挂起</el-button>
      </template>
    </el-dialog>

    <!-- 售后领料对话框 -->
    <el-dialog v-model="issueVisible" title="售后领料（AFTER_SALE，NORMAL 仓扣减）" width="480px">
      <el-form label-width="80px" size="small">
        <el-form-item label="物料" required>
          <el-input v-model="issueForm.itemCode" placeholder="物料编码" />
        </el-form-item>
        <el-form-item label="数量" required>
          <el-input-number v-model="issueForm.qty" :min="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreateIssue">创建（DRAFT）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as api from '@/api/pm/service-order'

const orders = ref([])
const filters = ref({ status: 'PROCESSING', keyword: '' })
const current = ref(null)
const records = ref([])
const currentSurvey = ref(null)
const improveTasks = ref([])
const issues = ref([])
const stats = ref({ byCustomer: [], byEngineer: [], byType: [] })
const newRecord = ref('')
const confirmLink = ref('')

const rootCauseVisible = ref(false)
const rootCauseForm = ref('EXTERNAL')
const ncrVisible = ref(false)
const ncrForm = ref('')
const agentConfirmVisible = ref(false)
const agentResult = ref('RESOLVED')
const agentNote = ref('')
const suspendVisible = ref(false)
const suspendReason = ref('')
const issueVisible = ref(false)
const issueForm = ref({ itemCode: '', qty: 1 })
const surveyForm = ref({ score: 5, suggestion: '' })

const isQuality = computed(() => current.value &&
  (current.value.complaintType === 'PRODUCT_QUALITY'
    || current.value.rootCause === 'MANUFACTURE' || current.value.rootCause === 'DESIGN'))

const ST_NAMES = { REGISTERED: '已登记', DISPATCHED: '已派单', PROCESSING: '处理中',
  PENDING_CONFIRM: '待确认', CLOSED: '已关闭' }
const ACT_NAMES = { ACCEPT: '受理', PROCESS: '处理', ROOT_CAUSE: '根因', SUSPEND: '挂起',
  RESUME: '恢复', REJECT: '驳回', SUBMIT_CONFIRM: '提交确认', CLOSE: '关闭', ESCALATE: '升级' }
const RC_NAMES = { DESIGN: '设计缺陷', MANUFACTURE: '制造缺陷', OPERATION: '操作不当', EXTERNAL: '外部原因' }

const statusName = s => ST_NAMES[s] || s
const actionName = a => ACT_NAMES[a] || a
const rootCauseName = r => RC_NAMES[r] || ''

async function loadOrders() {
  const { data } = await api.orderPage({
    status: filters.value.status || '', keyword: filters.value.keyword || ''
  })
  orders.value = data?.items || []
}

async function onSelect(row) {
  if (!row) return
  const { data } = await api.orderDetail(row.id)
  current.value = data
  records.value = data.records || []
  currentSurvey.value = data.survey || null
  improveTasks.value = data.improveTasks || []
  confirmLink.value = ''
  await loadIssues(row.orderNo)
}

async function loadIssues(orderNo) {
  try {
    const { data } = await api.issueByServiceOrder(orderNo)
    issues.value = data?.records || []
  } catch { issues.value = [] }
}

async function doAccept() {
  const { data } = await api.acceptOrder(current.value.id)
  current.value = { ...current.value, ...data }
  ElMessage.success('已受理（响应计时终点）')
  onSelect({ id: current.value.id })
}

async function doAppendRecord() {
  await api.appendRecord(current.value.id, { content: newRecord.value })
  newRecord.value = ''
  ElMessage.success('已追加（历史记录不可修改）')
  onSelect({ id: current.value.id })
}

function doSaveRootCause() {
  rootCauseVisible.value = false
  api.saveRootCause(current.value.id, { rootCause: rootCauseForm.value }).then(() => {
    ElMessage.success('根因已保存')
    onSelect({ id: current.value.id })
  })
}

function doLinkNcr() {
  if (!ncrForm.value.trim()) { ElMessage.warning('请填写 NCR 单号'); return }
  api.linkNcr(current.value.id, { ncrNo: ncrForm.value.trim() }).then(() => {
    ElMessage.success('NCR 已关联')
    ncrVisible.value = false
    onSelect({ id: current.value.id })
  })
}

async function doMarkCured() {
  await api.markCured(current.value.id)
  ElMessage.success('已标记根治')
  onSelect({ id: current.value.id })
}

async function doSubmitConfirm() {
  await api.submitConfirm(current.value.id)
  ElMessage.success('已提交客户确认（5 工作日未确认将提醒后默认关闭）')
  onSelect({ id: current.value.id })
}

async function doGenerateLink() {
  const { data } = await api.generateConfirmLink(current.value.id)
  confirmLink.value = data.url
  ElMessage.success('链接已生成（token 一次性）')
}

function doAgentConfirm() {
  api.agentConfirm(current.value.id, { result: agentResult.value, note: agentNote.value })
    .then(() => {
      ElMessage.success(agentResult.value === 'RESOLVED' ? '已代录确认并关闭' : '已驳回，退回处理重新计时')
      agentConfirmVisible.value = false
      agentNote.value = ''
      onSelect({ id: current.value.id })
      loadOrders()
    })
}

function doSuspend() {
  api.suspendOrder(SUSPEND_ID(), { reason: suspendReason.value || '待客户信息' }).then(() => {
    ElMessage.success('已挂起（SLA 计时暂停）')
    suspendVisible.value = false
    onSelect({ id: current.value.id })
  })
}

const SUSPEND_ID = () => current.value.id

async function doResume() {
  await api.resumeOrder(current.value.id)
  ElMessage.success('已恢复（挂起时长不计 SLA）')
  onSelect({ id: current.value.id })
}

function doAfterSaleIssue() {
  issueForm.value = { itemCode: '', qty: 1 }
  issueVisible.value = true
}

async function doCreateIssue() {
  if (!issueForm.value.itemCode) { ElMessage.warning('请填物料编码'); return }
  const { data } = await api.createAfterSaleIssue({
    workOrderNo: `SO-${current.value.orderNo}`,
    serviceOrderNo: current.value.orderNo,
    purpose: `服务工单 ${current.value.orderNo} 换件`,
    lines: [{ itemCode: issueForm.value.itemCode, qty: issueForm.value.qty }]
  })
  ElMessage.success(`售后领料单 ${data.issue.issueNo} 已创建（DRAFT），请仓库过账`)
  issueVisible.value = false
  loadIssues(current.value.orderNo)
}

async function doPostIssue(row) {
  await api.postIssue(row.id)
  ElMessage.success('已过账（库存扣减 + 服务成本累加）')
  onSelect({ id: current.value.id })
  loadOrders()
}

function doSaveSurvey() {
  if (!surveyForm.value.score) { ElMessage.warning('请评分'); return }
  api.saveSurvey(current.value.id, surveyForm.value).then(res => {
    ElMessage.success(res.data?.improveTask
      ? '回访已录入，已生成低分改进任务（BR-4.8-06）' : '回访已录入')
    onSelect({ id: current.value.id })
    loadStats()
  })
}

async function doUnreached() {
  await api.saveSurvey(current.value.id, { unreached: '1', contactTries: 3 })
  ElMessage.success('已标注「未回访」归档')
  onSelect({ id: current.value.id })
}

async function closeImprove(row) {
  await api.updateImproveTask(row.id, { status: 'CLOSED' })
  ElMessage.success('改进任务已闭环')
  onSelect({ id: current.value.id })
}

async function loadStats() {
  const { data } = await api.surveyStats({})
  stats.value = data || { byCustomer: [], byEngineer: [], byType: [] }
}

onMounted(() => { loadOrders(); loadStats() })
</script>

<style scoped>
.hint { color: #606266; font-size: 12px; line-height: 1.8; }
.field-hint { color: #909399; font-size: 12px; line-height: 1.5; }
.link-box { background: #f5f7fa; padding: 8px; border-radius: 4px; font-size: 12px; word-break: break-all; }
</style>
