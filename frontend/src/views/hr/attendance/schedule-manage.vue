<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">排班管理（10.3.1）</span>
        <div>
          <el-date-picker v-model="period" type="month" value-format="YYYYMM" style="width:130px;margin-right:8px" @change="load" />
          <el-button type="primary" @click="openBulk">部门批量</el-button>
          <el-button @click="doCopy">复制上月</el-button>
          <el-button @click="openShift">新建班次</el-button>
        </div>
      </div>
      <el-table :data="matrix" height="520" v-loading="loading">
        <el-table-column prop="empNo" label="工号" width="120" />
        <el-table-column prop="name" label="姓名" width="110" />
        <el-table-column label="当月排班" min-width="520">
          <template #default="{ row }">
            <el-tag v-for="s in row.schedules" :key="s.id" size="small" :type="s.isRest === '1' ? 'info' : 'success'"
                    style="margin:2px">
              {{ (s.workDate || '').slice(8) }}日 {{ s.isRest === '1' ? '休' : (shiftName(s.shiftId) || '班') }}
            </el-tag>
            <span v-if="!row.schedules || !row.schedules.length" class="muted">未排班（默认标准工时）</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openSingle(row)">单条排班</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 班次 -->
    <el-dialog v-model="shiftVisible" title="新建班次" width="440px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="shiftForm.name" /></el-form-item>
        <el-form-item label="上班时间"><el-time-picker v-model="shiftForm.startTime" format="HH:mm" value-format="HH:mm" style="width:100%" /></el-form-item>
        <el-form-item label="下班时间"><el-time-picker v-model="shiftForm.endTime" format="HH:mm" value-format="HH:mm" style="width:100%" /></el-form-item>
        <el-form-item label="容差(分)"><el-input-number v-model="shiftForm.toleranceMin" :min="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="shiftVisible=false">取消</el-button>
        <el-button type="primary" @click="submitShift">确定</el-button>
      </template>
    </el-dialog>

    <!-- 部门批量 -->
    <el-dialog v-model="bulkVisible" title="部门批量默认班次" width="440px">
      <el-form label-width="80px">
        <el-form-item label="部门">
          <el-select v-model="bulkForm.deptId" style="width:100%">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="班次">
          <el-select v-model="bulkForm.shiftId" style="width:100%">
            <el-option v-for="s in shifts" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期范围">
          <el-date-picker v-model="bulkRange" type="daterange" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bulkVisible=false">取消</el-button>
        <el-button type="primary" @click="submitBulk">确定</el-button>
      </template>
    </el-dialog>

    <!-- 单条排班 -->
    <el-dialog v-model="singleVisible" :title="`排班：${current.name}`" width="400px">
      <el-form label-width="80px">
        <el-form-item label="日期"><el-date-picker v-model="singleForm.workDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="班次">
          <el-select v-model="singleForm.shiftId" clearable style="width:100%" placeholder="选休息请勾选休息日">
            <el-option v-for="s in shifts" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="休息日"><el-switch v-model="singleForm.rest" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="singleVisible=false">取消</el-button>
        <el-button type="primary" @click="submitSingle">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  shiftList, createShift, schedule, bulkSchedule, copyMonth, deptMatrix
} from '@/api/hr/attendance'
import { deptList } from '@/api/hr/org-management'

const period = ref(new Date().toISOString().slice(0, 7).replace('-', ''))
const matrix = ref([])
const shifts = ref([])
const depts = ref([])
const loading = ref(false)
const shiftVisible = ref(false)
const shiftForm = ref({ name: '', startTime: '09:00', endTime: '18:00', toleranceMin: 10 })
const bulkVisible = ref(false)
const bulkForm = ref({ deptId: null, shiftId: null })
const bulkRange = ref([])
const singleVisible = ref(false)
const singleForm = ref({ workDate: '', shiftId: null, rest: false })
const current = ref({})

const shiftName = id => shifts.value.find(s => s.id === id)?.name || ''

async function load() {
  loading.value = true
  try {
    const [m, s, d] = await Promise.all([deptMatrix({ period: period.value }), shiftList(), deptList()])
    matrix.value = m.data || []
    shifts.value = s.data || []
    depts.value = d.data || []
  } finally {
    loading.value = false
  }
}

function openShift() { shiftForm.value = { name: '', startTime: '09:00', endTime: '18:00', toleranceMin: 10 }; shiftVisible.value = true }
async function submitShift() {
  try { await createShift(shiftForm.value); ElMessage.success('班次已建'); shiftVisible.value = false; load() }
  catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
function openBulk() { bulkVisible.value = true }
async function submitBulk() {
  if (!bulkForm.value.deptId || !bulkForm.value.shiftId || !bulkRange.value?.length) return ElMessage.warning('部门/班次/日期必选')
  await bulkSchedule({ deptId: bulkForm.value.deptId, shiftId: bulkForm.value.shiftId, fromDate: bulkRange.value[0], toDate: bulkRange.value[1] })
  ElMessage.success('批量排班完成'); bulkVisible.value = false; load()
}
async function doCopy() {
  const from = new Date(period.value.slice(0, 4), Number(period.value.slice(4)) - 2, 1)
  const prev = from.getFullYear().toString() + String(from.getMonth() + 1).padStart(2, '0')
  const out = await copyMonth(prev, period.value)
  ElMessage.success(`复制完成：${out.data?.copied} 条，冲突覆盖 ${out.data?.conflicts} 条（L4）`)
  load()
}
function openSingle(row) { current.value = row; singleForm.value = { workDate: '', shiftId: null, rest: false }; singleVisible.value = true }
async function submitSingle() {
  if (!singleForm.value.workDate) return ElMessage.warning('日期必填')
  await schedule({ empId: current.value.empId, workDate: singleForm.value.workDate, shiftId: singleForm.value.shiftId, isRest: singleForm.value.rest ? '1' : '0' })
  ElMessage.success('已排班（同日重排以最后一次为准）'); singleVisible.value = false; load()
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
.muted { color: #999; }
</style>
