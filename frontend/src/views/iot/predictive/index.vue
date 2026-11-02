<template>
  <div style="padding:16px">
    <el-radio-group v-model="tab" size="small" style="margin-bottom:12px">
      <el-radio-button value="linkage">告警联动</el-radio-button>
      <el-radio-button value="wo">维修工单</el-radio-button>
    </el-radio-group>

    <!-- 13.8.1 告警联动：趋势候选 → 幂等建单 -->
    <template v-if="tab === 'linkage'">
      <el-alert type="info" :closable="false" style="margin-bottom:10px"
                title="幂等规则：告警事件 ID 为建单幂等键，重复触发返回既有工单（409 幂等语义）" />
      <el-table :data="candidates" size="small" v-loading="loading">
        <el-table-column prop="eventNo" label="告警事件" width="180" />
        <el-table-column prop="deviceNo" label="设备" show-overflow-tooltip />
        <el-table-column prop="ruleSnap" label="规则快照" show-overflow-tooltip />
        <el-table-column prop="occurredAt" label="发生时间" width="160" />
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="createWo(row)">评估建单</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top:8px">
        <el-button size="small" @click="loadCandidates">刷新候选</el-button>
      </div>
    </template>

    <!-- 13.8.2 维修工单：误报率统计 + 误报标记 + 闭环 -->
    <template v-else>
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
    </template>

    <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:10px"
              :title="result.title" :description="result.desc" />
  </div>
</template>

<script setup>
/**
 * 13.8 预测维护两叶（add-iot-mobile）：告警联动（趋势候选幂等建单）/
 * 维修工单（误报率统计 + 标记 + 工单闭环回写）。
 */
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  trendCandidatesApi, predictCreateWoApi, falsePositiveRateApi,
  markFalsePositiveApi, predictWoClosedApi, alarmListApi,
} from '@/api/iot/monitor'

const route = useRoute()
const tab = ref(route.meta.tab || 'linkage')

const loading = ref(false)
const candidates = ref([])
const alarms = ref([])
const rate = ref({})
const result = ref(null)

async function loadCandidates() {
  loading.value = true
  try {
    const r = await trendCandidatesApi()
    candidates.value = r.data || []
  } finally {
    loading.value = false
  }
}

async function createWo(row) {
  try {
    const r = await predictCreateWoApi(row.eventNo)
    result.value = { type: 'success', title: `建单成功 ${r.data?.woNo || ''}`,
      desc: `告警 ${row.eventNo} 幂等关联` }
    loadCandidates()
  } catch (e) {
    // 409 幂等：已建单
    const msg = e?.response?.data?.message || e.message || '建单失败'
    result.value = { type: 'warning', title: '幂等返回（已建单）', desc: msg }
  }
}

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

function loadTab() {
  if (tab.value === 'linkage') loadCandidates()
  else loadAll()
}

watch(tab, loadTab)
onMounted(loadTab)
</script>
