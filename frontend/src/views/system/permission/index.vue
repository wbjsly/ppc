<template>
  <div class="perm-page">
    <el-tabs v-model="tab">
      <!-- ================= 16.2.1 角色管理 ================= -->
      <el-tab-pane label="角色管理" name="roles">
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
      </el-tab-pane>

      <!-- ================= 16.2.2 权限矩阵 ================= -->
      <el-tab-pane label="权限矩阵" name="matrix">
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
      </el-tab-pane>

      <!-- ================= 16.2.3 岗位基线 ================= -->
      <el-tab-pane label="岗位基线" name="baseline">
        <div class="toolbar">
          <el-input v-model="baselineKeyword" placeholder="岗位名称/编码" clearable
            style="width: 220px" @keyup.enter="loadBaselines" @clear="loadBaselines" />
          <el-button type="primary" @click="loadBaselines">查询</el-button>
          <el-tag type="warning" effect="plain">无基线岗位建号不授予任何角色</el-tag>
        </div>
        <el-table :data="baselines" v-loading="baselineLoading" border stripe>
          <el-table-column prop="posNo" label="岗位编码" width="140" />
          <el-table-column prop="posName" label="岗位名称" min-width="150" />
          <el-table-column prop="deptName" label="所属部门" width="140" />
          <el-table-column label="基线角色" min-width="260">
            <template #default="{ row }">
              <template v-if="row.roles && row.roles.length">
                <el-tag v-for="rc in row.roles" :key="rc" size="small"
                  :type="isPrivileged(rc) ? 'danger' : 'default'"
                  style="margin-right: 4px">{{ rc }}</el-tag>
              </template>
              <el-tag v-else-if="!row.hasBaseline" type="warning" effect="plain">未配置</el-tag>
              <span v-else class="muted">（空）</span>
            </template>
          </el-table-column>
          <el-table-column label="违规" width="150">
            <template #default="{ row }">
              <el-tag v-if="row.violations && row.violations.length" type="danger">
                {{ row.violations.join(',') }}
              </el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button link type="primary" @click="openBaselineEdit(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

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

    <!-- 岗位基线编辑对话框 -->
    <el-dialog v-model="baselineDlg" :title="'编辑基线：' + baselineForm.posName" width="520px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 10px"
        title="基线禁含审批权/金额权角色（L1 硬阻断，保存时校验）" />
      <el-select v-model="baselineForm.roles" multiple filterable allow-create default-first-option
        placeholder="选择或输入角色码" style="width: 100%">
        <el-option v-for="r in matrixRoles" :key="r.roleCode" :label="r.roleCode + '（' + r.roleName + '）'"
          :value="r.roleCode" />
      </el-select>
      <template #footer>
        <el-button @click="baselineDlg = false">取消</el-button>
        <el-button type="primary" @click="submitBaseline">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/permission'

const route = useRoute()
const router = useRouter()

// Tab 初始定位（沿 12.9/16.1 模式）：meta.tab 优先，白名单外回落 roles
const TAB_WHITELIST = ['roles', 'matrix', 'baseline']
const pickTab = () => {
  const m = route.meta?.tab
  const q = route.query?.tab
  if (TAB_WHITELIST.includes(m)) return m
  if (TAB_WHITELIST.includes(q)) return q
  return 'roles'
}
const tab = ref(pickTab())
watch(() => route.meta?.tab, () => { tab.value = pickTab() })

// ---------- 角色管理 ----------
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

// ---------- 权限矩阵 ----------
const matrixRoles = ref([])
const matrixRole = ref('')
const cells = ref([])
const treeRef = ref(null)
let matrixLoaded = false

async function loadMatrixRoles() {
  const { data } = await api.roleListAll()
  matrixRoles.value = data || []
}
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

// ---------- 岗位基线 ----------
const baselines = ref([])
const baselineLoading = ref(false)
const baselineKeyword = ref('')
const baselineDlg = ref(false)
const baselineForm = ref({ posId: '', posName: '', roles: [] })
const PRIVILEGED = new Set([
  'ROLE_ADMIN', 'ROLE_ASSET_ADMIN', 'ROLE_COMPLIANCE', 'ROLE_DEPT_HEAD', 'ROLE_EQUIP_MGR',
  'ROLE_EXEC', 'ROLE_FINANCE', 'ROLE_FINANCE_MGR', 'ROLE_FIN_ASSET', 'ROLE_FIN_MGR', 'ROLE_GM',
  'ROLE_HR', 'ROLE_HR_MGR', 'ROLE_PLAN_MGR', 'ROLE_PM', 'ROLE_PMGR', 'ROLE_PMO',
  'ROLE_PROCESS_MGR', 'ROLE_PURCHASER', 'ROLE_QUALITY_MGR', 'ROLE_SALES_DIRECTOR',
  'ROLE_SALES_MGR', 'ROLE_TECH_OWNER', 'ROLE_WAREHOUSE'
])
function isPrivileged(code) { return PRIVILEGED.has(code) }

async function loadBaselines() {
  baselineLoading.value = true
  try {
    const { data } = await api.baselineList({ keyword: baselineKeyword.value || undefined })
    baselines.value = data || []
  } finally {
    baselineLoading.value = false
  }
}
function openBaselineEdit(row) {
  baselineForm.value = { posId: row.posId, posName: row.posName, roles: [...(row.roles || [])] }
  baselineDlg.value = true
}
async function submitBaseline() {
  await api.saveBaseline(baselineForm.value.posId, baselineForm.value.roles)
  ElMessage.success('基线已保存')
  baselineDlg.value = false
  loadBaselines()
}

watch(tab, t => {
  if (t === 'matrix' && !matrixLoaded) { matrixLoaded = true; loadMatrix() }
  if (t === 'baseline' && !baselines.value.length) loadBaselines()
})

onMounted(async () => {
  await Promise.all([loadRoles(), loadMatrixRoles()])
  if (tab.value === 'matrix') { matrixLoaded = true; loadMatrix() }
  if (tab.value === 'baseline') loadBaselines()
})
</script>

<style scoped>
.perm-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.legend { display: flex; gap: 6px; margin-left: auto; }
.tree-node { display: inline-flex; gap: 8px; align-items: center; }
.muted { color: #909399; }
</style>
