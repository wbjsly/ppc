<template>
  <div style="padding:16px">
    <!-- 13.6.2 数据看板：最新测点 + 手动注入 -->
    <el-form inline size="small">
      <el-form-item label="设备"><el-input v-model="board.deviceNo" style="width:170px" /></el-form-item>
      <el-form-item label="测点">
        <el-select v-model="board.pointCode" style="width:140px">
          <el-option label="temperature" value="temperature" />
          <el-option label="vibration" value="vibration" />
          <el-option label="current" value="current" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadLatest">刷新</el-button>
        <el-button @click="injectAnomaly">注入异常曲线</el-button>
      </el-form-item>
    </el-form>
    <el-descriptions v-if="latest.ts" :column="3" border size="small">
      <el-descriptions-item label="时间">{{ latest.ts }}</el-descriptions-item>
      <el-descriptions-item label="测点">{{ latest.pointCode }}</el-descriptions-item>
      <el-descriptions-item label="值">
        <b :style="{ color: latest.quality === 'SUSPECT' ? '#F56C6C' : '#303133' }">
          {{ latest.val }}
        </b>
      </el-descriptions-item>
      <el-descriptions-item label="质量">{{ latest.quality }}</el-descriptions-item>
    </el-descriptions>
    <el-table :data="series" size="small" style="margin-top:10px" max-height="320">
      <el-table-column prop="ts" label="时间" width="180" />
      <el-table-column prop="pointCode" label="测点" width="120" />
      <el-table-column prop="val" label="值" width="110" />
      <el-table-column label="质量" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.quality === 'SUSPECT' ? 'danger' : 'success'">
            {{ row.quality }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="suspects.length" style="margin-top:8px">
      <el-alert type="warning" :closable="false" :title="`可疑数据 ${suspects.length} 条（已被告警过滤）`"
                :description="suspects.slice(0, 3).map(s => `${s.ts} = ${s.val}`).join('；')" />
    </div>

    <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:10px"
              :title="result.title" :description="result.desc" />
  </div>
</template>

<script setup>
/**
 * 13.6.2 数据看板（拆分自原设备监控操作台）：最新测点 + 序列查询 + 异常曲线注入。
 * 结果条见 useMonitoring.js（原页进入本叶不自动加载，仍由「刷新」触发）。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  telemetryLatestApi, telemetryQueryApi, telemetryInjectApi, telemetrySuspectApi,
} from '@/api/iot/monitor'
import { useMonitoring } from './useMonitoring'

const { result, ok } = useMonitoring()

const board = ref({ deviceNo: '', pointCode: 'temperature' })
const latest = ref({})
const series = ref([])
const suspects = ref([])

async function loadLatest() {
  try {
    const r = await telemetryLatestApi(board.value.deviceNo, board.value.pointCode)
    latest.value = r.data || {}
    const q = await telemetryQueryApi({
      deviceNo: board.value.deviceNo, pointCode: board.value.pointCode, limit: 100,
    })
    series.value = q.data || []
    const s = await telemetrySuspectApi({ deviceNo: board.value.deviceNo, limit: 20 })
    suspects.value = s.data || []
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '查询失败')
  }
}

async function injectAnomaly() {
  try {
    const pts = []
    const base = Date.now()
    for (let i = 0; i < 30; i++) {
      pts.push({
        deviceNo: board.value.deviceNo,
        pointCode: board.value.pointCode,
        val: 50 + i * 1.4 + (Math.random() * 2 - 1),
        quality: 'GOOD',
        ts: new Date(base - (30 - i) * 60000).toISOString(),
      })
    }
    // 可疑数据点（过滤演示）
    pts.push({ deviceNo: board.value.deviceNo, pointCode: board.value.pointCode,
      val: -999, quality: 'SUSPECT', ts: new Date().toISOString() })
    await telemetryInjectApi({ points: pts, batchTag: 'DEMO-' + Date.now() })
    ok('异常曲线已注入', `${pts.length} 点（含 1 条可疑数据）`)
    loadLatest()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '注入失败')
  }
}
</script>
