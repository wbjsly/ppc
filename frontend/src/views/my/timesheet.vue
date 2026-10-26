<template>
  <div class="my-timesheet">
    <el-card style="margin-bottom: 12px;">
      <div class="toolbar">
        <span class="date-label">填报日期</span>
        <el-date-picker v-model="workDate" type="date" value-format="YYYY-MM-DD"
          :disabled-date="d => d > new Date()" style="width: 180px;" @change="loadDay" />
        <el-tag v-if="today.exists" :type="statusType(today.sheet?.status)" size="large">
          {{ statusText(today.sheet?.status) }} · 合计 {{ today.sheet?.totalHours }}h
        </el-tag>
        <el-tag v-else type="info" size="large">未填报</el-tag>
        <el-button type="primary" :disabled="locked" @click="openAdd">添加明细</el-button>
        <el-button type="success" :disabled="locked || !lines.length" @click="saveAll">保存</el-button>
        <el-button type="warning" :disabled="!canSubmit" @click="submit">提交</el-button>
      </div>
      <div class="hint">
        0.5 小时步长 · 当日合计 ≤24h · 须关联 WBS 任务 · 本周/上周可正常补录（自动打补录标），
        超 2 周将自动发起 PMO 补录审批，超 4 周不可填报。
      </div>
    </el-card>

    <el-card>
      <el-table :data="lines" stripe>
        <el-table-column label="项目" width="220">
          <template #default="{ row }">
            <el-select v-model="row.projectId" filterable placeholder="选择项目" :disabled="locked"
              style="width: 100%;" @change="onProjectChange(row)">
              <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="p.name" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="WBS 任务" width="240">
          <template #default="{ row }">
            <el-select v-model="row.wbsId" filterable placeholder="选择任务" :disabled="locked"
              style="width: 100%;">
              <el-option v-for="t in tasksOf(row)" :key="t.id" :value="t.id"
                :label="`${t.wbsCode || ''} ${t.name}`" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="工时(h)" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.hours" :min="0.5" :max="24" :step="0.5"
              :precision="1" :disabled="locked" style="width: 110px;" />
          </template>
        </el-table-column>
        <el-table-column label="工作内容" min-width="220">
          <template #default="{ row }">
            <el-input v-model="row.workContent" maxlength="500" :disabled="locked" placeholder="工作内容摘要（必填）" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ $index }">
            <el-button link type="danger" :disabled="locked" @click="lines.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="total-line">合计：<b>{{ totalHours }}</b> h / 24 h</div>
    </el-card>

    <!-- 我的历史 -->
    <el-card style="margin-top: 12px;">
      <template #header><b>我的历史</b></template>
      <el-table :data="history" stripe>
        <el-table-column prop="workDate" label="日期" width="110" />
        <el-table-column prop="totalHours" label="合计(h)" width="90" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="补录" width="70">
          <template #default="{ row }">{{ row.isBackfill === '1' ? '补录' : '—' }}</template>
        </el-table-column>
        <el-table-column prop="rejectReason" label="驳回原因" min-width="180" show-overflow-tooltip />
        <el-table-column label="明细" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            {{ (row.lines || []).map(l => `${l.wbsName || l.wbsId} ${l.hours}h`).join('；') }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { myToday, myList, saveTimesheet, submitTimesheet, pmProjects, wbsTasks } from '@/api/pm/timesheet'

const workDate = ref(new Date().toISOString().slice(0, 10))
const today = ref({})
const history = ref([])
const projects = ref([])
const allTasks = ref([])   // {projectId, tasks[]}
const lines = ref([])

const locked = computed(() => ['SUBMITTED', 'APPROVED', 'BACKFILL'].includes(today.value.sheet?.status))
const canSubmit = computed(() =>
  today.value.exists && ['DRAFT', 'REJECTED'].includes(today.value.sheet?.status) && lines.value.length > 0)
const totalHours = computed(() =>
  lines.value.reduce((s, l) => s + (Number(l.hours) || 0), 0).toFixed(1))

onMounted(async () => {
  const { data } = await pmProjects()
  projects.value = (data || []).filter(p => p.status === 'ACTIVE')
  await Promise.all(projects.value.map(async p => {
    const { data: tree } = await wbsTasks(p.id)
    allTasks.value.push({ projectId: p.id, tasks: (tree || []).filter(n => n.level === 3) })
  }))
  await loadDay()
})

async function loadDay() {
  const { data } = await myToday()
  // myToday 恒为"今日"；切换日期时改用列表口径
  if (workDate.value === new Date().toISOString().slice(0, 10)) {
    today.value = data || {}
  } else {
    const { data: list } = await myList({ dateFrom: workDate.value, dateTo: workDate.value })
    const found = (list || [])[0]
    today.value = { exists: !!found, sheet: found }
  }
  lines.value = today.value.sheet ? JSON.parse(JSON.stringify(today.value.sheet.lines || [])) : []
  const { data: hist } = await myList({})
  history.value = hist || []
}

function tasksOf(row) {
  return allTasks.value.find(g => g.projectId === row.projectId)?.tasks || []
}

function onProjectChange(row) {
  row.wbsId = ''
}

function openAdd() {
  lines.value.push({ projectId: '', wbsId: '', hours: 1, workContent: '' })
}

async function saveAll() {
  if (!lines.value.length) { ElMessage.error('至少一条明细'); return }
  try {
    await saveTimesheet({
      id: today.value.sheet?.id,
      workDate: workDate.value,
      lines: lines.value
    })
    ElMessage.success('已保存（草稿）')
    await loadDay()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  }
}

async function submit() {
  try {
    await ElMessageBox.confirm('提交后进入主管审核（或补录审批），确认提交？', '提交确认', { type: 'warning' })
  } catch { return }
  try {
    const { data } = await submitTimesheet(today.value.sheet.id)
    if (data?.hint) {
      ElMessage.warning(data.hint)
    } else {
      ElMessage.success('已提交，等待审核')
    }
    await loadDay()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  }
}

function statusText(s) {
  return { DRAFT: '草稿', BACKFILL: '补录审批中', SUBMITTED: '待审核', APPROVED: '已通过', REJECTED: '已驳回' }[s] || s
}
function statusType(s) {
  return { DRAFT: 'info', BACKFILL: 'warning', SUBMITTED: 'warning', APPROVED: 'success', REJECTED: 'danger' }[s] || 'info'
}
</script>

<style scoped>
.toolbar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.date-label { color: #606266; }
.hint { color: #909399; font-size: 12px; margin-top: 8px; line-height: 1.7; }
.total-line { margin-top: 10px; text-align: right; color: #606266; }
</style>
