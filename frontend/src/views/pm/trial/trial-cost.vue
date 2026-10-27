<template>
  <div class="trial-cost">
    <el-card style="margin-bottom: 12px;">
      <div class="hint">
        试制成本汇总分析（FR-4.8-10-5）：按试制类（TRIAL）WBS 任务聚合材料+工时+外协成本，与试制任务预算比对，
        纳入项目整体成本偏差监控（沿 COST_TOLERANCE 统一口径）。试制与量产分账（BR-4.8-37），
        为量产报价提供依据。产品线维度依赖 MDM（本期不支持）。
      </div>
    </el-card>

    <el-card style="margin-bottom: 12px;">
      <el-form inline size="default">
        <el-form-item label="项目" required>
          <el-select v-model="projectId" filterable placeholder="选择项目" style="width: 320px;">
            <el-option v-for="p in projects" :key="p.id" :label="`${p.projectNo || ''} ${p.name}`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-button type="primary" :disabled="!projectId" @click="load">查询</el-button>
      </el-form>
    </el-card>

    <el-card v-if="summary" style="margin-bottom: 12px;">
      <template #header><b>试制成本汇总</b></template>
      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="试制任务数">{{ summary.trialTaskCount }}</el-descriptions-item>
        <el-descriptions-item label="材料成本">{{ summary.material }}</el-descriptions-item>
        <el-descriptions-item label="工时成本">{{ summary.labor }}</el-descriptions-item>
        <el-descriptions-item label="外协成本">{{ summary.subcontract }}</el-descriptions-item>
        <el-descriptions-item label="实际合计">{{ summary.totalActual }}</el-descriptions-item>
        <el-descriptions-item label="试制预算">{{ summary.budget }}</el-descriptions-item>
        <el-descriptions-item label="偏差率">
          <span v-if="summary.deviationRate != null"
            :style="{ color: Math.abs(summary.deviationRate) > Number(summary.costTolerance) ? '#F56C6C' : '' }">
            {{ summary.deviationRate }}%
          </span>
          <span v-else>—</span>
        </el-descriptions-item>
        <el-descriptions-item label="容差(COST_TOLERANCE)">{{ summary.costTolerance }}%</el-descriptions-item>
      </el-descriptions>
      <div class="field-hint">偏差率 =（实际合计 − 试制预算）/ 试制预算；超容差联动 11.6 偏差预警与冻结</div>
    </el-card>

    <el-card v-if="summary && summary.byType">
      <template #header><b>成本分类构成</b></template>
      <el-table :data="byTypeRows" size="small" border>
        <el-table-column prop="type" label="成本类型" width="160" />
        <el-table-column prop="name" label="说明" min-width="180" />
        <el-table-column prop="amount" label="金额" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import * as api from '@/api/pm/trial-material'
import { projectList } from '@/api/pm/project'

const projects = ref([])
const projectId = ref('')
const summary = ref(null)

const TYPE_NAMES = { MATERIAL: '材料', LABOR: '工时', SUBCONTRACT: '外协', TRAVEL: '差旅', OTHER: '其他', INDIRECT: '间接' }
const byTypeRows = computed(() => {
  if (!summary.value?.byType) return []
  return Object.entries(summary.value.byType).map(([k, v]) => ({ type: k, name: TYPE_NAMES[k] || k, amount: v }))
})

async function load() {
  const { data } = await api.trialCostSummary(projectId.value)
  summary.value = data
}

onMounted(async () => {
  const { data } = await projectList({})
  projects.value = (data || []).map(p => p.project || p)
})
</script>

<style scoped>
.hint { color: #909399; font-size: 13px; line-height: 1.7; }
.field-hint { color: #c0c4cc; font-size: 12px; margin-top: 8px; }
</style>
