<template>
  <div class="acc-page">
    <PageInfo title="账号管理— 账号生命周期" description="入职开通（HR 审批自动建号 + 人工兜底）、在岗变更（角色调整/密码重置）、离职回收（强停 + 权限清单快照）、账号归档（停用 90 天自动归档，归档不可恢复 C-0-05）。停用/归档账号 token 立即失效。" style="margin-bottom: 12px;" />
    <el-alert type="error" :closable="false" style="margin-bottom: 10px;"
      title="归档不可恢复（C-0-05）：归档账号禁止启用，token 即时失效，仅可查询历史审计" />
    <el-table :data="archivedRows" size="mini" border v-loading="loading">
      <el-table-column prop="username" label="用户名" width="140" />
      <el-table-column prop="nickName" label="姓名" width="120" />
      <el-table-column prop="archivedAt" label="归档时间" width="160" />
    </el-table>
  </div>
</template>

<script setup>
/**
 * 16.1.4 账号归档（拆分自原账号管理操作台）：归档账号台账（只读）。
 * 共享账号列表与加载见 useAccounts.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { computed, onMounted } from 'vue'
import { useAccounts } from './useAccounts'

const { loading, allRows, load } = useAccounts()

const archivedRows = computed(() => allRows.value.filter(r => r.status === '2'))

onMounted(load)
</script>

<style scoped>
.acc-page { padding: 4px; }
</style>
