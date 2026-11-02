<template>
  <div class="uar-page">
    <!-- ================= 16.6.3 回收执行 ================= -->
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
  </div>
</template>

<script setup>
/**
 * 16.6.3 回收执行（拆分自原存取复核操作台）：判定为收回的条目执行收回。
 * 共享任务列表与选中任务（与快照比对同一选择器）见 useAccessReview.js。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/access-review'
import { useAccessReview } from './useAccessReview'

const { tasks, itemTaskId, loadTasks, ensureTasks } = useAccessReview()

const reclaimItems = ref([])
const reclLoading = ref(false)

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

async function doReclaim(row) {
  await ElMessageBox.confirm(`确认执行收回：账号 ${row.targetUsername} 的 ${row.roleCode}？`, '执行收回',
    { type: 'warning' })
  await api.uarReclaim(row.id)
  ElMessage.success('收回已执行')
  loadReclaimItems()
  loadTasks()
}

onMounted(async () => {
  await ensureTasks()
  loadReclaimItems()
})
</script>

<style scoped>
.uar-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.muted { color: #909399; font-size: 12px; }
</style>
