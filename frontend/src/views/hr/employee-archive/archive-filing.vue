<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>建档登记（10.1.1）—— 六步状态机</span>
          <el-button type="primary" size="small" @click="createOnb">发起建档</el-button>
        </div>
      </template>
      <el-alert :closable="false" type="info" show-icon class="mb8"
        title="FR-4.7-1 六步：录用单接收 → 信息登记（人工核验留桩）→ 证件核验（离职证明 L1）→ 合同签订（试用期合规 L1）→ 生成主档（工号/身份证唯一 L1）→ 社保增员留桩" />

      <el-table :data="onbs" size="small" border>
        <el-table-column prop="onbNo" label="建档单号" width="130" />
        <el-table-column prop="offerNo" label="录用单" width="130" />
        <el-table-column prop="step" label="当前步骤" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'DONE' ? 'success' : row.status === 'RETURNED' ? 'warning' : 'primary'">
              {{ stepName(row.step) }}{{ row.status === 'DONE' ? '（完成）' : '' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">{{ statusName(row.status) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <template v-if="row.status !== 'DONE'">
              <el-button link type="primary" size="small" @click="openAdvance(row)">推进</el-button>
              <el-button link type="warning" size="small" @click="doReturn(row)">退回</el-button>
            </template>
            <span v-else class="muted">已完成{{ row.empId ? '，主档已生成' : '' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 推进弹窗（按步骤动态表单） -->
    <el-dialog v-model="advVisible" :title="'推进步骤 ' + (advRow?.step || '') + '：' + stepName(advRow?.step)" width="640px">
      <!-- ①录用单 -->
      <template v-if="advRow?.step === 1">
        <el-form label-width="110px">
          <el-form-item label="录用单编号"><el-input v-model="form.offerNo" /></el-form-item>
          <el-form-item label="已审批通过"><el-switch v-model="form.offerApproved" /></el-form-item>
        </el-form>
      </template>
      <!-- ②信息登记 -->
      <template v-else-if="advRow?.step === 2">
        <el-form label-width="110px">
          <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
          <el-form-item label="身份证号"><el-input v-model="form.idCardNo" /></el-form-item>
          <el-form-item label="手机号"><el-input v-model="form.phone" /></el-form-item>
          <el-form-item label="银行卡号"><el-input v-model="form.bankCardNo" /></el-form-item>
          <el-form-item label="实名核验"><el-switch v-model="form.realnameCheck" /></el-form-item>
          <el-form-item label="四要素核验"><el-switch v-model="form.bank4Check" /></el-form-item>
          <div class="muted">外部接口不存在——以上为人工核验留桩（核验人/时间自动记录）</div>
        </el-form>
      </template>
      <!-- ③证件核验 -->
      <template v-else-if="advRow?.step === 3">
        <el-checkbox v-model="form.docIdCard">身份证正反面</el-checkbox>
        <el-checkbox v-model="form.docDegree">学历学位证书</el-checkbox>
        <el-checkbox v-model="form.docProof" class="ml16">离职证明（必备 L1）</el-checkbox>
        <el-checkbox v-model="form.docCheckup" class="ml16">体检报告</el-checkbox>
      </template>
      <!-- ④合同签订 -->
      <template v-else-if="advRow?.step === 4">
        <el-form label-width="110px">
          <el-form-item label="合同编号"><el-input v-model="form.contractNo" /></el-form-item>
          <el-form-item label="合同起止">
            <el-date-picker v-model="contractRange" type="daterange" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="试用期(月)"><el-input-number v-model="form.probationMonths" :min="0" :max="12" /></el-form-item>
          <div class="muted">试用期合规 L1：合同期 ≥3 年 → 试用期 ≤6 个月；&lt;3 年 → ≤2 个月</div>
        </el-form>
      </template>
      <!-- ⑤生成主档 -->
      <template v-else-if="advRow?.step === 5">
        <el-form label-width="110px">
          <el-form-item label="所属部门">
            <el-select v-model="form.deptId" style="width:100%">
              <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="岗位">
            <el-select v-model="form.posId" style="width:100%">
              <el-option v-for="p in positions" :key="p.id" :label="p.name" :value="p.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="入职日期"><el-date-picker v-model="form.hireDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <div class="muted">工号 EMP+年+4 位流水自动生成；身份证全库唯一，重复引导「重新入职」（rehire-probe）</div>
        </el-form>
      </template>
      <!-- ⑥社保增员 -->
      <template v-else-if="advRow?.step === 6">
        <el-form label-width="110px">
          <el-form-item label="缴纳地"><el-input v-model="form.socialPlace" /></el-form-item>
          <el-form-item label="参保类型"><el-input v-model="form.socialType" placeholder="五险一金" /></el-form-item>
          <div class="muted">社保接口留桩登记；完成后自动生成「入职开通账号」审批待办（HR 经理）</div>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="advVisible = false">取消</el-button>
        <el-button type="primary" @click="doAdvance">推进</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onbList, onbCreate, onbAdvance, onbReturn, deptList, posList } from '@/api/hr/employee-archive'

const onbs = ref([])
const depts = ref([])
const positions = ref([])
const advVisible = ref(false)
const advRow = ref(null)
const contractRange = ref([])
const form = ref({})

const STEP_NAMES = { 1: '①录用单接收', 2: '②信息登记', 3: '③证件核验', 4: '④合同签订', 5: '⑤生成主档', 6: '⑥社保增员' }
const stepName = (s) => STEP_NAMES[s] || '—'
const statusName = (s) => ({ IN_PROGRESS: '推进中', RETURNED: '已退回', DONE: '完成' }[s] || s)

async function loadAll() {
  try { onbs.value = (await onbList({})).data || [] } catch (e) { /* ignore */ }
  try { depts.value = (await deptList()).data || [] } catch (e) { /* ignore */ }
  try { positions.value = (await posList({})).data || [] } catch (e) { /* ignore */ }
}

async function createOnb() {
  const { value: offerNo } = await ElMessageBox.prompt('录用审批单编号', '发起建档', {
    inputValidator: (v) => (v && v.trim()) || '必填'
  })
  const { value: approved } = await ElMessageBox.confirm('录用单是否已审批通过？', '门禁确认',
    { confirmButtonText: '已通过', cancelButtonText: '未通过' }).then(() => true).catch(() => false)
  try {
    await onbCreate({ offerNo, offerApproved: approved ? '1' : '0' })
    ElMessage.success('建档单已创建（步骤①）')
    loadAll()
  } catch (e) { /* ignore */ }
}

function openAdvance(row) {
  advRow.value = row
  form.value = { offerApproved: false, realnameCheck: false, bank4Check: false,
    docProof: false, probationMonths: 3 }
  contractRange.value = []
  advVisible.value = true
}

async function doAdvance() {
  const step = advRow.value.step
  let payload = {}
  if (step === 1) payload = { offerApproved: form.value.offerApproved ? '1' : '0' }
  else if (step === 2) {
    payload = {
      candidateJson: JSON.stringify({ name: form.value.name, idCardNo: form.value.idCardNo,
        phone: form.value.phone, bankCardNo: form.value.bankCardNo }),
      realnameCheck: form.value.realnameCheck ? '1' : '0',
      bank4Check: form.value.bank4Check ? '1' : '0'
    }
  } else if (step === 3) {
    const docs = {}
    if (form.value.docIdCard) docs['身份证'] = { submitted: true }
    if (form.value.docDegree) docs['学历证书'] = { submitted: true }
    if (form.value.docProof) docs['离职证明'] = { submitted: true }
    if (form.value.docCheckup) docs['体检报告'] = { submitted: true }
    payload = { docsJson: JSON.stringify(docs) }
  } else if (step === 4) {
    payload = { contractJson: JSON.stringify({ contractNo: form.value.contractNo,
      contractStart: contractRange.value?.[0], contractEnd: contractRange.value?.[1],
      probationMonths: form.value.probationMonths }) }
  } else if (step === 5) {
    payload = { deptId: form.value.deptId, posId: form.value.posId, hireDate: form.value.hireDate }
  } else if (step === 6) {
    payload = { socialJson: JSON.stringify({ place: form.value.socialPlace,
      type: form.value.socialType, date: new Date().toISOString().slice(0, 10) }) }
  }
  try {
    await onbAdvance(advRow.value.id, payload)
    ElMessage.success('已推进')
    advVisible.value = false
    loadAll()
  } catch (e) { /* ignore */ }
}

async function doReturn(row) {
  const { value } = await ElMessageBox.prompt('退回原因', '退回上一步', {
    inputValidator: (v) => (v && v.trim()) || '原因必填'
  })
  try {
    await onbReturn(row.id, value)
    ElMessage.success('已退回')
    loadAll()
  } catch (e) { /* ignore */ }
}

onMounted(loadAll)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.mb8 { margin-bottom: 8px; }
.ml16 { margin-left: 16px; }
.muted { color: #909399; font-size: 12px; }
</style>
