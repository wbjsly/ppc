<template>
  <div class="perm-page">
    <!-- ================= 16.2.1 角色管理 ================= -->
    <div class="toolbar">
      <el-input v-model="roleQuery.keyword" placeholder="编码/名称模糊搜索" clearable
        style="width: 220px" @keyup.enter="loadRoles" @clear="loadRoles" />
      <el-button type="primary" @click="loadRoles">查询</el-button>
      <el-button type="success" @click="openCreate">新建角色</el-button>
    </div>
    <el-table :data="roles" v-loading="roleLoading" border stripe>
      <el-table-column prop="roleCode" label="角色编码" width="220" />
      <el-table-column prop="roleName" label="角色名称" min-width="160" />
      <el-table-column prop="sortOrder" label="排序" width="80" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === '1' ? 'success' : 'info'">
            {{ row.status === '1' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230">
        <template #default="{ row }">
          <el-button link type="primary" @click="openRename(row)">改名</el-button>
          <el-button link :type="row.status === '1' ? 'warning' : 'success'"
            @click="toggleStatus(row)">{{ row.status === '1' ? '停用' : '启用' }}</el-button>
          <el-button link type="danger" @click="removeRole(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top: 12px" layout="total, prev, pager, next"
      :total="roleTotal" :page-size="roleQuery.size" v-model:current-page="roleQuery.current"
      @current-change="loadRoles" />

    <!-- 新建 / 改名 角色对话框 -->
    <el-dialog v-model="roleDlg" :title="roleForm.id ? '改名 / 调整' : '新建角色'" width="460px">
      <el-form label-width="90px">
        <el-form-item label="角色编码" v-if="!roleForm.id">
          <el-input v-model="roleForm.roleCode" placeholder="ROLE_ 大写字母/数字/下划线" />
        </el-form-item>
        <el-form-item label="角色名称">
          <el-input v-model="roleForm.roleName" placeholder="如：计划专员" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="roleForm.sortOrder" :min="0" :max="9999" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDlg = false">取消</el-button>
        <el-button type="primary" @click="submitRole">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 16.2.1 角色管理（拆分自原权限管理操作台）：角色台账 / 新建 / 改名 / 启停 / 删除。
 * 共享角色全量列表见 usePermission.js。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/permission'
import { usePermission } from './usePermission'

const { loadMatrixRoles } = usePermission()

const roles = ref([])
const roleTotal = ref(0)
const roleLoading = ref(false)
const roleQuery = ref({ current: 1, size: 20, keyword: '' })
const roleDlg = ref(false)
const roleForm = ref({ id: '', roleCode: '', roleName: '', sortOrder: 0 })

async function loadRoles() {
  roleLoading.value = true
  try {
    const { data } = await api.rolePage(roleQuery.value)
    roles.value = data.records || []
    roleTotal.value = Number(data.total) || 0
  } finally {
    roleLoading.value = false
  }
}
function openCreate() {
  roleForm.value = { id: '', roleCode: '', roleName: '', sortOrder: 0 }
  roleDlg.value = true
}
function openRename(row) {
  roleForm.value = { id: row.id, roleCode: row.roleCode, roleName: row.roleName, sortOrder: row.sortOrder || 0 }
  roleDlg.value = true
}
async function submitRole() {
  if (roleForm.value.id) {
    await api.updateRole(roleForm.value.id, {
      roleName: roleForm.value.roleName, sortOrder: roleForm.value.sortOrder
    })
    ElMessage.success('已保存')
  } else {
    await api.createRole({ ...roleForm.value })
    ElMessage.success('角色已创建（初始无任何菜单可见性，请到权限矩阵授予）')
  }
  roleDlg.value = false
  loadRoles()
  loadMatrixRoles()
}
async function toggleStatus(row) {
  const next = row.status === '1' ? '0' : '1'
  if (next === '0') {
    await ElMessageBox.confirm(`停用角色 ${row.roleCode}？`, '确认', { type: 'warning' })
  }
  await api.updateRole(row.id, { status: next })
  ElMessage.success(next === '1' ? '已启用' : '已停用')
  loadRoles()
}
async function removeRole(row) {
  await ElMessageBox.confirm(
    `删除角色 ${row.roleCode}？被用户绑定/菜单 PERM/矩阵覆盖引用时将被拒绝`, '确认删除',
    { type: 'warning' })
  await api.deleteRole(row.id)
  ElMessage.success('已删除')
  loadRoles()
}

onMounted(async () => {
  await Promise.all([loadRoles(), loadMatrixRoles()])
})
</script>

<style scoped>
.perm-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
</style>
