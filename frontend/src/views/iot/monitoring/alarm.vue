<template>
  <div style="padding:16px">
    <!-- 13.6.3 阈值告警：闭环操作 -->
    <div style="margin-bottom:8px">
      <el-button size="small" @click="scanEscalate">超时升级扫描</el-button>
      <el-button size="small" type="primary" @click="loadAlarms">刷新</el-button>
    </div>
    <el-table :data="alarms" size="small" v-loading="loading">
      <el-table-column prop="eventNo" label="事件号" width="170" />
      <el-table-column prop="deviceNo" label="设备" show-overflow-tooltip />
      <el-table-column label="级别" width="70">
        <template #default="{ row }">
          <el-tag size="small" :type="row.alertLevel === 'L1' ? 'danger'
            : row.alertLevel === 'L2' ? 'warning' : 'info'">{{ row.alertLevel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="mergeCnt" label="合并" width="60" />
      <el-table-column label="停机" width="90">
        <template #default="{ row }">
          <span v-if="row.stopCmdAt">{{ row.stopAckMs }}ms</span>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90" />
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <template v-if="row.status === 'OPEN'">
            <el-button size="small" type="warning" link @click="alarmConfirm(row)">确认</el-button>
            <el-button size="small" link @click="alarmProcessing(row)">处理</el-button>
          </template>
          <el-button v-if="row.status !== 'CLOSED'" size="small" type="success" link
                     @click="alarmClose(row)">关闭</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:10px"
              :title="result.title" :description="result.desc" />
  </div>
</template>

<script setup>
/**
 * 13.6.3 阈值告警（拆分自原设备监控操作台）：告警闭环（确认/处理/关闭）+ 超时升级扫描。
 * 加载态与结果条见 useMonitoring.js。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  alarmListApi, alarmConfirmApi, alarmProcessingApi, alarmCloseApi, alarmScanEscalateApi,
} from '@/api/iot/monitor'
import { useMonitoring } from './useMonitoring'

const { loading, result } = useMonitoring()

const alarms = ref([])

async function loadAlarms() {
  loading.value = true
  try {
    const r = await alarmListApi()
    alarms.value = r.data || []
  } finally {
    loading.value = false
  }
}

async function alarmConfirm(row) {
  try {
    await alarmConfirmApi(row.eventNo, { remark: '移动端确认' })
    ElMessage.success('已确认')
    loadAlarms()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '确认失败')
  }
}

async function alarmProcessing(row) {
  try {
    await alarmProcessingApi(row.eventNo, { remark: '处理中' })
    ElMessage.success('已转处理中')
    loadAlarms()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

async function alarmClose(row) {
  try {
    await alarmCloseApi(row.eventNo, { remark: '已恢复关闭' })
    ElMessage.success('已关闭')
    loadAlarms()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '关闭失败')
  }
}

async function scanEscalate() {
  const r = await alarmScanEscalateApi()
  ElMessage.info(`超时升级 ${r.data?.escalated || 0} 条`)
  loadAlarms()
}

onMounted(() => {
  loadAlarms()
})
</script>
