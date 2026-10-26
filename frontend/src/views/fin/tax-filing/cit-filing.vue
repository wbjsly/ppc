<template>
  <div class="page">
    <div class="head">
      <span class="title">企业所得税季报预缴（累计利润 × 税率 + 手工调整项）</span>
      <div>
        <el-input v-model="period" placeholder="所属期 yyyyMM" style="width: 130px; margin-right: 8px" size="small" />
        <el-button size="small" @click="load">查询</el-button>
        <el-button type="primary" size="small" @click="openGenerate">生成预缴申报</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" class="mb8"
      title="税基 = 当年累计利润总额（总账 6xxx 取数）+ 手工纳税调整项汇总；应补退 = 累计应纳 − 已预缴（负值归零不退）。不做纳税调整明细（偏差记录）" />

    <el-table :data="rows" size="small" border>
      <el-table-column prop="returnNo" label="申报单号" width="180" />
      <el-table-column prop="period" label="所属期" width="80" />
      <el-table-column prop="citBase" label="累计利润" width="110" />
      <el-table-column prop="citAdjust" label="调整项" width="90" />
      <el-table-column label="税率" width="70">
        <template #default="{ row }">{{ row.citRate ? (Number(row.citRate) * 100).toFixed(0) + '%' : '—' }}</template>
      </el-table-column>
      <el-table-column prop="citCumPayable" label="累计应纳" width="100" />
      <el-table-column prop="citPrepaid" label="已预缴" width="90" />
      <el-table-column prop="citDue" label="本期应补退" width="100" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PAID' ? 'success' : row.status === 'REJECTED' ? 'danger' : 'warning'" size="small">
            {{ { DRAFT: '草稿', PENDING_APPROVAL: '审批中', APPROVED: '已审批', SUBMITTED: '已提交', FILED: '已申报', PAID: '已缴款', REJECTED: '已驳回', OVERDUE: '逾期' }[row.status] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DRAFT'" link type="primary" size="small" @click="doSubmit(row)">提交审批</el-button>
          <el-button v-if="row.lockFlag === '1'" link type="success" size="small" @click="openRegister(row)">登记回执/缴款</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 生成预缴申报 -->
    <el-dialog v-model="generateVisible" title="生成所得税预缴申报" width="480px">
      <el-form label-width="130px" size="small">
        <el-form-item label="所属期 yyyyMM"><el-input v-model="generateForm.period" style="width: 140px" /></el-form-item>
        <el-form-item label="纳税调整项汇总"><el-input-number v-model="generateForm.citAdjust" :precision="2" :step="10000" /></el-form-item>
        <el-form-item label="年内已预缴"><el-input-number v-model="generateForm.citPrepaid" :precision="2" :step="10000" /></el-form-item>
        <el-form-item label="利润总额（手工兜底）">
          <el-input-number v-model="generateForm.citBase" :precision="2" :step="100000" />
          <div class="muted" style="font-size: 12px">总账无损益数据时才填；与账载不一致将按 C-4.14-04 阻断</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="generateVisible = false">取消</el-button>
        <el-button type="primary" @click="doGenerate">生成</el-button>
      </template>
    </el-dialog>

    <!-- 回执/缴款登记（同增值税链） -->
    <el-dialog v-model="registerVisible" title="回执/缴款登记（离线留桩）" width="480px">
      <el-form label-width="100px" size="small">
        <el-form-item label="提交日期"><el-date-picker v-model="registerForm.submitDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="回执编号"><el-input v-model="registerForm.receiptNo" /></el-form-item>
        <el-form-item label="申报成功日"><el-date-picker v-model="registerForm.fileDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="缴款日期"><el-date-picker v-model="registerForm.payDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="缴款金额"><el-input-number v-model="registerForm.payAmount" :precision="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" @click="doRegister">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { taxGenerateCit, taxSubmit, taxRegister, taxPage } from '@/api/fin/tax-filing'

const period = ref(new Date().toISOString().slice(0, 7).replace('-', ''))
const rows = ref([])
const generateVisible = ref(false)
const generateForm = ref({})
const registerVisible = ref(false)
const registerRow = ref(null)
const registerForm = ref({})

async function load() {
  try {
    const { data } = await taxPage({ size: 200, period: period.value, taxType: 'CIT' })
    rows.value = data.records || data
  } catch (e) { /* ignore */ }
}

function openGenerate() {
  generateForm.value = { period: period.value, citAdjust: 0, citPrepaid: 0, citBase: undefined }
  generateVisible.value = true
}

async function doGenerate() {
  try {
    const { data } = await taxGenerateCit(generateForm.value)
    ElMessage.success(`预缴申报已生成：${data.returnNo}（本期应补 ${data.citDue}）`)
    generateVisible.value = false
    load()
  } catch (e) { /* 422 已提示 */ }
}

async function doSubmit(row) {
  try {
    await taxSubmit(row.id)
    ElMessage.success('已提交三级审批')
    load()
  } catch (e) { /* ignore */ }
}

function openRegister(row) {
  registerRow.value = row
  registerForm.value = { submitDate: '', receiptNo: '', fileDate: '', payDate: '', payAmount: row.citDue }
  registerVisible.value = true
}

async function doRegister() {
  try {
    await taxRegister(registerRow.value.id, registerForm.value)
    ElMessage.success('登记成功')
    registerVisible.value = false
    load()
  } catch (e) { /* ignore */ }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 12px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.title { font-size: 15px; font-weight: 600; }
.mb8 { margin-bottom: 8px; }
.muted { color: #909399; }
</style>
