<template>
  <div style="padding:16px">
    <!-- 13.9.1 移动看板：五指标 + 生成 -->
    <el-form inline size="small">
      <el-form-item label="报告期">
        <el-input v-model="period" placeholder="yyyy-MM" style="width:120px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="generate">生成报告</el-button>
        <el-button @click="load">刷新</el-button>
      </el-form-item>
    </el-form>
    <el-table :data="reports" size="small" v-loading="loading">
      <el-table-column prop="period" label="报告期" width="110" />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'PUBLISHED' ? 'success'
            : row.status === 'ARCHIVED' ? 'warning' : 'info'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="五指标快照" min-width="420">
        <template #default="{ row }">
          <template v-if="parse(row.metricsJson)">
            <el-tag v-for="(v, k) in parse(row.metricsJson)" :key="k" size="small"
                    style="margin-right:6px">
              {{ metricName(k) }}: {{ v }}
            </el-tag>
          </template>
        </template>
      </el-table-column>
      <el-table-column prop="publishAt" label="发布时间" width="160" />
    </el-table>

    <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:10px"
              :title="result.title" :description="result.desc" />
  </div>
</template>

<script setup>
/**
 * 13.9.1 移动看板（拆分自原服务报告操作台）：五指标月度报告生成。
 * 报告期/列表/加载态/结果条见 useReport.js（与报告订阅页共享）。
 */
import { onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { slaGenerateApi } from '@/api/iot/todo'
import { useReport } from './useReport'

const { period, reports, loading, result, load } = useReport()

const METRIC_NAMES = {
  availabilityPct: '可用率%', syncSuccessPct: '同步成功率%',
  avgAlertRespMin: '响应min', offlineSharePct: '离线占比%',
  alarmClosePct: '告警闭环%',
}

function metricName(k) {
  return METRIC_NAMES[k] || k
}

function parse(json) {
  try {
    return JSON.parse(json)
  } catch (e) {
    return null
  }
}

async function generate() {
  try {
    const r = await slaGenerateApi(period.value)
    result.value = { type: 'success', title: `报告已生成 ${r.data?.period}`,
      desc: String(r.data?.metricsJson || '') }
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '生成失败')
  }
}

onMounted(() => {
  load()
})
</script>
