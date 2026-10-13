<template>
  <div class="standard-list">
    <!-- 6.1.4 抽样方案视图 -->
    <el-card v-if="view === 'sampling'">
      <template #header>
        <div class="card-header">
          <span class="ttl">抽样方案（6.1.4）</span>
          <div>
            <el-button v-if="canEdit" type="success" size="small" @click="openPlan()">新建方案</el-button>
            <el-button size="small" :loading="loading" @click="loadPlans">刷新</el-button>
          </div>
        </div>
      </template>
      <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
                title="引用锁（spec inspection-standard）：被审批中/已发布版本引用的方案禁止修改，请「复制为新方案」后调整规则。" />
      <el-table :data="plans" v-loading="loading" stripe>
        <el-table-column prop="planCode" label="方案编码" width="150">
          <template #default="{ row }"><b>{{ row.planCode }}</b></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="inspectionLevel" label="检验水平" width="100" align="center" />
        <el-table-column prop="aqlLevel" label="AQL" min-width="180" />
        <el-table-column prop="sampleSizeRule" label="样本量规则" min-width="140" show-overflow-tooltip />
        <el-table-column prop="acceptRule" label="Ac" width="90" />
        <el-table-column prop="rejectRule" label="Re" width="90" />
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canEdit" link type="primary" @click="openPlan(row)">编辑</el-button>
            <el-button v-if="canEdit" link type="warning" @click="copyPlan(row)">复制为新方案</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!plans.length && !loading" description="暂无抽样方案" :image-size="80" />
    </el-card>

    <!-- 标准列表视图（6.1.1 申请 / 6.1.3 发布 / 6.1.5 退役共用） -->
    <el-card v-else>
      <template #header>
        <div class="card-header">
          <span class="ttl">{{ viewTitle }}</span>
          <div>
            <el-button v-if="view === 'apply' && canEdit" type="success" size="small" @click="openCreate">新建标准</el-button>
            <el-button size="small" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="view === 'publish'" type="info" :closable="false" style="margin-bottom: 10px;"
                title="版本发布视图：默认过滤含草稿/审批中版本的标准；提交发布与签署进度在标准详情内查看。" />
      <el-alert v-if="view === 'retire'" type="warning" :closable="false" style="margin-bottom: 10px;"
                title="退役口径（BR-4.12-58 / C-4.12-20）：停用仅阻止新任务引用，历史任务与 NCR 追溯关系保持不变，禁止物理删除。" />

      <div class="filters">
        <el-input v-model="keyword" placeholder="编码/名称" style="width: 220px;" clearable @keyup.enter="load" />
        <el-select v-model="statusFilter" placeholder="状态" style="width: 130px;" clearable>
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已启用" value="ACTIVE" />
          <el-option label="已退役" value="RETIRED" />
        </el-select>
        <el-button type="primary" @click="load">查询</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="standardCode" label="标准编码" width="170">
          <template #default="{ row }"><b>{{ row.standardCode }}</b></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="scopeType" label="范围" width="80" />
        <el-table-column prop="riskLevel" label="风险" width="70" align="center" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="stdStatusTag(row.status)">{{ stdStatusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="现行版本" width="90" align="center">
          <template #default="{ row }">{{ row.currentVersion > 0 ? 'V' + row.currentVersion : '—' }}</template>
        </el-table-column>
        <el-table-column label="最新版本" width="110" align="center">
          <template #default="{ row }">
            <template v-if="row.latestVersion && row.latestVersion.no">
              <el-tag size="small" :type="verStatusTag(row.latestVersion.status)">
                V{{ row.latestVersion.no }} {{ verStatusName(row.latestVersion.status) }}
              </el-tag>
            </template>
            <span v-else style="color: #c0c4cc;">—</span>
          </template>
        </el-table-column>
        <el-table-column label="审批中" width="80" align="center">
          <template #default="{ row }">
            <el-badge v-if="row.pendingCount > 0" :value="row.pendingCount" type="warning" />
            <span v-else style="color: #c0c4cc;">0</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="$router.push(`/m/6.1.standard/${row.id}`)">详情</el-button>
            <template v-if="view === 'apply' && canEdit && row.status !== 'RETIRED'">
              <el-button link type="warning" @click="openCreateVersion(row)">新建版本</el-button>
            </template>
            <template v-if="view === 'retire' && canEdit && row.status !== 'RETIRED'">
              <el-button link type="danger" @click="doRetire(row)">退役</el-button>
            </template>
            <span v-if="view === 'retire' && row.status === 'RETIRED'" style="color: #c0c4cc;">已退役</span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top: 10px;" layout="total, prev, pager, next" :total="total"
                     :page-size="size" :current-page="page" @current-change="p => { page = p; load() }" />
    </el-card>

    <!-- 新建标准 -->
    <el-dialog v-model="stdVisible" title="新建标准" width="480px">
      <el-form label-width="100px">
        <el-form-item label="标准编码" required>
          <el-input v-model="stdForm.standardCode" placeholder="如 STD-MCU-001（创建后不可改）" />
        </el-form-item>
        <el-form-item label="名称" required><el-input v-model="stdForm.name" /></el-form-item>
        <el-form-item label="范围类型">
          <el-select v-model="stdForm.scopeType">
            <el-option label="物料" value="ITEM" />
            <el-option label="品类" value="CATEGORY" />
            <el-option label="工序" value="PROCESS" />
            <el-option label="供方" value="SUPPLIER" />
            <el-option label="客户" value="CUSTOMER" />
          </el-select>
        </el-form-item>
        <el-form-item label="风险等级">
          <el-select v-model="stdForm.riskLevel">
            <el-option label="A 高" value="A" />
            <el-option label="B 中" value="B" />
            <el-option label="C 低" value="C" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="stdForm.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stdVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveStandard">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新建版本（轻量入口；完整编辑在详情页） -->
    <el-dialog v-model="verVisible" title="新建草稿版本" width="520px">
      <el-form label-width="110px">
        <el-form-item label="生效日期" required>
          <el-date-picker v-model="verForm.effectiveFrom" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="变更原因" required>
          <el-input v-model="verForm.changeReason" placeholder="如：客户要求加严外观检验" />
        </el-form-item>
        <el-form-item label="AQL 等级">
          <el-input v-model="verForm.aqlLevel" />
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false"
                title="草稿创建后将进入标准详情页继续维护检验特性与适用范围。" />
      <template #footer>
        <el-button @click="verVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="createVersion">创建并进入详情</el-button>
      </template>
    </el-dialog>

    <!-- 抽样方案编辑 -->
    <el-dialog v-model="planVisible" title="抽样方案" width="520px">
      <el-form label-width="110px">
        <el-form-item label="方案编码" required>
          <el-input v-model="planForm.planCode" :disabled="!!planForm.id" />
        </el-form-item>
        <el-form-item label="名称" required><el-input v-model="planForm.name" /></el-form-item>
        <el-form-item label="检验水平">
          <el-select v-model="planForm.inspectionLevel">
            <el-option label="I" value="I" /><el-option label="II" value="II" /><el-option label="III" value="III" />
          </el-select>
        </el-form-item>
        <el-form-item label="AQL"><el-input v-model="planForm.aqlLevel" /></el-form-item>
        <el-form-item label="样本量规则"><el-input v-model="planForm.sampleSizeRule" /></el-form-item>
        <el-form-item label="Ac 规则"><el-input v-model="planForm.acceptRule" /></el-form-item>
        <el-form-item label="Re 规则"><el-input v-model="planForm.rejectRule" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePlan">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getStandardsApi, createStandardApi, getStandardDetailApi,
  retireStandardApi, createVersionApi,
  getSamplingPlansApi, saveSamplingPlanApi, copySamplingPlanApi
} from '@/api/qms/standard'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const roles = computed(() => userStore.userInfo?.roles || [])
const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))
const canEdit = computed(() => isAdmin.value || roles.value.includes('ROLE_QUALITY_ENG'))

// view：apply（6.1.1）/ publish（6.1.3）/ sampling（6.1.4）/ retire（6.1.5）
const view = computed(() => route.meta.view || 'apply')
const viewTitle = computed(() => ({
  apply: '标准申请（6.1.1）',
  publish: '版本发布（6.1.3）',
  retire: '标准退役（6.1.5）'
}[view.value] || '检验标准管理'))

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const statusFilter = ref('')
const plans = ref([])

const stdVisible = ref(false)
const stdForm = ref({})
const verVisible = ref(false)
const verForm = ref({})
const planVisible = ref(false)
const planForm = ref({})

function stdStatusName(s) { return { DRAFT: '草稿', ACTIVE: '已启用', RETIRED: '已退役' }[s] || s }
function stdStatusTag(s) { return { DRAFT: 'info', ACTIVE: 'success', RETIRED: 'danger' }[s] || 'info' }
function verStatusName(s) { return { DRAFT: '草稿', PENDING_APPROVE: '审批中', RELEASED: '已发布', RETIRED: '已退役' }[s] || s }
function verStatusTag(s) { return { DRAFT: 'info', PENDING_APPROVE: 'warning', RELEASED: 'success', RETIRED: 'danger' }[s] || 'info' }

/** 列表加载：单次请求带版本摘要，不逐行查明细（spec inspection-standard 列表摘要） */
async function load() {
  loading.value = true
  try {
    const res = await getStandardsApi({
      current: page.value, size: size.value, keyword: keyword.value, status: statusFilter.value
    })
    let recs = res.data?.records || []
    total.value = res.data?.total || 0
    // publish 视图：默认过滤含草稿/审批中版本的标准
    if (view.value === 'publish') {
      recs = recs.filter(r => r.hasPendingOrDraft)
    }
    rows.value = recs
  } catch (e) { /* 拦截器已提示 */ } finally { loading.value = false }
}

async function loadPlans() {
  loading.value = true
  try {
    const res = await getSamplingPlansApi()
    plans.value = res.data || []
  } catch (e) { /* ignored */ } finally { loading.value = false }
}

function reload() {
  if (view.value === 'sampling') loadPlans()
  else load()
}

function openCreate() {
  stdForm.value = { standardCode: '', name: '', scopeType: 'ITEM', riskLevel: 'C', description: '' }
  stdVisible.value = true
}

async function saveStandard() {
  const f = stdForm.value
  if (!f.standardCode || !f.name) { ElMessage.warning('编码与名称必填'); return }
  saving.value = true
  try {
    const res = await createStandardApi(f)
    ElMessage.success('已保存')
    stdVisible.value = false
    const id = res.data?.id
    if (id) router.push(`/m/6.1.standard/${id}`)
    else await load()
  } catch (e) { /* 拦截器提示 */ } finally { saving.value = false }
}

function openCreateVersion(row) {
  verForm.value = {
    standardId: row.id,
    effectiveFrom: '',
    changeReason: '',
    aqlLevel: 'GB/T 2828.1 一般检验水平 II'
  }
  verVisible.value = true
}

async function createVersion() {
  const f = verForm.value
  if (!f.effectiveFrom) { ElMessage.warning('生效日期必填'); return }
  if (!f.changeReason) { ElMessage.warning('变更原因必填'); return }
  saving.value = true
  try {
    const res = await createVersionApi(f.standardId, {
      ...f,
      inspectionLevel: 'II',
      characteristics: [{
        characteristicName: '', ctqFlag: '1', regulatoryFlag: '0', specType: 'NUMERIC',
        lowerLimit: '', upperLimit: '', targetValue: '', unit: '', methodName: ''
      }],
      applicabilities: [{ priority: 50 }]
    })
    ElMessage.success('草稿版本已创建，请在详情页完善特性与适用范围')
    verVisible.value = false
    const vid = res.data?.version?.id
    router.push(`/m/6.1.standard/${f.standardId}${vid ? `?v=${vid}` : ''}`)
  } catch (e) { /* 拦截器提示 */ } finally { saving.value = false }
}

async function doRetire(row) {
  await ElMessageBox.confirm(
    `确认退役标准 ${row.standardCode}？退役后不再生成新检验任务，历史追溯不变。`,
    '标准退役', { type: 'warning' })
  saving.value = true
  try {
    await retireStandardApi(row.id)
    ElMessage.success('已退役')
    await load()
  } catch (e) { /* 拦截器提示 */ } finally { saving.value = false }
}

function openPlan(row) {
  planForm.value = row ? { ...row } : {
    planCode: '', name: '', inspectionLevel: 'II',
    aqlLevel: 'GB/T 2828.1 一般检验水平 II', sampleSizeRule: '', acceptRule: '', rejectRule: ''
  }
  planVisible.value = true
}

async function savePlan() {
  const f = planForm.value
  if (!f.planCode || !f.name) { ElMessage.warning('编码与名称必填'); return }
  saving.value = true
  try {
    await saveSamplingPlanApi(f)
    ElMessage.success('已保存')
    planVisible.value = false
    await loadPlans()
  } catch (e) { /* 拦截器提示（引用锁 422 由拦截器展示） */ } finally { saving.value = false }
}

async function copyPlan(row) {
  const { value } = await ElMessageBox.prompt(
    `为被引用方案 ${row.planCode} 创建可编辑副本，请输入新方案编码：`,
    '复制为新方案', {
      inputValidator: v => (v && v.trim() ? true : '新编码必填'),
      inputValue: `${row.planCode}-COPY`
    })
  saving.value = true
  try {
    await copySamplingPlanApi(row.id, value.trim())
    ElMessage.success('已复制，新方案可自由编辑')
    await loadPlans()
  } catch (e) { /* 拦截器提示 */ } finally { saving.value = false }
}

watch(view, reload)
onMounted(reload)
</script>

<style scoped>
.standard-list { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.ttl { font-weight: bold; font-size: 16px; }
.filters { display: flex; gap: 8px; margin-bottom: 10px; }
</style>
