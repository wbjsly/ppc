<template>
  <div>
    <PdaShell :title="'设备巡检 · ' + tabTitle" scan @scan="onScan" @sync="doSync">
      <!-- 13.3.2 点检记录 -->
      <el-table :data="records" size="small" v-loading="loading">
        <el-table-column prop="deviceNo" label="设备" show-overflow-tooltip />
        <el-table-column prop="itemsJson" label="点检项" show-overflow-tooltip />
        <el-table-column label="异常" width="60">
          <template #default="{ row }">
            <el-tag v-if="row.abnormalFlag === '1'" size="small" type="danger">异常</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="submitAt" label="提交时间" width="150" />
      </el-table>

      <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:8px"
                :title="result.title" :description="result.desc" />
    </PdaShell>
  </div>
</template>

<script setup>
/**
 * 13.3.2 点检记录（拆分自原设备巡检操作台）：已提交点检记录查询。
 * 加载态、结果条与扫码签到见 useInspection.js。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import PdaShell from '../_pda/PdaShell.vue'
import { inspectListApi } from '@/api/iot/inspect'
import { useInspection } from './useInspection'

const route = useRoute()
const tabTitle = computed(() => route.meta.title || '设备巡检')

const { loading, result, onScan, doSync } = useInspection()

const records = ref([])

async function loadRecords() {
  loading.value = true
  try {
    const r = await inspectListApi({ status: 'SUBMITTED' })
    records.value = r.data || []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadRecords()
})
</script>
