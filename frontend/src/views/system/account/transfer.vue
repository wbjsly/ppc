<template>
  <div class="acc-page">
    <PageInfo title="账号管理— 账号生命周期" description="入职开通（HR 审批自动建号 + 人工兜底）、在岗变更（角色调整/密码重置）、离职回收（强停 + 权限清单快照）、账号归档（停用 90 天自动归档，归档不可恢复 C-0-05）。停用/归档账号 token 立即失效。" style="margin-bottom: 12px;" />
    <el-table :data="activeRows" size="mini" border v-loading="loading">
      <el-table-column prop="username" label="用户名" width="140" />
      <el-table-column prop="nickName" label="姓名" width="120" />
      <el-table-column prop="phone" label="手机号" width="120" />
      <el-table-column prop="lastLoginAt" label="最后登录" width="160" />
      <el-table-column label="强制改密" width="90" align="center">
        <template #default="{row}">
          <el-tag v-if="row.mustChangePwd" size="mini" type="warning">待改密</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{row}">
          <el-button link type="primary" size="mini" @click="openRoles(row)">角色调整</el-button>
          <el-button link type="warning" size="mini" @click="doResetPwd(row)">重置密码</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 角色调整 -->
    <el-dialog v-model="rolesDlg" :title="`角色调整 - ${rolesForm.username || ''}`" width="480px">
      <el-checkbox-group v-model="rolesForm.roleIds">
        <el-checkbox v-for="r in roleOpts" :key="r.id" :label="r.id" style="margin-right:12px;">
          {{ r.roleName || r.roleCode }}
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="rolesDlg = false">取消</el-button>
        <el-button type="primary" @click="saveRoles">保存（全量替换，留痕）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 16.1.2 在岗变更（拆分自原账号管理操作台）：角色调整（全量替换留痕）/ 重置密码。
 * 共享账号列表与加载见 useAccounts.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as acct from '@/api/system/account'
import { useAccounts } from './useAccounts'

const { loading, activeRows, load } = useAccounts()

const rolesDlg = ref(false)
const rolesForm = ref({ userId: '', username: '', roleIds: [] })
const roleOpts = ref([])

async function openRoles(row) {
  if (!roleOpts.value.length) {
    const res = await acct.roleOptions()
    roleOpts.value = res.data || []
  }
  const detail = await acct.accountDetail(row.id)
  rolesForm.value = {
    userId: row.id,
    username: row.username,
    roleIds: (detail.data.roleCodes || []).map(code =>
      (roleOpts.value.find(r => r.roleCode === code) || {}).id).filter(Boolean)
  }
  rolesDlg.value = true
}

async function saveRoles() {
  await acct.adjustRoles(rolesForm.value.userId, rolesForm.value.roleIds)
  ElMessage.success('角色已调整（前后集合已留痕）')
  rolesDlg.value = false
}

async function doResetPwd(row) {
  try {
    await ElMessageBox.confirm(`重置 ${row.username} 的密码？新密码一次性展示。`, '重置密码', { type: 'warning' })
  } catch { return }
  const res = await acct.resetPassword(row.id)
  ElMessageBox.alert(`新密码（仅此一次展示，请立即转交本人）：\n\n${res.data.newPassword}\n\n登录后将强制改密。`, '重置成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.acc-page { padding: 4px; }
</style>
