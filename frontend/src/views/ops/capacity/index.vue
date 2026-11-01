<template>
  <div class="page">
    <el-tabs v-model="tab">
      <!-- 18.4.1 容量预警 -->
      <el-tab-pane label="容量预警" name="alert">
        <el-form inline>
          <el-form-item label="天数">
            <el-input-number v-model="days" :min="7" :max="90" @change="load" />
          </el-form-item>
          <el-button @click="load">查询</el-button>
        </el-form>
        <el-table :data="metrics" border size="small">
          <el-table-column prop="metricDate" label="日期" width="120" />
          <el-table-column prop="metricKey" label="指标" width="130">
            <template #default="{ row }">{{ keyLabel(row.metricKey) }}</template>
          </el-table-column>
          <el-table-column prop="metricValue" label="数值" width="120">
            <template #default="{ row }">
              <span :style="{ color: row.metricValue > 80 ? '#f56c6c' : '#67c23a' }">{{ row.metricValue }}{{ row.unit === '%' ? '%' : ' MB' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="70" />
        </el-table>
        <el-alert type="info" :closable="false" style="margin-top: 10px"
                  title="任一指标连续 3 天 >80% 自动产生 P1 容量告警并外推红线日期（BR-5.8-10）；CPU 为进程级口径" />
      </el-tab-pane>

      <!-- 18.4.2 扩容执行 -->
      <el-tab-pane label="扩容执行" name="scale">
        <el-button type="primary" @click="collect">立即采集评估</el-button>
        <el-table :data="latest" border size="small" style="margin-top: 10px">
          <el-table-column prop="metricKey" label="指标" width="130">
            <template #default="{ row }">{{ keyLabel(row.metricKey) }}</template>
          </el-table-column>
          <el-table-column prop="metricDate" label="采集日" width="120" />
          <el-table-column prop="metricValue" label="当前值" width="120" />
          <el-table-column label="扩容建议">
            <template #default="{ row }">
              {{ row.metricValue > 80 ? '建议扩容/清理（排入变更窗口执行）' : '水位正常' }}
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 18.4.3 结账保障 -->
      <el-tab-pane label="结账保障" name="guard">
        <el-alert type="warning" :closable="false" style="margin-bottom: 12px"
                  title="开启后（结账保障期 1~5 日语义）非紧急变更提交将被阻断（BR-5.8-14）" />
        <el-switch v-model="guard" active-text="保障开启" inactive-text="保障关闭" @change="toggleGuard" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { capacityMetricsApi, collectCapacityApi, guardStatusApi, setGuardApi } from '@/api/ops/ops-sla'

const route = useRoute()
const tab = ref(route.meta.tab || 'alert')
const days = ref(30)
const metrics = ref([])
const guard = ref(false)

const latest = computed(() => {
  const byKey = {}
  for (const m of metrics.value) {
    if (!byKey[m.metricKey]) byKey[m.metricKey] = m
  }
  return Object.values(byKey)
})

onMounted(async () => {
  load()
  const r = await guardStatusApi()
  guard.value = r.data.enabled
})

async function load() {
  const r = await capacityMetricsApi(days.value)
  metrics.value = r.data || []
}
async function collect() {
  const r = await collectCapacityApi()
  ElMessage.success(`采集完成（${r.data.collected} 指标）`)
  load()
}
async function toggleGuard(v) {
  await setGuardApi(v)
  ElMessage.success(v ? '结账保障已开启' : '结账保障已关闭')
}
function keyLabel(k) { return { DB_SIZE: 'DB 存储', CONN_POOL: '连接池', CPU: '进程 CPU' }[k] || k }
</script>
