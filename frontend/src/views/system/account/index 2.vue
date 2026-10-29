<template>
  <div class="acc-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="账号管理（16.1）— 账号生命周期"
      description="入职开通（HR 审批自动建号 + 人工兜底）、在岗变更（角色调整/密码重置）、离职回收（强停 + 权限清单快照）、账号归档（停用 90 天自动归档，归档不可恢复 C-0-05）。停用/归档账号 token 立即失效。" />
    <el-tabs v-model="tab">
      <!-- ===== 入职开通 ===== -->
      <el-tab-pane label="入职开通" name="onboard">
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
      </el-tab-pane>

      <!-- ===== 在岗变更 ===== -->
      <el-tab-pane label="在岗变更" name="transfer">
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
      </el-tab-pane>

      <!-- ===== 离职回收 ===== -->
      <el-tab-pane label="离职回收" name="exit">
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
      </el-tab-pane>

      <!-- ===== 账号归档 ===== -->
      <el-tab-pane label="账号归档" name="archive">
        <el-alert type="error" :closable="false" style="margin-bottom: 10px;"
          title="归档不可恢复（C-0-05）：归档账号禁止启用，token 即时失效，仅可查询历史审计" />
        <el-table :data="archivedRows" size="mini" border v-loading="loading">
          <el-table-column prop="username" label="用户名" width="140" />
          <el-table-column prop="nickName" label="姓名" width="120" />
          <el-table-column prop="archivedAt" label="归档时间" width="160" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

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
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as acct from '@/api/system/account'

const route = useRoute()
const loading = ref(false)
// Tab 初始定位（design D6）：meta.tab 白名单，非法回落 onboard
const TAB_WHITELIST = ['onboard', 'transfer', 'exit', 'archive']
const initialTab = route.meta?.tab || route.query.tab
const tab = ref(TAB_WHITELIST.includes(initialTab) ? initialTab : 'onboard')

const allRows = ref([])
const manualEmpId = ref('')
const rolesDlg = ref(false)
const rolesForm = ref({ userId: '', username: '', roleIds: [] })
const roleOpts = ref([])

const activeRows = computed(() => allRows.value.filter(r => r.status === '1'))
const disabledRows = computed(() => allRows.value.filter(r => r.status === '0'))
const archivedRows = computed(() => allRows.value.filter(r => r.status === '2'))
const onboardRows = computed(() => allRows.value.filter(r => r.status === '1' && r.mustChangePwd))

async function load() {
  loading.value = true
  try {
    const res = await acct.accountPage({ size: 200 })
    allRows.value = res.data.records || []
  } finally { loading.value = false }
}

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
.hint { color: #909399; font-size: 12px; }
</style>
