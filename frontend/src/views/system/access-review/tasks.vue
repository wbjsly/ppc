<template>
  <div class="uar-page">
    <!-- ================= 16.6.1 季度任务 ================= -->
    <div class="toolbar">
      <el-button type="primary" @click="loadTasks">刷新</el-button>
      <el-button type="danger" plain :loading="generating" @click="doGenerate">手动生成季度任务</el-button>
      <el-button type="warning" plain @click="doSweep">逾期升级扫描（BR-5.6-10）</el-button>
      <PageInfo class="page-info--inline" title="风险分层：特权/敏感账号 100% 人工复核；普通账号仅基线偏离进清单；基线内自动通过。完成时限 10 个工作日。" />
    </div>
    <el-table :data="tasks" v-loading="taskLoading" border stripe>
      <el-table-column prop="cycleTag" label="周期" width="110" />
      <el-table-column label="截止时间" width="170" :formatter="(_, r) => fmtTime(r.dueAt)" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="taskType(row.status)" size="small">{{ taskLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="完成度" width="180">
        <template #default="{ row }">
          <el-progress :percentage="pct(row.judgedItems, row.totalItems)"
            :status="row.judgedItems >= row.totalItems ? 'success' : ''" />
          <span class="muted">{{ row.judgedItems }}/{{ row.totalItems }} 项，收回 {{ row.reclaimItems }}</span>
        </template>
      </el-table-column>
      <el-table-column label="逾期升级" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.escalatedAt" type="danger" size="small">已升级</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button link type="primary" @click="viewItems(row)">快照/判定</el-button>
          <el-button link type="success" @click="doSummarize(row)">汇总报告</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 16.6.1 季度任务（拆分自原存取复核操作台）：任务台账 / 手动生成 / 逾期升级扫描 / 汇总。
 * 共享任务列表与快照明细状态见 useAccessReview.js。
 * 「快照/判定」入口设置共享任务并加载快照明细（原为切到快照 tab，tab 栏移除后
 * 由用户进入快照比对页查看，选中任务与明细为共享单例状态）。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/access-review'
import { useAccessReview } from './useAccessReview'
import { fmtTime, taskType, taskLabel } from './uar-helpers'

const { tasks, taskLoading, itemTaskId, loadTasks, ensureTasks, loadItems } = useAccessReview()

const generating = ref(false)

function pct(a, b) {
  return b ? Math.round((a || 0) * 100 / b) : 0
}

async function doGenerate() {
  await ElMessageBox.confirm('确认生成本季度复核任务？风险分层：特权/敏感 100% 人工，普通仅基线偏离。', '生成季度任务',
    { type: 'warning' })
  generating.value = true
  try {
    const { data } = await api.uarGenerate()
    ElMessage.success(`任务已生成：人工 ${data.humanItems} 项，基线内自动通过 ${data.autoPassedAccounts} 账号`)
    loadTasks()
  } finally {
    generating.value = false
  }
}

async function doSweep() {
  const { data } = await api.uarSweepOverdue()
  ElMessage.success(`扫描完成：升级 ${data.escalated}，重复提醒 ${data.reminded}`)
  loadTasks()
}

async function doSummarize(row) {
  const { data } = await api.uarSummarize(row.id)
  ElMessage.success(`已汇总：覆盖率 ${data.coverageRate}%，收回 ${data.reclaimedItems} 项（${data.reclaimRate}%）`)
  loadTasks()
}

function viewItems(row) {
  itemTaskId.value = row.id
  loadItems()
}

onMounted(ensureTasks)
</script>

<style scoped>
.uar-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.muted { color: #909399; font-size: 12px; }
</style>
