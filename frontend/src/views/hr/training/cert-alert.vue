<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>资格认证到期预警（10.9.3，BR-4.7-06：到期前 60 天复审提醒 · 失效限制排班）</span>
          <div>
            <el-button size="small" @click="load">刷新</el-button>
            <el-button size="small" type="warning" @click="flipScan">手动翻转扫描</el-button>
          </div>
        </div>
      </template>

      <el-tabs v-model="tab">
        <el-tab-pane :label="`临期复审（${lists.reviewDue.length}）`" name="due">
          <el-table :data="dueRows" size="small" border>
            <el-table-column prop="empNo" label="工号" width="90" />
            <el-table-column prop="empName" label="姓名" width="90" />
            <el-table-column prop="cateName" label="证书类别" min-width="160" />
            <el-table-column prop="validTo" label="到期日" width="110" />
            <el-table-column label="剩余天数" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.daysLeft <= 15 ? 'danger' : 'warning'">{{ row.daysLeft }} 天</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="empName2" label="提醒" width="120">
              <template #default>已推送 HR 与员工</template>
            </el-table-column>
          </el-table>
          <div class="muted mtips">临期由每日任务自动翻转（VALID→REVIEW_DUE）并推送站内通知与 hr_remind，未读不重发。</div>
        </el-tab-pane>

        <el-tab-pane :label="`已失效（${lists.expired.length}）`" name="expired">
          <el-table :data="expiredRows" size="small" border>
            <el-table-column prop="empNo" label="工号" width="90" />
            <el-table-column prop="empName" label="姓名" width="90" />
            <el-table-column prop="cateName" label="证书类别" min-width="160" />
            <el-table-column prop="validTo" label="到期日" width="110" />
            <el-table-column label="门禁" width="110">
              <template #default>
                <el-tag size="small" type="danger">限制排班上岗</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <div class="muted mtips">恢复须员工重新提交证书并经 HR 审核通过（重新认证），门禁自动解除。</div>
        </el-tab-pane>

        <el-tab-pane :label="`带病排班（${sick.length}）`" name="sick">
          <el-table :data="sick" size="small" border>
            <el-table-column prop="empNo" label="工号" width="90" />
            <el-table-column prop="empName" label="姓名" width="90" />
            <el-table-column prop="deptName" label="部门" width="120" />
            <el-table-column prop="workDate" label="排班日" width="110" />
            <el-table-column prop="shiftName" label="班次" width="110" />
            <el-table-column label="失效证书" min-width="180">
              <template #default="{ row }">
                <el-tag size="small" type="danger">{{ row.certCate }}</el-tag>
                <span class="muted"> 到期 {{ row.certValidTo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="处置" width="180">
              <template #default="{ row }">
                <el-button link type="danger" size="small" @click="dispose(row, 'CANCEL')">取消排班</el-button>
                <el-button link type="primary" size="small" @click="dispose(row, 'REASSIGN')">改派他人</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="muted mtips">门禁上线前已排的未来班次不回溯删除，在此人工处置（HR 或本部门主管）；新排班已被系统直接拦截。</div>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { certAlertLists, certFlipScan, sickSchedules, sickDispose } from '@/api/hr/training'
import { empList } from '@/api/hr/employee-archive'

const tab = ref('due')
const lists = ref({ reviewDue: [], expired: [], sickSchedules: [] })
const sick = ref([])
const emps = ref([])

const dueRows = computed(() => (lists.value.reviewDue || []).map(r => ({ ...r.cert, daysLeft: r.daysLeft })))
const expiredRows = computed(() => (lists.value.expired || []).map(r => ({ ...r.cert })))

async function load() {
  const [{ data }, { data: ss }, { data: es }] = await Promise.all([
    certAlertLists(), sickSchedules(), empList({})
  ])
  lists.value = data
  sick.value = ss || []
  emps.value = es || []
}

async function flipScan() {
  try {
    const { data } = await certFlipScan()
    ElMessage.success(`翻转完成：临期 ${data.reviewDue} 条，失效 ${data.expired} 条`)
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

async function dispose(row, action) {
  let newEmpId = null
  if (action === 'CANCEL') {
    try {
      await ElMessageBox.confirm(`取消 ${row.empName} 在 ${row.workDate} 的排班？`, '处置确认', { type: 'warning' })
    } catch { return }
  } else {
    try {
      const { value } = await ElMessageBox.prompt('改派给谁？输入目标员工 ID 或工号（从员工档案查询）',
        '改派他人', { inputPlaceholder: '员工 ID' })
      const target = emps.value.find(e => e.id === value || e.empNo === value)
      if (!target) { ElMessage.warning('未找到该员工'); return }
      newEmpId = target.id
    } catch { return }
  }
  try {
    const { data } = await sickDispose(row.scheduleId, { action, newEmpId })
    ElMessage.success(data.result)
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.muted { color: #909399; font-size: 12px; }
.mtips { margin-top: 8px; }
</style>
