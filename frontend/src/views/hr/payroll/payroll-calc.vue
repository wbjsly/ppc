<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">薪资核算（10.5.3）</span>
        <div>
          <el-input v-model="period" placeholder="核算期 如 202610" clearable style="width:140px;margin-right:8px" @change="load" />
          <el-button @click="doPreview">数据准备</el-button>
          <el-button type="primary" :loading="generating" @click="doGenerate">核算生成批次</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-alert type="info" :closable="false" style="margin-bottom:12px"
                title="应发=基本工资+绩效+补贴+加班工资（取固化金额）；实发=应发-社保个人-公积金个人-个税-其他扣款；负数 L1 阻断，环比>20% L4 待确认；考勤挂起员工暂停核算（C-4.7-01）" />

      <el-descriptions v-if="prep" :column="4" border style="margin-bottom:12px">
        <el-descriptions-item label="核算期">{{ prep.period }}</el-descriptions-item>
        <el-descriptions-item label="在职人数">{{ prep.empCount }}</el-descriptions-item>
        <el-descriptions-item label="考勤挂起">{{ prep.holdCount }} 人</el-descriptions-item>
        <el-descriptions-item label="已有批次">{{ prep.hasActiveRun ? '是（须作废重建）' : '无' }}</el-descriptions-item>
      </el-descriptions>

      <!-- 批次列表 -->
      <el-table :data="runs" height="180" style="margin-bottom:12px" @row-click="loadItems">
        <el-table-column prop="runNo" label="批次号" width="170" />
        <el-table-column prop="period" label="核算期" width="90" />
        <el-table-column prop="empCount" label="人数" width="70" />
        <el-table-column prop="grossTotal" label="应发合计" width="120" />
        <el-table-column prop="deductTotal" label="扣款合计" width="120" />
        <el-table-column prop="netTotal" label="实发合计" width="120" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ row.runType === 'CORRECTION' ? '更正' : '正常' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="{ DRAFT: 'info', SUBMITTING: 'warning', APPROVED: 'success', REJECTED: 'danger', VOID: 'danger' }[row.status]">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT' || row.status === 'REJECTED'" link type="primary" size="small"
                       @click.stop="doSubmit(row)">提交审批</el-button>
            <el-button v-if="row.status === 'DRAFT' || row.status === 'REJECTED' || row.status === 'SUBMITTING'" link type="danger" size="small"
                       @click.stop="doVoid(row)">作废重建</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 明细快照 -->
      <el-table :data="items" height="300" v-if="items.length">
        <el-table-column prop="empNo" label="工号" width="100" />
        <el-table-column prop="empName" label="姓名" width="90" />
        <el-table-column prop="baseSalary" label="基本" width="90" />
        <el-table-column label="绩效" width="110">
          <template #default="{ row }">{{ row.perfAmount }}<span v-if="row.perfGrade">（{{ row.perfGrade }}×{{ row.perfCoef }}）</span></template>
        </el-table-column>
        <el-table-column prop="allowance" label="补贴" width="80" />
        <el-table-column prop="overtimeWage" label="加班工资" width="90" />
        <el-table-column prop="gross" label="应发" width="100" />
        <el-table-column label="扣款" width="180">
          <template #default="{ row }">社保{{ row.socialEe }} 公积金{{ row.providentEe }} 个税{{ row.iit }} 其他{{ row.otherDeduct }}</template>
        </el-table-column>
        <el-table-column prop="net" label="实发" width="100" />
        <el-table-column label="标记" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.negFlag === '1'" type="danger" size="small">负数</el-tag>
            <el-tag v-else-if="row.volatileFlag === '1'" type="warning" size="small">波动</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="perfNote" label="备注" min-width="100" />
      </el-table>
    </el-card>

    <!-- 补扣项 -->
    <el-card shadow="never" style="margin-top:12px">
      <div class="toolbar">
        <span class="title">补扣项（{{ period }}）</span>
        <el-button type="primary" size="small" @click="openAdj">录入</el-button>
      </div>
      <el-table :data="adjustments" height="180">
        <el-table-column prop="empId" label="员工ID" width="200" />
        <el-table-column prop="itemType" label="类型" width="140" />
        <el-table-column prop="amount" label="金额（正=补贴 负=扣款）" width="160" />
        <el-table-column prop="reason" label="事由" min-width="140" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">{{ row.locked === '1' ? '已锁' : '可改' }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="adjVisible" title="录入补扣项" width="420px">
      <el-form label-width="90px">
        <el-form-item label="员工ID"><el-input v-model="adjForm.empId" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="adjForm.itemType" style="width:100%">
            <el-option label="餐补" value="MEAL_ALLOW" />
            <el-option label="交通补贴" value="TRANSPORT_ALLOW" />
            <el-option label="全勤奖" value="ATTEND_BONUS" />
            <el-option label="迟到罚款（负）" value="LATE_FINE" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额"><el-input-number v-model="adjForm.amount" :step="50" /></el-form-item>
        <el-form-item label="事由"><el-input v-model="adjForm.reason" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjVisible = false">取消</el-button>
        <el-button type="primary" @click="doSaveAdj">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { payPreview, payGenerate, paySubmit, payVoid, payRunList, payRunItems, payAdjustmentList, savePayAdjustment } from '@/api/hr/payroll'

const now = new Date()
const period = ref(`${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}`)
const prep = ref(null)
const runs = ref([])
const items = ref([])
const adjustments = ref([])
const generating = ref(false)
const adjVisible = ref(false)
const adjForm = ref({ empId: '', itemType: 'MEAL_ALLOW', amount: 300, reason: '' })

async function load() {
  const [r, a] = await Promise.all([
    payRunList({ period: period.value }),
    payAdjustmentList({ period: period.value })
  ])
  runs.value = r.data || []
  adjustments.value = a.data || []
  items.value = []
}
async function doPreview() {
  const { data } = await payPreview(period.value)
  prep.value = data
  ElMessage.success(`数据准备完成：${data.empCount} 人（挂起 ${data.holdCount}）`)
}
async function doGenerate() {
  const { value } = await ElMessageBox.prompt(
    `核算 ${period.value} 并生成 PayrollRun？（生成后明细冻结，须作废重建才能改）`,
    '确认生成', { type: 'warning', inputValue: period.value, inputPattern: /^\d{6}$/, inputErrorMessage: 'yyyyMM' })
  generating.value = true
  try {
    const { data } = await payGenerate(value)
    ElMessage.success(`批次 ${data.runNo} 已生成（${data.empCount} 人，实发合计 ${data.netTotal}）`)
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
  finally { generating.value = false }
}
async function doSubmit(row) {
  try {
    await paySubmit(row.id, {})
    ElMessage.success('已提交审批'); load()
  } catch (e) {
    const msg = e?.response?.data?.msg || e?.message
    if (msg && msg.includes('波动')) {
      await ElMessageBox.confirm(msg + '，确认继续提交？', 'L4 波动确认', { type: 'warning' })
      await paySubmit(row.id, { confirmVolatile: true })
      ElMessage.success('已确认波动并提交'); load()
    } else {
      ElMessage.error(msg)
    }
  }
}
async function doVoid(row) {
  const { value } = await ElMessageBox.prompt('作废原因', '作废重建', { inputPattern: /\S+/, inputErrorMessage: '原因必填' })
  try {
    await payVoid(row.id, { reason: value })
    ElMessage.success('已作废（补扣项解锁）'); load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function loadItems(row) {
  const { data } = await payRunItems(row.id)
  items.value = data || []
}
function openAdj() { adjForm.value = { empId: '', itemType: 'MEAL_ALLOW', amount: 300, reason: '' }; adjVisible.value = true }
async function doSaveAdj() {
  if (!adjForm.value.empId) return ElMessage.warning('员工ID必填')
  try {
    await savePayAdjustment({ ...adjForm.value, period: period.value })
    ElMessage.success('已录入'); adjVisible.value = false; load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
