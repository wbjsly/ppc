<template>
  <div class="cost-variance">
    <el-card style="margin-bottom: 12px;">
      <div class="toolbar">
        <el-select v-model="projectId" placeholder="选择项目查看偏差" filterable style="width: 320px;">
          <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="p.name" />
        </el-select>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
      <div class="hint">
        偏差率 =（实际累计 − PV）/ PV × 100%，PV = 预算基线 × 时间进度%（线性法）。
        双阈值（BR-4.8-21）：偏差率 &gt; COST_TOLERANCE（默认 0.5%，系统参数可调）→ 仅预警；
        实际累计 &gt; 预算基线 → 自动冻结后续支出（领料/外协付款/工时录入）。
      </div>
    </el-card>

    <el-row :gutter="12" style="margin-bottom: 12px;">
      <el-col :span="5" v-for="c in cards" :key="c.label">
        <el-card shadow="hover">
          <div class="stat-label">{{ c.label }}</div>
          <div class="stat-value" :style="{ color: c.color }">{{ c.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card v-if="board" style="margin-bottom: 12px;">
      <template #header>
        <b>五分类实际成本（{{ board.projectName }}）</b>
        <el-tag v-if="board.frozen" type="danger" effect="dark" style="margin-left: 10px;">已冻结</el-tag>
      </template>
      <el-descriptions :column="6" border size="small">
        <el-descriptions-item label="预算基线">{{ board.baseline }}</el-descriptions-item>
        <el-descriptions-item label="PV（时间进度）">{{ board.pv }}</el-descriptions-item>
        <el-descriptions-item label="实际累计">{{ board.actual }}</el-descriptions-item>
        <el-descriptions-item label="偏差率">
          <b :style="{ color: rateColor }">{{ board.deviationRate === null ? '—' : board.deviationRate + '%' }}</b>
        </el-descriptions-item>
        <el-descriptions-item label="人工">{{ board.byType.LABOR }}</el-descriptions-item>
        <el-descriptions-item label="材料/外协/差旅/间接">
          {{ board.byType.MATERIAL }} / {{ board.byType.SUBCONTRACT }} / {{ board.byType.TRAVEL }} / {{ board.byType.INDIRECT }}
        </el-descriptions-item>
      </el-descriptions>
      <div v-if="board.frozen" class="freeze-reason">冻结原因：{{ board.freezeReason }}</div>
    </el-card>

    <el-card>
      <template #header><b>成本偏差预警</b></template>
      <el-table :data="alerts" v-loading="loading" stripe>
        <el-table-column prop="alert.alertDate" label="预警日" width="110" />
        <el-table-column prop="projectName" label="项目" min-width="180" show-overflow-tooltip />
        <el-table-column label="级别" width="90">
          <template #default="{ row }">
            <el-tag :type="row.alert.level === 'RED' ? 'danger' : 'warning'" size="small">
              {{ row.alert.level === 'RED' ? '红色' : '黄色' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="alert.deviationRate" label="偏差率(%)" width="100" />
        <el-table-column prop="alert.pv" label="PV" width="110" :precision="2" />
        <el-table-column prop="alert.actual" label="实际累计" width="110" :precision="2" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.alert.handledFlag === '1'" type="success" size="small">已处理</el-tag>
            <el-tag v-else type="info" size="small">待处理</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="alert.handledNote" label="处理说明" min-width="140" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.alert.handledFlag !== '1'" link type="primary"
              @click="handle(row.alert.id)">已处理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import * as api from '@/api/cost'

const loading = ref(false)
const projectId = ref('')
const projects = ref([])
const board = ref(null)
const alerts = ref([])

const cards = computed(() => {
  const b = board.value
  return [
    { label: '预算基线', value: b ? b.baseline : '—', color: '#303133' },
    { label: 'PV（时间进度）', value: b ? b.pv : '—', color: '#303133' },
    { label: '实际累计', value: b ? b.actual : '—', color: '#303133' },
    { label: '偏差率', value: b && b.deviationRate !== null ? b.deviationRate + '%' : '—', color: rateColor.value },
    { label: '状态', value: b ? (b.frozen ? '已冻结' : '正常') : '—', color: b && b.frozen ? '#f56c6c' : '#67c23a' }
  ]
})

const rateColor = computed(() => {
  const b = board.value
  if (!b || b.deviationRate === null) return '#909399'
  if (b.frozen || b.deviationRate > 10) return '#f56c6c'
  if (b.deviationRate > 0.5) return '#e6a23c'
  return '#67c23a'
})

async function load() {
  const { data } = await api.pmProjects()
  projects.value = (data || []).map(p => p.project || p)
  if (!projectId.value && projects.value.length) {
    projectId.value = projects.value[0].id
  }
  if (projectId.value) {
    loading.value = true
    try {
      const [v, a] = await Promise.all([
        api.costVariance(projectId.value),
        api.costAlerts({ projectId: projectId.value })
      ])
      board.value = v.data
      alerts.value = a.data || []
    } finally {
      loading.value = false
    }
  }
}

async function handle(id) {
  const { value } = await ElMessageBox.prompt('处理说明（可选）', '标记已处理', { inputPlaceholder: '如：已调整后续任务估算' })
  await api.handleCostAlert(id, { note: value })
  ElMessage.success('已标记处理')
  load()
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; gap: 10px; align-items: center; }
.hint { margin-top: 8px; color: #909399; font-size: 12px; line-height: 1.6; }
.stat-label { color: #909399; font-size: 12px; margin-bottom: 6px; }
.stat-value { font-size: 20px; font-weight: 600; }
.freeze-reason { margin-top: 10px; color: #f56c6c; font-size: 12px; }
</style>
