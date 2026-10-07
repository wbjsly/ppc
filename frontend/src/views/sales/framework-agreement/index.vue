<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.11.1 框架订单（销售框架协议）"
              description="协议编号系统生成且创建后不可改；行级三量跟踪：剩余可下达 = 总量 − 已下达、剩余可发 = 已下达 − 已发；下达超总量 / 发货超已下达以 L1 硬阻断（C-4.3-10）；总量调整、单价重谈与提前终止须经销售总监 L2 审批（S-4.3-11），调减不得低于已发量、终止冻结后续发货。协议与销售合同（11.11）为两个独立对象（D11）。" />

    <el-row :gutter="12" class="mb12">
      <el-col :span="6" v-for="k in kpis" :key="k.label">
        <div class="kpi-box">
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value" :style="{ color: k.color || '#303133' }">{{ k.value }}</div>
        </div>
      </el-col>
    </el-row>

    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <b>框架协议列表</b>
          <div>
            <el-input v-model="keyword" placeholder="编号/客户/名称" size="small"
                      style="width:200px" clearable @change="load" />
            <el-select v-model="filterStatus" clearable placeholder="状态" size="small"
                       style="width:130px;margin-left:8px" @change="load">
              <el-option label="生效中 EFFECTIVE" value="EFFECTIVE" />
              <el-option label="已终止 TERMINATED" value="TERMINATED" />
              <el-option label="已过期 EXPIRED" value="EXPIRED" />
            </el-select>
            <el-button size="small" type="primary" style="margin-left:8px"
                       @click="openCreate">新建协议</el-button>
            <el-button size="small" @click="load">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table :data="list" size="small" border @row-click="openDetail" highlight-current-row
                v-loading="loading">
        <el-table-column prop="fwNo" label="协议编号" width="170" />
        <el-table-column prop="customerName" label="客户" min-width="140" show-overflow-tooltip />
        <el-table-column prop="title" label="名称" min-width="130" show-overflow-tooltip />
        <el-table-column label="有效期" width="190">
          <template #default="{ row }">
            {{ row.effectiveDate }} ~ {{ row.expireDate }}
          </template>
        </el-table-column>
        <el-table-column label="协议总量" width="110" align="right">
          <template #default="{ row }">{{ Number(row.totalQty) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="stTag(row.status)">{{ stName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="变更" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.pendingChange" size="small" type="warning">审批中</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" @click.stop="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawerVisible" size="760px"
               :title="current ? `框架协议 ${current.fwNo}` : '详情'">
      <template v-if="current">
        <el-descriptions :column="3" size="small" border class="mb12">
          <el-descriptions-item label="编号">
            <b>{{ current.fwNo }}</b>（生成后锁定）
          </el-descriptions-item>
          <el-descriptions-item label="客户">{{ current.customerName }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="stTag(current.status)">{{ stName(current.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="有效期" :span="2">
            {{ current.effectiveDate }} ~ {{ current.expireDate }}
          </el-descriptions-item>
          <el-descriptions-item label="协议总量">{{ Number(current.totalQty) }}</el-descriptions-item>
          <el-descriptions-item v-if="current.terminateReason" label="终止原因" :span="3">
            {{ current.terminateReason }}（{{ current.terminateBy }} @
            {{ (current.terminateAt || '').replace('T', ' ').slice(0, 16) }}）
          </el-descriptions-item>
        </el-descriptions>

        <el-alert v-if="current.pendingChange" type="warning" :closable="false" show-icon
                  class="mb12" title="变更审批中（销售经理 → 销售总监 L2），通过后生效" />

        <h4>协议行（三量与余量）</h4>
        <el-table :data="detailLines" size="small" border class="mb12">
          <el-table-column prop="line.lineNo" label="#" width="42" />
          <el-table-column prop="line.itemCode" label="物料" width="120" />
          <el-table-column prop="line.itemName" label="名称" min-width="120" show-overflow-tooltip />
          <el-table-column label="总量/已下达/已发" width="170" align="right">
            <template #default="{ row }">
              {{ Number(row.line.totalQty) }} / {{ Number(row.line.releasedQty) }} /
              <b>{{ Number(row.line.shippedQty) }}</b>
            </template>
          </el-table-column>
          <el-table-column label="剩余可下达" width="100" align="right">
            <template #default="{ row }">
              <span :style="{ color: Number(row.remainRelease) > 0 ? '#67c23a' : '#c0c4cc' }">
                {{ Number(row.remainRelease) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="锁定价" width="90" align="right">
            <template #default="{ row }">
              {{ row.line.unitPrice == null ? '—' : '¥' + Number(row.line.unitPrice) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220">
            <template #default="{ row }">
              <el-button size="small" type="primary" :disabled="!active || Number(row.remainRelease) <= 0"
                         @click="openRelease(row)">下达</el-button>
              <el-button size="small" :disabled="!active" @click="openChange('TOTAL', row)">调总量</el-button>
              <el-button size="small" :disabled="!active" @click="openChange('PRICE', row)">重谈价</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="card-head mb12">
          <h4 style="margin:0">下达单</h4>
          <el-button size="small" type="danger" :disabled="!active"
                     @click="openChange('TERMINATE', null)">提前终止</el-button>
        </div>
        <el-table :data="detailReleases" size="small" border class="mb12">
          <el-table-column label="下达单号" width="170">
            <template #default="{ row }">{{ row.release.releaseNo }}</template>
          </el-table-column>
          <el-table-column prop="release.itemCode" label="物料" width="110" />
          <el-table-column label="下达/已发" width="110" align="right">
            <template #default="{ row }">
              {{ Number(row.release.qty) }} / {{ Number(row.release.shippedQty) }}
            </template>
          </el-table-column>
          <el-table-column label="交期" width="105">
            <template #default="{ row }">{{ row.release.deliverDate || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="relTag(row.release.status)">
                {{ relName(row.release.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button v-if="row.release.status !== 'CANCELLED'
                            && Number(row.release.shippedQty) === 0"
                         size="small" type="danger" @click="doCancelRelease(row.release)">
                取消
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <h4>变更历史（S-4.3-11 留痕）</h4>
        <el-table :data="changeRows" size="small" border>
          <el-table-column label="类型" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.result === 'APPROVED' ? 'success' : 'danger'">
                {{ chgName(row.type) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="前后值" min-width="200">
            <template #default="{ row }">{{ beforeText(row) }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" min-width="150" show-overflow-tooltip />
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              {{ row.result === 'APPROVED' ? '已生效' : '已驳回' }}
            </template>
          </el-table-column>
          <el-table-column label="时间" width="140">
            <template #default="{ row }">{{ (row.doneAt || '').replace('T', ' ').slice(0, 16) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!changeRows.length" description="暂无变更" :image-size="50" />
      </template>
    </el-drawer>

    <!-- 新建协议 -->
    <el-dialog v-model="createVisible" title="新建销售框架协议" width="720px">
      <el-form label-width="92px" size="small">
        <el-form-item label="客户" required>
          <el-select v-model="createForm.customerId" filterable style="width:100%">
            <el-option v-for="c in customers" :key="c.id"
                       :label="`${c.customerCode} ${c.customerName}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="createForm.title" placeholder="可空" />
        </el-form-item>
        <el-form-item label="有效期" required>
          <el-date-picker v-model="createForm.effectiveDate" type="date" value-format="YYYY-MM-DD"
                          placeholder="生效" style="width:45%" />
          <span style="margin:0 6px">~</span>
          <el-date-picker v-model="createForm.expireDate" type="date" value-format="YYYY-MM-DD"
                          placeholder="失效" style="width:45%" />
        </el-form-item>
        <el-form-item label="协议行" required>
          <div style="width:100%">
            <div v-for="(l, i) in createForm.lines" :key="i" class="line-row">
              <el-select v-model="l.itemCode" filterable placeholder="物料" size="small"
                         style="width:200px">
                <el-option v-for="it in items" :key="it.itemCode"
                           :label="`${it.itemCode} ${it.itemName}`" :value="it.itemCode" />
              </el-select>
              <el-input-number v-model="l.totalQty" :min="0" :precision="3" size="small"
                               placeholder="协议总量" style="width:130px" />
              <el-input-number v-model="l.unitPrice" :min="0" :precision="4" size="small"
                               placeholder="锁定单价" style="width:130px" />
              <el-button size="small" type="danger" text
                         @click="createForm.lines.splice(i, 1)">删除</el-button>
            </div>
            <el-button size="small" @click="createForm.lines.push(
              { itemCode: '', totalQty: 0, unitPrice: 0 })">+ 加行</el-button>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreate">创建（编号自动生成锁定）</el-button>
      </template>
    </el-dialog>

    <!-- 下达 -->
    <el-dialog v-model="releaseVisible" title="下达框架订单" width="480px">
      <el-descriptions v-if="releaseRow" :column="2" size="small" border class="mb12">
        <el-descriptions-item label="物料">{{ releaseRow.line.itemCode }}</el-descriptions-item>
        <el-descriptions-item label="剩余可下达">
          <b>{{ Number(releaseRow.remainRelease) }}</b>
        </el-descriptions-item>
      </el-descriptions>
      <el-form label-width="90px" size="small">
        <el-form-item label="下达数量" required>
          <el-input-number v-model="releaseForm.qty" :min="0" :precision="3"
                           :max="releaseRow ? Number(releaseRow.remainRelease) : 0"
                           style="width:100%" />
        </el-form-item>
        <el-form-item label="要求交期">
          <el-date-picker v-model="releaseForm.deliverDate" type="date" value-format="YYYY-MM-DD"
                          style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="releaseVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doRelease">下达</el-button>
      </template>
    </el-dialog>

    <!-- 变更 / 终止 -->
    <el-dialog v-model="changeVisible" :title="chgTitle" width="560px">
      <el-form label-width="110px" size="small">
        <el-form-item v-if="changeForm.type !== 'TERMINATE'" label="协议行">{{ changeLineLabel }}</el-form-item>
        <el-form-item v-if="changeForm.type === 'TOTAL'" label="新总量" required>
          <el-input-number v-model="changeForm.totalQty" :min="0" :precision="3"
                           style="width:100%" />
          <span class="hint">调减不得低于该行已发量（S-4.3-11）</span>
        </el-form-item>
        <el-form-item v-if="changeForm.type === 'PRICE'" label="新锁定单价" required>
          <el-input-number v-model="changeForm.unitPrice" :min="0" :precision="4"
                           style="width:100%" />
        </el-form-item>
        <el-form-item label="变更原因" required>
          <el-input v-model="changeForm.reason" type="textarea" :rows="2"
                    placeholder="必填（≥2 字，S-4.3-11 留痕）" />
        </el-form-item>
        <el-alert type="warning" :closable="false" show-icon
                  :title="changeForm.type === 'TERMINATE'
                    ? '提前终止：通过后冻结后续下达与发货，未发余量不可执行'
                    : '提交后走销售经理 → 销售总监 L2 审批，通过后生效'" />
      </el-form>
      <template #footer>
        <el-button @click="changeVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doChange">提交审批</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getFrameworksApi, getFrameworkDetailApi, createFrameworkApi, updateFrameworkApi,
  releaseFrameworkApi, cancelReleaseApi, changeFrameworkApi
} from '@/api/sd/framework'
import { getGroupPageApi } from '@/api/mdm/customer'
import { getItemPageApi } from '@/api/mdm/item'

const list = ref([])
const loading = ref(false)
const saving = ref(false)
const keyword = ref('')
const filterStatus = ref('')
const customers = ref([])
const items = ref([])
const drawerVisible = ref(false)
const current = ref(null)
const detailLines = ref([])
const detailReleases = ref([])
const createVisible = ref(false)
const releaseVisible = ref(false)
const changeVisible = ref(false)
const releaseRow = ref(null)
const changeLine = ref(null)

const createForm = reactive({
  customerId: '', title: '', effectiveDate: '', expireDate: '', remark: '',
  lines: [{ itemCode: '', totalQty: 0, unitPrice: 0 }]
})
const releaseForm = ref({ qty: 0, deliverDate: '' })
const changeForm = ref({ type: 'TOTAL', totalQty: 0, unitPrice: 0, reason: '' })

const active = computed(() => current.value && current.value.status === 'EFFECTIVE')

const kpis = computed(() => {
  const eff = list.value.filter(f => f.status === 'EFFECTIVE').length
  const term = list.value.filter(f => f.status === 'TERMINATED').length
  const pend = list.value.filter(f => f.pendingChange).length
  const total = list.value.reduce((s, f) => s + Number(f.totalQty || 0), 0)
  return [
    { label: '生效中', value: eff, color: '#67c23a' },
    { label: '已终止', value: term, color: '#f56c6c' },
    { label: '变更审批中', value: pend, color: '#e6a23c' },
    { label: '协议总量', value: total, color: '#409eff' }
  ]
})

const changeRows = computed(() => {
  const log = current.value && current.value._changeLog
  return log || []
})
const chgTitle = computed(() => ({
  TOTAL: '总量调整（销售总监 L2 审批）',
  PRICE: '单价重谈（销售总监 L2 审批）',
  TERMINATE: '提前终止（销售总监 L2 审批）'
})[changeForm.value.type])
const changeLineLabel = computed(() => changeLine.value
  ? `#${changeLine.value.line.lineNo} ${changeLine.value.line.itemCode}（当前总量 `
    + `${Number(changeLine.value.line.totalQty)}，已发 ${Number(changeLine.value.line.shippedQty)}）`
  : '')

function stName(s) {
  return ({ EFFECTIVE: '生效中', TERMINATED: '已终止', EXPIRED: '已过期' })[s] || s
}
function stTag(s) {
  return ({ EFFECTIVE: 'success', TERMINATED: 'danger', EXPIRED: 'info' })[s] || 'info'
}
function relName(s) {
  return ({ OPEN: '未发', PARTIAL: '部分发', SHIPPED: '已发完', CANCELLED: '已取消' })[s] || s
}
function relTag(s) {
  return ({ OPEN: 'info', PARTIAL: 'warning', SHIPPED: 'success', CANCELLED: 'danger' })[s] || 'info'
}
function chgName(t) {
  return ({ TOTAL: '总量调整', PRICE: '单价重谈', TERMINATE: '提前终止' })[t] || t
}
function beforeText(row) {
  if (row.type === 'TERMINATE') return '—'
  const before = row.before || []
  return before.map(b => `#${b.lineNo}: ${b.before} → ${b.after}`).join('；')
}

async function load() {
  loading.value = true
  try {
    const res = await getFrameworksApi({
      current: 1, size: 100,
      keyword: keyword.value || undefined,
      status: filterStatus.value || undefined
    })
    list.value = res.data.records || res.data || []
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  const res = await getFrameworkDetailApi(row.id)
  const d = res.data || {}
  current.value = { ...(d.framework || {}), _changeLog: d.changeLog || [] }
  detailLines.value = d.lines || []
  detailReleases.value = d.releases || []
  drawerVisible.value = true
}

async function openCreate() {
  createForm.customerId = ''
  createForm.title = ''
  createForm.effectiveDate = new Date().toISOString().slice(0, 10)
  createForm.expireDate = ''
  createForm.remark = ''
  createForm.lines = [{ itemCode: '', totalQty: 0, unitPrice: 0 }]
  createVisible.value = true
}

async function doCreate() {
  if (!createForm.customerId) return ElMessage.warning('客户必填')
  if (!createForm.expireDate) return ElMessage.warning('失效日期必填')
  if (!createForm.lines.length || createForm.lines.some(l => !l.itemCode || Number(l.totalQty) <= 0)) {
    return ElMessage.warning('协议行须含物料与大于 0 的总量')
  }
  saving.value = true
  try {
    const res = await createFrameworkApi(createForm)
    ElMessage.success('已创建：协议编号 ' + res.data.fwNo + '（生成后锁定）')
    createVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

function openRelease(row) {
  releaseRow.value = row
  releaseForm.value = { qty: 0, deliverDate: '' }
  releaseVisible.value = true
}

async function doRelease() {
  if (Number(releaseForm.value.qty) <= 0) return ElMessage.warning('下达数量必须大于 0')
  saving.value = true
  try {
    const res = await releaseFrameworkApi(current.value.id, {
      lineId: releaseRow.value.line.id,
      qty: releaseForm.value.qty,
      deliverDate: releaseForm.value.deliverDate || null
    })
    ElMessage.success('已下达：' + res.data.releaseNo)
    releaseVisible.value = false
    openDetail({ id: current.value.id })
    load()
  } finally {
    saving.value = false
  }
}

async function doCancelRelease(r) {
  await ElMessageBox.confirm(`取消下达单 ${r.releaseNo}？将回退已下达量`, '确认',
    { type: 'warning' })
  await cancelReleaseApi(r.id, '执行视图取消')
  ElMessage.success('已取消')
  openDetail({ id: current.value.id })
}

function openChange(type, row) {
  changeForm.value = {
    type,
    totalQty: row ? Number(row.line.totalQty) : 0,
    unitPrice: row ? Number(row.line.unitPrice || 0) : 0,
    reason: ''
  }
  changeLine.value = row
  changeVisible.value = true
}

async function doChange() {
  if (!changeForm.value.reason || changeForm.value.reason.trim().length < 2) {
    return ElMessage.warning('变更原因必填（≥2 字）')
  }
  saving.value = true
  try {
    const payload = { type: changeForm.value.type, reason: changeForm.value.reason.trim() }
    if (changeForm.value.type !== 'TERMINATE') {
      payload.lines = changeForm.value.type === 'TOTAL'
        ? [{ lineId: changeLine.value.line.id, totalQty: changeForm.value.totalQty }]
        : [{ lineId: changeLine.value.line.id, unitPrice: changeForm.value.unitPrice }]
    }
    await changeFrameworkApi(current.value.id, payload)
    ElMessage.success('已提交审批（销售经理 → 销售总监 L2）')
    changeVisible.value = false
    openDetail({ id: current.value.id })
    load()
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  load()
  const c = await getGroupPageApi({ current: 1, size: 200 })
  customers.value = c.data.records || c.data || []
  try {
    const i = await getItemPageApi({ current: 1, size: 300 })
    items.value = i.data.records || i.data || []
  } catch (e) {
    items.value = []
  }
})
</script>

<style scoped>
.page-wrap { padding: 12px; }
.mb12 { margin-bottom: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.kpi-box { background: #f5f7fa; border-radius: 4px; padding: 10px 12px; }
.kpi-label { font-size: 12px; color: #909399; margin-bottom: 4px; }
.kpi-value { font-size: 18px; font-weight: 600; }
.line-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; }
.hint { font-size: 12px; color: #909399; }
</style>
