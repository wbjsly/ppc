<template>
  <div class="page">
    <el-card shadow="never" style="margin-bottom:12px">
      <div class="toolbar">
        <span class="title">假期余额（按类型分组，FEFO 序）</span>
        <div>
          <el-button type="warning" @click="grantScan">年假年度发放</el-button>
          <el-button @click="expireScan">年假当年清零</el-button>
          <el-button type="primary" @click="openSubmit">请假申请</el-button>
        </div>
      </div>
      <el-table :data="balanceRows" height="200" style="margin-bottom:12px">
        <el-table-column prop="typeCode" label="类型" width="120">
          <template #default="{ row }">{{ typeName(row.typeCode) }}</template>
        </el-table-column>
        <el-table-column prop="total" label="可用合计" width="110" />
        <el-table-column label="批次明细（FEFO）" min-width="400">
          <template #default="{ row }">
            <el-tag v-for="b in row.batches" :key="b.id" size="small" style="margin:2px">
              {{ b.remain }}{{ b.qtyUnit === 'HOUR' ? 'h' : '天' }}（至 {{ b.expiredAt }}）
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-table :data="applies" height="200">
        <el-table-column prop="lvNo" label="单号" width="170" />
        <el-table-column prop="empName" label="姓名" width="100" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ typeName(row.typeCode) }}</template>
        </el-table-column>
        <el-table-column label="起止" width="200">
          <template #default="{ row }">{{ row.startDate }} ~ {{ row.endDate }}</template>
        </el-table-column>
        <el-table-column prop="duration" label="时长" width="70" />
        <el-table-column label="扣款" width="100">
          <template #default="{ row }">{{ row.deductAmount != null ? '¥' + row.deductAmount : '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ lvStatus(row.status) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <template v-if="row.status === 'DRAFT'">
              <el-button link type="success" size="small" @click="approve(row, true)">通过</el-button>
              <el-button link type="danger" size="small" @click="approve(row, false)">驳回</el-button>
            </template>
            <el-button v-else-if="row.status === 'APPROVED'" link type="warning" size="small"
                       @click="cancel(row)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 假期类型规则 -->
    <el-card shadow="never">
      <div class="toolbar"><span class="title">假期类型规则（参数化）</span></div>
      <el-table :data="types">
        <el-table-column prop="name" label="类型" width="110" />
        <el-table-column label="消耗余额" width="90">
          <template #default="{ row }">{{ row.needBalance === 'Y' ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column prop="dailyDeductionRate" label="日扣减系数" width="110" />
        <el-table-column prop="statutoryDays" label="法定上限" width="100" />
        <el-table-column label="计薪" width="80">
          <template #default="{ row }">{{ row.paidFlag === 'Y' ? '是' : '否' }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 请假申请 -->
    <el-dialog v-model="submitVisible" title="请假申请" width="480px">
      <el-form label-width="90px">
        <el-form-item label="假期类型">
          <el-select v-model="form.typeCode" style="width:100%">
            <el-option v-for="t in types" :key="t.typeCode" :label="t.name" :value="t.typeCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="起止日期">
          <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <el-form-item label="事由"><el-input v-model="form.reason" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="代理人"><el-input v-model="form.delegate" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitVisible=false">取消</el-button>
        <el-button type="primary" @click="doSubmit">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  leaveTypes, leaveBalances, annualGrantScan, annualExpireScan,
  leaveSubmit, leaveApprove, leaveCancel, leaveApplyList
} from '@/api/hr/attendance'

const types = ref([])
const balances = ref({})
const applies = ref([])
const submitVisible = ref(false)
const dateRange = ref([])
const form = ref({ typeCode: '', reason: '', delegate: '' })

const balanceRows = computed(() => {
  const byType = balances.value.byType || {}
  const totals = balances.value.totals || {}
  return Object.keys(byType).map(code => ({
    typeCode: code,
    total: totals[code],
    batches: byType[code]
  }))
})

const typeName = code => types.value.find(t => t.typeCode === code)?.name || code
const lvStatus = s => ({ DRAFT: '待审批', APPROVING: '审批中', APPROVED: '已批准', REJECTED: '已驳回', CANCELLED: '已取消' }[s] || s)

async function load() {
  const [t, b, a] = await Promise.all([leaveTypes(), leaveBalances({}), leaveApplyList({})])
  types.value = t.data || []
  balances.value = b.data || {}
  applies.value = a.data || []
}

function openSubmit() {
  form.value = { typeCode: '', reason: '', delegate: '' }
  dateRange.value = []
  submitVisible.value = true
}
async function doSubmit() {
  if (!form.value.typeCode || !dateRange.value?.length) return ElMessage.warning('类型与日期必选')
  try {
    await leaveSubmit({
      ...form.value,
      startDate: dateRange.value[0],
      endDate: dateRange.value[1]
    })
    ElMessage.success('请假已提交'); submitVisible.value = false; load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function approve(row, ok) {
  try {
    await leaveApprove(row.id, { approve: ok })
    ElMessage.success(ok ? '已批准（FEFO 扣减）' : '已驳回'); load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function cancel(row) {
  try {
    await leaveCancel(row.id, '计划变更')
    ElMessage.success('已取消（扣减回滚留痕）'); load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function grantScan() {
  const out = await annualGrantScan()
  ElMessage.success(`年假发放：${out.data?.granted} 人，跳过 ${out.data?.skipped}（同年幂等）`); load()
}
async function expireScan() {
  const out = await annualExpireScan()
  ElMessage.success(`年假清零：${out.data?.expired} 批（留痕）`); load()
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
