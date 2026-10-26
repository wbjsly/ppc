<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">考勤终审（10.3.5）</span>
        <div>
          <el-date-picker v-model="period" type="month" value-format="YYYYMM" style="width:130px;margin-right:8px" @change="load" />
          <el-button type="primary" :disabled="!mgrConfirmed.length" @click="doFinalize">终审锁定（{{ mgrConfirmed.length }} 条）</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>

      <!-- 待终审（主管已确认） -->
      <el-table :data="mgrConfirmed" height="220" style="margin-bottom:12px">
        <el-table-column prop="empNo" label="工号" width="110" />
        <el-table-column prop="empName" label="姓名" width="100" />
        <el-table-column prop="excDate" label="日期" width="110" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small">{{ { LATE: '迟到', EARLY: '早退', MISSING: '缺卡', ABSENT: '旷工' }[row.excType] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="confirmOpinion" label="主管意见" min-width="160" />
      </el-table>

      <!-- 月度汇总（锁定+挂起标记） -->
      <el-table :data="summary" height="280">
        <el-table-column prop="empNo" label="工号" width="110" />
        <el-table-column prop="name" label="姓名" width="100" />
        <el-table-column prop="lateCount" label="迟到" width="70" />
        <el-table-column prop="earlyCount" label="早退" width="70" />
        <el-table-column prop="missingCount" label="缺卡" width="70" />
        <el-table-column prop="absentDays" label="旷工" width="70" />
        <el-table-column label="请假扣款" width="110">
          <template #default="{ row }">¥{{ row.leaveDeduct || 0 }}</template>
        </el-table-column>
        <el-table-column label="加班时长" width="90">
          <template #default="{ row }">{{ row.overtimeHours || 0 }}h</template>
        </el-table-column>
        <el-table-column label="加班工资" width="100">
          <template #default="{ row }">¥{{ row.overtimeWage || 0 }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.pendingHold" type="danger" size="small">挂起 C-4.7-01</el-tag>
            <el-tag v-else-if="row.locked" type="success" size="small">已锁定</el-tag>
            <el-tag v-else type="info" size="small">未锁定</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button v-if="row.locked" link type="warning" size="small" @click="openCorrect(row)">更正</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 更正 -->
    <el-dialog v-model="correctVisible" :title="`更正：${current.name}`" width="440px">
      <el-alert type="warning" :closable="false" style="margin-bottom:12px"
                title="更正将解锁该员工该月汇总行，重聚合后可重新终审" />
      <el-input v-model="correctReason" type="textarea" :rows="3" placeholder="更正原因（留痕）" />
      <template #footer>
        <el-button @click="correctVisible=false">取消</el-button>
        <el-button type="primary" @click="submitCorrect">提交更正</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { exceptionList, finalize, attendanceSummary, correct } from '@/api/hr/attendance'

const period = ref(new Date().toISOString().slice(0, 7).replace('-', ''))
const mgrConfirmed = ref([])
const summary = ref([])
const correctVisible = ref(false)
const correctReason = ref('')
const current = ref({})

async function load() {
  const [e, s] = await Promise.all([
    exceptionList({ status: 'MGR_CONFIRMED' }),
    attendanceSummary(period.value)
  ])
  mgrConfirmed.value = e.data || []
  summary.value = s.data || []
}

async function doFinalize() {
  try {
    const out = await finalize({ period: period.value })
    ElMessage.success(`终审完成：${out.data?.finalized} 条，锁定 ${out.data?.lockedEmployees} 人`)
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
function openCorrect(row) { current.value = row; correctReason.value = ''; correctVisible.value = true }
async function submitCorrect() {
  if (!correctReason.value) return ElMessage.warning('更正原因必填')
  try {
    const empExceptions = await exceptionList({ status: 'HR_FINAL' })
    const target = (empExceptions.data || []).find(x => x.empId === current.value.empId)
    if (!target) return ElMessage.warning('未找到该员工可更正的终审异常')
    await correct({ exceptionId: target.id, reason: correctReason.value })
    ElMessage.success('更正已受理（解锁重聚合）'); correctVisible.value = false; load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
