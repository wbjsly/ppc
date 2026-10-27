<template>
  <div class="complaint-accept">
    <el-card style="margin-bottom: 12px;">
      <div class="hint">
        投诉受理（FR-4.8-9-1/2，BR-4.8-03）：登记即 SLA 计时起点（非派单时间）。
        自动定级按「投诉类型 × 影响程度」映射（一级 2h/24h、二级 4h/72h、三级 8h/7 工作日），
        客服可人工调整但须注明理由（L5 审计留痕）。派单按服务工程师在手工单最少推荐，可改派；
        无可派人员置「待指派」并自动升级服务主管。重复投诉不阻止建单（每次投诉独立 SLA 计时）。
      </div>
    </el-card>

    <el-card style="margin-bottom: 12px;">
      <template #header><b>投诉登记</b></template>
      <el-form label-width="100px" size="default">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="客户" required>
              <el-select v-model="form.customerId" filterable placeholder="选择客户主数据（必填 L1）"
                style="width: 100%;">
                <el-option v-for="c in customers" :key="c.id"
                  :label="c.customerName || c.name" :value="c.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="投诉类型" required>
              <el-select v-model="form.complaintType" style="width: 100%;">
                <el-option value="PRODUCT_QUALITY" label="产品质量" />
                <el-option value="DELIVERY_DELAY" label="交付延迟" />
                <el-option value="SERVICE_ATTITUDE" label="服务态度" />
                <el-option value="FUNCTION_DEFECT" label="功能缺陷" />
                <el-option value="OTHER" label="其他" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="影响程度" required>
              <el-select v-model="form.impactLevel" style="width: 100%;">
                <el-option value="PRODUCTION" label="影响生产/安全（→一级）" />
                <el-option value="CORE" label="核心功能不可用（→二级）" />
                <el-option value="NORMAL" label="一般（→三级）" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="关联合同">
              <el-input v-model="form.contractNo" placeholder="可选，合同编号" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="所需技能">
              <el-input v-model="form.skillTag" placeholder="参考标签（非主数据，仅筛选参考）" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="期望解决时限">
              <el-date-picker v-model="form.expectDeadline" type="datetime"
                value-format="YYYY-MM-DDTHH:mm:ss" placeholder="可选" style="width: 100%;" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="投诉描述" required>
          <el-input v-model="form.description" type="textarea" :rows="3"
            placeholder="客户反馈的具体问题（必填 L1）" />
        </el-form-item>
      </el-form>
      <el-button type="primary" :loading="submitting" :disabled="!form.customerId"
        @click="doRegister">登记并自动定级</el-button>
    </el-card>

    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <b>工单列表</b>
          <div>
            <el-select v-model="filters.status" clearable placeholder="状态" style="width: 150px; margin-right: 8px;">
              <el-option value="REGISTERED" label="已登记" />
              <el-option value="DISPATCHED" label="已派单" />
              <el-option value="PROCESSING" label="处理中" />
              <el-option value="PENDING_CONFIRM" label="待客户确认" />
              <el-option value="CLOSED" label="已关闭" />
            </el-select>
            <el-select v-model="filters.level" clearable placeholder="等级" style="width: 100px; margin-right: 8px;">
              <el-option :value="1" label="一级" />
              <el-option :value="2" label="二级" />
              <el-option :value="3" label="三级" />
            </el-select>
            <el-input v-model="filters.keyword" clearable placeholder="单号/客户/描述"
              style="width: 180px; margin-right: 8px;" @keyup.enter="loadOrders" />
            <el-button @click="loadOrders">查询</el-button>
          </div>
        </div>
      </template>
      <el-table :data="orders" size="small" border>
        <el-table-column prop="orderNo" label="工单号" width="150" />
        <el-table-column prop="customerName" label="客户" width="140" />
        <el-table-column label="等级" width="80">
          <template #default="{ row }">
            <el-tag :type="row.level === 1 ? 'danger' : row.level === 2 ? 'warning' : 'info'" size="small">
              {{ row.level === 1 ? '一级' : row.level === 2 ? '二级' : '三级' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="complaintType" label="类型" width="100">
          <template #default="{ row }">{{ typeName(row.complaintType) }}</template>
        </el-table-column>
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="SLA 响应截止" width="160">
          <template #default="{ row }">{{ (row.respDeadline || '').replace('T', ' ') }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusName(row.status) }}</el-tag>
            <el-tag v-if="row.suspendedFlag === '1'" size="small" type="warning">挂起</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="重复投诉" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.duplicateOf" size="small" type="warning">重复</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="engineerName" label="工程师" width="90" />
        <el-table-column label="升级" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.escalateStage > 0" size="small" type="danger">L{{ row.escalateStage }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDispatch(row)">派单/改派</el-button>
            <el-button link type="warning" size="small" @click="openLevel(row)">调整定级</el-button>
            <el-button v-if="row.duplicateCandidates && row.duplicateCandidates.length"
              link type="info" size="small" @click="openDuplicate(row)">查重({{ row.duplicateCandidates.length }})</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top: 10px; justify-content: flex-end;"
        layout="total, prev, pager, next" :total="total" :page-size="20" />
    </el-card>

    <!-- 派单对话框 -->
    <el-dialog v-model="dispatchVisible" title="派单 / 改派" width="480px">
      <el-form label-width="90px">
        <el-form-item label="推荐工程师">
          <el-tag v-if="recommended" type="success">
            {{ recommended.nickName || recommended.username }}（在手 {{ recommended.inHandCount }} 单）
          </el-tag>
          <el-tag v-else type="info">无在岗服务工程师，请人工指派</el-tag>
        </el-form-item>
        <el-form-item label="服务工程师">
          <el-select v-model="dispatchForm.engineerId" clearable filterable style="width: 100%;"
            placeholder="留空 = 待指派（自动升级服务主管）">
            <el-option v-for="e in engineers" :key="e.userId"
              :label="`${e.nickName || e.username}（在手 ${e.inHandCount}）`" :value="e.userId" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dispatchVisible = false">取消</el-button>
        <el-button type="primary" @click="doDispatch">确认派单</el-button>
      </template>
    </el-dialog>

    <!-- 调整定级对话框 -->
    <el-dialog v-model="levelVisible" title="人工调整定级（L5 留痕）" width="480px">
      <el-form label-width="90px">
        <el-form-item label="新等级" required>
          <el-radio-group v-model="levelForm.level">
            <el-radio :value="1">一级（2h 响应/24h 解决）</el-radio>
            <el-radio :value="2">二级（4h/72h）</el-radio>
            <el-radio :value="3">三级（8h/7 工作日）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="调整理由" required>
          <el-input v-model="levelForm.reason" type="textarea" :rows="2"
            placeholder="理由必填（空则 422），写入审计日志 Before/After" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="levelVisible = false">取消</el-button>
        <el-button type="primary" @click="doAdjustLevel">确认调整</el-button>
      </template>
    </el-dialog>

    <!-- 重复投诉候选 -->
    <el-dialog v-model="dupVisible" title="重复投诉候选（标注不合并，SLA 独立计时）" width="560px">
      <el-table :data="dupCandidates" size="small" border>
        <el-table-column prop="orderNo" label="前单号" width="150" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">{{ statusName(row.status) }}</template>
        </el-table-column>
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="doMarkDuplicate(row)">关联</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 登记结果（定级 + 重复候选提示） -->
    <el-dialog v-model="resultVisible" title="登记结果" width="520px">
      <el-descriptions v-if="result" :column="1" border size="small">
        <el-descriptions-item label="工单号">{{ result.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="自动定级">L{{ result.level }}（响应截止
          {{ (result.respDeadline || '').replace('T', ' ') }}）</el-descriptions-item>
        <el-descriptions-item label="重复投诉候选">
          {{ (result.duplicateCandidates || []).length }} 条（可在列表点「查重」关联标注）
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button type="primary" @click="resultVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as api from '@/api/pm/service-order'
import { getGroupPageApi } from '@/api/mdm/customer'

const customers = ref([])
const orders = ref([])
const total = ref(0)
const submitting = ref(false)
const filters = ref({ status: '', level: '', keyword: '' })

const dispatchVisible = ref(false)
const dispatchForm = ref({ id: '', engineerId: '' })
const engineers = ref([])
const recommended = ref(null)

const levelVisible = ref(false)
const levelForm = ref({ id: '', level: 1, reason: '' })

const dupVisible = ref(false)
const dupCandidates = ref([])
const dupOrderId = ref('')

const resultVisible = ref(false)
const result = ref(null)

const form = ref({
  customerId: '', complaintType: 'PRODUCT_QUALITY', impactLevel: 'NORMAL',
  contractNo: '', skillTag: '', expectDeadline: '', description: ''
})

const TYPE_NAMES = {
  PRODUCT_QUALITY: '产品质量', DELIVERY_DELAY: '交付延迟', SERVICE_ATTITUDE: '服务态度',
  FUNCTION_DEFECT: '功能缺陷', OTHER: '其他'
}
const ST_NAMES = {
  REGISTERED: '已登记', DISPATCHED: '已派单', PROCESSING: '处理中',
  PENDING_CONFIRM: '待确认', CLOSED: '已关闭'
}

const typeName = t => TYPE_NAMES[t] || t
const statusName = s => ST_NAMES[s] || s
const statusType = s => ({ REGISTERED: 'info', DISPATCHED: 'primary', PROCESSING: 'warning',
  PENDING_CONFIRM: 'warning', CLOSED: 'info' }[s] || 'info')

async function loadCustomers() {
  try {
    const { data } = await getGroupPageApi({ current: 1, size: 200, status: '1' })
    const list = data?.records || data?.items || data || []
    customers.value = Array.isArray(list) ? list : []
  } catch { customers.value = [] }
}

async function loadOrders() {
  const { data } = await api.orderPage({
    status: filters.value.status || '', level: filters.value.level ?? '',
    keyword: filters.value.keyword || ''
  })
  orders.value = data?.items || []
  total.value = data?.total || 0
}

async function doRegister() {
  submitting.value = true
  try {
    const { data } = await api.registerOrder(form.value)
    result.value = data
    resultVisible.value = true
    ElMessage.success(`已登记 ${data.orderNo}，自动定级 L${data.level}`)
    form.value.description = ''
    loadOrders()
  } finally {
    submitting.value = false
  }
}

async function openDispatch(row) {
  dispatchForm.value = { id: row.id, engineerId: row.engineerId || '' }
  const { data } = await api.recommendEngineer()
  engineers.value = data?.engineers || []
  recommended.value = data?.recommended || null
  dispatchVisible.value = true
}

async function doDispatch() {
  await api.dispatchOrder(dispatchForm.value.id, { engineerId: dispatchForm.value.engineerId || '' })
  ElMessage.success('派单成功')
  dispatchVisible.value = false
  loadOrders()
}

function openLevel(row) {
  levelForm.value = { id: row.id, level: row.level, reason: '' }
  levelVisible.value = true
}

async function doAdjustLevel() {
  if (!levelForm.value.reason.trim()) { ElMessage.warning('调整定级须填写理由（L5 审计留痕）'); return }
  await api.adjustLevel(levelForm.value.id, levelForm.value)
  ElMessage.success('定级已调整（已留痕）')
  levelVisible.value = false
  loadOrders()
}

function openDuplicate(row) {
  dupOrderId.value = row.id
  dupCandidates.value = row.duplicateCandidates || []
  dupVisible.value = true
}

async function doMarkDuplicate(prev) {
  await api.markDuplicate(dupOrderId.value, { duplicateOf: prev.id })
  ElMessage.success(`已关联前单 ${prev.orderNo}（SLA 独立计时）`)
  dupVisible.value = false
  loadOrders()
}

onMounted(() => { loadCustomers(); loadOrders() })
</script>

<style scoped>
.hint { color: #606266; font-size: 12px; line-height: 1.8; }
</style>
