<template>
  <div>
    <PdaShell :title="'设备巡检 · ' + tabTitle" scan @scan="onScan" @sync="doSync">
      <!-- 13.3.3 异常标记（联动 13.4 异常单） -->
      <el-table :data="exceptions" size="small" v-loading="loading">
        <el-table-column prop="excNo" label="异常单" width="150" />
        <el-table-column prop="deviceNo" label="设备" show-overflow-tooltip />
        <el-table-column prop="severity" label="级别" width="70" />
        <el-table-column prop="status" label="状态" width="80" />
      </el-table>

      <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:8px"
                :title="result.title" :description="result.desc" />
    </PdaShell>
  </div>
</template>

<script setup>
/**
 * 13.3.3 异常标记（拆分自原设备巡检操作台）：设备异常单列表（联动异常上报）。
 * 加载态、结果条与扫码签到见 useInspection.js。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import PdaShell from '../_pda/PdaShell.vue'
import { exceptionListApi } from '@/api/iot/inspect'
import { useInspection } from './useInspection'

const route = useRoute()
const tabTitle = computed(() => route.meta.title || '设备巡检')

const { loading, result, onScan, doSync } = useInspection()

const exceptions = ref([])

async function loadExceptions() {
  try {
    const r = await exceptionListApi({ excType: 'EQUIPMENT' })
    exceptions.value = r.data || []
  } catch (e) { exceptions.value = [] }
}

onMounted(() => {
  loadExceptions()
})
</script>
