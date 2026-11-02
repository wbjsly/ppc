<template>
  <div class="app-container">
    <!-- ==================== 16.7.1 日志检索 ==================== -->
    <el-form :inline="true" size="small" @submit.prevent>
      <el-form-item label="操作人">
        <el-input v-model="q.changeBy" placeholder="CHANGE_BY 模糊" clearable style="width:140px" />
      </el-form-item>
      <el-form-item label="动作">
        <el-input v-model="q.fieldName" placeholder="FIELD_NAME 模糊" clearable style="width:160px" />
      </el-form-item>
      <el-form-item label="对象">
        <el-input v-model="q.empId" placeholder="EMP_ID 模糊" clearable style="width:140px" />
      </el-form-item>
      <el-form-item label="关键词">
        <el-input v-model="q.keyword" placeholder="前后值/说明" clearable style="width:160px" />
      </el-form-item>
      <el-form-item label="时间范围">
        <el-date-picker v-model="q.range" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss"
          start-placeholder="起" end-placeholder="止" style="width:340px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadLogs(1)">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>
    <el-alert type="info" :closable="false" style="margin-bottom:8px"
      title="敏感内容（前后值/说明）已按脱敏策略自动掩码展示；导出取证包同样应用掩码。审计日志禁止修改与删除（BR-5.6-13，数据库触发器硬阻断）。" />
    <el-table :data="logs.rows" size="small" v-loading="logs.loading" border>
      <el-table-column prop="changeAt" label="时间" width="160" />
      <el-table-column prop="changeBy" label="操作人" width="110" />
      <el-table-column prop="fieldName" label="动作" width="170" show-overflow-tooltip />
      <el-table-column prop="empId" label="对象" width="140" show-overflow-tooltip />
      <el-table-column prop="oldValue" label="前值" show-overflow-tooltip />
      <el-table-column prop="newValue" label="后值" show-overflow-tooltip />
      <el-table-column prop="reason" label="说明" show-overflow-tooltip />
      <el-table-column prop="sourceIp" label="来源 IP" width="120" />
    </el-table>
    <el-pagination style="margin-top:8px;text-align:right" background layout="total, prev, pager, next"
      :total="logs.total" :page-size="logs.size" :current-page="logs.page"
      @current-change="loadLogs" />

    <el-divider content-position="left">防篡改尝试记录（BR-5.6-13）</el-divider>
    <el-button size="small" @click="loadTamper">刷新尝试记录</el-button>
    <el-button size="small" type="warning" @click="runTamperSweep">触发告警扫描（幂等）</el-button>
    <el-table :data="tamper.rows" size="small" border style="margin-top:8px">
      <el-table-column prop="attemptAt" label="尝试时间" width="160" />
      <el-table-column prop="actionType" label="动作" width="90" />
      <el-table-column prop="empId" label="对象" width="140" show-overflow-tooltip />
      <el-table-column prop="fieldName" label="字段" width="150" show-overflow-tooltip />
      <el-table-column prop="changeBy" label="操作者" width="120" />
      <el-table-column label="结果" width="100">
        <template #default="{ row }">
          <el-tag size="mini" :type="row.bypass === '1' ? 'warning' : 'danger'">
            {{ row.bypass === '1' ? '豁免放行' : '已拒绝' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 16.7.1 日志检索（拆分自原审计日志操作台）：多条件日志查询 + 防篡改尝试记录。
 * 本页逻辑为单页独有，无跨页共享状态。
 */
import { reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { searchLogs, tamperList, tamperSweep } from '@/api/system/audit-log'

// ---------- 日志检索 ----------
const q = reactive({ changeBy: '', fieldName: '', empId: '', keyword: '', range: null })
const logs = reactive({ rows: [], total: 0, page: 1, size: 50, loading: false })

const loadLogs = async (page) => {
  if (page) logs.page = page
  logs.loading = true
  try {
    const params = { page: logs.page, size: logs.size }
    if (q.changeBy) params.changeBy = q.changeBy
    if (q.fieldName) params.fieldName = q.fieldName
    if (q.empId) params.empId = q.empId
    if (q.keyword) params.keyword = q.keyword
    if (q.range && q.range.length === 2) { params.from = q.range[0]; params.to = q.range[1] }
    const { data } = await searchLogs(params)
    logs.rows = data.rows || []
    logs.total = data.total || 0
  } finally {
    logs.loading = false
  }
}
const resetQuery = () => {
  q.changeBy = ''; q.fieldName = ''; q.empId = ''; q.keyword = ''; q.range = null
  loadLogs(1)
}

// ---------- 防篡改尝试 ----------
const tamper = reactive({ rows: [] })
const loadTamper = async () => {
  const { data } = await tamperList(100)
  tamper.rows = data || []
}
const runTamperSweep = async () => {
  const { data } = await tamperSweep()
  ElMessage.success(`扫描完成：新增 ${data.fresh} 条，通知 ${data.notified} 条`)
  loadTamper()
}

onMounted(() => {
  loadLogs(1)
  loadTamper()
})
</script>
