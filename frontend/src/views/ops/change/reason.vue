<template>
  <div class="page">
    <el-table :data="rolledBack" border size="small">
      <el-table-column prop="ticketNo" label="单号" width="180" />
      <el-table-column prop="title" label="标题" />
      <el-table-column prop="rollbackReason" label="回滚原因" />
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" type="primary" :disabled="!!row.rollbackReason"
                     @click="close(row)">关闭工单</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-alert type="warning" :closable="false" style="margin-top: 10px"
              title="回滚原因为空时关闭将被阻断（BR-5.8-04）" />
  </div>
</template>

<script setup>
/**
 * 18.1.5 回滚原因（拆分自原变更管理操作台）：已回滚变更的原因台账与关单。
 * 列表数据来自 useChange.js。
 */
import { computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { closeChangeApi } from '@/api/ops/ops-sla'
import { useChange } from './useChange'

const { tickets, load } = useChange()

const rolledBack = computed(() => tickets.value.filter(t => t.status === 'ROLLED_BACK'))

async function close(row) {
  let reason = row.rollbackReason
  if (!reason) {
    const r = await ElMessageBox.prompt('须填写回滚原因方可关闭（BR-5.8-04）', '关闭工单', { type: 'warning' })
    reason = r.value
  }
  await closeChangeApi(row.id, reason)
  ElMessage.success('已关闭')
  load()
}

onMounted(load)
</script>
