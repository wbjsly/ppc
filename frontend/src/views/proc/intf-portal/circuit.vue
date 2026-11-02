<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="限流熔断（15.5）：令牌桶限流、熔断降级、配额档位、灰度切流量"
      description="复用 open-api-gateway。超限 429+Retry-After（BR-5.5-05）、连续超限自动降档（BR-5.5-06）、错误率熔断半开（BR-5.5-07）。灰度切流量限 ADMIN。" />

    <!-- 15.5.2 熔断降级 -->
    <el-table :data="channels" border size="small">
      <el-table-column prop="partnerCode" label="伙伴" width="120" />
      <el-table-column label="熔断状态" width="130">
        <template #default="{ row }">
          <el-tag size="small" :type="row.circuitState === 'OPEN' ? 'danger' : row.circuitState === 'HALF_OPEN' ? 'warning' : 'success'">
            {{ row.circuitState || 'CLOSED' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="circuitHit" label="触发次数" width="100" align="center">
        <template #default="{ row }">{{ row.circuitHit ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <el-button size="small" type="warning" :disabled="row.circuitState === 'CLOSED'"
                     @click="resetCircuit(row)">解除熔断</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-alert type="warning" :closable="false" style="margin-top:10px"
              title="错误率熔断半开探测（BR-5.5-07）：OPEN 拒绝流量 → 半开试探 → 恢复 CLOSED；解除熔断立即恢复全量" />
  </div>
</template>

<script setup>
/**
 * 15.5.2 熔断降级（拆分自原限流熔断操作台）：解除熔断。
 * 共享状态见 useChannels.js；解除熔断为本页独有，留页内。
 */
import { onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { resetCircuitApi } from '@/api/proc/interface-integration'
import { useChannels } from './useChannels'

const { channels, loadChannels } = useChannels()

onMounted(loadChannels)

async function resetCircuit(row) {
  await ElMessageBox.confirm(`解除伙伴 ${row.partnerCode} 的熔断？`, '提示', { type: 'warning' })
  await resetCircuitApi(row.id)
  ElMessage.success('已解除熔断')
  loadChannels()
}
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
