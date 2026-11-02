<template>
  <div class="acc-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="账号管理（16.1）— 账号生命周期"
      description="入职开通（HR 审批自动建号 + 人工兜底）、在岗变更（角色调整/密码重置）、离职回收（强停 + 权限清单快照）、账号归档（停用 90 天自动归档，归档不可恢复 C-0-05）。停用/归档账号 token 立即失效。" />
    <el-form inline size="mini">
      <el-form-item>
        <el-button type="warning" size="mini" @click="doSweep">执行生命周期清扫（24h 强停 + 90 天归档）</el-button>
      </el-form-item>
    </el-form>
    <el-table :data="disabledRows" size="mini" border v-loading="loading">
      <el-table-column prop="username" label="用户名" width="140" />
      <el-table-column prop="nickName" label="姓名" width="120" />
      <el-table-column prop="updateDate" label="停用时间" width="160" />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{row}">
          <el-button link type="danger" size="mini" @click="doArchive(row)">归档</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-table :data="activeRows" size="mini" border style="margin-top:12px">
      <el-table-column prop="username" label="在岗账号" width="140" />
      <el-table-column prop="nickName" label="姓名" width="120" />
      <el-table-column label="操作" width="120">
        <template #default="{row}">
          <el-button link type="danger" size="mini" @click="doDisable(row)">强停</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 16.1.3 离职回收（拆分自原账号管理操作台）：生命周期清扫 / 强停 / 归档。
 * 共享账号列表与加载见 useAccounts.js。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as acct from '@/api/system/account'
import { useAccounts } from './useAccounts'

const { loading, allRows, activeRows, load } = useAccounts()

const disabledRows = computed(() => allRows.value.filter(r => r.status === '0'))

async function doDisable(row) {
  try {
    await ElMessageBox.confirm(`强停 ${row.username}？账号 token 将立即失效，权限清单快照写入审计。`, '强停', { type: 'warning' })
  } catch { return }
  const res = await acct.disableAccount(row.id)
  ElMessage.success(`已强停；权限清单快照：${res.data.roleSnapshot}`)
  load()
}

async function doArchive(row) {
  let input
  try {
    const r = await ElMessageBox.prompt(
      `归档不可恢复（C-0-05）。请输入用户名 ${row.username} 以确认：`,
      '账号归档二次确认', { inputPattern: /.+/, inputValue: '' })
    input = r.value
  } catch { return }
  try {
    await acct.archiveAccount(row.id, input)
    ElMessage.success('已归档（不可恢复）')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message)
  }
}

async function doSweep() {
  const res = await acct.sweepAccounts()
  ElMessage.success(`清扫完成：强停 ${res.data.disabled} 个，归档 ${res.data.archived} 个`)
  load()
}

onMounted(load)
</script>

<style scoped>
.acc-page { padding: 4px; }
</style>
