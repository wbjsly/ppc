<template>
  <div class="page">
    <el-table :data="executable" border size="small">
      <el-table-column prop="ticketNo" label="单号" width="180" />
      <el-table-column prop="title" label="标题" />
      <el-table-column prop="status" label="状态" width="110">
        <template #default="{ row }">{{ stLabel(row.status) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" type="warning" @click="rollback(row)">回滚</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 18.1.4 失败回滚（拆分自原变更管理操作台）：执行中/已审批变更的回滚操作。
 * 列表数据来自 useChange.js，状态映射见 change-helpers.js。
 */
import { computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { rollbackChangeApi } from '@/api/ops/ops-sla'
import { useChange } from './useChange'
import { stLabel } from './change-helpers'

const { tickets, load } = useChange()

const executable = computed(() => tickets.value.filter(t => t.status === 'EXECUTING' || t.status === 'APPROVED'))

async function rollback(row) {
  const { value } = await ElMessageBox.prompt('回滚原因（将写入台账）', '回滚确认', { type: 'warning' })
  await rollbackChangeApi(row.id, value)
  ElMessage.success('已回滚')
  load()
}

onMounted(load)
</script>
