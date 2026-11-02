<template>
  <div class="page">
    <el-form inline>
      <el-form-item label="级别">
        <el-select v-model="level" clearable style="width: 120px" @change="load">
          <el-option label="P1" value="P1" /><el-option label="P2" value="P2" /><el-option label="P3" value="P3" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="status" clearable style="width: 140px" @change="load">
          <el-option label="待处理" value="OPEN" /><el-option label="已认领" value="CLAIMED" />
          <el-option label="已升级" value="ESCALATED" /><el-option label="已处置" value="RESOLVED" />
        </el-select>
      </el-form-item>
      <el-button @click="load">查询</el-button>
    </el-form>
    <el-table :data="alerts" border size="small">
      <el-table-column prop="alertNo" label="告警号" width="190" />
      <el-table-column prop="level" label="级别" width="70">
        <template #default="{ row }">
          <el-tag size="small" :type="row.level === 'P1' ? 'danger' : row.level === 'P2' ? 'warning' : 'info'">{{ row.level }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column prop="escalationLevel" label="升级" width="60" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button v-if="row.status === 'OPEN'" size="small" @click="claim(row)">认领</el-button>
          <el-button v-if="row.status !== 'RESOLVED'" size="small" type="success" @click="resolve(row)">处置</el-button>
        </template>
      </el-table-column>
    </el-table>
    <h4 style="margin: 14px 0 6px">风暴聚合根因（BR-5.8-08）</h4>
    <el-table :data="storm" border size="small">
      <el-table-column prop="title" label="根因事件" />
      <el-table-column prop="source" label="来源" width="120" />
      <el-table-column prop="affectedCnt" label="影响面" width="80" />
      <el-table-column prop="createDate" label="时间" width="160">
        <template #default="{ row }">{{ fmt(row.createDate) }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 18.2.2 阈值告警（拆分自原监控处置操作台）：告警筛选、认领/处置与风暴聚合根因。
 * 告警列表与筛选条件来自 useMonitor.js，时间格式见 monitor-helpers.js。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { claimAlertApi, resolveAlertApi, stormRootsApi } from '@/api/ops/ops-sla'
import { useMonitor } from './useMonitor'
import { fmt } from './monitor-helpers'

const { level, status, alerts, load } = useMonitor()

const storm = ref([])

onMounted(() => { load(); loadStorm() })

async function loadStorm() {
  const r = await stormRootsApi(20)
  storm.value = r.data || []
}

async function claim(row) {
  await claimAlertApi(row.id)
  ElMessage.success('已认领')
  load()
}

async function resolve(row) {
  await resolveAlertApi(row.id, '页面处置')
  ElMessage.success('已处置')
  load()
}
</script>
