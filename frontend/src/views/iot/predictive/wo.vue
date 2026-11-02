<template>
  <div style="padding:16px">
    <!-- 13.8.2 维修工单：误报率统计 + 误报标记 + 闭环 -->
    <el-card shadow="never" style="margin-bottom:10px">
      <el-descriptions v-if="rate.ratePct !== undefined" :column="4" border size="small">
        <el-descriptions-item label="窗口">{{ rate.windowDays }} 天</el-descriptions-item>
        <el-descriptions-item label="转工单数">{{ rate.totalWo }}</el-descriptions-item>
        <el-descriptions-item label="误报数">{{ rate.falsePositive }}</el-descriptions-item>
        <el-descriptions-item label="误报率">
          <b :style="{ color: rate.overThreshold ? '#F56C6C' : '#67C23A' }">
            {{ rate.ratePct }}%
          </b>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert v-if="rate.hint" type="warning" :closable="false" style="margin-top:8px"
                :title="rate.hint" />
    </el-card>
    <el-table :data="alarms" size="small">
      <el-table-column prop="eventNo" label="告警事件" width="180" />
      <el-table-column prop="deviceNo" label="设备" show-overflow-tooltip />
      <el-table-column prop="linkWoId" label="工单" width="160" />
      <el-table-column label="误报" width="70">
        <template #default="{ row }">
          <el-tag v-if="row.falsePositive === '1'" size="small" type="danger">误报</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90" />
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button size="small" link @click="markFp(row, true)">标误报</el-button>
          <el-button size="small" link @click="markFp(row, false)">标正常</el-button>
          <el-button v-if="row.linkWoId && row.status !== 'CLOSED'" size="small"
                     type="success" link @click="closeWo(row)">工单闭环</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div style="margin-top:8px">
      <el-button size="small" @click="loadAll">刷新</el-button>
    </div>

    <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:10px"
              :title="result.title" :description="result.desc" />
  </div>
</template>

<script setup>
/**
 * 13.8.2 维修工单（拆分自原预测维护操作台）：误报率统计 + 标误报/标正常 + 工单闭环回写。
 * 结果条见 usePredictive.js（与告警联动页共享）。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  falsePositiveRateApi, markFalsePositiveApi, predictWoClosedApi, alarmListApi,
} from '@/api/iot/monitor'
import { usePredictive } from './usePredictive'

const { result } = usePredictive()

const alarms = ref([])
const rate = ref({})

async function loadRate() {
  const r = await falsePositiveRateApi()
  rate.value = r.data || {}
}

async function loadAlarms() {
  const r = await alarmListApi({ alertLevel: 'L2' })
  alarms.value = (r.data || []).filter(a => a.alertType === 'TREND' || a.linkWoId)
}

async function loadAll() {
  loadRate()
  loadAlarms()
}

async function markFp(row, fp) {
  try {
    await markFalsePositiveApi(row.eventNo, fp)
    ElMessage.success(fp ? '已标误报' : '已标正常')
    loadAll()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '标记失败')
  }
}

async function closeWo(row) {
  try {
    const r = await predictWoClosedApi(row.linkWoId)
    result.value = { type: 'success', title: '工单闭环回写完成',
      desc: `联动告警关闭 ${r.data?.closedAlarms || 0} 条` }
    loadAll()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '闭环失败')
  }
}

onMounted(() => {
  loadAll()
})
</script>
