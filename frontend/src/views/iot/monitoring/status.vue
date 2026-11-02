<template>
  <div style="padding:16px">
    <!-- 13.6.4 状态监控：分布 -->
    <div style="margin-top:12px">
      <el-row :gutter="12">
        <el-col v-for="(v, k) in dist" :key="k" :span="6">
          <el-card shadow="never">
            <el-statistic :title="statusName(k)" :value="Number(v)" />
          </el-card>
        </el-col>
      </el-row>
      <div style="margin-top:10px">
        <el-button size="small" @click="loadDist">刷新分布</el-button>
      </div>
    </div>

    <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:10px"
              :title="result.title" :description="result.desc" />
  </div>
</template>

<script setup>
/**
 * 13.6.4 状态监控（拆分自原设备监控操作台）：设备状态分布统计。
 * 结果条见 useMonitoring.js。
 */
import { ref, onMounted } from 'vue'
import { deviceStatusDistApi } from '@/api/iot/monitor'
import { useMonitoring } from './useMonitoring'

const { result } = useMonitoring()

const dist = ref({})

function statusName(k) {
  return { ONLINE: '运行中', OFFLINE: '停机', FAULT: '故障', MAINT: '维护中' }[k] || k
}

async function loadDist() {
  const r = await deviceStatusDistApi()
  dist.value = r.data || {}
}

onMounted(() => {
  loadDist()
})
</script>
