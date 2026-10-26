<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>税收优惠管理（9.6.2）</span>
          <el-button type="primary" size="small" @click="loadAll">刷新</el-button>
        </div>
      </template>
      <el-alert :closable="false" type="info" show-icon class="mb8"
        title="BR-4.14-05：申报数据须落在备案范围内，越界申报生成将被 422 阻断；BR-4.14-06：到期前 90 天预警续期，政策废止自动终止并评估补税风险" />

      <!-- 到期预警 / 冻结终止 -->
      <el-row :gutter="12" class="mb8">
        <el-col :span="12">
          <el-card shadow="never" body-style="padding:12px">
            <div class="warn-title">到期预警（≤{{ warnings.warnDays || 90 }} 天）</div>
            <el-table :data="warnings.expiring || []" size="small" max-height="220">
              <el-table-column prop="preference.prefNo" label="备案号" width="130" />
              <el-table-column prop="preference.policyName" label="政策" min-width="140" show-overflow-tooltip />
              <el-table-column prop="daysToEnd" label="剩余天数" width="90" />
              <el-table-column label="操作" width="90">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openRenew(row.preference)">续期</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card shadow="never" body-style="padding:12px">
            <div class="warn-title">冻结/终止（申报将被阻断）</div>
            <el-table :data="warnings.frozenOrTerminated || []" size="small" max-height="220">
              <el-table-column prop="preference.prefNo" label="备案号" width="130" />
              <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
            </el-table>
          </el-card>
        </el-col>
      </el-row>

      <!-- 登记 -->
      <el-form inline class="mb8" size="small">
        <el-form-item label="政策名称"><el-input v-model="form.policyName" style="width:170px" /></el-form-item>
        <el-form-item label="依据文号"><el-input v-model="form.policyDocNo" style="width:150px" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.prefType" style="width:130px">
            <el-option label="即征即退" value="VAT_REFUND" />
            <el-option label="加计扣除" value="RND_SUPER" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="适用主体"><el-input v-model="form.subject" style="width:150px" /></el-form-item>
        <el-form-item label="期间">
          <el-date-picker v-model="periodRange" type="daterange" value-format="YYYY-MM-DD"
            start-placeholder="起" end-placeholder="止" style="width:230px" />
        </el-form-item>
        <el-form-item><el-button type="primary" @click="register">登记备案</el-button></el-form-item>
      </el-form>

      <!-- 台账 -->
      <el-table :data="rows" size="small" border>
        <el-table-column label="备案号" width="130">
          <template #default="{ row }">{{ row.preference.prefNo }}</template>
        </el-table-column>
        <el-table-column label="政策名称" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.preference.policyName }}</template>
        </el-table-column>
        <el-table-column label="依据文号" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.preference.policyDocNo }}</template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ typeName(row.preference.prefType) }}</template>
        </el-table-column>
        <el-table-column label="适用主体" width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.preference.subject }}</template>
        </el-table-column>
        <el-table-column label="优惠期间" width="190">
          <template #default="{ row }">{{ row.preference.prefStart }} ~ {{ row.preference.prefEnd }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.preference.status === 'ACTIVE' ? 'success'
              : row.preference.status === 'FROZEN' || row.preference.status === 'EXPIRED' ? 'warning' : 'danger'" size="small">
              {{ statusName(row.preference.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <template v-if="row.preference.status === 'ACTIVE'">
              <el-button link type="primary" size="small" @click="openRenew(row.preference)">续期</el-button>
              <el-button link type="warning" size="small" @click="freeze(row.preference)">冻结</el-button>
              <el-button link type="danger" size="small" @click="revoke(row.preference)">废止</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { prefList, prefRegister, prefWarnings, prefRenew, prefFreeze, prefRevoke } from '@/api/fin/tax-risk-control'

const rows = ref([])
const warnings = ref({})
const periodRange = ref([])
const form = ref({ policyName: '', policyDocNo: '', prefType: 'VAT_REFUND', subject: '' })

const typeName = (t) => ({ VAT_REFUND: '即征即退', RND_SUPER: '加计扣除', OTHER: '其他' }[t] || t)
const statusName = (s) => ({ ACTIVE: '生效', FROZEN: '冻结', EXPIRED: '过期', TERMINATED: '终止' }[s] || s)

async function loadAll() {
  try { rows.value = (await prefList({})).data || [] } catch (e) { /* ignore */ }
  try { warnings.value = (await prefWarnings()).data || {} } catch (e) { /* ignore */ }
}

async function register() {
  if (!periodRange.value || periodRange.value.length !== 2) {
    ElMessage.warning('请选择优惠期间'); return
  }
  try {
    await prefRegister({ ...form.value, prefStart: periodRange.value[0], prefEnd: periodRange.value[1] })
    ElMessage.success('备案登记成功')
    form.value = { policyName: '', policyDocNo: '', prefType: 'VAT_REFUND', subject: '' }
    periodRange.value = []
    loadAll()
  } catch (e) { /* ignore */ }
}

async function openRenew(pref) {
  const { value } = await ElMessageBox.prompt(
    `续期 ${pref.prefNo}（原止日 ${pref.prefEnd}）。新止日（yyyy-MM-dd）`, '续期登记',
    { inputPattern: /^\d{4}-\d{2}-\d{2}$/, inputErrorMessage: '格式 yyyy-MM-dd' })
  const { value: basis } = await ElMessageBox.prompt('续期依据', '续期依据（必填）', {
    inputValidator: (v) => (v && v.trim()) || '依据必填'
  })
  try {
    await prefRenew(pref.id, { newEnd: value, basis })
    ElMessage.success('续期成功（原期间/新期间/依据已留痕）')
    loadAll()
  } catch (e) { /* ignore */ }
}

async function freeze(pref) {
  const { value } = await ElMessageBox.prompt('冻结原因（如研发人员占比下降）', '手工冻结', {
    inputValidator: (v) => (v && v.trim()) || '原因必填'
  })
  try {
    await prefFreeze(pref.id, { reason: value })
    ElMessage.success('已冻结（后续申报校验将阻断该优惠）')
    loadAll()
  } catch (e) { /* ignore */ }
}

async function revoke(pref) {
  const { value } = await ElMessageBox.prompt(
    '补税风险评估说明（政策废止 → 自动终止适用，评估说明必填留痕）', '政策废止终止', {
      inputValidator: (v) => (v && v.trim()) || '评估说明必填'
    })
  try {
    await prefRevoke(pref.id, { riskAssessNote: value })
    ElMessage.success('已终止并留痕补税风险评估')
    loadAll()
  } catch (e) { /* ignore */ }
}

onMounted(loadAll)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.mb8 { margin-bottom: 8px; }
.warn-title { font-weight: 600; margin-bottom: 8px; }
</style>
