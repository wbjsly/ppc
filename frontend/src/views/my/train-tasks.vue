<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>我的培训任务（自助端）</span>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>
      <el-alert :closable="false" type="info" show-icon class="mb8"
        title="确认参加/已完成后，最终结果以 HR 登记的考试成绩为准（成绩≥合格线才计完成）" />
      <el-table :data="rows" size="small" border>
        <el-table-column label="课程" min-width="180">
          <template #default="{ row }">{{ row.plan?.courseName }}</template>
        </el-table-column>
        <el-table-column label="计划日期" width="180">
          <template #default="{ row }">{{ row.plan?.dateFrom || '—' }} ~ {{ row.plan?.dateTo || '—' }}</template>
        </el-table-column>
        <el-table-column label="完成期限" width="110">
          <template #default="{ row }">{{ row.plan?.dueDate || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="tag(row.task.status)">{{ name(row.task.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="task.score" label="成绩" width="70" />
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button v-if="row.task.status === 'PENDING'" type="primary" size="small"
              @click="confirm(row.task)">确认参加</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { myTrainTasks, myTrainConfirm } from '@/api/hr/training'

const rows = ref([])
const name = (s) => ({ PENDING: '待确认', CONFIRMED: '已确认', COMPLETED: '已完成',
  FAILED: '未通过', CANCELLED: '已取消' }[s] || s)
const tag = (s) => ({ PENDING: 'info', CONFIRMED: 'warning', COMPLETED: 'success',
  FAILED: 'danger', CANCELLED: 'info' }[s] || 'info')

async function load() {
  try { rows.value = (await myTrainTasks()).data || [] } catch (e) { /* ignore */ }
}

async function confirm(task) {
  try {
    const { data } = await myTrainConfirm(task.id)
    ElMessage.success(data.hint || '已确认')
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.mb8 { margin-bottom: 8px; }
</style>
