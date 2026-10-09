<template>
  <div class="lead-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="线索录入（11.1.1）"
      description="来源渠道为预设枚举；提交前按「公司名称 + 联系人」查重（重复仅提示可关联）；分配后 3 个工作日未跟进自动提醒，30 天未跟进自动回收线索池。"
    />

    <el-tabs v-model="tab">
      <!-- ==================== 线索列表 ==================== -->
      <el-tab-pane label="线索列表" name="list">
        <el-card>
          <template #header>
            <div class="card-header">
              <span style="font-weight: bold; font-size: 16px;">线索列表</span>
              <div class="header-actions">
                <el-input v-model="query.keyword" placeholder="编号/公司/联系人" clearable
                          :prefix-icon="Search" style="width: 200px;" @keyup.enter="loadData(1)" />
                <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px;">
                  <el-option label="跟进中" value="OPEN" />
                  <el-option label="已转化" value="CONVERTED" />
                  <el-option label="已关闭" value="CLOSED" />
                  <el-option label="已回收" value="RECYCLED" />
                </el-select>
                <el-select v-model="query.grade" placeholder="等级" clearable style="width: 100px;">
                  <el-option v-for="g in ['A','B','C','D']" :key="g" :label="g" :value="g" />
                </el-select>
                <el-button type="primary" @click="loadData(1)">查询</el-button>
                <el-button type="primary" :icon="Plus" @click="openForm()">新建线索</el-button>
                <el-button :icon="Refresh" @click="runSweep">执行提醒/回收扫描</el-button>
              </div>
            </div>
          </template>

          <el-table :data="rows" v-loading="loading" stripe>
            <el-table-column prop="leadNo" label="线索编号" width="150" />
            <el-table-column prop="companyName" label="客户名称" min-width="150" show-overflow-tooltip />
            <el-table-column prop="contactName" label="联系人" width="90" />
            <el-table-column prop="sourceChannel" label="来源" width="90">
              <template #default="{ row }">{{ channelName(row.sourceChannel) }}</template>
            </el-table-column>
            <el-table-column prop="score" label="评分" width="80">
              <template #default="{ row }">{{ row.score == null ? '-' : row.score }}</template>
            </el-table-column>
            <el-table-column prop="grade" label="等级" width="70">
              <template #default="{ row }">
                <el-tag v-if="row.grade" :type="gradeType(row.grade)" size="small">{{ row.grade }}</el-tag>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column prop="ownerName" label="跟进人" width="110">
              <template #default="{ row }">{{ row.ownerName || (row.ownerId ? row.ownerId : '线索池') }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="nextPlanDate" label="下次计划" width="110">
              <template #default="{ row }">{{ row.nextPlanDate || '-' }}</template>
            </el-table-column>
            <el-table-column label="超期提醒" width="100">
              <template #default="{ row }">
                <el-tag v-if="overdue(row)" type="danger" size="small">未跟进超期</el-tag>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="260" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openDetail(row)">跟进记录</el-button>
                <el-button link type="primary" :disabled="!row.ownerId" @click="openFollowup(row)">记跟进</el-button>
                <el-button link type="primary" v-if="!row.ownerId" @click="claim(row)">认领</el-button>
                <el-button link type="warning" v-if="row.ownerId" @click="openAssign(row)">改派</el-button>
                <el-button link type="danger" v-if="row.status === 'OPEN'" @click="toPool(row)">入池</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination style="margin-top: 14px; justify-content: flex-end;"
                         layout="total, prev, pager, next" :total="total"
                         :page-size="query.size" :current-change="loadData" />
        </el-card>
      </el-tab-pane>

      <!-- ==================== 线索池 ==================== -->
      <el-tab-pane label="线索池" name="pool">
        <el-card>
          <template #header>
            <div class="card-header">
              <span style="font-weight: bold; font-size: 16px;">线索池（D 级入池 / 30 天回收）</span>
              <el-button type="primary" @click="loadPool">刷新</el-button>
            </div>
          </template>
          <el-table :data="poolRows" v-loading="poolLoading" stripe>
            <el-table-column label="线索" min-width="180">
              <template #default="{ row }">{{ leadBrief(row.leadId) }}</template>
            </el-table-column>
            <el-table-column prop="reason" label="入池原因" width="110">
              <template #default="{ row }">{{ poolReason(row.reason) }}</template>
            </el-table-column>
            <el-table-column prop="gradeAtEntry" label="入池等级" width="90" />
            <el-table-column prop="pooledAt" label="入池时间" width="160">
              <template #default="{ row }">{{ (row.pooledAt || '').replace('T', ' ') }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'PENDING' ? 'warning' : 'success'" size="small">
                  {{ row.status === 'PENDING' ? '待指派' : '已指派' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="row.status !== 'PENDING'"
                           @click="openPoolAssign(row)">指派</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 新建线索 -->
    <el-dialog v-model="formVisible" title="新建线索" width="640px">
      <el-alert v-if="dupWarn" type="warning" :closable="false" show-icon style="margin-bottom: 10px;"
                :title="dupWarn" />
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="来源渠道" prop="sourceChannel">
          <el-select v-model="form.sourceChannel" style="width: 100%;">
            <el-option v-for="c in channels" :key="c.v" :label="c.l" :value="c.v" />
          </el-select>
        </el-form-item>
        <el-form-item label="客户名称" prop="companyName">
          <el-input v-model="form.companyName" maxlength="128" @blur="doDuplicate" />
        </el-form-item>
        <el-form-item label="联系人" prop="contactName">
          <el-input v-model="form.contactName" maxlength="64" @blur="doDuplicate" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.contactPhone" maxlength="32" />
        </el-form-item>
        <el-form-item label="需求描述">
          <el-input v-model="form.demandDesc" type="textarea" :rows="2" maxlength="1000" />
        </el-form-item>
        <el-form-item label="预期金额">
          <el-input-number v-model="form.expectAmount" :min="0" :precision="2" style="width: 200px;" />
        </el-form-item>
        <el-form-item label="预期成交日">
          <el-date-picker v-model="form.expectCloseDate" type="date" value-format="YYYY-MM-DD" style="width: 200px;" />
        </el-form-item>
        <el-form-item label="跟进负责人">
          <el-select v-model="form.ownerId" clearable filterable style="width: 100%;" placeholder="留空则为未分配">
            <el-option v-for="u in owners" :key="u.id" :label="u.name" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联既有线索" v-if="dupList.length">
          <el-select v-model="form.duplicateOf" clearable style="width: 100%;"
                     placeholder="查重命中，可选择关联（不选则作为新线索）">
            <el-option v-for="d in dupList" :key="d.id" :label="`${d.leadNo} ${d.companyName}`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 记跟进（仅追加） -->
    <el-dialog v-model="followVisible" :title="`记跟进 — ${current.companyName || ''}`" width="560px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px;"
                title="跟进记录仅可追加，保存后不可修改或删除（FR-4.8-1-4）" />
      <el-form label-width="110px">
        <el-form-item label="跟进方式" required>
          <el-select v-model="followForm.followType" style="width: 100%;">
            <el-option v-for="t in followTypes" :key="t.v" :label="t.l" :value="t.v" />
          </el-select>
        </el-form-item>
        <el-form-item label="跟进内容" required>
          <el-input v-model="followForm.content" type="textarea" :rows="4" maxlength="2000" />
        </el-form-item>
        <el-form-item label="下次跟进日">
          <el-date-picker v-model="followForm.nextPlan" type="date" value-format="YYYY-MM-DD"
                          placeholder="留空按等级节奏自动计算（A/3、B/7、C 14 天）" style="width: 100%;" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="followVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitFollowup">保存</el-button>
      </template>
    </el-dialog>

    <!-- 改派 -->
    <el-dialog v-model="assignVisible" title="改派跟进人" width="460px">
      <el-form label-width="100px">
        <el-form-item label="新跟进人" required>
          <el-select v-model="assignTo" filterable style="width: 100%;">
            <el-option v-for="u in owners" :key="u.id" :label="u.name" :value="u.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitAssign">确认改派</el-button>
      </template>
    </el-dialog>

    <!-- 跟进记录抽屉 -->
    <el-drawer v-model="detailVisible" :title="`跟进记录 — ${current.companyName || ''}`" size="560px">
      <el-timeline v-if="followups.length">
        <el-timeline-item v-for="f in followups" :key="f.id"
                          :timestamp="(f.followAt || '').replace('T', ' ')" placement="top">
          <div style="font-weight: bold;">{{ followTypeName(f.followType) }} · {{ f.operatorName || f.operatorId }}</div>
          <div style="white-space: pre-wrap;">{{ f.content }}</div>
          <div style="color: #909399; font-size: 12px;">下次计划：{{ f.nextPlan || '-' }}</div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无跟进记录" :image-size="60" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus, Refresh } from '@element-plus/icons-vue'
import {
  getLeadsApi, getLeadApi, checkDuplicateApi, createLeadApi, assignLeadApi, claimLeadApi,
  toPoolApi, sweepLeadsApi, getFollowupsApi, addFollowupApi, getLeadPoolApi, assignFromPoolApi
} from '@/api/crm/lead'

const channels = [
  { v: 'EXPO', l: '展会' }, { v: 'WEB', l: '网络' }, { v: 'CHANNEL', l: '渠道' },
  { v: 'REFERRAL', l: '转介绍' }, { v: 'OUTBOUND', l: '主动开拓' }
]
const followTypes = [
  { v: 'CALL', l: '电话' }, { v: 'VISIT', l: '拜访' }, { v: 'DEMO', l: '演示' },
  { v: 'PROPOSAL', l: '方案发送' }, { v: 'WECHAT', l: '微信' }, { v: 'OTHER', l: '其他' }
]
// 测试账号（change 052 种子），改派对象从这里选
const owners = [
  { id: 'user-sales01', name: 'sales01（销售）' },
  { id: 'user-salesmgr01', name: 'salesmgr01（销售经理）' },
  { id: 'user-sddirector01', name: 'sddirector01（销售总监）' }
]

const tab = ref('list')
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ current: 1, size: 10, keyword: '', status: '', grade: '' })

const poolLoading = ref(false)
const poolRows = ref([])

const formVisible = ref(false)
const formRef = ref(null)
const form = ref({})
const dupList = ref([])
const dupWarn = ref('')
const rules = {
  sourceChannel: [{ required: true, message: '请选择来源渠道', trigger: 'change' }],
  companyName: [{ required: true, message: '请输入客户名称', trigger: 'blur' }],
  contactName: [{ required: true, message: '请输入联系人', trigger: 'blur' }]
}

const followVisible = ref(false)
const followForm = ref({})
const assignVisible = ref(false)
const assignTo = ref('')
const detailVisible = ref(false)
const current = ref({})
const followups = ref([])

const channelName = v => (channels.find(c => c.v === v) || {}).l || v
const followTypeName = v => (followTypes.find(t => t.v === v) || {}).l || v
const statusName = s => ({ OPEN: '跟进中', CONVERTED: '已转化', CLOSED: '已关闭', RECYCLED: '已回收' }[s] || s)
const statusType = s => ({ OPEN: 'primary', CONVERTED: 'success', CLOSED: 'info', RECYCLED: 'warning' }[s] || 'info')
const gradeType = g => ({ A: 'success', B: '', C: 'warning', D: 'danger' }[g] || 'info')

/** 超期：已分配 3 个工作日且此后无跟进（FR-4.8-1-3） */
function overdue(row) {
  if (row.status !== 'OPEN' || !row.ownerId) return false
  const base = row.lastFollowupAt && new Date(row.lastFollowupAt) > new Date(row.assignedAt || 0)
    ? new Date(row.lastFollowupAt) : (row.assignedAt ? new Date(row.assignedAt) : null)
  if (!base) return false
  const days = (Date.now() - base.getTime()) / 86400000
  return days >= 5 && !row.remindAt
}
const poolReason = r => ({ D_GRADE: 'D 级入池', RECYCLE: '超期回收', MANUAL: '手工入池' }[r] || r)
function leadBrief(id) {
  const l = rows.value.find(x => x.id === id)
  return l ? `${l.leadNo} ${l.companyName}` : id
}

async function loadData(page) {
  if (page) query.value.current = page
  loading.value = true
  try {
    const res = await getLeadsApi(query.value)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

async function loadPool() {
  poolLoading.value = true
  try {
    const res = await getLeadPoolApi('PENDING')
    poolRows.value = res.data || []
  } finally {
    poolLoading.value = false
  }
}

async function doDuplicate() {
  const f = form.value
  if (!f.companyName || !f.contactName) return
  const res = await checkDuplicateApi({ companyName: f.companyName, contactName: f.contactName })
  dupList.value = res.data || []
  dupWarn.value = dupList.value.length
    ? `L4 查重命中 ${dupList.value.length} 条（公司名称 + 联系人相同），可关联既有线索或留空作为新线索`
    : ''
}

function openForm() {
  form.value = { sourceChannel: '', companyName: '', contactName: '', contactPhone: '',
                 demandDesc: '', expectAmount: null, expectCloseDate: '', ownerId: '', duplicateOf: '', remark: '' }
  dupList.value = []
  dupWarn.value = ''
  formVisible.value = true
}

async function submitForm() {
  await formRef.value.validate()
  saving.value = true
  try {
    const f = { ...form.value }
    const owner = owners.find(o => o.id === f.ownerId)
    if (owner) { f.ownerName = owner.name.split('（')[0] }
    else { f.ownerId = '' }
    await createLeadApi(f)
    ElMessage.success('线索已录入')
    formVisible.value = false
    loadData(1)
  } finally {
    saving.value = false
  }
}

async function claim(row) {
  await ElMessageBox.confirm(`确认认领「${row.companyName}」？同一线索同一时间只能由一人跟进。`, '认领确认', { type: 'warning' })
  await claimLeadApi(row.id)
  ElMessage.success('已认领')
  loadData()
}

function openAssign(row) {
  current.value = row
  assignTo.value = ''
  assignVisible.value = true
}

async function submitAssign() {
  if (!assignTo.value) { ElMessage.warning('请选择新跟进人'); return }
  saving.value = true
  try {
    const u = owners.find(o => o.id === assignTo.value)
    await assignLeadApi(current.value.id, assignTo.value, u ? u.name.split('（')[0] : assignTo.value)
    ElMessage.success('已改派')
    assignVisible.value = false
    loadData()
  } finally {
    saving.value = false
  }
}

async function toPool(row) {
  const { value } = await ElMessageBox.prompt('入池原因', '线索入池', {
    inputValue: 'MANUAL', type: 'warning',
    inputValidator: v => !!v || '原因必填'
  })
  await toPoolApi(row.id, value, '手工入池')
  ElMessage.success('已入池')
  loadData()
}

async function openFollowup(row) {
  current.value = row
  followForm.value = { followType: 'CALL', content: '', nextPlan: '' }
  followVisible.value = true
}

async function submitFollowup() {
  if (!followForm.value.followType || !followForm.value.content) {
    ElMessage.warning('跟进方式与内容必填'); return
  }
  saving.value = true
  try {
    await addFollowupApi(current.value.id, followForm.value)
    ElMessage.success('跟进已记录（仅追加，不可修改）')
    followVisible.value = false
    loadData()
  } finally {
    saving.value = false
  }
}

async function openDetail(row) {
  current.value = row
  const res = await getFollowupsApi(row.id)
  followups.value = res.data || []
  detailVisible.value = true
}

async function openPoolAssign(row) {
  current.value = row
  const { value } = await ElMessageBox.prompt('指派给（填写账号 ID）', '线索池指派', {
    inputValue: 'user-sales01', type: 'warning',
    inputValidator: v => !!v || '必填'
  })
  const u = owners.find(o => o.id === value)
  await assignFromPoolApi(row.id, value, u ? u.name.split('（')[0] : value)
  ElMessage.success('已指派')
  loadPool()
  loadData()
}

async function runSweep() {
  const res = await sweepLeadsApi()
  ElMessage.success(`扫描完成：提醒 ${res.data.reminded} 条，回收 ${res.data.recycled} 条`)
  loadData()
}

onMounted(() => { loadData(); loadPool() })
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
