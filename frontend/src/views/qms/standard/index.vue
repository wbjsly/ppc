<template>
  <div class="qms-standard">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">{{ title }}</span>
          <div>
            <el-button size="small" @click="$router.push('/m/qms-approval')">审批待办</el-button>
            <el-button size="small" type="primary" :loading="loading" @click="reload">刷新</el-button>
          </div>
        </div>
      </template>

      <el-tabs :model-value="activeTab" @tab-change="onTabChange">
        <!-- 6.1.1 标准申请 -->
        <el-tab-pane label="标准申请" name="apply">
          <div style="margin-bottom: 10px; display: flex; gap: 8px;">
            <el-input v-model="keyword" placeholder="编码/名称" style="width: 220px;" clearable @keyup.enter="load" />
            <el-select v-model="statusFilter" placeholder="状态" style="width: 130px;" clearable>
              <el-option label="草稿" value="DRAFT" />
              <el-option label="已启用" value="ACTIVE" />
              <el-option label="已退役" value="RETIRED" />
            </el-select>
            <el-button type="primary" @click="load">查询</el-button>
            <el-button v-if="canEdit" type="success" @click="openCreate">新建标准</el-button>
          </div>
          <el-table :data="rows" v-loading="loading" stripe>
            <el-table-column prop="standardCode" label="标准编码" width="160">
              <template #default="{ row }"><b>{{ row.standardCode }}</b></template>
            </el-table-column>
            <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
            <el-table-column prop="scopeType" label="范围" width="90" />
            <el-table-column prop="riskLevel" label="风险" width="70" align="center" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="stdStatusTag(row.status)">{{ stdStatusName(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="currentVersion" label="现行版本" width="90" align="center">
              <template #default="{ row }">{{ row.currentVersion > 0 ? 'V' + row.currentVersion : '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="260" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openDetail(row)">详情</el-button>
                <el-button v-if="canEdit && row.status !== 'RETIRED'" link type="warning" @click="openCreateVersion(row)">
                  新建版本
                </el-button>
                <el-button v-if="canEdit && row.status !== 'RETIRED'" link type="danger" @click="doRetire(row)">
                  退役
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination style="margin-top: 10px;" layout="total, prev, pager, next" :total="total"
                         :page-size="size" :current-page="page" @current-change="p => { page = p; load() }" />
        </el-tab-pane>

        <!-- 6.1.2 标准审批 -->
        <el-tab-pane label="标准审批" name="approval">
          <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
                    title="发布审批（C-4.12-18）：含 CTQ/安全法规特性的版本需质量经理 + 技术负责人并行双签，缺一不可。"
                    description="审批动作在「审批待办」完成；本页仅展示在途与结果。" />
          <el-table :data="pendingVersions" stripe>
            <el-table-column prop="standardCode" label="标准" width="160" />
            <el-table-column prop="versionNo" label="版本" width="80" align="center">
              <template #default="{ row }">V{{ row.versionNo }}</template>
            </el-table-column>
            <el-table-column prop="changeReason" label="变更原因" min-width="200" show-overflow-tooltip />
            <el-table-column prop="effectiveFrom" label="生效起" width="110" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="verStatusTag(row.status)">{{ verStatusName(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default>
                <el-button link type="primary" @click="$router.push('/m/qms-approval')">去签署</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!pendingVersions.length" description="无在途发布审批" :image-size="80" />
        </el-tab-pane>

        <!-- 6.1.3 版本发布 -->
        <el-tab-pane label="版本发布" name="publish">
          <el-table :data="versionsFlat" stripe>
            <el-table-column prop="standardCode" label="标准" width="160" />
            <el-table-column prop="versionNo" label="版本" width="70" align="center">
              <template #default="{ row }">V{{ row.versionNo }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="verStatusTag(row.status)">{{ verStatusName(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="effectiveFrom" label="生效起" width="110" />
            <el-table-column prop="effectiveTo" label="生效止" width="110" />
            <el-table-column prop="changeReason" label="变更原因" min-width="180" show-overflow-tooltip />
            <el-table-column label="操作" width="140">
              <template #default="{ row }">
                <el-button v-if="row.status === 'DRAFT' && canEdit" link type="primary"
                           @click="doPublish(row)">提交发布</el-button>
                <span v-else style="color: #c0c4cc;">—</span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!versionsFlat.length" description="暂无版本" :image-size="80" />
        </el-tab-pane>

        <!-- 6.1.4 抽样方案 -->
        <el-tab-pane label="抽样方案" name="sampling">
          <div style="margin-bottom: 10px;">
            <el-button v-if="canEdit" type="success" @click="openPlan()">新建方案</el-button>
          </div>
          <el-table :data="plans" stripe>
            <el-table-column prop="planCode" label="方案编码" width="150" />
            <el-table-column prop="name" label="名称" min-width="160" />
            <el-table-column prop="inspectionLevel" label="检验水平" width="100" align="center" />
            <el-table-column prop="aqlLevel" label="AQL" min-width="180" />
            <el-table-column prop="sampleSizeRule" label="样本量规则" min-width="160" show-overflow-tooltip />
            <el-table-column prop="status" label="状态" width="90" />
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button v-if="canEdit" link type="primary" @click="openPlan(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 6.1.5 标准退役 -->
        <el-tab-pane label="标准退役" name="retire">
          <el-alert type="warning" :closable="false" style="margin-bottom: 10px;"
                    title="退役口径（BR-4.12-58 / C-4.12-20）：停用仅阻止新任务引用，历史任务与 NCR 追溯关系保持不变，禁止物理删除。" />
          <el-table :data="rows" stripe>
            <el-table-column prop="standardCode" label="标准编码" width="160" />
            <el-table-column prop="name" label="名称" min-width="180" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="stdStatusTag(row.status)">{{ stdStatusName(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button v-if="canEdit && row.status !== 'RETIRED'" link type="danger" @click="doRetire(row)">
                  退役
                </el-button>
                <span v-else style="color: #c0c4cc;">已退役</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 新建/编辑标准 -->
    <el-dialog v-model="stdVisible" :title="stdForm.id ? '编辑标准' : '新建标准'" width="480px">
      <el-form label-width="100px">
        <el-form-item label="标准编码" required>
          <el-input v-model="stdForm.standardCode" placeholder="如 STD-MCU-001（创建后不可改）"
                    :disabled="!!stdForm.id" />
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

    <!-- 版本编辑（特性 + 适用范围） -->
    <el-dialog v-model="verVisible" :title="verForm.versionId ? '编辑草稿版本' : '新建版本'" width="860px" top="4vh">
      <el-form label-width="110px" inline>
        <el-form-item label="抽样方案">
          <el-select v-model="verForm.samplingPlanId" clearable placeholder="选择方案" style="width: 200px;">
            <el-option v-for="p in plans" :key="p.id" :label="`${p.planCode} ${p.name}`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="AQL 等级">
          <el-input v-model="verForm.aqlLevel" style="width: 230px;" placeholder="GB/T 2828.1 一般检验水平 II" />
        </el-form-item>
        <el-form-item label="检验水平">
          <el-select v-model="verForm.inspectionLevel" style="width: 110px;">
            <el-option label="I" value="I" /><el-option label="II" value="II" /><el-option label="III" value="III" />
          </el-select>
        </el-form-item>
        <el-form-item label="生效日期" required>
          <el-date-picker v-model="verForm.effectiveFrom" type="date" value-format="YYYY-MM-DD" style="width: 150px;" />
        </el-form-item>
        <el-form-item label="变更原因" required>
          <el-input v-model="verForm.changeReason" style="width: 250px;" />
        </el-form-item>
      </el-form>

      <el-divider content-position="left">检验特性（CTQ 至少一条；计量型须上下限）</el-divider>
      <el-table :data="verForm.characteristics" size="small" border>
        <el-table-column label="特性名" min-width="150">
          <template #default="{ row }"><el-input v-model="row.characteristicName" size="small" /></template>
        </el-table-column>
        <el-table-column label="CTQ" width="70" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.ctqFlag" active-value="1" inactive-value="0" />
          </template>
        </el-table-column>
        <el-table-column label="安全/法规" width="90" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.regulatoryFlag" active-value="1" inactive-value="0" />
          </template>
        </el-table-column>
        <el-table-column label="规格类型" width="120">
          <template #default="{ row }">
            <el-select v-model="row.specType" size="small">
              <el-option label="计量型" value="NUMERIC" />
              <el-option label="计数型" value="COUNT" />
              <el-option label="外观" value="LOOK" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="下限" width="100">
          <template #default="{ row }"><el-input v-model="row.lowerLimit" size="small" :disabled="row.specType !== 'NUMERIC'" /></template>
        </el-table-column>
        <el-table-column label="上限" width="100">
          <template #default="{ row }"><el-input v-model="row.upperLimit" size="small" :disabled="row.specType !== 'NUMERIC'" /></template>
        </el-table-column>
        <el-table-column label="目标值" width="100">
          <template #default="{ row }"><el-input v-model="row.targetValue" size="small" /></template>
        </el-table-column>
        <el-table-column label="单位" width="80">
          <template #default="{ row }"><el-input v-model="row.unit" size="small" /></template>
        </el-table-column>
        <el-table-column label="方法" width="130">
          <template #default="{ row }"><el-input v-model="row.methodName" size="small" /></template>
        </el-table-column>
        <el-table-column label="" width="60">
          <template #default="{ $index }">
            <el-button link type="danger" @click="verForm.characteristics.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" style="margin-top: 6px;" @click="verForm.characteristics.push(charTemplate())">+ 特性</el-button>

      <el-divider content-position="left">适用范围（优先级数值越小越具体）</el-divider>
      <el-table :data="verForm.applicabilities" size="small" border>
        <el-table-column label="物料编码" width="160">
          <template #default="{ row }"><el-input v-model="row.materialCode" size="small" /></template>
        </el-table-column>
        <el-table-column label="品类编码" width="140">
          <template #default="{ row }"><el-input v-model="row.categoryCode" size="small" /></template>
        </el-table-column>
        <el-table-column label="供应商 ID" width="150">
          <template #default="{ row }"><el-input v-model="row.supplierId" size="small" /></template>
        </el-table-column>
        <el-table-column label="优先级" width="100">
          <template #default="{ row }"><el-input v-model="row.priority" size="small" /></template>
        </el-table-column>
        <el-table-column label="" width="60">
          <template #default="{ $index }">
            <el-button link type="danger" @click="verForm.applicabilities.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" style="margin-top: 6px;" @click="verForm.applicabilities.push({ priority: 50 })">+ 范围</el-button>

      <template #footer>
        <el-button @click="verVisible = false">取消</el-button>
        <el-button v-if="verForm.versionId" type="warning" :loading="saving" @click="saveVersion(true)">保存修改</el-button>
        <el-button v-else type="primary" :loading="saving" @click="saveVersion(false)">创建草稿</el-button>
      </template>
    </el-dialog>

    <!-- 抽样方案编辑 -->
    <el-dialog v-model="planVisible" title="抽样方案" width="520px">
      <el-form label-width="110px">
        <el-form-item label="方案编码" required><el-input v-model="planForm.planCode" :disabled="!!planForm.id" /></el-form-item>
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
  getStandardsApi, createStandardApi, updateStandardApi, getStandardDetailApi,
  retireStandardApi, createVersionApi, updateVersionApi, publishVersionApi,
  getSamplingPlansApi, saveSamplingPlanApi
} from '@/api/qms/standard'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const roles = computed(() => userStore.userInfo?.roles || [])
const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))
const canEdit = computed(() => isAdmin.value || roles.value.includes('ROLE_QUALITY_ENG'))

const TAB_MAP = { apply: '/m/6.1.1', approval: '/m/6.1.2', publish: '/m/6.1.3', sampling: '/m/6.1.4', retire: '/m/6.1.5' }
const activeTab = ref(route.meta.tab || 'apply')
const title = computed(() => route.meta.title || '检验标准管理')
function onTabChange(name) {
  if (TAB_MAP[name] && route.path !== TAB_MAP[name]) router.push(TAB_MAP[name])
}
watch(() => route.meta.tab, v => { if (v) activeTab.value = v })

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const statusFilter = ref('')
const pendingVersions = ref([])
const versionsFlat = ref([])
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

function charTemplate() {
  return { characteristicName: '', ctqFlag: '0', regulatoryFlag: '0', specType: 'NUMERIC',
           lowerLimit: '', upperLimit: '', targetValue: '', unit: '', methodName: '' }
}

async function load() {
  loading.value = true
  try {
    const res = await getStandardsApi({ current: page.value, size: size.value, keyword: keyword.value, status: statusFilter.value })
    rows.value = res.data?.records || []
    total.value = res.data?.total || 0
    // 审批/发布页签数据：逐个标准取版本
    const pend = []
    const flat = []
    for (const s of rows.value) {
      const d = await getStandardDetailApi(s.id)
      for (const v of (d.data?.versions || [])) {
        const row = { ...v, standardCode: s.standardCode, standardId: s.id }
        flat.push(row)
        if (v.status === 'PENDING_APPROVE' || v.status === 'DRAFT') pend.push(row)
      }
    }
    pendingVersions.value = pend.filter(v => v.status === 'PENDING_APPROVE')
    versionsFlat.value = flat
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function loadPlans() {
  try {
    const res = await getSamplingPlansApi()
    plans.value = res.data || []
  } catch (e) { /* ignored */ }
}

function reload() { load(); loadPlans() }

function openCreate() {
  stdForm.value = { id: null, standardCode: '', name: '', scopeType: 'ITEM', riskLevel: 'C', description: '' }
  stdVisible.value = true
}

async function saveStandard() {
  const f = stdForm.value
  if (!f.standardCode || !f.name) { ElMessage.warning('编码与名称必填'); return }
  saving.value = true
  try {
    if (f.id) await updateStandardApi(f.id, f)
    else await createStandardApi(f)
    ElMessage.success('已保存')
    stdVisible.value = false
    await load()
  } catch (e) { /* 拦截器提示 */ } finally { saving.value = false }
}

function openCreateVersion(row) {
  verForm.value = {
    standardId: row.id, versionId: null, samplingPlanId: '', aqlLevel: 'GB/T 2828.1 一般检验水平 II',
    inspectionLevel: 'II', effectiveFrom: '', changeReason: '',
    characteristics: [charTemplate()], applicabilities: [{ materialCode: row.scopeType === 'ITEM' ? '' : '', priority: 50 }]
  }
  verVisible.value = true
}

async function openDetail(row) {
  const d = await getStandardDetailApi(row.id)
  const versions = d.data?.versions || []
  const draft = versions.find(v => v.status === 'DRAFT')
  if (draft) {
    openVersionForEdit(row, draft)
  } else {
    verForm.value = {
      standardId: row.id, versionId: null, samplingPlanId: '', aqlLevel: 'GB/T 2828.1 一般检验水平 II',
      inspectionLevel: 'II', effectiveFrom: '', changeReason: '',
      characteristics: [charTemplate()], applicabilities: [{ priority: 50 }]
    }
    verVisible.value = true
    ElMessage.info('该标准无草稿版本，已为你打开新建版本表单')
  }
}

async function openVersionForEdit(row, v) {
  const d = await getStandardDetailApi(row.id)
  const detail = d.data || {}
  const ch = await import('@/api/qms/standard').then(m => m.getCharacteristicsApi(v.id))
  const appl = (detail.applicabilities || []).filter(a => a.versionId === v.id)
  verForm.value = {
    standardId: row.id, versionId: v.id, samplingPlanId: v.samplingPlanId || '',
    aqlLevel: v.aqlLevel, inspectionLevel: v.inspectionLevel,
    effectiveFrom: v.effectiveFrom || '', changeReason: v.changeReason || '',
    characteristics: (ch.data || []).length ? ch.data.map(c => ({ ...c })) : [charTemplate()],
    applicabilities: appl.length ? appl.map(a => ({ ...a })) : [{ priority: 50 }]
  }
  verVisible.value = true
}

async function saveVersion(isUpdate) {
  const f = verForm.value
  if (!f.effectiveFrom) { ElMessage.warning('生效日期必填'); return }
  if (!f.changeReason) { ElMessage.warning('变更原因必填'); return }
  saving.value = true
  try {
    if (isUpdate) await updateVersionApi(f.versionId, f)
    else await createVersionApi(f.standardId, f)
    ElMessage.success(isUpdate ? '草稿已更新' : '草稿版本已创建')
    verVisible.value = false
    await load()
  } catch (e) { /* 拦截器提示 */ } finally { saving.value = false }
}

async function doPublish(v) {
  saving.value = true
  try {
    const res = await publishVersionApi(v.id)
    const info = res.data || {}
    ElMessage.success(info.doubleSign
      ? `已提交发布（双签：质量经理 + 技术负责人），审批单 ${info.apprNo || ''}`
      : `已提交发布（质量经理审批），审批单 ${info.apprNo || ''}`)
    await load()
  } catch (e) { /* 拦截器提示 */ } finally { saving.value = false }
}

async function doRetire(row) {
  await ElMessageBox.confirm(`确认退役标准 ${row.standardCode}？退役后不再生成新检验任务，历史追溯不变。`, '标准退役', { type: 'warning' })
  saving.value = true
  try {
    await retireStandardApi(row.id)
    ElMessage.success('已退役')
    await load()
  } catch (e) { /* 拦截器提示 */ } finally { saving.value = false }
}

function openPlan(row) {
  planForm.value = row ? { ...row } : { planCode: '', name: '', inspectionLevel: 'II', aqlLevel: 'GB/T 2828.1 一般检验水平 II', sampleSizeRule: '', acceptRule: '', rejectRule: '' }
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
  } catch (e) { /* 拦截器提示 */ } finally { saving.value = false }
}

onMounted(reload)
</script>

<style scoped>
.qms-standard { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>
