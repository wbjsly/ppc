<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>信息维护（10.1.2）—— 档案列表与变更日志</span>
          <el-button type="primary" size="small" @click="loadAll">刷新</el-button>
        </div>
      </template>

      <!-- 试用期提醒 -->
      <el-alert v-if="remindings.length" :closable="false" type="warning" show-icon class="mb8"
        :title="`试用期 ≤15 天提醒：${remindings.map(r => r.name + '(' + r.probationEnd + ')').join('、')}`" />

      <el-form inline class="mb8" size="small">
        <el-form-item label="部门">
          <el-select v-model="query.deptId" clearable style="width:150px">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable style="width:120px">
            <el-option label="试用期" value="PROBATION" />
            <el-option label="正式" value="REGULAR" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字"><el-input v-model="query.keyword" style="width:140px" /></el-form-item>
        <el-form-item><el-button type="primary" @click="loadAll">查询</el-button></el-form-item>
      </el-form>

      <el-table :data="emps" size="small" border>
        <el-table-column label="工号" width="110">
          <template #default="{ row }">{{ row.employee.empNo }}</template>
        </el-table-column>
        <el-table-column label="姓名" width="100">
          <template #default="{ row }">{{ row.employee.name }}</template>
        </el-table-column>
        <el-table-column label="身份证号" width="170">
          <template #default="{ row }">{{ mask(row.employee.idCardNo) }}</template>
        </el-table-column>
        <el-table-column label="部门/岗位" min-width="140">
          <template #default="{ row }">{{ deptName(row.employee.deptId) }} / {{ posName(row.employee.posId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.employee.empStatus === 'PROBATION' ? 'warning'
              : row.employee.empStatus === 'REGULAR' ? 'success' : 'info'" size="small">
              {{ empStatusName(row.employee.empStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="试用期" width="120">
          <template #default="{ row }">
            <span v-if="row.probationWarning" style="color:#E6A23C">剩 {{ row.probationDaysLeft }} 天</span>
            <span v-else-if="row.probationDaysLeft != null">{{ row.probationDaysLeft }} 天</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="外派/借调" width="130">
          <template #default="{ row }">
            <el-tag v-if="secFlags[row.employee.id]" type="warning" size="small">
              {{ secFlags[row.employee.id] }}
            </el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row.employee)">详情/日志</el-button>
            <el-button link type="primary" size="small" @click="openEdit(row.employee)">修改</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 详情/变更日志 -->
    <el-dialog v-model="detailVisible" :title="`档案详情 - ${detailEmp?.name || ''}`" width="720px">
      <el-descriptions v-if="detailEmp" :column="2" border size="small">
        <el-descriptions-item label="工号">{{ detailEmp.empNo }}</el-descriptions-item>
        <el-descriptions-item label="身份证号">{{ detailEmp.idCardNo }}</el-descriptions-item>
        <el-descriptions-item label="入职日期">{{ detailEmp.hireDate }}</el-descriptions-item>
        <el-descriptions-item label="合同起止">{{ detailEmp.contractStart }} ~ {{ detailEmp.contractEnd }}</el-descriptions-item>
        <el-descriptions-item label="社保缴纳地">{{ detailEmp.socialPlace || '—' }}</el-descriptions-item>
        <el-descriptions-item label="系统账号">{{ detailEmp.userId ? '已关联' : '待开通审批' }}</el-descriptions-item>
      </el-descriptions>
      <div class="log-title">变更日志（BR-4.7-07 全量留痕，不可删）</div>
      <el-table :data="logs" size="small" max-height="260">
        <el-table-column prop="fieldName" label="字段" width="110" />
        <el-table-column prop="oldValue" label="前值" width="130" />
        <el-table-column prop="newValue" label="后值" width="130" />
        <el-table-column prop="reason" label="原因" min-width="120" show-overflow-tooltip />
        <el-table-column prop="changeBy" label="操作人" width="90" />
        <el-table-column prop="changeAt" label="时间" width="150" />
      </el-table>
    </el-dialog>

    <!-- 修改 -->
    <el-dialog v-model="editVisible" :title="`修改档案 - ${editEmp?.name || ''}`" width="520px">
      <el-form label-width="90px">
        <el-form-item label="姓名"><el-input v-model="editForm.name" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="editForm.phone" /></el-form-item>
        <el-form-item label="部门">
          <el-select v-model="editForm.deptId" style="width:100%">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="岗位">
          <el-select v-model="editForm.posId" style="width:100%">
            <el-option v-for="p in positions" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="变更原因"><el-input v-model="editForm.reason" /></el-form-item>
        <div class="muted">身份证号/工号锁定（BR-4.7-07）：直改 422，须走「锁定特批」（L2）</div>
        <el-form-item label=" " style="margin-top:8px">
          <el-button size="small" @click="openLockChange">锁定字段特批</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="doEdit">保存（留痕）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { empList, empDetail, empUpdate, empLockChange, probationRemindings,
  deptList, posList } from '@/api/hr/employee-archive'
import { secInFlightFlags } from '@/api/hr/secondment'

const emps = ref([])
const secFlags = ref({})
const depts = ref([])
const positions = ref([])
const remindings = ref([])
const logs = ref([])
const query = ref({ deptId: '', status: '', keyword: '' })
const detailVisible = ref(false)
const detailEmp = ref(null)
const editVisible = ref(false)
const editEmp = ref(null)
const editForm = ref({})

const empStatusName = (s) => ({ PROBATION: '试用期', REGULAR: '正式', RESIGNED: '离职' }[s] || s)
const deptName = (id) => depts.value.find(d => d.id === id)?.name || id || '—'
const posName = (id) => positions.value.find(p => p.id === id)?.name || id || '—'
const mask = (v) => v ? v.slice(0, 6) + '********' : '—'

async function loadAll() {
  try { depts.value = (await deptList()).data || [] } catch (e) { /* ignore */ }
  try { positions.value = (await posList({})).data || [] } catch (e) { /* ignore */ }
  try { secFlags.value = (await secInFlightFlags()).data || {} } catch (e) { /* ignore */ }
  try {
    emps.value = (await empList({ ...query.value })).data || []
  } catch (e) { /* ignore */ }
  try { remindings.value = (await probationRemindings()).data || [] } catch (e) { /* ignore */ }
}

async function openDetail(emp) {
  detailEmp.value = emp
  detailVisible.value = true
  try {
    const { data } = await empDetail(emp.id)
    detailEmp.value = data.employee
    logs.value = data.changeLogs || []
  } catch (e) { /* ignore */ }
}

function openEdit(emp) {
  editEmp.value = emp
  editForm.value = { name: emp.name, phone: emp.phone, deptId: emp.deptId, posId: emp.posId, reason: '' }
  editVisible.value = true
}

async function doEdit() {
  try {
    await empUpdate(editEmp.value.id, editForm.value)
    ElMessage.success('已保存（变更日志留痕）')
    editVisible.value = false
    loadAll()
  } catch (e) { /* ignore */ }
}

async function openLockChange() {
  const { value: field } = await ElMessageBox.prompt('锁定字段（idCardNo / empNo）', '锁定特批', {
    inputValidator: (v) => ['idCardNo', 'empNo'].includes(v) || '仅支持 idCardNo / empNo'
  })
  const { value: newValue } = await ElMessageBox.prompt('新值', '新值', {
    inputValidator: (v) => (v && v.trim()) || '必填'
  })
  const { value: reason } = await ElMessageBox.prompt('特批原因', '原因', {
    inputValidator: (v) => (v && v.trim()) || '必填'
  })
  try {
    await empLockChange(editEmp.value.id, { field, newValue, reason })
    ElMessage.success('特批申请已提交（HR 经理审批）')
  } catch (e) { /* ignore */ }
}

onMounted(loadAll)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.mb8 { margin-bottom: 8px; }
.log-title { font-weight: 600; margin: 12px 0 8px; }
.muted { color: #909399; font-size: 12px; }
</style>
