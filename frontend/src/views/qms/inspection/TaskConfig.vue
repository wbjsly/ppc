<template>
  <div class="task-config">
    <el-card>
      <template #header>
        <div class="card-header">
          <div class="hd-left">
            <el-button link type="primary" @click="$router.back()">← 返回</el-button>
            <span class="ttl">检验任务配置（巡检 / 免检 / 严格度 / 客户协议）</span>
          </div>
          <el-button size="small" type="primary" :loading="loading" @click="loadAll">刷新</el-button>
        </div>
      </template>

      <!-- 巡检计划 -->
      <el-divider content-position="left">巡检计划（IPQC 定时建批）</el-divider>
      <div class="bar">
        <el-button v-if="canEdit" type="success" size="small" @click="openPlan()">新建巡检计划</el-button>
      </div>
      <el-table :data="plans" size="small" stripe style="margin-bottom: 18px;">
        <el-table-column prop="planCode" label="计划编码" width="160" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="itemCode" label="物料" width="150" />
        <el-table-column prop="intervalMinutes" label="间隔(分)" width="90" align="center" />
        <el-table-column prop="nextRunTime" label="下次触发" width="170" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
              {{ row.status === 'ACTIVE' ? '运行中' : '已暂停' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button v-if="canEdit" link type="primary" @click="doTogglePlan(row)">
              {{ row.status === 'ACTIVE' ? '暂停' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 免检与严格度 -->
      <el-divider content-position="left">免检（BR-4.12-12，质量经理审批，不合格自动取消）</el-divider>
      <div class="bar">
        <el-button v-if="canEdit" type="success" size="small" @click="exemptVisible = true">提交免检申请</el-button>
      </div>
      <el-table :data="exempts" size="small" stripe style="margin-bottom: 18px;">
        <el-table-column prop="materialCode" label="物料" width="160" />
        <el-table-column prop="materialName" label="名称" min-width="140" />
        <el-table-column prop="supplierName" label="供应商" width="140" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success'
              : (row.status === 'PENDING' ? 'warning' : 'info')">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="disableReason" label="停用原因" min-width="180" show-overflow-tooltip />
      </el-table>

      <el-divider content-position="left">严格度（连 2 批不合格转加严 / 加严下连 3 批合格解除）</el-divider>
      <el-table :data="strictnessRows" size="small" stripe style="margin-bottom: 18px;">
        <el-table-column prop="materialCode" label="物料" width="160" />
        <el-table-column prop="supplierId" label="供应商" width="150" />
        <el-table-column label="严格度" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.strictness === 'TIGHTENED' ? 'danger' : 'success'">
              {{ row.strictness }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="triggerType" label="触发" width="100" />
        <el-table-column prop="consecPass" label="连续合格" width="100" align="center" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="releaseBasis" label="解除依据" min-width="180" show-overflow-tooltip />
      </el-table>

      <!-- 客户质量协议 -->
      <el-divider content-position="left">客户质量协议（加严 CTQ / 连续投诉升全检）</el-divider>
      <div class="bar">
        <el-button v-if="canEdit" type="success" size="small" @click="openAgree()">新建协议</el-button>
      </div>
      <el-table :data="agreements" size="small" stripe>
        <el-table-column prop="agreeCode" label="协议编码" width="150">
          <template #default="{ row }"><b>{{ row.agreeCode }}</b></template>
        </el-table-column>
        <el-table-column prop="customerName" label="客户" width="150" show-overflow-tooltip />
        <el-table-column label="适用对象" width="180">
          <template #default="{ row }">{{ row.scopeType }}: {{ row.scopeValue }}</template>
        </el-table-column>
        <el-table-column prop="sampleLevelOverride" label="档位覆盖" width="90" align="center" />
        <el-table-column label="连续投诉" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.complaintStreak >= 2" size="small" type="danger">{{ row.complaintStreak }}（全检）</el-tag>
            <span v-else>{{ row.complaintStreak || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="agreeTag(row.status)">{{ agreeStatusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250">
          <template #default="{ row }">
            <el-button v-if="canEdit && row.status === 'DRAFT'" link type="primary"
                       @click="doActivate(row)">生效</el-button>
            <el-button v-if="canEdit && row.status === 'ACTIVE'" link type="warning"
                       @click="doLogComplaint(row)">登记投诉</el-button>
            <el-button v-if="canEdit && row.complaintStreak > 0" link type="info"
                       @click="doClearStreak(row)">清零</el-button>
            <el-button v-if="canEdit && row.status === 'ACTIVE'" link type="danger"
                       @click="doStop(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!agreements.length" description="暂无客户协议" :image-size="60" />
    </el-card>

    <!-- 巡检计划编辑 -->
    <el-dialog v-model="planVisible" title="巡检计划" width="520px">
      <el-form label-width="110px">
        <el-form-item label="计划名称" required><el-input v-model="planForm.name" /></el-form-item>
        <el-form-item label="物料编码" required><el-input v-model="planForm.itemCode" /></el-form-item>
        <el-form-item label="物料名称"><el-input v-model="planForm.itemName" /></el-form-item>
        <el-form-item label="工序"><el-input v-model="planForm.processName" /></el-form-item>
        <el-form-item label="间隔（分钟）"><el-input-number v-model="planForm.intervalMinutes" :min="1" /></el-form-item>
        <el-form-item label="批量"><el-input-number v-model="planForm.lotQty" :min="1" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="savePlan">保存</el-button>
      </template>
    </el-dialog>

    <!-- 免检申请 -->
    <el-dialog v-model="exemptVisible" title="免检申请（质量经理审批）" width="500px">
      <el-form label-width="100px">
        <el-form-item label="物料编码" required><el-input v-model="exemptForm.materialCode" /></el-form-item>
        <el-form-item label="物料名称"><el-input v-model="exemptForm.materialName" /></el-form-item>
        <el-form-item label="供应商 ID"><el-input v-model="exemptForm.supplierId" placeholder="空=不限供方" /></el-form-item>
        <el-form-item label="供应商名称"><el-input v-model="exemptForm.supplierName" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exemptVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveExempt">提交申请</el-button>
      </template>
    </el-dialog>

    <!-- 客户协议编辑 -->
    <el-dialog v-model="agreeVisible" title="客户质量协议" width="560px">
      <el-form label-width="110px">
        <el-form-item label="协议编码" required>
          <el-input v-model="agreeForm.agreeCode" placeholder="如 QA-CUST-001（创建后不可改）" :disabled="!!agreeForm.id" />
        </el-form-item>
        <el-form-item label="客户 ID" required><el-input v-model="agreeForm.customerId" /></el-form-item>
        <el-form-item label="客户名称"><el-input v-model="agreeForm.customerName" /></el-form-item>
        <el-form-item label="适用类型" required>
          <el-radio-group v-model="agreeForm.scopeType">
            <el-radio label="ITEM">物料</el-radio>
            <el-radio label="CATEGORY">品类</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="适用对象" required>
          <el-input v-model="agreeForm.scopeValue" :placeholder="agreeForm.scopeType === 'ITEM' ? '物料编码' : '品类编码'" />
        </el-form-item>
        <el-form-item label="档位覆盖">
          <el-select v-model="agreeForm.sampleLevelOverride" clearable placeholder="不覆盖">
            <el-option label="A（全检）" value="A" />
            <el-option label="B（50%）" value="B" />
            <el-option label="C（20%）" value="C" />
          </el-select>
        </el-form-item>
        <el-form-item label="加严 CTQ">
          <el-input v-model="agreeForm.tightCtqJson" type="textarea" :rows="3"
                    placeholder='JSON 数组：[{"name":"包装跌落","specType":"LOOK","method":"1m 跌落"}]' />
        </el-form-item>
        <el-form-item label="生效起">
          <el-date-picker v-model="agreeForm.effectiveFrom" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="生效止">
          <el-date-picker v-model="agreeForm.effectiveTo" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="agreeVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveAgree">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getPatrolPlansApi, createPatrolPlanApi, togglePatrolPlanApi,
  getExemptsApi, applyExemptApi, getStrictnessApi,
  getAgreementsApi, saveAgreementApi, activateAgreementApi, stopAgreementApi,
  logComplaintApi, clearStreakApi
} from '@/api/qms/inspection'

const userStore = useUserStore()
const roles = computed(() => userStore.userInfo?.roles || [])
const canEdit = computed(() => roles.value.includes('ROLE_ADMIN')
  || roles.value.includes('ROLE_QUALITY_ENG') || roles.value.includes('ROLE_QUALITY_MGR'))

const loading = ref(false)
const busy = ref(false)
const plans = ref([])
const exempts = ref([])
const strictnessRows = ref([])
const agreements = ref([])

const planVisible = ref(false)
const planForm = reactive({ name: '', itemCode: '', itemName: '', processName: '', intervalMinutes: 120, lotQty: 1 })
const exemptVisible = ref(false)
const exemptForm = reactive({ materialCode: '', materialName: '', supplierId: '', supplierName: '' })
const agreeVisible = ref(false)
const agreeForm = reactive({})

function agreeStatusName(s) { return { DRAFT: '草稿', ACTIVE: '生效', EXPIRED: '过期', STOPPED: '停用' }[s] || s }
function agreeTag(s) { return { DRAFT: 'info', ACTIVE: 'success', EXPIRED: 'info', STOPPED: 'danger' }[s] || 'info' }

async function loadAll() {
  loading.value = true
  try {
    plans.value = (await getPatrolPlansApi()).data || []
    exempts.value = (await getExemptsApi({})).data || []
    strictnessRows.value = (await getStrictnessApi({})).data || []
    agreements.value = (await getAgreementsApi({})).data || []
  } catch (e) { /* 拦截器提示 */ } finally { loading.value = false }
}

function openPlan() {
  Object.assign(planForm, { name: '', itemCode: '', itemName: '', processName: '', intervalMinutes: 120, lotQty: 1 })
  planVisible.value = true
}
async function savePlan() {
  if (!planForm.name || !planForm.itemCode) { ElMessage.warning('名称与物料必填'); return }
  busy.value = true
  try {
    await createPatrolPlanApi({ ...planForm })
    ElMessage.success('计划已创建（到点自动生成 IPQC 批）')
    planVisible.value = false
    await loadAll()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}
async function doTogglePlan(row) {
  try { await togglePatrolPlanApi(row.id); await loadAll() } catch (e) { /* ignored */ }
}

async function saveExempt() {
  if (!exemptForm.materialCode) { ElMessage.warning('物料编码必填'); return }
  busy.value = true
  try {
    await applyExemptApi({ ...exemptForm })
    ElMessage.success('已提交，待质量经理审批')
    exemptVisible.value = false
    await loadAll()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}

function openAgree() {
  Object.assign(agreeForm, {
    id: null, agreeCode: '', customerId: '', customerName: '',
    scopeType: 'ITEM', scopeValue: '', sampleLevelOverride: '',
    tightCtqJson: '', effectiveFrom: '', effectiveTo: ''
  })
  agreeVisible.value = true
}
async function saveAgree() {
  if (!agreeForm.agreeCode || !agreeForm.customerId || !agreeForm.scopeValue) {
    ElMessage.warning('编码/客户/适用对象必填'); return
  }
  if (agreeForm.tightCtqJson && agreeForm.tightCtqJson.trim()) {
    try { JSON.parse(agreeForm.tightCtqJson) } catch (e) {
      ElMessage.warning('加严 CTQ 不是合法 JSON'); return
    }
  }
  busy.value = true
  try {
    await saveAgreementApi({ ...agreeForm })
    ElMessage.success('已保存（草稿，点击「生效」启用）')
    agreeVisible.value = false
    await loadAll()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}
async function doActivate(row) {
  try { await activateAgreementApi(row.id); ElMessage.success('已生效'); await loadAll() } catch (e) { /* 拦截器提示 */ }
}
async function doStop(row) {
  try {
    await ElMessageBox.confirm(`停用协议 ${row.agreeCode}？仅阻止新批命中，历史批快照不变。`, '停用', { type: 'warning' })
    await stopAgreementApi(row.id)
    ElMessage.success('已停用')
    await loadAll()
  } catch (e) { /* cancelled or 拦截器提示 */ }
}
async function doLogComplaint(row) {
  try {
    const { value } = await ElMessageBox.prompt(
      `登记客户 ${row.customerName || row.customerId} 一次出货投诉（计数 +1，≥2 次该客户 OQC 自动全检）：`,
      '投诉登记', { inputValidator: v => (v && v.trim().length >= 2 ? true : '原因必填（≥2 字）') })
    await logComplaintApi(row.id, value.trim())
    ElMessage.success('已登记')
    await loadAll()
  } catch (e) { /* cancelled or 拦截器提示 */ }
}
async function doClearStreak(row) {
  try {
    await ElMessageBox.confirm(`清零 ${row.customerName || row.customerId} 的连续投诉计数？`, '计数清零', { type: 'warning' })
    await clearStreakApi(row.id, '计数清零')
    ElMessage.success('已清零，恢复常规抽样')
    await loadAll()
  } catch (e) { /* cancelled or 拦截器提示 */ }
}

onMounted(loadAll)
</script>

<style scoped>
.task-config { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.hd-left { display: flex; align-items: center; gap: 8px; }
.ttl { font-weight: bold; font-size: 16px; }
.bar { margin-bottom: 10px; }
</style>
