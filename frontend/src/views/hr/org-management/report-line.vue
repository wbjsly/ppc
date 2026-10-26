<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">汇报关系（10.2.3）</span>
        <el-button @click="load">刷新</el-button>
      </div>
      <el-table :data="employees" height="560">
        <el-table-column prop="empNo" label="工号" width="130" />
        <el-table-column prop="name" label="姓名" width="120" />
        <el-table-column label="部门" width="140">
          <template #default="{ row }">{{ deptName(row.deptId) }}</template>
        </el-table-column>
        <el-table-column label="直接汇报人" width="180">
          <template #default="{ row }">
            <span v-if="row.reportToEmpId">{{ empName(row.reportToEmpId) }}</span>
            <el-tag v-else type="info" size="small">无</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.empStatus === 'RESIGNED' ? 'info' : 'success'" size="small">
              {{ statusText(row.empStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openSet(row)">设汇报人</el-button>
            <el-button link type="primary" size="small" @click="openChain(row)">汇报链</el-button>
            <el-button link type="primary" size="small" @click="openSubs(row)">下属</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 设汇报人 -->
    <el-dialog v-model="setVisible" :title="`设汇报人：${current.name}`" width="480px">
      <el-alert type="info" :closable="false" style="margin-bottom: 12px"
                title="防环校验：自汇报/成环/深度超限均拒绝；变更写变更日志（D2）" />
      <el-select v-model="reportToEmpId" filterable clearable placeholder="选择汇报人（空=清空）"
                 style="width: 100%">
        <el-option v-for="e in candidates" :key="e.id"
                   :label="`${e.name}（${e.empNo}）`" :value="e.id" />
      </el-select>
      <el-input v-model="reason" placeholder="变更原因" style="margin-top: 12px" />
      <template #footer>
        <el-button @click="setVisible = false">取消</el-button>
        <el-button type="primary" @click="submitSet">保存</el-button>
      </template>
    </el-dialog>

    <!-- 汇报链 -->
    <el-dialog v-model="chainVisible" :title="`汇报链：${current.name}`" width="480px">
      <el-timeline v-if="chain.length">
        <el-timeline-item v-for="(c, i) in chain" :key="c.empId" :timestamp="c.empNo">
          第 {{ i + 1 }} 级上级：{{ c.name }}
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="无上级（汇报链顶端）" />
    </el-dialog>

    <!-- 下属 -->
    <el-dialog v-model="subsVisible" :title="`直接下属：${current.name}`" width="480px">
      <el-table :data="subs">
        <el-table-column prop="empNo" label="工号" />
        <el-table-column prop="name" label="姓名" />
        <el-table-column prop="empStatus" label="状态" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  employeeList, deptList, setReportTo, reportAncestors, reportSubordinates
} from '@/api/hr/org-management'

const employees = ref([])
const depts = ref([])
const setVisible = ref(false)
const chainVisible = ref(false)
const subsVisible = ref(false)
const current = ref({})
const reportToEmpId = ref(null)
const reason = ref('')
const chain = ref([])
const subs = ref([])

const candidates = computed(() =>
  employees.value.filter(e => e.id !== current.value.id && e.empStatus !== 'RESIGNED'))

const deptName = id => depts.value.find(d => d.id === id)?.name || id || '—'
const empName = id => employees.value.find(e => e.id === id)?.name || id
const statusText = s => ({ PROBATION: '试用', REGULAR: '正式', RESIGNED: '离职' }[s] || s)

async function load() {
  const [e, d] = await Promise.all([employeeList(), deptList()])
  employees.value = e.data || []
  depts.value = d.data || []
}

function openSet(row) {
  current.value = row
  reportToEmpId.value = row.reportToEmpId
  reason.value = ''
  setVisible.value = true
}
async function submitSet() {
  try {
    await setReportTo(current.value.id, {
      reportToEmpId: reportToEmpId.value,
      reason: reason.value || '汇报关系维护'
    })
    ElMessage.success('已保存并留痕')
    setVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message || '保存失败')
  }
}
async function openChain(row) {
  current.value = row
  const { data } = await reportAncestors(row.id)
  chain.value = data?.ancestors || []
  chainVisible.value = true
}
async function openSubs(row) {
  current.value = row
  const { data } = await reportSubordinates(row.id)
  subs.value = data || []
  subsVisible.value = true
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
