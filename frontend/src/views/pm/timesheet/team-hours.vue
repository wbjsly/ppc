<template>
  <div class="team-hours">
    <el-card style="margin-bottom: 12px;">
      <div class="toolbar">
        <el-select v-model="empId" placeholder="按员工过滤（留空=全部）" filterable clearable style="width: 240px;">
          <el-option v-for="e in employees" :key="e.empId" :value="e.empId" :label="`${e.empName}（${e.empId}）`" />
        </el-select>
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
          start-placeholder="开始" end-placeholder="结束" style="width: 260px;" />
        <el-button type="primary" :icon="Refresh" @click="load">查询</el-button>
        <el-button @click="$router.push('/m/11.5.2')">去审核 →</el-button>
      </div>
      <div class="hint">团队工时看板：项目经理/PMO 查看全项目工时明细（员工填报走「我的工时」自助页）。</div>
    </el-card>

    <el-card>
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="workDate" label="日期" width="110" />
        <el-table-column prop="empName" label="员工" width="120" show-overflow-tooltip />
        <el-table-column prop="empId" label="账号" width="120" />
        <el-table-column prop="totalHours" label="合计(h)" width="90" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="补录" width="70">
          <template #default="{ row }">{{ row.isBackfill === '1' ? '补录' : '—' }}</template>
        </el-table-column>
        <el-table-column label="明细" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            {{ (row.lines || []).map(l => `${l.wbsName || l.wbsId} ${l.hours}h`).join('；') }}
          </template>
        </el-table-column>
        <el-table-column prop="reviewBy" label="审核人" width="110" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { myList } from '@/api/pm/timesheet'

const loading = ref(false)
const empId = ref('')
const range = ref([])
const rows = ref([])
const employees = ref([])

onMounted(load)

async function load() {
  loading.value = true
  try {
    const params = {}
    if (empId.value) params.empId = empId.value
    if (range.value?.length === 2) {
      params.dateFrom = range.value[0]
      params.dateTo = range.value[1]
    }
    const { data } = await myList(params)
    rows.value = data || []
    // 员工下拉取自结果集去重
    const m = new Map()
    for (const r of rows.value) m.set(r.empId, { empId: r.empId, empName: r.empName })
    employees.value = [...m.values()]
  } finally {
    loading.value = false
  }
}

function statusText(s) {
  return { DRAFT: '草稿', BACKFILL: '补录审批中', SUBMITTED: '待审核', APPROVED: '已通过', REJECTED: '已驳回' }[s] || s
}
function statusType(s) {
  return { DRAFT: 'info', BACKFILL: 'warning', SUBMITTED: 'warning', APPROVED: 'success', REJECTED: 'danger' }[s] || 'info'
}
</script>

<style scoped>
.toolbar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.hint { color: #909399; font-size: 12px; margin-top: 8px; }
</style>
