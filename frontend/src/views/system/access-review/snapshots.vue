<template>
  <div class="uar-page">
    <!-- ================= 16.6.2 快照比对 ================= -->
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
  </div>
</template>

<script setup>
/**
 * 16.6.2 快照比对（拆分自原存取复核操作台）：快照明细展开 / 保留·收回判定。
 * 共享任务列表、选中任务与快照明细状态见 useAccessReview.js。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/access-review'
import { useAccessReview } from './useAccessReview'
import { fmtTime } from './uar-helpers'

const {
  tasks, itemTaskId, ensureTasks, loadTasks,
  items, itemLoading, mineOnly, loadItems
} = useAccessReview()

const expanded = ref([])

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
function riskType(r) {
  return r === 'PRIVILEGED' ? 'danger' : r === 'SENSITIVE' ? 'warning' : 'info'
}
function riskLabel(r) {
  return r === 'PRIVILEGED' ? '特权' : r === 'SENSITIVE' ? '敏感' : '普通'
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

onMounted(async () => {
  await ensureTasks()
  loadItems()
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
