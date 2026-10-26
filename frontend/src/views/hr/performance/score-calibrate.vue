<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">评分校准（10.10.3，自建状态机批量流转）</span>
        <div>
          <el-input v-model="period" placeholder="周期 如 2026H2" clearable style="width:140px;margin-right:8px" @change="load" />
          <el-button type="warning" :disabled="!sel.length" @click="doCalibrate">校准通过（{{ sel.length }}）</el-button>
          <el-button type="danger" plain :disabled="sel.length !== 1" @click="doReject">驳回重评</el-button>
          <el-button type="success" :disabled="!selC.length" @click="doConfirm">确认结果（{{ selC.length }}）</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-table :data="scores" height="440" @selection-change="onSel">
        <el-table-column type="selection" width="40" :selectable="r => ['SUBMITTED','CALIBRATING'].includes(r.status)" />
        <el-table-column prop="empNo" label="工号" width="110" />
        <el-table-column prop="empName" label="姓名" width="100" />
        <el-table-column prop="score" label="分值" width="70" />
        <el-table-column label="等级（可调整）" width="130">
          <template #default="{ row }">{{ row.grade }} ×{{ row.coef }}</template>
        </el-table-column>
        <el-table-column prop="period" label="周期" width="90" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="{ DRAFT: 'info', SUBMITTED: 'warning', CALIBRATING: 'warning', CALIBRATED: 'primary', CONFIRMED: 'success' }[row.status]">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="showLogs(row)">日志</el-button>
            <el-button v-if="row.status === 'CALIBRATED'" link type="success" size="small" @click="doConfirmOne(row)">确认</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="adjustVisible" title="校准（等级调整须填原因）" width="400px">
      <el-form label-width="90px">
        <el-form-item label="调整为等级">
          <el-select v-model="adjustGrade" clearable placeholder="不调整" style="width:140px">
            <el-option v-for="g in ['A','B','C','D']" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="adjustReason" type="textarea" :rows="2" placeholder="等级调整时必填（如：部门拉平）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="warning" @click="confirmAdjust">提交校准</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="logsVisible" :title="`校准日志（${logsScoreId}）`" width="560px">
      <el-table :data="logs" height="300">
        <el-table-column prop="opAt" label="时间" width="160" />
        <el-table-column prop="action" label="动作" width="100" />
        <el-table-column label="等级变动" width="110">
          <template #default="{ row }">{{ row.fromGrade || '—' }} → {{ row.toGrade || '—' }}</template>
        </el-table-column>
        <el-table-column prop="operator" label="操作人" width="110" />
        <el-table-column prop="reason" label="原因" min-width="120" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { scoreList, calibrate, confirmScores, scoreLogs } from '@/api/hr/performance'

const period = ref('')
const scores = ref([])
const sel = ref([])
const selC = ref([])
const adjustVisible = ref(false)
const adjustGrade = ref('')
const adjustReason = ref('')
const logsVisible = ref(false)
const logs = ref([])
const logsScoreId = ref('')

async function load() {
  const params = { status: 'SUBMITTED,CALIBRATING,CALIBRATED' }
  const { data } = await scoreList(period.value ? { period: period.value } : {})
  scores.value = (data || []).filter(r => r.status !== 'DRAFT')
}
function onSel(rows) {
  sel.value = rows.filter(r => ['SUBMITTED', 'CALIBRATING'].includes(r.status))
  selC.value = rows.filter(r => r.status === 'CALIBRATED')
}
function doCalibrate() { adjustVisible.value = true; adjustGrade.value = ''; adjustReason.value = '' }
async function confirmAdjust() {
  if (adjustGrade.value && !adjustReason.value.trim()) return ElMessage.warning('等级调整须填原因')
  try {
    const out = await calibrate({
      scoreIds: sel.value.map(r => r.id),
      action: 'CALIBRATE',
      toGrade: adjustGrade.value || null,
      reason: adjustReason.value || null
    })
    ElMessage.success(`校准 ${out.data?.processed} 条`)
    if (out.data?.errors?.length) ElMessage.warning(out.data.errors.join('；'))
    adjustVisible.value = false; load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function doReject() {
  const { value } = await ElMessageBox.prompt('驳回原因', '驳回重评', { inputPattern: /\S+/, inputErrorMessage: '原因必填' })
  const out = await calibrate({ scoreIds: [sel.value[0].id], action: 'REJECT', reason: value })
  ElMessage.success(`驳回 ${out.data?.processed} 条`); load()
}
async function doConfirm() {
  const out = await confirmScores(selC.value.map(r => r.id))
  ElMessage.success(`确认 ${out.data?.confirmed} 条`)
  if (out.data?.errors?.length) ElMessage.warning(out.data.errors.join('；'))
  load()
}
async function doConfirmOne(row) {
  const out = await confirmScores([row.id])
  if (out.data?.errors?.length) ElMessage.error(out.data.errors.join('；'))
  else { ElMessage.success('已确认'); load() }
}
async function showLogs(row) {
  const { data } = await scoreLogs(row.id)
  logs.value = data || []
  logsScoreId.value = row.empName || row.empId
  logsVisible.value = true
}
onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
