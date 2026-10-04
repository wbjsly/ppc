<template>
  <div class="menu-management">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">菜单管理</span>
          <div class="header-actions">
            <el-input
              v-model="keyword"
              placeholder="搜索菜单名称/编号"
              clearable
              :prefix-icon="Search"
              style="width: 220px;"
            />
            <el-button type="primary" :icon="Plus" @click="handleAdd()" />
          </div>
        </div>
      </template>

      <el-table
        :data="filteredTree" v-loading="loading" stripe
        row-key="id" default-expand-all
        :tree-props="{ children: 'children' }"
      >
        <el-table-column prop="menuCode" label="编号" width="90">
          <template #default="{ row }">{{ row.menuCode || '-' }}</template>
        </el-table-column>
        <el-table-column prop="title" label="菜单名称" min-width="180">
          <template #default="{ row }">
            <span>{{ row.title }}</span>
            <el-tag v-if="row.status === '0'" type="info" size="small" style="margin-left: 6px;">禁用</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="路由路径" width="160">
          <template #default="{ row }">{{ row.path || '-' }}</template>
        </el-table-column>
        <el-table-column prop="icon" label="图标" width="110">
          <template #default="{ row }">
            <span v-if="row.icon" style="display: inline-flex; align-items: center; gap: 4px;">
              <el-icon><component :is="row.icon" /></el-icon>{{ row.icon }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="70" />
        <el-table-column prop="perm" label="可见角色" width="200">
          <template #default="{ row }">
            <el-tag v-if="!row.perm" size="small" type="info">所有用户</el-tag>
            <el-tag v-for="r in (row.perm || '').split(',').filter(Boolean)" :key="r" size="small" style="margin-right: 4px;">
              {{ roleMap[r.trim()] || r.trim() }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === '1' ? 'success' : 'info'" size="small">{{ row.status === '1' ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <template v-if="depthOf(row) < 3">
              <el-tooltip content="新增子菜单">
                <el-button link type="success" :icon="Plus" @click="handleAdd(row.id)" />
              </el-tooltip>
            </template>
            <el-tooltip content="编辑">
              <el-button link type="primary" :icon="Edit" @click="handleEdit(row)" />
            </el-tooltip>
            <el-tooltip content="删除">
              <el-button link type="danger" :icon="Delete" @click="handleDelete(row)" />
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 表单弹窗 -->
    <el-dialog v-model="formVisible" :title="dialogTitle" width="560px" @closed="onClosed">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px;">
        本工程采用前端路由与菜单数据分离：新建菜单路径会被占位路由承接，对应页面开发完成后在
        <code>router/index.js</code> 注册正式路由即可生效。
      </el-alert>

      <el-form :model="form" :rules="rules" ref="formRef" label-width="100px">
        <el-form-item label="上级菜单" prop="parentId">
          <el-select v-model="form.parentId" style="width: 100%;" filterable>
            <el-option label="（顶级菜单）" value="" />
            <el-option
              v-for="p in parentOptions"
              :key="p.id"
              :label="p.label"
              :value="p.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="菜单编号" prop="menuCode">
          <el-input v-model="form.menuCode" placeholder="对应 08 清单编号，如 16.2（可空）" />
        </el-form-item>
        <el-form-item label="菜单名称" prop="title">
          <el-input v-model="form.title" placeholder="如：角色权限" />
        </el-form-item>
        <el-form-item label="路由路径" prop="path">
          <el-input v-model="form.path" placeholder="如：/system/role；目录菜单留空" />
        </el-form-item>
        <el-form-item label="图标">
          <el-input v-model="form.icon" placeholder="Element Plus 图标名，如：Setting" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :step="10" />
        </el-form-item>
        <el-form-item label="可见角色">
          <el-select v-model="permList" multiple placeholder="不选 = 所有登录用户可见" style="width: 100%;">
            <el-option v-for="r in roleOptions" :key="r.roleCode" :label="r.roleName" :value="r.roleCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="1">启用</el-radio>
            <el-radio value="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onActivated } from 'vue'
import { getMenuListApi, createMenuApi, updateMenuApi, deleteMenuApi } from '@/api/system/menu'
import { getRoleListApi } from '@/api/system/role'
import { useMenuStore } from '@/store/menu'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Search } from '@element-plus/icons-vue'

const menuStore = useMenuStore()
const menuList = ref([])
const loading = ref(false)
const keyword = ref('')
const formVisible = ref(false)
const submitLoading = ref(false)
const formRef = ref(null)
const editId = ref('')
const isEdit = ref(false)
const roleOptions = ref([])

const roleMap = computed(() => {
  const map = {}
  for (const r of roleOptions.value) {
    map[r.roleCode] = r.roleName
  }
  return map
})

const form = reactive({
  title: '', path: '', icon: '', sortOrder: 0, status: '1', parentId: '', menuCode: ''
})
const permList = ref([])

/** 平铺列表 → 树（任意层级），逐层按 sortOrder 排序 */
const treeData = computed(() => {
  const map = {}
  const roots = []
  menuList.value.forEach(item => {
    map[item.id] = { ...item, children: [] }
  })
  menuList.value.forEach(item => {
    const node = map[item.id]
    if (item.parentId && map[item.parentId]) {
      map[item.parentId].children.push(node)
    } else {
      roots.push(node)
    }
  })
  const sortRec = list => {
    list.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
    list.forEach(n => sortRec(n.children))
  }
  sortRec(roots)
  return roots
})

const filteredTree = computed(() => {
  if (!keyword.value) return treeData.value
  const kw = keyword.value.toLowerCase()
  const filterNode = nodes =>
    nodes.reduce((acc, node) => {
      const hit = node.title.toLowerCase().includes(kw) ||
        (node.menuCode || '').toLowerCase().includes(kw)
      const children = filterNode(node.children)
      if (hit || children.length > 0) {
        acc.push({ ...node, children: hit ? node.children : children })
      }
      return acc
    }, [])
  return filterNode(treeData.value)
})

/** 节点深度：顶级=1，最多允许 3 级（对齐 08 文档层级） */
function depthOf(node) {
  let depth = 1
  let current = menuList.value.find(m => m.id === node.id)
  const seen = new Set()
  while (current && current.parentId && !seen.has(current.id)) {
    seen.add(current.id)
    depth++
    current = menuList.value.find(m => m.id === current.parentId)
  }
  return depth
}

/** 上级菜单候选：排除自身与自身后代，且其深度 < 3 */
const parentOptions = computed(() => {
  const descendants = new Set()
  if (editId.value) {
    const collect = parentId => {
      menuList.value
        .filter(m => m.parentId === parentId)
        .forEach(child => {
          descendants.add(child.id)
          collect(child.id)
        })
    }
    collect(editId.value)
  }
  return menuList.value
    .filter(m => m.id !== editId.value && !descendants.has(m.id) && depthOf(m) < 3)
    .map(m => ({
      id: m.id,
      label: `${m.menuCode ? m.menuCode + ' ' : ''}${'　'.repeat(depthOf(m) - 1)}${m.title}`
    }))
})

const dialogTitle = computed(() => {
  if (isEdit.value) return '编辑菜单'
  return form.parentId ? '新增子菜单' : '新增菜单'
})

const rules = {
  title: [{ required: true, message: '请输入菜单名称', trigger: 'blur' }]
}

async function loadData() {
  loading.value = true
  try {
    const res = await getMenuListApi()
    menuList.value = res.data || []
  } catch {
    // 弹错由拦截器统一处理
  } finally {
    loading.value = false
  }
}

async function fetchRoles() {
  try {
    const res = await getRoleListApi()
    roleOptions.value = res.data || []
  } catch {
    // ignore
  }
}

function handleAdd(parentId = '') {
  Object.assign(form, {
    title: '', path: '', icon: '', sortOrder: 0, status: '1', parentId, menuCode: ''
  })
  permList.value = []
  editId.value = ''
  isEdit.value = false
  formVisible.value = true
}

function handleEdit(row) {
  Object.assign(form, {
    title: row.title,
    path: row.path || '',
    icon: row.icon || '',
    sortOrder: row.sortOrder || 0,
    status: row.status,
    parentId: row.parentId || '',
    menuCode: row.menuCode || ''
  })
  permList.value = (row.perm || '').split(',').filter(Boolean).map(s => s.trim())
  editId.value = row.id
  isEdit.value = true
  formVisible.value = true
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除菜单「${row.title}」？存在子菜单时将被拒绝。`,
      '提示',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteMenuApi(row.id)
    ElMessage.success('删除成功')
    await refreshAll()
  } catch {
    // 后端拒绝（如存在子菜单）的错误提示已由拦截器展示，此处静默
  }
}

async function handleSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitLoading.value = true
  try {
    const data = {
      ...form,
      perm: permList.value.join(',')
    }
    if (isEdit.value) {
      data.id = editId.value
      await updateMenuApi(data)
    } else {
      await createMenuApi(data)
    }
    ElMessage.success('保存成功')
    formVisible.value = false
    await refreshAll()
  } catch {
    // 弹错由拦截器统一处理
  } finally {
    submitLoading.value = false
  }
}

function onClosed() {
  formRef.value?.clearValidate()
}

/** 保存/删除后同时刷新管理表格与侧边栏菜单 */
async function refreshAll() {
  menuStore.reset()
  menuStore.fetchMenus().catch(() => {})
  await loadData()
}

onMounted(() => {
  loadData()
  fetchRoles()
})

onActivated(() => {
  loadData()
  fetchRoles()
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
