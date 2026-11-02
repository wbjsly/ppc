<template>
  <div class="perm-page">
    <!-- ================= 16.2.2 权限矩阵 ================= -->
    <div class="toolbar">
      <el-select v-model="matrixRole" placeholder="选择角色" style="width: 260px"
        filterable @change="loadMatrix">
        <el-option v-for="r in matrixRoles" :key="r.id" :label="r.roleName + '（' + r.roleCode + '）'"
          :value="r.roleCode" />
      </el-select>
      <el-button type="warning" plain :disabled="!matrixRole"
        @click="clearOverrides">清除该角色全部覆盖</el-button>
      <span class="legend">
        <el-tag size="small" type="info">基线</el-tag>
        <el-tag size="small" type="primary">强制包含</el-tag>
        <el-tag size="small" type="danger">强制排除</el-tag>
      </span>
    </div>
    <el-alert v-if="matrixRole" type="info" :closable="false" show-icon style="margin-bottom: 10px"
      title="矩阵仅改菜单可见性；接口(API)授权由服务端代码级细规则定义，勾选后若接口仍 403 属预期边界" />
    <el-tree v-if="matrixRole" ref="treeRef" :data="treeData" node-key="id" show-checkbox
      check-strictly default-expand-all :expand-on-click-node="false"
      :props="{ label: 'label' }" @check="onTreeCheck">
      <template #default="{ data }">
        <span class="tree-node">
          <span>{{ data.label }}</span>
          <el-tag v-if="data.source === 'OVERRIDE_ADD'" size="small" type="primary">强制包含</el-tag>
          <el-tag v-else-if="data.source === 'OVERRIDE_REMOVE'" size="small" type="danger">强制排除</el-tag>
          <el-tag v-else-if="data.baseline" size="small" type="info">基线</el-tag>
        </span>
      </template>
    </el-tree>
    <el-empty v-else description="请选择角色查看权限矩阵" />
  </div>
</template>

<script setup>
/**
 * 16.2.2 权限矩阵（拆分自原权限管理操作台）：角色菜单可见性勾选（强制包含/排除覆盖）。
 * 共享角色全量列表见 usePermission.js。
 */
import { ref, computed, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/permission'
import { usePermission } from './usePermission'

const { matrixRoles, loadMatrixRoles } = usePermission()

const matrixRole = ref('')
const cells = ref([])
const treeRef = ref(null)

const treeData = computed(() => {
  const map = new Map()
  cells.value.forEach(c => map.set(c.id, { ...c, label: c.title, children: [] }))
  const roots = []
  cells.value.forEach(c => {
    const node = map.get(c.id)
    if (c.parentId && map.has(c.parentId)) map.get(c.parentId).children.push(node)
    else roots.push(node)
  })
  return roots
})
async function loadMatrix() {
  if (!matrixRole.value) return
  const { data } = await api.matrix(matrixRole.value)
  cells.value = data || []
  await nextTick()
  const effective = cells.value.filter(c => c.effective).map(c => c.id)
  treeRef.value?.setCheckedKeys(effective, false)
}
async function onTreeCheck(data, info) {
  const checked = (info.checkedKeys || []).includes(data.id)
  await api.setCell(matrixRole.value, data.id, checked)
  await loadMatrix()
}
async function clearOverrides() {
  await ElMessageBox.confirm(
    `清除角色 ${matrixRole.value} 的全部矩阵覆盖，恢复 099 基线口径？`, '确认清除',
    { type: 'warning' })
  await api.clearOverrides(matrixRole.value)
  ElMessage.success('已清除，回退基线')
  await loadMatrix()
}

onMounted(async () => {
  await loadMatrixRoles()
  loadMatrix()
})
</script>

<style scoped>
.perm-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.legend { display: flex; gap: 6px; margin-left: auto; }
.tree-node { display: inline-flex; gap: 8px; align-items: center; }
</style>
