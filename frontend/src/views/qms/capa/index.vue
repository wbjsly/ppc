<template>
  <div class="capa-page">
    <el-alert type="warning" :closable="false" style="margin-bottom: 12px;"
      title="CAPA/8D（6.6）：Critical NCR 或 3 月 3 次重复不合格自动立项绑定 NCR（BR-4.12-31）"
      description="D1~D8 顺序推进不可跳序；D2 须 5W2H；D4 根本原因 + 验证证据缺一不可（浅层根因退回）；D6 措施全完成才进 D7；D7 无效/部分有效回 D4 并阻断 NCR 关闭；复发自动关联原单并上调严重度（BR-4.12-36）。" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 160px;" @change="load">
          <el-option label="分析中" value="ANALYZING" />
          <el-option label="验证中" value="VERIFYING" />
          <el-option label="重新分析" value="REANALYZING" />
          <el-option label="进行中" value="OPEN" />
          <el-option label="已关闭" value="CLOSED" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="CAPA 号 / 标题 / NCR" clearable
          style="width: 220px;" @keyup.enter="load" @clear="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <span class="spacer" />
        <el-button v-if="canEdit" type="success" @click="openCreate">手工立项</el-button>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading" @row-click="openDetail" row-style="cursor:pointer">
        <el-table-column prop="capaNo" label="CAPA 号" width="150">
          <template #default="{ row }"><b>{{ row.capaNo }}</b></template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
        <el-table-column prop="ncrNo" label="关联 NCR" width="150" show-overflow-tooltip />
        <el-table-column label="严重度" width="95" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{ CRITICAL: 'danger', MAJOR: 'warning', MINOR: 'info' }[row.severity]">
              {{ row.severity }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{ OPEN: 'primary', ANALYZING: 'warning', VERIFYING: 'warning',
              REANALYZING: 'danger', CLOSED: 'success' }[row.status]">
              {{ { OPEN: '立项', ANALYZING: '分析中', VERIFYING: '验证中',
                   REANALYZING: '重新分析', CLOSED: '已关闭' }[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="currentStep" label="当前步" width="80" align="center" />
        <el-table-column label="遏制倒计时" width="150">
          <template #default="{ row }">
            <template v-if="row.containDueTime">
              <span :style="{ color: row.containEscalated === '1' ? '#F56C6C' : '' }">
                {{ row.containEscalated === '1' ? '已升级质量总监' : row.containDueTime }}
              </span>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="时限" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.overdue" type="danger" size="small">超期 {{ row.dueDate }}</el-tag>
            <span v-else>{{ row.dueDate || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="验证结论" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.verifyResult && row.verifyResult !== 'PENDING'" size="small"
              :type="{ VALID: 'success', PARTIAL: 'warning', INVALID: 'danger' }[row.verifyResult]">
              {{ { VALID: '有效', PARTIAL: '部分有效', INVALID: '无效' }[row.verifyResult] }}
            </el-tag>
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

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawer"
      :title="detail.capa ? `${detail.capa.capaNo} · ${detail.capa.currentStep}` : 'CAPA 详情'"
      size="780px" destroy-on-close>
      <div v-if="detail.capa">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="标题" :span="2">{{ detail.capa.title }}</el-descriptions-item>
          <el-descriptions-item label="严重度">{{ detail.capa.severity }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ STATUS_TEXT[detail.capa.status] || detail.capa.status }}</el-descriptions-item>
          <el-descriptions-item label="关联 NCR">{{ detail.capa.ncrNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="时限">{{ detail.capa.dueDate }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.capa.rootCause" label="根本原因" :span="2">
            {{ detail.capa.rootCause }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.capa.rootCauseEvidence" label="验证证据" :span="2">
            {{ detail.capa.rootCauseEvidence }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.capa.originCapaId" label="复发关联" :span="2">
            <el-tag type="warning" size="small">复发（BR-4.12-36）</el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 8D 步骤 -->
        <h4 class="sec">8D 步骤（顺序推进，不可跳序）</h4>
        <el-steps :active="stepActive" align-center finish-status="success" size="small">
          <el-step v-for="st in (detail.steps || [])" :key="st.stepCode" :title="st.stepCode"
            :description="st.status === 'DONE' ? (st.ownerName || '完成') : '待办'" />
        </el-steps>

        <!-- 当前步骤推进 -->
        <div v-if="canEdit && detail.capa.status !== 'CLOSED'" class="op-block" style="margin-top:12px;">
          <div style="font-weight:600;margin-bottom:8px;">
            推进 {{ detail.capa.currentStep }}
            <span style="color:#909399;font-weight:400;font-size:12px;">
              （{{ STEP_HINT[detail.capa.currentStep] || '' }}）
            </span>
          </div>
          <el-input v-model="stepForm.ownerName" placeholder="步骤负责人（必填）" style="margin-bottom:8px;" />
          <el-input v-model="stepForm.output" type="textarea" :rows="2" placeholder="步骤产出物（必填）" />
          <template v-if="detail.capa.currentStep === 'D4'">
            <el-input v-model="stepForm.rootCause" type="textarea" :rows="2"
              placeholder="根本原因：5Why/鱼骨图分析过程（浅层描述会被退回）" style="margin-top:8px;" />
            <el-input v-model="stepForm.rootCauseEvidence" type="textarea" :rows="2"
              placeholder="数据/实验验证证据（必填，BR-4.12-33）" style="margin-top:8px;" />
          </template>
          <template v-if="detail.capa.currentStep === 'D7'">
            <el-radio-group v-model="stepForm.verifyResult" style="margin-top:8px;">
              <el-radio-button value="VALID">有效</el-radio-button>
              <el-radio-button value="PARTIAL">部分有效</el-radio-button>
              <el-radio-button value="INVALID">无效（回 D4）</el-radio-button>
            </el-radio-group>
          </template>
          <el-button type="primary" size="small" style="margin-top:8px;" @click="doAdvance">
            完成 {{ detail.capa.currentStep }}
          </el-button>
        </div>
        <el-alert v-if="detail.capa.status === 'CLOSED'" type="success" :closable="false"
          title="已关闭（只读归档，经验已沉淀）" style="margin-top:12px;" />

        <!-- 措施 -->
        <h4 class="sec">纠正/预防措施（{{ (detail.actions || []).length }}）</h4>
        <el-table :data="detail.actions || []" size="mini" border>
          <el-table-column label="类型" width="70" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.actionType === 'CORRECT' ? 'primary' : 'info'">
                {{ row.actionType === 'CORRECT' ? '纠正' : '预防' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="措施" min-width="180" show-overflow-tooltip />
          <el-table-column prop="ownerName" label="负责人" width="80" />
          <el-table-column prop="dueDate" label="完成时间" width="100" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'DONE' ? 'success' : 'warning'">
                {{ row.status === 'DONE' ? '已完成' : '待执行' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="标准变更" width="90" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.standardChangeFlag === '1'" size="small" type="danger">需审批</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button v-if="canEdit && row.status !== 'DONE'" link type="primary"
                @click="doCompleteAction(row)">完成</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="canEdit && detail.capa.status !== 'CLOSED'" style="margin-top:8px;">
          <el-button size="small" @click="addActionVisible = true">新增措施</el-button>
        </div>
      </div>
    </el-drawer>

    <!-- 新增措施 -->
    <el-dialog v-model="addActionVisible" title="新增纠正/预防措施" width="520px">
      <el-form label-width="100px" size="small">
        <el-form-item label="类型" required>
          <el-radio-group v-model="actionForm.actionType">
            <el-radio-button value="CORRECT">纠正</el-radio-button>
            <el-radio-button value="PREVENT">预防</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="措施描述" required>
          <el-input v-model="actionForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="负责人" required>
          <el-input v-model="actionForm.ownerName" />
        </el-form-item>
        <el-form-item label="完成时间" required>
          <el-date-picker v-model="actionForm.dueDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="预期效果" required>
          <el-input v-model="actionForm.effectDesc" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="标准/SOP 变更">
          <el-switch v-model="actionForm.standardChange" />
          <span style="font-size:12px;color:#909399;margin-left:8px;">开启后自动挂变更审批（BR-4.12-34）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addActionVisible = false">取消</el-button>
        <el-button type="primary" @click="doAddAction">创建</el-button>
      </template>
    </el-dialog>

    <!-- 手工立项 -->
    <el-dialog v-model="createVisible" title="手工立项 CAPA" width="480px">
      <el-form label-width="90px" size="small">
        <el-form-item label="标题" required>
          <el-input v-model="createForm.title" placeholder="如：客户投诉：标签错贴" />
        </el-form-item>
        <el-form-item label="严重度">
          <el-select v-model="createForm.severity" style="width:100%;">
            <el-option label="Critical" value="CRITICAL" />
            <el-option label="Major" value="MAJOR" />
            <el-option label="Minor" value="MINOR" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源">
          <el-select v-model="createForm.sourceType" style="width:100%;">
            <el-option label="投诉" value="COMPLAINT" />
            <el-option label="审核问题" value="AUDIT" />
            <el-option label="重复不合格" value="REPEAT" />
            <el-option label="手工" value="MANUAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联 NCR">
          <el-input v-model="createForm.ncrId" placeholder="可选：不合格品 NCR 的 ID" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">立项</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getCapaPageApi, getCapaDetailApi, createCapaApi,
  advanceStepApi, addActionApi, completeActionApi
} from '@/api/qms/capa'

const route = useRoute()
const userStore = useUserStore()

const STATUS_TEXT = {
  OPEN: '立项', ANALYZING: '分析中', VERIFYING: '验证中',
  REANALYZING: '重新分析', CLOSED: '已关闭'
}
const STEP_HINT = {
  D1: '组建 8D 小组', D2: '问题描述（What/Who/When/Where/Why/How/How many 必填）',
  D3: '临时遏制措施', D4: '根因分析（根本原因 + 验证证据缺一不可，浅层描述退回）',
  D5: '纠正措施制定（先录入措施）', D6: '措施执行（全部措施完成方可推进）',
  D7: '有效性验证（无效/部分有效自动回 D4）', D8: '关闭与经验沉淀'
}

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const tab = route.meta.tab || 'list'
const filters = reactive({ status: '', keyword: '', current: 1, size: 10 })
if (tab === 'verify') filters.status = 'VERIFYING'
if (tab === 'actions') filters.status = 'VERIFYING'

const myRoles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const canEdit = computed(() => myRoles.value.some(r =>
  ['ROLE_ADMIN', 'ROLE_QUALITY_ENG', 'ROLE_QUALITY_MGR'].includes(r)))

async function load() {
  loading.value = true
  try {
    const res = await getCapaPageApi({
      current: filters.current, size: filters.size,
      status: filters.status || undefined, keyword: filters.keyword || undefined
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
const stepForm = reactive({ ownerName: '', output: '', rootCause: '', rootCauseEvidence: '', verifyResult: '' })
const stepActive = computed(() => {
  const steps = detail.value.steps || []
  return steps.filter(s => s.status === 'DONE').length
})

async function openDetail(row) {
  const res = await getCapaDetailApi(row.id)
  detail.value = res.data || {}
  stepForm.ownerName = ''
  stepForm.output = ''
  stepForm.rootCause = ''
  stepForm.rootCauseEvidence = ''
  stepForm.verifyResult = ''
  drawer.value = true
}

async function doAdvance() {
  try {
    await advanceStepApi(detail.value.capa.id, {
      stepCode: detail.value.capa.currentStep,
      ...stepForm
    })
    ElMessage.success(`${detail.value.capa.currentStep} 已完成`)
    openDetail({ id: detail.value.capa.id })
    load()
  } catch { /* 拦截器已弹错 */ }
}

// ---------- 措施 ----------
const addActionVisible = ref(false)
const actionForm = reactive({
  actionType: 'CORRECT', description: '', ownerName: '',
  dueDate: '', effectDesc: '', standardChange: false
})
async function doAddAction() {
  try {
    await addActionApi(detail.value.capa.id, {
      ...actionForm, standardChangeFlag: actionForm.standardChange ? '1' : '0'
    })
    ElMessage.success('措施已创建')
    addActionVisible.value = false
    openDetail({ id: detail.value.capa.id })
  } catch { /* 拦截器已弹错 */ }
}
async function doCompleteAction(row) {
  try {
    const { value } = await ElMessageBox.prompt('完成情况/实际效果（可选）', '措施完成',
      { inputValue: row.effectDesc || '' })
    await completeActionApi(row.id, value)
    ElMessage.success('措施已完成')
    openDetail({ id: detail.value.capa.id })
  } catch { /* 取消或拦截器已处理 */ }
}

// ---------- 立项 ----------
const createVisible = ref(false)
const createForm = reactive({ title: '', severity: 'MAJOR', sourceType: 'COMPLAINT', ncrId: '' })
function openCreate() {
  createForm.title = ''
  createForm.severity = 'MAJOR'
  createForm.sourceType = 'COMPLAINT'
  createForm.ncrId = ''
  createVisible.value = true
}
async function doCreate() {
  try {
    const res = await createCapaApi({ ...createForm })
    ElMessage.success('CAPA 已立项（8 步骤已生成）')
    createVisible.value = false
    load()
    if (res.data) openDetail(res.data)
  } catch { /* 拦截器已弹错 */ }
}

onMounted(async () => {
  if (!userStore.userInfo) {
    try { await userStore.getUserInfo() } catch { /* ignore */ }
  }
  load()
})
</script>

<style scoped>
.capa-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.spacer { flex: 1; }
.sec { margin: 16px 0 8px; font-size: 14px; color: #303133; }
.op-block { background: #fafafa; border: 1px solid #ebeef5; border-radius: 4px; padding: 12px; }
</style>
