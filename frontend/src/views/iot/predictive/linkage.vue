<template>
  <div style="padding:16px">
    <!-- 13.8.1 告警联动：趋势候选 → 幂等建单 -->
    <PageInfo title="幂等规则：告警事件 ID 为建单幂等键，重复触发返回既有工单（409 幂等语义）" style="margin-bottom:10px" />
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

    <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:10px"
              :title="result.title" :description="result.desc" />
  </div>
</template>

<script setup>
/**
 * 13.8.1 告警联动（拆分自原预测维护操作台）：趋势候选幂等建单（409 幂等语义）。
 * 结果条见 usePredictive.js（与维修工单页共享）。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, onMounted } from 'vue'
import { trendCandidatesApi, predictCreateWoApi } from '@/api/iot/monitor'
import { usePredictive } from './usePredictive'

const { result } = usePredictive()

const loading = ref(false)
const candidates = ref([])

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

onMounted(() => {
  loadCandidates()
})
</script>
