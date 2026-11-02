<template>
  <div class="page">
    <el-alert type="info" :closable="false" style="margin-bottom: 10px"
              title="启动校验：窗口内且剩余时间 ≥ (预计+回滚)×1.5 分钟方可启动（BR-5.8-06）" />
    <el-table :data="approvable" border size="small">
      <el-table-column prop="ticketNo" label="单号" width="180" />
      <el-table-column prop="title" label="标题" />
      <el-table-column prop="estimateMin" label="预计(分)" width="90" />
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button size="small" type="success" @click="approve(row)">审批</el-button>
          <el-button size="small" type="primary" :disabled="row.status !== 'APPROVED'"
                     @click="start(row)">启动</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 18.1.3 窗口发布（拆分自原变更管理操作台）：待审批/可启动变更的审批与启动。
 * 列表数据来自 useChange.js，操作后刷新共享列表。
 */
import { computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { approveChangeApi, startChangeApi } from '@/api/ops/ops-sla'
import { useChange } from './useChange'

const { tickets, load } = useChange()

const approvable = computed(() => tickets.value.filter(t => t.status === 'SUBMITTED' || t.status === 'APPROVED'))

async function approve(row) {
  try {
    await approveChangeApi(row.id)
    ElMessage.success('审批通过（窗口判定已记录）')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '审批被阻断（预案三件套不齐 BR-5.8-01）')
  }
}

async function start(row) {
  try {
    const r = await startChangeApi(row.id)
    ElMessage.success(`已启动（窗口剩余 ${r.data.remainingMin} 分钟）`)
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '启动被阻断（窗口剩余不足 BR-5.8-06）')
  }
}

onMounted(load)
</script>
