<template>
  <div>
    <!-- 14.8.2 自动推送 -->
    <el-card shadow="never">
      <div slot="header"><span>触达统计（当月）</span></div>
      <el-row :gutter="12">
        <el-col :span="4"><el-statistic title="推送总数" :value="stats.total || 0" /></el-col>
        <el-col :span="4"><el-statistic title="送达数" :value="stats.sent || 0" /></el-col>
        <el-col :span="4"><el-statistic title="失败数" :value="stats.failed || 0" /></el-col>
        <el-col :span="4"><el-statistic title="降级数" :value="stats.degraded || 0" /></el-col>
        <el-col :span="4"><el-statistic title="送达率(%)" :value="Number(stats.sendRate || 0)" :precision="2" /></el-col>
        <el-col :span="4"><el-statistic title="阅读率(%)" :value="Number(stats.readRate || 0)" :precision="2" /></el-col>
      </el-row>
    </el-card>

    <el-card shadow="never" style="margin-top:12px">
      <div slot="header"><span>推送日志</span></div>
      <el-form inline size="small" @submit.prevent>
        <el-form-item label="接收人">
          <el-input v-model="logQuery.receiver" placeholder="用户名" clearable style="width:140px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="logQuery.status" clearable placeholder="全部" style="width:120px">
            <el-option label="已送达" value="SENT" />
            <el-option label="失败" value="FAILED" />
            <el-option label="降级" value="DEGRADED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="small" @click="loadLogs(1)">查询</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="logs" border size="small">
        <el-table-column prop="pushDate" label="推送日" width="100" />
        <el-table-column prop="receiver" label="接收人" width="110" />
        <el-table-column prop="reportCode" label="日报" width="110" />
        <el-table-column label="通道" width="80">
          <template #default="{ row }">
            {{ { MAIL: '邮件', WECOM: '企微', NOTICE: '站内信' }[row.channel] || row.channel }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="{ SENT: 'success', FAILED: 'danger', DEGRADED: 'warning' }[row.sendStatus]"
                    size="mini">{{ { SENT: '送达', FAILED: '失败', DEGRADED: '降级' }[row.sendStatus] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="retryCnt" label="重试" width="60" />
        <el-table-column prop="dataCutoff" label="数据截止" width="100" />
        <el-table-column label="质量" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.qualityFlag === 'Y'" type="danger" size="mini">异常</el-tag>
            <el-tag v-else type="success" size="mini">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMsg" label="错误" min-width="140" show-overflow-tooltip />
      </el-table>
            <AppPagination
        style="margin-top:10px;text-align:right"
        :total="logTotal"
        v-model:page-size="logQuery.size"
        :current-page="logQuery.page"
        @current-change="p => loadLogs(p)"
      />
    </el-card>
  </div>
</template>

<script setup>
import AppPagination from '@/components/AppPagination.vue'
/**
 * 14.8.2 自动推送（拆分自原日报订阅操作台）：当月触达统计 / 推送日志查询。
 * 统计与日志状态见 useDailyReport.js（订阅配置页「试推」后亦刷新该数据）。
 */
import { onMounted } from 'vue'
import { useDailyReport } from './useDailyReport'

const { stats, logs, logTotal, logQuery, loadStats, loadLogs } = useDailyReport()

onMounted(() => {
  loadStats()
  loadLogs(1)
})
</script>
