<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>培训计划（10.9.1，编制即发布 · 任务展开到人 · 完成率）</span>
          <div>
            <el-select v-model="year" size="small" style="width: 110px; margin-right: 8px" @change="load">
              <el-option v-for="y in years" :key="y" :label="y + ' 年'" :value="y" />
            </el-select>
            <el-button type="primary" size="small" @click="openCreate">新建计划</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" size="small" border>
        <el-table-column prop="plan.planNo" label="计划号" width="120" />
        <el-table-column prop="plan.courseName" label="课程" min-width="160" />
        <el-table-column label="对象" width="160">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ row.plan.targetType === 'DEPT' ? '部门' : '人员' }}</el-tag>
            <span class="muted"> {{ targetName(row.plan) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="期限" width="110">
          <template #default="{ row }">{{ row.plan.dueDate || '—' }}</template>
        </el-table-column>
        <el-table-column label="完成率" width="150">
          <template #default="{ row }">
            <el-progress :percentage="Number(row.stats.rate)" :stroke-width="10"
              :status="Number(row.stats.rate) === 100 ? 'success' : undefined" />
            <div class="muted">{{ row.stats.completed }}/{{ row.stats.should }} 人</div>
          </template>
        </el-table-column>
        <el-table-column label="费用" width="130">
          <template #default="{ row }">
            <div v-if="row.plan.budget">预算 ¥{{ row.plan.budget }}</div>
            <div v-if="row.plan.actualCost">实际 ¥{{ row.plan.actualCost }}</div>
            <span v-if="!row.plan.budget && !row.plan.actualCost" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.plan.status === 'PUBLISHED' ? 'success' : 'info'">
              {{ row.plan.status === 'PUBLISHED' ? '执行中' : '已作废' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row.plan)">任务明细</el-button>
            <el-button v-if="row.plan.status === 'PUBLISHED'" link type="primary" size="small"
              @click="openCost(row.plan)">费用登记</el-button>
            <el-button v-if="row.plan.status === 'PUBLISHED'" link type="danger" size="small"
              @click="doCancel(row.plan)">作废</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建计划 -->
    <el-dialog v-model="createVisible" title="新建培训计划（编制即发布）" width="560px">
      <el-form label-width="90px" :model="form">
        <el-form-item label="课程名称"><el-input v-model="form.courseName" /></el-form-item>
        <el-form-item label="培训方式">
          <el-select v-model="form.trainType" style="width: 160px">
            <el-option label="内训" value="内训" /><el-option label="外训" value="外训" />
            <el-option label="线上" value="线上" />
          </el-select>
        </el-form-item>
        <el-form-item label="培训对象">
          <el-radio-group v-model="form.targetType" style="margin-right: 8px">
            <el-radio value="DEPT">按部门</el-radio><el-radio value="MEMBERS">指定人员</el-radio>
          </el-radio-group>
          <el-select v-if="form.targetType === 'DEPT'" v-model="form.targetValue" placeholder="选择部门"
            style="width: 200px">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <el-select v-else v-model="memberIds" multiple placeholder="选择员工" style="width: 260px">
            <el-option v-for="e in emps" :key="e.id" :label="`${e.empNo} ${e.name}`" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="计划日期">
          <el-date-picker v-model="form.dateRange" type="daterange" value-format="YYYY-MM-DD"
            start-placeholder="开始" end-placeholder="结束" />
        </el-form-item>
        <el-form-item label="完成期限">
          <el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD" />
          <div class="muted">期限前 3 天与逾期自动催办（参数 TRAIN_TASK_REMIND_DAYS）</div>
        </el-form-item>
        <el-form-item label="计划学时"><el-input-number v-model="form.planHours" :min="0.5" :step="0.5" /></el-form-item>
        <el-form-item label="合格线"><el-input-number v-model="form.passScore" :min="0" :max="100" />
          <span class="muted">缺省 TRAIN_PASS_SCORE=60</span></el-form-item>
        <el-form-item label="关联技能">
          <el-input v-model="form.skillCode" placeholder="技能编码（可空，通过后写入技能矩阵）" style="width: 220px" />
          <el-input v-model="form.skillLevel" placeholder="等级" style="width: 90px; margin-left: 8px" />
        </el-form-item>
        <el-form-item label="预算(元)"><el-input-number v-model="form.budget" :min="0" :precision="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">发布</el-button>
      </template>
    </el-dialog>

    <!-- 任务明细 -->
    <el-dialog v-model="detailVisible" :title="`任务明细 - ${detail.plan?.courseName || ''}`" width="820px">
      <div class="mb8">
        <el-tag size="small" type="info">应参训 {{ detail.stats?.should }} 人</el-tag>
        <el-tag size="small" type="success" style="margin-left: 6px">完成 {{ detail.stats?.completed }}</el-tag>
        <el-tag size="small" type="danger" style="margin-left: 6px">未通过 {{ detail.stats?.failed }}</el-tag>
        <span class="muted" style="margin-left: 10px">完成率 {{ detail.stats?.rate }}%</span>
        <span v-if="detail.plan?.changeLogJson" class="muted" style="margin-left: 10px">
          变更留痕 {{ JSON.parse(detail.plan.changeLogJson).length }} 条</span>
      </div>
      <el-table :data="detail.tasks || []" size="small" border max-height="380">
        <el-table-column prop="task.empNo" label="工号" width="90" />
        <el-table-column prop="task.empName" label="姓名" width="90" />
        <el-table-column label="自助确认" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.task.confirmAt" size="small">已确认</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="taskTag(row.task.status)">{{ taskStatusName(row.task.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="task.score" label="成绩" width="70" />
        <el-table-column prop="task.actualHours" label="学时" width="70" />
        <el-table-column label="登记" width="200">
          <template #default="{ row }">
            <el-button v-if="detail.plan?.status === 'PUBLISHED' && row.task.status !== 'CANCELLED'"
              link type="primary" size="small" @click="openResult(row.task)">登记结果</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer><el-button @click="detailVisible = false">关闭</el-button></template>
    </el-dialog>

    <!-- 结果登记 -->
    <el-dialog v-model="resultVisible" title="结果登记（完成判定以 HR 登记为准）" width="420px">
      <el-form label-width="90px">
        <el-form-item label="出勤">
          <el-select v-model="resultForm.attend" style="width: 140px">
            <el-option label="正常" value="正常" /><el-option label="迟到" value="迟到" />
            <el-option label="缺勤" value="缺勤" />
          </el-select>
        </el-form-item>
        <el-form-item label="考试成绩"><el-input-number v-model="resultForm.score" :min="0" :max="100" /></el-form-item>
        <el-form-item label="实际学时"><el-input-number v-model="resultForm.actualHours" :min="0" :step="0.5" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="resultForm.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resultVisible = false">取消</el-button>
        <el-button type="primary" @click="doResult">登记</el-button>
      </template>
    </el-dialog>

    <!-- 费用登记 -->
    <el-dialog v-model="costVisible" :title="`费用登记 - ${costPlan?.courseName || ''}`" width="440px">
      <el-form label-width="100px">
        <el-form-item label="实际费用(元)"><el-input-number v-model="costAmount" :min="0" :precision="2" /></el-form-item>
      </el-form>
      <el-alert :closable="false" type="info" show-icon
        title="登记后自动生成计提凭证（借 管理费用-职工教育经费 / 贷 应付职工薪酬-职工教育经费）；金额变动自动红冲重开，作废计划自动冲销" />
      <template #footer>
        <el-button @click="costVisible = false">取消</el-button>
        <el-button type="primary" @click="doCost">登记并生成凭证</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  planList, planCreate, planCancel, planDetail, trainResult, planCost
} from '@/api/hr/training'
import { deptList } from '@/api/hr/employee-archive'
import { empList } from '@/api/hr/employee-archive'

const year = ref(new Date().getFullYear())
const years = [new Date().getFullYear() - 1, new Date().getFullYear(), new Date().getFullYear() + 1]
const rows = ref([])
const depts = ref([])
const emps = ref([])
const memberIds = ref([])

const createVisible = ref(false)
const form = ref({})
const detailVisible = ref(false)
const detail = ref({})
const resultVisible = ref(false)
const resultForm = ref({})
const resultTaskId = ref(null)
const costVisible = ref(false)
const costPlan = ref(null)
const costAmount = ref(0)

const taskStatusName = (s) => ({ PENDING: '待参加', CONFIRMED: '已确认', COMPLETED: '已完成',
  FAILED: '未通过', CANCELLED: '已取消' }[s] || s)
const taskTag = (s) => ({ PENDING: 'info', CONFIRMED: 'warning', COMPLETED: 'success',
  FAILED: 'danger', CANCELLED: 'info' }[s] || 'info')
const targetName = (p) => {
  if (p.targetType === 'DEPT') return depts.value.find(d => d.id === p.targetValue)?.name || p.targetValue
  const names = String(p.targetValue).split(',').map(id => emps.value.find(e => e.id === id)?.name || id)
  return names.length > 2 ? `${names.slice(0, 2).join('、')} 等 ${names.length} 人` : names.join('、')
}

async function load() {
  const [{ data }, { data: ds }, { data: es }] = await Promise.all([
    planList({ year: year.value }), deptList(), empList({})
  ])
  rows.value = data || []
  depts.value = ds || []
  emps.value = es || []
}

function openCreate() {
  form.value = { courseName: '', trainType: '内训', targetType: 'DEPT', targetValue: '',
    dateRange: null, dueDate: '', planHours: 8, passScore: 60, skillCode: '', skillLevel: '', budget: null }
  memberIds.value = []
  createVisible.value = true
}

async function doCreate() {
  if (!form.value.courseName || !form.value.dueDate) { ElMessage.warning('课程名称与完成期限必填'); return }
  const targetValue = form.value.targetType === 'DEPT'
    ? form.value.targetValue : memberIds.value.join(',')
  if (!targetValue) { ElMessage.warning('请选择培训对象'); return }
  try {
    const { data } = await planCreate({
      ...form.value,
      targetValue,
      planYear: year.value,
      dateFrom: form.value.dateRange?.[0],
      dateTo: form.value.dateRange?.[1]
    })
    ElMessage.success(`已发布，展开参训任务 ${data.expanded} 人`)
    createVisible.value = false
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

async function openDetail(plan) {
  const { data } = await planDetail(plan.id)
  detail.value = data
  detailVisible.value = true
}

function openResult(task) {
  resultTaskId.value = task.id
  resultForm.value = { attend: '正常', score: task.score ?? 80, actualHours: task.actualHours ?? 8, remark: task.remark || '' }
  resultVisible.value = true
}

async function doResult() {
  try {
    const { data } = await trainResult(resultTaskId.value, resultForm.value)
    ElMessage.success(`已登记：${taskStatusName(data.task.status)}（合格线 ${data.passScore}）`)
    resultVisible.value = false
    detail.value = await planDetail(detail.value.plan.id).then(r => r.data)
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

function openCost(plan) {
  costPlan.value = plan
  costAmount.value = Number(plan.actualCost || 0)
  costVisible.value = true
}

async function doCost() {
  try {
    const { data } = await planCost(costPlan.value.id, { actualCost: costAmount.value })
    const msg = { 0: '金额无变化', 1: '已生成计提凭证', 2: '已红冲原凭证并按新金额重开', 3: '已冲销（费用清零）' }[data.action]
    ElMessage.success(msg)
    costVisible.value = false
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

async function doCancel(plan) {
  try {
    await ElMessageBox.confirm(`作废计划「${plan.courseName}」？未完成任务将连带取消，已生成凭证将红字冲销。`,
      '作废确认', { type: 'warning' })
    await planCancel(plan.id)
    ElMessage.success('已作废')
    load()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e?.response?.data?.msg || e?.message) }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.mb8 { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
</style>
