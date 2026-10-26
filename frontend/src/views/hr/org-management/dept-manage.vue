<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">部门管理（10.2.1）</span>
        <div>
          <el-button type="primary" @click="openCreate">新建部门</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-table :data="depts" row-key="id" default-expand-all :tree-props="{ children: 'children' }">
        <el-table-column prop="deptNo" label="编号" width="120" />
        <el-table-column prop="name" label="部门名称" min-width="180" />
        <el-table-column label="部门主管" width="160">
          <template #default="{ row }">
            <span v-if="row.managerEmpId">{{ row.managerName || row.managerEmpId }}</span>
            <el-tag v-else type="info" size="small">未设置</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="成本中心" width="140">
          <template #default="{ row }">
            <span v-if="row.costCenter">{{ row.costCenter }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '1' ? 'success' : 'info'" size="small">
              {{ row.status === '1' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openManager(row)">设主管</el-button>
            <el-button link type="primary" size="small" @click="openCostCenter(row)">成本中心</el-button>
            <el-button link type="primary" size="small" @click="openMerge(row)">合并到…</el-button>
            <el-button v-if="row.status === '1'" link type="danger" size="small"
                       @click="deactivate(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建部门 -->
    <el-dialog v-model="createVisible" title="新建部门" width="440px">
      <el-form label-width="80px">
        <el-form-item label="名称">
          <el-input v-model="createForm.name" />
        </el-form-item>
        <el-form-item label="上级部门">
          <el-select v-model="createForm.parentId" clearable placeholder="无">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">确定</el-button>
      </template>
    </el-dialog>

    <!-- 设主管 -->
    <el-dialog v-model="managerVisible" :title="`设部门主管：${current.name}`" width="480px">
      <el-alert type="info" :closable="false" style="margin-bottom: 12px"
                title="主管须为本部门在职员工；变更走调整单审批留痕（D3）" />
      <el-select v-model="managerEmpId" filterable clearable placeholder="选择员工"
                 style="width: 100%">
        <el-option v-for="e in deptEmployees" :key="e.id"
                   :label="`${e.name}（${e.empNo}）`" :value="e.id" />
      </el-select>
      <template #footer>
        <el-button @click="managerVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!managerEmpId" @click="submitManager">提交审批</el-button>
      </template>
    </el-dialog>

    <!-- 成本中心 -->
    <el-dialog v-model="ccVisible" :title="`成本中心：${current.name}`" width="400px">
      <el-input v-model="costCenter" placeholder="成本中心编码" />
      <template #footer>
        <el-button @click="ccVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCostCenter">保存</el-button>
      </template>
    </el-dialog>

    <!-- 合并 -->
    <el-dialog v-model="mergeVisible" :title="`合并：${current.name} → …`" width="440px">
      <el-alert type="warning" :closable="false" style="margin-bottom: 12px"
                title="合并走审批；通过后员工/岗位/汇报线级联承接，源部门停用（S-4.1-05 HR 侧）" />
      <el-select v-model="mergeTargetId" filterable placeholder="承接部门" style="width: 100%">
        <el-option v-for="d in mergeCandidates" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <template #footer>
        <el-button @click="mergeVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!mergeTargetId" @click="submitMerge">提交审批</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  deptList, createDept, deactivateDept, setCostCenter,
  createMerge, createManagerChange, employeeList
} from '@/api/hr/org-management'

const depts = ref([])
const createVisible = ref(false)
const createForm = ref({ name: '', parentId: null })
const managerVisible = ref(false)
const managerEmpId = ref(null)
const ccVisible = ref(false)
const costCenter = ref('')
const mergeVisible = ref(false)
const mergeTargetId = ref(null)
const current = ref({})
const employees = ref([])

const deptEmployees = computed(() =>
  employees.value.filter(e => e.deptId === current.value.id && e.empStatus !== 'RESIGNED'))
const mergeCandidates = computed(() =>
  depts.value.filter(d => d.id !== current.value.id))

function buildTree(list) {
  const map = {}
  list.forEach(d => { map[d.id] = { ...d, children: [] } })
  const roots = []
  list.forEach(d => {
    const node = map[d.id]
    if (d.parentId && map[d.parentId]) map[d.parentId].children.push(node)
    else roots.push(node)
  })
  return roots
}

async function load() {
  const [d, e] = await Promise.all([deptList(), employeeList()])
  depts.value = buildTree(d.data || [])
  employees.value = e.data || []
}

function openCreate() {
  createForm.value = { name: '', parentId: null }
  createVisible.value = true
}
async function submitCreate() {
  if (!createForm.value.name) return ElMessage.warning('名称必填')
  await createDept(createForm.value)
  ElMessage.success('已创建')
  createVisible.value = false
  load()
}
function openManager(row) {
  current.value = row
  managerEmpId.value = row.managerEmpId
  managerVisible.value = true
}
async function submitManager() {
  await createManagerChange({ deptId: current.value.id, managerEmpId: managerEmpId.value })
  ElMessage.success('主管变更调整单已提交审批')
  managerVisible.value = false
  load()
}
function openCostCenter(row) {
  current.value = row
  costCenter.value = row.costCenter || ''
  ccVisible.value = true
}
async function submitCostCenter() {
  await setCostCenter(current.value.id, costCenter.value)
  ElMessage.success('已保存')
  ccVisible.value = false
  load()
}
function openMerge(row) {
  current.value = row
  mergeTargetId.value = null
  mergeVisible.value = true
}
async function submitMerge() {
  await createMerge({ sourceId: current.value.id, targetId: mergeTargetId.value })
  ElMessage.success('合并调整单已提交审批')
  mergeVisible.value = false
  load()
}
async function deactivate(row) {
  try {
    await deactivateDept(row.id)
    ElMessage.success('已停用')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message || '停用失败')
  }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
.muted { color: #999; }
</style>
