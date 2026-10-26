<template>
  <div class="project-progress">
    <el-card style="margin-bottom: 12px;">
      <div class="toolbar">
        <el-select v-model="projectId" placeholder="选择项目（留空=我的全部）" filterable clearable
          style="width: 320px;" @change="load">
          <el-option v-for="p in projects" :key="p.id" :value="p.id"
            :label="`${p.projectNo || p.name}（${p.status}）`" />
        </el-select>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </el-card>

    <!-- 完成率卡片 -->
    <el-row :gutter="12" style="margin-bottom: 12px;">
      <el-col :span="8">
        <el-card>
          <el-statistic title="里程碑总数" :value="board.total" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <el-statistic title="已完成（DONE）" :value="board.done"
            :value-style="{ color: '#67c23a' }" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <div class="rate-wrap">
            <div class="rate-title">完成率</div>
            <el-progress type="dashboard" :percentage="ratePercent" :width="120" />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 里程碑进度表（偏差标红 >5 / 升级标紫 >15） -->
    <el-card>
      <el-table :data="board.rows" v-loading="loading" stripe
        :row-class-name="rowClass">
        <el-table-column prop="name" label="里程碑" min-width="160" show-overflow-tooltip />
        <el-table-column prop="phaseName" label="阶段" width="140" show-overflow-tooltip />
        <el-table-column prop="planEndDate" label="计划完成" width="110" />
        <el-table-column prop="actualEndDate" label="实际完成" width="110">
          <template #default="{ row }">{{ row.actualEndDate || '—' }}</template>
        </el-table-column>
        <el-table-column label="偏差(工作日)" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.escalatedFlag" type="danger" effect="dark" size="small">
              +{{ row.deviationWorkdays }} 升级
            </el-tag>
            <el-tag v-else-if="row.l4Flag" type="danger" size="small">
              +{{ row.deviationWorkdays }} L4
            </el-tag>
            <span v-else-if="row.deviationWorkdays > 0" style="color: #e6a23c;">
              +{{ row.deviationWorkdays }}
            </span>
            <span v-else style="color: #909399;">{{ row.deviationWorkdays }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reworkCount" label="返工" width="60" />
        <el-table-column label="预警标记" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.escalatedNotified === '1'" type="warning" size="small">已升级</el-tag>
            <el-tag v-else-if="row.delayNotified === '1'" size="small">已通知</el-tag>
            <span v-else style="color: #909399;">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="remind(row)">催办</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="legend">
        每日 03:30 自动扫描：滞后 >5 工作日 L4 通知 PM+PMO；>15 工作日升级 PMO 并生成催办；
        评审通过后 3 工作日未审批提醒 PMO（均幂等，同里程碑同级别只提醒一次）。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { milestoneProgress, remindMilestone, pmProjects } from '@/api/pm/milestone'

const loading = ref(false)
const projects = ref([])
const projectId = ref('')
const board = ref({ total: 0, done: 0, rate: 0, rows: [] })

const ratePercent = computed(() => Number(board.value.rate || 0))

onMounted(async () => {
  const { data } = await pmProjects()
  projects.value = data || []
  load()
})

async function load() {
  loading.value = true
  try {
    const { data } = await milestoneProgress(projectId.value || undefined)
    board.value = data || { total: 0, done: 0, rate: 0, rows: [] }
  } finally {
    loading.value = false
  }
}

async function remind(row) {
  try {
    await remindMilestone(row.id)
    ElMessage.success(`已催办「${row.name}」（通知 PMO）`)
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message)
  }
}

function rowClass({ row }) {
  if (row.escalatedFlag) return 'row-escalated'
  if (row.l4Flag) return 'row-l4'
  return ''
}

function statusText(s) {
  return { PLANNED: '已规划', SUBMITTING: '提交中', REVIEWING: '评审中',
    APPROVED: '待审批', DONE: '已完成', REWORK: '返工中' }[s] || s
}
function statusType(s) {
  return { PLANNED: 'info', SUBMITTING: 'warning', REVIEWING: 'warning',
    APPROVED: 'primary', DONE: 'success', REWORK: 'danger' }[s] || 'info'
}
</script>

<style scoped>
.toolbar { display: flex; gap: 12px; align-items: center; }
.rate-wrap { display: flex; flex-direction: column; align-items: center; }
.rate-title { color: #909399; font-size: 13px; margin-bottom: 4px; }
.legend { color: #909399; font-size: 12px; margin-top: 10px; line-height: 1.7; }
:deep(.row-l4) { --el-table-tr-bg-color: #fef0f0; }
:deep(.row-escalated) { --el-table-tr-bg-color: #f4e8ff; }
</style>
