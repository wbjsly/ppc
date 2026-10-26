<template>
  <div class="hours-collect">
    <el-card style="margin-bottom: 12px;">
      <div class="toolbar">
        <el-select v-model="projectId" placeholder="选择项目" filterable style="width: 320px;" @change="load">
          <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="p.projectNo ? `${p.projectNo} ${p.name}` : p.name" />
        </el-select>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </el-card>

    <el-row :gutter="12" style="margin-bottom: 12px;">
      <el-col :span="8">
        <el-card><el-statistic title="计划工时（WBS 预估）" :value="Number(board.planHours || 0)" :precision="1" /></el-card>
      </el-col>
      <el-col :span="8">
        <el-card><el-statistic title="实际工时（已归集）" :value="Number(board.actualHours || 0)" :precision="1"
          :value-style="{ color: '#409eff' }" /></el-card>
      </el-col>
      <el-col :span="8">
        <el-card><el-statistic title="人工成本（归集）" :value="Number(board.laborCost || 0)" :precision="2"
          :value-style="{ color: '#e6a23c' }" /></el-card>
      </el-col>
    </el-row>

    <el-card>
      <template #header><b>四级汇总（阶段 → 工作包 → 任务）</b></template>
      <el-table :data="treeRows" v-loading="loading" row-key="wbsId" :tree-props="{ children: 'children' }" stripe>
        <el-table-column prop="name" label="WBS 元素" min-width="260">
          <template #default="{ row }">
            <span :style="{ paddingLeft: (row.depth || 0) * 18 + 'px' }">
              <el-tag v-if="row.levelName" size="small" style="margin-right: 6px;">{{ row.levelName }}</el-tag>
              {{ row.name }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="planHours" label="计划工时" width="110" :precision="1" />
        <el-table-column prop="actualHours" label="实际工时" width="110" :precision="1" />
        <el-table-column label="偏差" width="110">
          <template #default="{ row }">
            <span v-if="row.planHours > 0" :style="{ color: deviationColor(row) }">
              {{ ((row.actualHours - row.planHours) / row.planHours * 100).toFixed(1) }}%
            </span>
            <span v-else style="color: #909399;">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="laborCost" label="人工成本" width="120" :precision="2" v-if="hasCost" />
      </el-table>
      <div class="hint">
        计划口径 = WBS 任务预估工时级联；实际口径 = 审批通过归集明细；人工成本 = 工时 × 职级费率（快照）。
        费率按工时日期落在生效区间匹配，无命中暂用当前费率（HR 会收到 L4 提示）。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { collectSummary, pmProjects } from '@/api/pm/timesheet'

const loading = ref(false)
const projects = ref([])
const projectId = ref('')
const board = ref({})

const hasCost = computed(() => (board.value.tasks || []).some(t => Number(t.laborCost) > 0))

// 扁平转树（阶段→包→任务）
const treeRows = computed(() => {
  const b = board.value
  const taskMap = new Map((b.tasks || []).map(t => [t.wbsId, { ...t, levelName: '任务', children: [] }]))
  const pkgNodes = (b.packages || []).map(p => ({
    ...p, levelName: '工作包', children: [...taskMap.values()].filter(t => t.parentId === p.wbsId)
  }))
  const pkgMap = new Map(pkgNodes.map(p => [p.wbsId, p]))
  return (b.phases || []).map(ph => ({
    ...ph, levelName: '阶段', children: pkgNodes.filter(p => p.parentId === ph.wbsId)
  }))
})

onMounted(async () => {
  const { data } = await pmProjects()
  projects.value = data || []
  if (projects.value.length) {
    projectId.value = projects.value[0].id
    load()
  }
})

async function load() {
  if (!projectId.value) return
  loading.value = true
  try {
    const { data } = await collectSummary(projectId.value)
    board.value = data || {}
  } finally {
    loading.value = false
  }
}

function deviationColor(row) {
  if (!row.planHours) return '#909399'
  const rate = (row.actualHours - row.planHours) / row.planHours * 100
  if (rate > 20) return '#f56c6c'
  if (rate > 10) return '#e6a23c'
  return '#67c23a'
}
</script>

<style scoped>
.toolbar { display: flex; gap: 12px; align-items: center; }
.hint { color: #909399; font-size: 12px; margin-top: 10px; line-height: 1.7; }
</style>
