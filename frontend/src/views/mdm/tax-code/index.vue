<template>
  <div class="tax-code">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">税码维护</span>
          <div class="header-actions">
            <el-input v-model="query.keyword" placeholder="税码/政策文号" clearable
                      :prefix-icon="Search" style="width: 180px;" @keyup.enter="loadData(1)" />
            <el-select v-model="query.scope" placeholder="适用范围" clearable style="width: 120px;" @change="loadData(1)">
              <el-option label="国内" value="DOMESTIC" />
              <el-option label="出口" value="EXPORT" />
              <el-option label="免税" value="EXEMPT" />
            </el-select>
            <el-select v-model="query.calcType" placeholder="计税方式" clearable style="width: 120px;" @change="loadData(1)">
              <el-option label="一般计税" value="GENERAL" />
              <el-option label="简易计税" value="SIMPLIFIED" />
              <el-option label="差额征税" value="DIFFERENTIAL" />
            </el-select>
            <el-select v-model="query.lifecycle" placeholder="计算态" clearable style="width: 110px;" @change="loadData(1)">
              <el-option label="未生效" value="NOT_EFFECTIVE" />
              <el-option label="生效中" value="EFFECTIVE" />
              <el-option label="已失效" value="EXPIRED" />
            </el-select>
            <el-button type="primary" @click="loadData(1)">查询</el-button>
            <el-button v-if="isAdmin" type="primary" :icon="Plus" @click="openCreate">新建税码</el-button>
          </div>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
        title="区间规则（BR-4.1-17）：同一税码编号的生效区间须首尾衔接（不重叠、不断档）；已失效历史不可篡改（BR-4.1-18）。"
        description="政策文号（C-4.1-04）与生效/失效日期必填；税率 4 位小数；税码编号创建后不可改（C-4.1-01）。" />

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="税码编号" width="120">
          <template #default="{ row }"><b>{{ row.taxCode }}</b></template>
        </el-table-column>
        <el-table-column prop="taxRate" label="税率%" width="90" align="right" />
        <el-table-column label="区间" width="200">
          <template #default="{ row }">{{ row.effectiveDate }} ~ {{ row.expireDate }}</template>
        </el-table-column>
        <el-table-column label="适用范围" width="90">
          <template #default="{ row }">{{ scopeName(row.scope) }}</template>
        </el-table-column>
        <el-table-column label="计税方式" width="100">
          <template #default="{ row }">{{ calcName(row.calcType) }}</template>
        </el-table-column>
        <el-table-column label="税率类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.rateKind === 'ZERO' || row.rateKind === 'EXEMPT' ? 'warning' : undefined">
              {{ kindName(row.rateKind) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="policyNo" label="政策文号" min-width="160" show-overflow-tooltip />
        <el-table-column label="计算态" width="90">
          <template #default="{ row }">
            <el-tag :type="lcTag(row.lifecycle)" size="small">{{ lcName(row.lifecycle) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openCompare(row)">对比</el-button>
            <template v-if="row.lifecycle === 'EXPIRED'">
              <span style="color: #909399; font-size: 12px;">历史只读</span>
            </template>
            <template v-else-if="isAdmin">
              <el-button link type="primary" @click="openEdit(row)">变更</el-button>
              <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination layout="total, prev, pager, next" style="margin-top: 12px; justify-content: flex-end;"
        :total="total" :page-size="query.size" :current-change="loadData" />

      <el-divider content-position="left">税码试算查询（4.14 计税预铺，C-4.1-01 口径）</el-divider>
      <el-form inline>
        <el-form-item label="税码编号">
          <el-input v-model="trialForm.taxCode" placeholder="如 VAT-13" style="width: 160px;" />
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker v-model="trialForm.date" type="date" value-format="YYYY-MM-DD" style="width: 150px;" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="doTrial">试算</el-button>
        </el-form-item>
      </el-form>
      <el-card v-if="trialResult" shadow="never">
        <template v-if="trialResult.applicable">
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="税率%"><b>{{ trialResult.taxRate }}</b></el-descriptions-item>
            <el-descriptions-item label="税率类型">{{ kindName(trialResult.rateKind) }}</el-descriptions-item>
            <el-descriptions-item label="计税方式">{{ calcName(trialResult.calcType) }}</el-descriptions-item>
            <el-descriptions-item label="适用范围">{{ scopeName(trialResult.scope) }}</el-descriptions-item>
            <el-descriptions-item label="政策文号" :span="2">{{ trialResult.policyNo }}</el-descriptions-item>
            <el-descriptions-item label="生效区间" :span="3">{{ trialResult.effectiveDate }} ~ {{ trialResult.expireDate }}</el-descriptions-item>
          </el-descriptions>
        </template>
        <el-result v-else icon="warning" :title="trialResult.message" :sub-title="trialResult.hint">
          <template #extra>
            <ul style="text-align: left; color: #909399;">
              <li v-for="(r, i) in trialResult.reasons" :key="i">{{ r }}</li>
            </ul>
          </template>
        </el-result>
      </el-card>
    </el-card>

    <!-- 新建/变更弹窗（760px：label 110 + 3 列 span-8 保证输入位充足） -->
    <el-dialog v-model="formVisible" :title="form.id ? '变更税码记录' : '新建税码'" width="760px">
      <el-alert v-if="conflictMsg" type="error" show-icon :closable="false" :title="conflictMsg" style="margin-bottom: 10px;" />
      <el-form label-width="110px">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="税码编号" required>
              <el-input v-model="form.taxCode" :disabled="!!form.id" maxlength="32" placeholder="VAT-13"
                        style="text-transform: uppercase;" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="税率%" required>
              <el-input-number v-model="form.taxRate" :min="0" :max="100" :precision="4" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="适用范围" required>
              <el-select v-model="form.scope" style="width: 100%;">
                <el-option label="国内" value="DOMESTIC" />
                <el-option label="出口" value="EXPORT" />
                <el-option label="免税" value="EXEMPT" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="生效日期" required>
              <el-date-picker v-model="form.effectiveDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="失效日期" required>
              <el-date-picker v-model="form.expireDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="计税方式" required>
              <el-select v-model="form.calcType" style="width: 100%;">
                <el-option label="一般计税" value="GENERAL" />
                <el-option label="简易计税" value="SIMPLIFIED" />
                <el-option label="差额征税" value="DIFFERENTIAL" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item label="政策文号" required>
              <el-input v-model="form.policyNo" maxlength="64" placeholder="如：税总公告2026年第15号（C-4.1-04）" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="税率类型" required>
              <el-select v-model="form.rateKind" style="width: 100%;">
                <el-option label="标准税率" value="STANDARD" />
                <el-option label="低税率" value="LOW" />
                <el-option label="零税率" value="ZERO" />
                <el-option label="免税" value="EXEMPT" />
              </el-select>
            </el-form-item>
          </el-col>
          <div v-if="form.id" class="form-tip" style="margin-bottom: 8px; grid-column: span 24;">
            税码编号为区间序列键，创建后不可修改（C-4.1-01）
          </div>
          <el-col v-if="form.id" :span="24">
            <el-form-item label="变更原因" required>
              <el-input v-model="form.changeReason" maxlength="255" placeholder="必填（≥2 字）" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 版本对比抽屉 -->
    <el-drawer v-model="cmpVisible" title="版本对比（逐字段）" size="620px">
      <el-form inline>
        <el-form-item label="from">
          <el-select v-model="cmpFrom" placeholder="选择版本" style="width: 150px;">
            <el-option v-for="v in cmpVersions" :key="v.versionNo"
                       :label="`V${v.versionNo}（${v.opType === 'CREATE' ? '新建' : '变更'}）`"
                       :value="v.versionNo" :disabled="v.versionNo === cmpTo" />
          </el-select>
        </el-form-item>
        <el-form-item label="to">
          <el-select v-model="cmpTo" placeholder="选择版本" style="width: 150px;">
            <el-option v-for="v in cmpVersions" :key="v.versionNo"
                       :label="`V${v.versionNo}（${v.opType === 'CREATE' ? '新建' : '变更'}）`"
                       :value="v.versionNo" :disabled="v.versionNo === cmpFrom" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="!cmpFrom || !cmpTo || cmpFrom === cmpTo"
                     :loading="cmpLoading" @click="runDiff">对比</el-button>
        </el-form-item>
      </el-form>
      <el-alert v-if="cmpFrom && cmpTo && cmpFrom === cmpTo" type="warning" :closable="false"
                title="同一版本无需对比" style="margin-bottom: 10px;" />
      <el-table v-if="cmpFields.length" :data="cmpFields" border size="small">
        <el-table-column label="字段" width="140">
          <template #default="{ row }">{{ fieldName(row.field) }}</template>
        </el-table-column>
        <el-table-column label="旧值" min-width="160">
          <template #header>旧值 V{{ cmpFrom }}</template>
          <template #default="{ row }"><span class="val-old">{{ row.from || '（空）' }}</span></template>
        </el-table-column>
        <el-table-column label="新值" min-width="160">
          <template #header>新值 V{{ cmpTo }}</template>
          <template #default="{ row }"><span class="val-new">{{ row.to || '（空）' }}</span></template>
        </el-table-column>
      </el-table>
      <el-empty v-else-if="cmpDone" description="两版本无字段差异" :image-size="70" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/user'
import {
  getTaxPageApi, createTaxApi, updateTaxApi, deleteTaxApi, trialTaxApi,
  getTaxVersionsApi, getTaxDiffApi
} from '@/api/mdm/tax-code'

const userStore = useUserStore()
const isAdmin = computed(() => (userStore.userInfo?.roles || []).includes('ROLE_ADMIN'))
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ keyword: '', scope: '', calcType: '', lifecycle: '', current: 1, size: 10 })

function scopeName(s) {
  return { DOMESTIC: '国内', EXPORT: '出口', EXEMPT: '免税' }[s] || s
}
function calcName(c) {
  return { GENERAL: '一般', SIMPLIFIED: '简易', DIFFERENTIAL: '差额' }[c] || c
}
function kindName(k) {
  return { STANDARD: '标准', LOW: '低税率', ZERO: '零税率', EXEMPT: '免税' }[k] || k
}
function lcName(lc) {
  return { NOT_EFFECTIVE: '未生效', EFFECTIVE: '生效中', EXPIRED: '已失效' }[lc] || lc
}
function lcTag(lc) {
  return { NOT_EFFECTIVE: 'info', EFFECTIVE: 'success', EXPIRED: 'warning' }[lc]
}
function fieldName(f) {
  return {
    taxCode: '税码编号', taxRate: '税率值', effectiveDate: '生效日期', expireDate: '失效日期',
    scope: '适用范围', policyNo: '政策文号', calcType: '计税方式', rateKind: '税率类型'
  }[f] || f
}

async function loadData(page) {
  if (page) query.value.current = page
  loading.value = true
  try {
    const res = await getTaxPageApi(query.value)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

// ---------- 新建/变更 ----------
const formVisible = ref(false)
const form = ref({})
const conflictMsg = ref('')

function openCreate() {
  form.value = {
    taxCode: '', taxRate: null, effectiveDate: '', expireDate: '',
    scope: 'DOMESTIC', policyNo: '', calcType: 'GENERAL', rateKind: 'STANDARD'
  }
  conflictMsg.value = ''
  formVisible.value = true
}
function openEdit(row) {
  form.value = { ...row, changeReason: '' }
  conflictMsg.value = ''
  formVisible.value = true
}
async function submitForm() {
  conflictMsg.value = ''
  saving.value = true
  try {
    if (form.value.id) {
      await updateTaxApi(form.value)
      ElMessage.success('变更已生效')
    } else {
      await createTaxApi(form.value)
      ElMessage.success('新建成功')
    }
    formVisible.value = false
    loadData()
  } catch (e) {
    const msg = e?.message || ''
    if (msg.includes('区间') || msg.includes('断档') || msg.includes('缺口') || msg.includes('必填')
        || msg.includes('税率') || msg.includes('编号') || msg.includes('篡改') || msg.includes('枚举')
        || msg.includes('范围') || msg.includes('方式') || msg.includes('类型') || msg.includes('日期')) {
      conflictMsg.value = msg
    }
  } finally {
    saving.value = false
  }
}
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`删除税码 [${row.taxCode}] 区间 [${row.effectiveDate}, ${row.expireDate}]？`, '删除确认', { type: 'warning' })
  } catch (e) {
    return
  }
  await deleteTaxApi(row.id)
  ElMessage.success('已删除')
  loadData()
}

// ---------- 版本对比 ----------
const cmpVisible = ref(false)
const cmpLoading = ref(false)
const cmpDone = ref(false)
const cmpEntityId = ref('')
const cmpVersions = ref([])
const cmpFrom = ref(null)
const cmpTo = ref(null)
const cmpFields = ref([])

async function openCompare(row) {
  cmpEntityId.value = row.id
  cmpFrom.value = null
  cmpTo.value = null
  cmpFields.value = []
  cmpDone.value = false
  cmpVisible.value = true
  const res = await getTaxVersionsApi(row.id)
  cmpVersions.value = res.data
  if (cmpVersions.value.length >= 2) {
    cmpFrom.value = cmpVersions.value[0].versionNo
    cmpTo.value = cmpVersions.value[cmpVersions.value.length - 1].versionNo
  } else if (cmpVersions.value.length === 1) {
    cmpTo.value = cmpVersions.value[0].versionNo
  }
}
async function runDiff() {
  if (cmpFrom.value === cmpTo.value) return
  cmpLoading.value = true
  try {
    const res = await getTaxDiffApi(cmpEntityId.value, cmpFrom.value, cmpTo.value)
    cmpFields.value = res.data.fields || []
    cmpDone.value = true
  } finally {
    cmpLoading.value = false
  }
}

// ---------- 试算 ----------
const trialForm = ref({ taxCode: '', date: '', })
const trialResult = ref(null)

async function doTrial() {
  if (!trialForm.value.taxCode) {
    ElMessage.warning('请填写税码编号')
    return
  }
  loading.value = true
  try {
    const res = await trialTaxApi(trialForm.value)
    trialResult.value = res.data
  } finally {
    loading.value = false
  }
}

onMounted(() => loadData())
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.form-tip { font-size: 12px; color: #909399; line-height: 1.5; }
.val-old { background: #fde2e2; color: #c45656; padding: 2px 6px; border-radius: 3px; }
.val-new { background: #d9f7be; color: #52c41a; padding: 2px 6px; border-radius: 3px; }
</style>
