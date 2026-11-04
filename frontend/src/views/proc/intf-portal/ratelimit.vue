<template>
  <div class="intf-page">
    <PageInfo title="限流熔断：令牌桶限流、熔断降级、配额档位、灰度切流量" description="复用 open-api-gateway。超限 429+Retry-After（BR-5.5-05）、连续超限自动降档（BR-5.5-06）、错误率熔断半开（BR-5.5-07）。灰度切流量限 ADMIN。" style="margin-bottom:12px" />

    <!-- 15.5.1 令牌限流 -->
    <el-table :data="channels" border size="small">
      <el-table-column prop="partnerCode" label="伙伴" width="120" />
      <el-table-column prop="rateTier" label="限流档" width="110" />
      <el-table-column label="档位调整" width="180">
        <template #default="{ row }">
          <el-select :value="row.rateTier" size="small" style="width:140px"
                     @change="v => setTier(row, v)">
            <el-option label="战略500" value="STRATEGIC" />
            <el-option label="普通100" value="NORMAL" />
            <el-option label="新伙伴30" value="NEW" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column prop="rateHit" label="近期超限" width="100">
        <template #default="{ row }">{{ row.rateHit ?? '—' }}</template>
      </el-table-column>
    </el-table>
    <el-alert type="info" :closable="false" style="margin-top:10px"
              title="令牌桶限流：超限 429 + Retry-After（BR-5.5-05），连续超限自动降档（BR-5.5-06）" />
  </div>
</template>

<script setup>
/**
 * 15.5.1 令牌限流（拆分自原限流熔断操作台）：限流档位调整。
 * 共享状态与调档动作见 useChannels.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { onMounted } from 'vue'
import { useChannels } from './useChannels'

const { channels, loadChannels, setTier } = useChannels()

onMounted(loadChannels)
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
