<template>
  <div class="uar-page">
    <el-tabs v-model="tab">
      <!-- ================= 16.6.1 季度任务 ================= -->
      <el-tab-pane label="季度任务" name="tasks">
        <div class="toolbar">
          <el-button type="primary" @click="loadTasks">刷新</el-button>
          <el-button type="danger" plain :loading="generating" @click="doGenerate">手动生成季度任务</el-button>
          <el-button type="warning" plain @click="doSweep">逾期升级扫描（BR-5.6-10）</el-button>
        </div>
        <el-alert type="info" :closable="false" style="margin-bottom: 10px"
          title="风险分层：特权/敏感账号 100% 人工复核；普通账号仅基线偏离进清单；基线内自动通过。完成时限 10 个工作日。" />
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
      </el-tab-pane>

      <!-- ================= 16.6.2 快照比对 ================= -->
      <el-tab-pane label="快照比对" name="snapshots">
        <div class="toolbar">
          <el-select v-model="itemTaskId" placeholder="选择任务" style="width: 180px" @change="loadItems">
            <el-option v-for="t in tasks" :key="t.id" :label="t.cycleTag" :value="t.id" />
          </el-select>
          <el-checkbox v-model="mineOnly" @change="loadItems">仅本人待办</el-checkbox>
          <el-button type="primary" @click="loadItems">查询</el-button>
        </div>
        <el-table :data="items" v-loading="itemLoading" border stripe
          :row-key="(r) => r.id" :expand-row-keys="expanded">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="snap-box">
                <h4>权限快照（生成时刻固化）</h4>
                <p>当前角色：{{ snapOf(row).roles || [] }}</p>
                <p>岗位基线：{{ snapOf(row).baseline || [] }}</p>
                <p>基线偏离：{{ snapOf(row).beyondBaseline || [] }}</p>
                <p>近 90 天操作：{{ snapOf(row).opCount90d ?? '—' }} 条，
                  最后操作 {{ fmtTime(snapOf(row).lastOpAt) }}</p>
                <p>
                  <el-tag v-if="hasAnomaly(row, 'UNUSED')" type="warning" size="small">长期未使用</el-tag>
                  <el-tag v-if="hasAnomaly(row, 'BEYOND_BASELINE')" type="danger" size="small">超基线</el-tag>
                  <el-tag v-if="hasAnomaly(row, 'OFFBOARDED')" type="danger" size="small">离职未回收</el-tag>
                  <span v-if="!(snapOf(row).anomalies || []).length" class="muted">无异常</span>
                </p>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="targetUsername" label="账号" width="130" />
          <el-table-column prop="roleCode" label="角色" width="180" />
          <el-table-column label="风险" width="90">
            <template #default="{ row }">
              <el-tag :type="riskType(row.riskTag)" size="small">{{ riskLabel(row.riskTag) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="异常" width="180">
            <template #default="{ row }">
              <el-tag v-if="hasAnomaly(row, 'UNUSED')" type="warning" size="small" style="margin:2px">未使用</el-tag>
              <el-tag v-if="hasAnomaly(row, 'BEYOND_BASELINE')" type="danger" size="small" style="margin:2px">超基线</el-tag>
              <el-tag v-if="hasAnomaly(row, 'OFFBOARDED')" type="danger" size="small" style="margin:2px">离职</el-tag>
              <span v-if="!(snapOf(row).anomalies || []).length" class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="判定" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.judgment === 'KEEP'" type="success" size="small">保留</el-tag>
              <el-tag v-else-if="row.judgment === 'RECLAIM'" type="danger" size="small">收回</el-tag>
              <span v-else class="muted">待复核</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <template v-if="!row.judgment">
                <el-button link type="success" @click="judge(row, 'KEEP')">保留</el-button>
                <el-button link type="danger" @click="judge(row, 'RECLAIM')">收回</el-button>
              </template>
              <span v-else class="muted">{{ row.judgeReason || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ================= 16.6.3 回收执行 ================= -->
      <el-tab-pane label="回收执行" name="reclaim">
        <div class="toolbar">
          <el-select v-model="itemTaskId" placeholder="选择任务" style="width: 180px" @change="loadReclaimItems">
            <el-option v-for="t in tasks" :key="t.id" :label="t.cycleTag" :value="t.id" />
          </el-select>
          <el-button type="primary" @click="loadReclaimItems">查询</el-button>
        </div>
        <el-table :data="reclaimItems" v-loading="reclLoading" border stripe>
          <el-table-column prop="targetUsername" label="账号" width="130" />
          <el-table-column prop="roleCode" label="角色" width="180" />
          <el-table-column label="执行状态" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.execStatus === 'EXECUTED'" type="success" size="small">已回收</el-tag>
              <el-tag v-else-if="row.execStatus === 'SKIPPED'" type="info" size="small">已保留</el-tag>
              <el-tag v-else type="warning" size="small">待执行</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="judgeReason" label="判定理由" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button v-if="row.judgment === 'RECLAIM' && row.execStatus === 'PENDING'"
                link type="danger" @click="doReclaim(row)">执行收回</el-button>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ================= 16.6.4 复核报告 ================= -->
      <el-tab-pane label="复核报告" name="reports">
        <div class="toolbar">
          <el-button type="primary" @click="loadReports">刷新</el-button>
        </div>
        <el-table :data="reports" v-loading="repLoading" border stripe>
          <el-table-column prop="cycleTag" label="周期" width="110" />
          <el-table-column label="覆盖率" width="160">
            <template #default="{ row }">
              <el-progress :percentage="Number(row.coverageRate || 0)" :status="Number(row.coverageRate) >= 100 ? 'success' : ''" />
            </template>
          </el-table-column>
          <el-table-column label="收回" width="140">
            <template #default="{ row }">{{ row.reclaimedItems }} 项（{{ row.reclaimRate }}%）</template>
          </el-table-column>
          <el-table-column label="ADMIN 确认" width="160">
            <template #default="{ row }">
              <span v-if="row.confirmAt">{{ fmtTime(row.confirmAt) }}</span>
              <el-button v-else link type="primary" @click="doConfirm(row)">确认</el-button>
            </template>
          </el-table-column>
          <el-table-column label="内审会签" width="180">
            <template #default="{ row }">
              <el-tag v-if="row.archived === '1'" type="success" size="small">已归档</el-tag>
              <el-button v-else-if="row.confirmAt" link type="warning" @click="doSign(row)">内审会签</el-button>
              <span v-else class="muted">待 ADMIN 确认</span>
            </template>
          </el-table-column>
          <el-table-column prop="taskStatus" label="任务状态" width="100">
            <template #default="{ row }">
              <el-tag :type="taskType(row.taskStatus)" size="small">{{ taskLabel(row.taskStatus) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/access-review'

const route = useRoute()

// Tab 定位（沿 16.1~16.5 模式）
const TAB_WHITELIST = ['tasks', 'snapshots', 'reclaim', 'reports']
const pickTab = () => {
  const m = route.meta?.tab
  return TAB_WHITELIST.includes(m) ? m : 'tasks'
}
const tab = ref(pickTab())
watch(() => route.meta?.tab, () => { tab.value = pickTab() })

// ---------- Tab1 季度任务 ----------
const tasks = ref([])
const taskLoading = ref(false)
const generating = ref(false)

async function loadTasks() {
  taskLoading.value = true
  try {
    const { data } = await api.uarTaskList()
    tasks.value = data || []
  } finally {
    taskLoading.value = false
  }
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
  if (tab.value === 'reports') loadReports()
}

// ---------- Tab2/3 快照与回收 ----------
const itemTaskId = ref('')
const mineOnly = ref(false)
const items = ref([])
const reclaimItems = ref([])
const itemLoading = ref(false)
const reclLoading = ref(false)
const expanded = ref([])

async function loadItems() {
  if (!itemTaskId.value) return
  itemLoading.value = true
  try {
    const { data } = await api.uarItemList(itemTaskId.value, mineOnly.value)
    items.value = data || []
  } finally {
    itemLoading.value = false
  }
}

async function loadReclaimItems() {
  if (!itemTaskId.value) return
  reclLoading.value = true
  try {
    const { data } = await api.uarItemList(itemTaskId.value, false)
    reclaimItems.value = (data || []).filter((i) => i.judgment)
  } finally {
    reclLoading.value = false
  }
}

function viewItems(row) {
  itemTaskId.value = row.id
  tab.value = 'snapshots'
  loadItems()
}

async function judge(row, judgment) {
  let reason = null
  if (judgment === 'KEEP' && (hasAnomaly(row, 'UNUSED') || hasAnomaly(row, 'BEYOND_BASELINE'))) {
    const { value } = await ElMessageBox.prompt(
      '该角色长期未使用或超出岗位基线，保留须填写业务理由（≥10 字）', '保留理由',
      { inputValidator: (v) => (v && v.trim().length >= 10) || '理由不少于 10 字' })
    reason = value
  } else if (judgment === 'KEEP') {
    const { value } = await ElMessageBox.prompt('保留理由（可选）', '保留', { inputValue: '' })
    reason = value
  }
  await api.uarJudge(row.id, { judgment, reason })
  ElMessage.success('判定已记录')
  loadItems()
  loadTasks()
}

async function doReclaim(row) {
  await ElMessageBox.confirm(`确认执行收回：账号 ${row.targetUsername} 的 ${row.roleCode}？`, '执行收回',
    { type: 'warning' })
  await api.uarReclaim(row.id)
  ElMessage.success('收回已执行')
  loadReclaimItems()
  loadTasks()
}

// ---------- Tab4 报告 ----------
const reports = ref([])
const repLoading = ref(false)

async function loadReports() {
  repLoading.value = true
  try {
    const { data } = await api.uarReportList()
    reports.value = data || []
  } finally {
    repLoading.value = false
  }
}

async function doConfirm(row) {
  await api.uarConfirm(row.taskId)
  ElMessage.success('已确认（双签第一步）')
  loadReports()
}

async function doSign(row) {
  await ElMessageBox.confirm('确认内审会签并归档？会签后报告不可更改（C-5.6-05）。', '内审会签',
    { type: 'warning' })
  await api.uarSign(row.taskId)
  ElMessage.success('已会签归档')
  loadReports()
  loadTasks()
}

// ---------- 展示辅助 ----------
function snapOf(row) {
  try {
    return JSON.parse(row.snapshotJson || '{}')
  } catch (e) {
    return {}
  }
}
function hasAnomaly(row, code) {
  return (snapOf(row).anomalies || []).includes(code)
}
function pct(a, b) {
  return b ? Math.round((a || 0) * 100 / b) : 0
}
function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}
function riskType(r) {
  return r === 'PRIVILEGED' ? 'danger' : r === 'SENSITIVE' ? 'warning' : 'info'
}
function riskLabel(r) {
  return r === 'PRIVILEGED' ? '特权' : r === 'SENSITIVE' ? '敏感' : '普通'
}
function taskType(s) {
  return s === 'ARCHIVED' ? 'success' : s === 'COMPLETED' ? 'warning' : 'info'
}
function taskLabel(s) {
  return ({ REVIEWING: '复核中', COMPLETED: '待归档', ARCHIVED: '已归档' })[s] || s
}

// ---------- 挂载 ----------
watch(tab, (t) => {
  if (t === 'snapshots' && itemTaskId.value) loadItems()
  if (t === 'reclaim' && itemTaskId.value) loadReclaimItems()
  if (t === 'reports') loadReports()
})

onMounted(async () => {
  await loadTasks()
  if (tasks.value.length && !itemTaskId.value) {
    itemTaskId.value = tasks.value[0].id
  }
  if (tab.value === 'snapshots') loadItems()
  if (tab.value === 'reclaim') loadReclaimItems()
  if (tab.value === 'reports') loadReports()
})
</script>

<style scoped>
.uar-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.muted { color: #909399; font-size: 12px; }
.snap-box { margin: 8px 24px; padding: 10px; background: #f5f7fa; border-radius: 4px; }
.snap-box h4 { margin: 0 0 6px; }
.snap-box p { margin: 4px 0; font-size: 13px; }
</style>
