<template>
  <div class="page">
    <el-tabs v-model="tab">
      <!-- 18.3.1 值班看板 -->
      <el-tab-pane label="值班看板" name="oncall">
        <el-form inline>
          <el-form-item label="日期">
            <el-date-picker v-model="form.dutyDate" type="date" value-format="YYYY-MM-DD" style="width: 160px" />
          </el-form-item>
          <el-form-item label="层级">
            <el-select v-model="form.level" style="width: 100px">
              <el-option label="L1" value="L1" /><el-option label="L2" value="L2" /><el-option label="L3" value="L3" />
            </el-select>
          </el-form-item>
          <el-form-item label="值班人">
            <el-input v-model="form.userName" style="width: 140px" />
          </el-form-item>
          <el-button type="primary" @click="saveOncall">保存排班</el-button>
        </el-form>
        <el-table :data="oncall" border size="small">
          <el-table-column prop="dutyDate" label="日期" width="120" />
          <el-table-column prop="onLevel" label="层级" width="80" />
          <el-table-column prop="userName" label="值班人" />
          <el-table-column prop="backupUser" label="备份" />
        </el-table>
      </el-tab-pane>

      <!-- 18.3.2 层级升级 -->
      <el-tab-pane label="层级升级" name="chain">
        <el-button type="primary" @click="scan">手动触发升级扫描</el-button>
        <span style="margin-left: 10px; color: #909399; font-size: 12px">
          调度每 5 分钟自动扫描：P1 15 分钟未认领升级 / 层级 30 分钟 L1→L2→L3（BR-5.8-02/03）
        </span>
        <el-table :data="logs" border size="small" style="margin-top: 10px">
          <el-table-column prop="alertId" label="告警ID" width="200" show-overflow-tooltip />
          <el-table-column prop="fromLevel" label="原层级" width="80">
            <template #default="{ row }">{{ row.fromLevel || '—' }}</template>
          </el-table-column>
          <el-table-column prop="toLevel" label="升级至" width="80" />
          <el-table-column prop="reason" label="原因" />
          <el-table-column prop="escalateAt" label="升级时间" width="160">
            <template #default="{ row }">{{ fmt(row.escalateAt) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 18.3.3 升级工单 -->
      <el-tab-pane label="升级工单" name="tickets">
        <el-table :data="tickets" border size="small">
          <el-table-column prop="alertNo" label="告警号" width="190" />
          <el-table-column prop="level" label="级别" width="70" />
          <el-table-column prop="title" label="标题" />
          <el-table-column prop="escalationLevel" label="升级层级" width="90">
            <template #default="{ row }">L{{ row.escalationLevel }}</template>
          </el-table-column>
          <el-table-column prop="handler" label="当前处理人" width="120" />
          <el-table-column prop="status" label="状态" width="100" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { oncallApi, saveOncallApi, escalationLogsApi, scanEscalationApi, escalationTicketsApi } from '@/api/ops/ops-sla'

const route = useRoute()
const tab = ref(route.meta.tab || 'oncall')
const oncall = ref([])
const logs = ref([])
const tickets = ref([])
const form = ref({ dutyDate: '', level: 'L1', userName: '' })

onMounted(() => { loadOncall(); loadLogs(); loadTickets() })

async function loadOncall() {
  const r = await oncallApi(7)
  oncall.value = r.data || []
}
async function loadLogs() {
  const r = await escalationLogsApi({ limit: 50 })
  logs.value = r.data || []
}
async function loadTickets() {
  const r = await escalationTicketsApi()
  tickets.value = r.data || []
}
async function saveOncall() {
  if (!form.value.dutyDate || !form.value.userName) {
    ElMessage.warning('日期与值班人必填')
    return
  }
  await saveOncallApi(form.value.dutyDate, form.value.level, form.value.userName)
  ElMessage.success('排班已保存')
  loadOncall()
}
async function scan() {
  const r = await scanEscalationApi()
  ElMessage.success(`扫描完成，处理 ${r.data.handled} 条`)
  loadLogs(); loadTickets()
}
function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '' }
</script>
