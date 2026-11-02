<template>
  <div class="acc-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="账号管理（16.1）— 账号生命周期"
      description="入职开通（HR 审批自动建号 + 人工兜底）、在岗变更（角色调整/密码重置）、离职回收（强停 + 权限清单快照）、账号归档（停用 90 天自动归档，归档不可恢复 C-0-05）。停用/归档账号 token 立即失效。" />
    <el-alert type="warning" :closable="false" style="margin-bottom: 10px;"
      title="HR 建档审批通过后自动建号（首次登录强制改密）；下方为未建号员工的人工兜底入口" />
    <el-form inline size="mini">
      <el-form-item label="员工档案ID">
        <el-input v-model="manualEmpId" placeholder="hr_employee.ID" style="width:220px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" size="mini" @click="doManualCreate">人工建号（幂等）</el-button>
      </el-form-item>
    </el-form>
    <el-table :data="onboardRows" size="mini" border v-loading="loading">
      <el-table-column prop="username" label="用户名" width="140" />
      <el-table-column prop="nickName" label="姓名" width="120" />
      <el-table-column label="员工档案" min-width="220">
        <template #default="{row}">
          <span v-if="row.employee">{{ row.employee.empNo }} / {{ row.employee.name }}</span>
          <span v-else class="hint">未关联</span>
        </template>
      </el-table-column>
      <el-table-column label="强制改密" width="90" align="center">
        <template #default="{row}">
          <el-tag v-if="row.mustChangePwd" size="mini" type="warning">待改密</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="createDate" label="创建时间" width="160" />
    </el-table>
  </div>
</template>

<script setup>
/**
 * 16.1.1 入职开通（拆分自原账号管理操作台）：人工建号（幂等）+ 待改密员工列表。
 * 共享账号列表与加载见 useAccounts.js。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as acct from '@/api/system/account'
import { useAccounts } from './useAccounts'

const { loading, allRows, load } = useAccounts()

const manualEmpId = ref('')

const onboardRows = computed(() => allRows.value.filter(r => r.status === '1' && r.mustChangePwd))

async function doManualCreate() {
  if (!manualEmpId.value) { ElMessage.warning('请填写员工档案 ID'); return }
  try {
    const res = await acct.manualCreate(manualEmpId.value)
    ElMessage.success(`建号成功：${res.data.username}（初始密码随机生成，首次登录强制改密）`)
    manualEmpId.value = ''
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message)
  }
}

onMounted(load)
</script>

<style scoped>
.acc-page { padding: 4px; }
.hint { color: #909399; font-size: 12px; }
</style>
