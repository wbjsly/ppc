<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="限流熔断（15.5）：令牌桶限流、熔断降级、配额档位、灰度切流量"
      description="复用 open-api-gateway。超限 429+Retry-After（BR-5.5-05）、连续超限自动降档（BR-5.5-06）、错误率熔断半开（BR-5.5-07）。灰度切流量限 ADMIN。" />

    <!-- 15.5.3 配额管理 -->
    <el-row :gutter="12" style="margin-bottom:12px">
      <el-col :span="8" v-for="t in TIERS" :key="t.value">
        <el-card shadow="never" class="tier-card">
          <div class="tier-name">{{ t.label }}</div>
          <div class="tier-qps">{{ t.qps }} req/s</div>
          <div class="tier-desc">{{ t.desc }}</div>
          <div class="tier-used">当前使用伙伴：{{ tierCount(t.value) }} 家</div>
        </el-card>
      </el-col>
    </el-row>
    <el-table :data="channels" border size="small">
      <el-table-column prop="partnerCode" label="伙伴" width="140" />
      <el-table-column prop="rateTier" label="配额档" width="130">
        <template #default="{ row }">
          <el-tag size="small">{{ tierLabel(row.rateTier) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="调整配额" width="180">
        <template #default="{ row }">
          <el-select :value="row.rateTier" size="small" style="width:140px"
                     @change="v => setTier(row, v)">
            <el-option v-for="t in TIERS" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </template>
      </el-table-column>
    </el-table>
    <el-alert type="info" :closable="false" style="margin-top:10px"
              title="配额档位与限流档同源：调档即调整该伙伴的令牌桶配额，档位变更留痕（rate-tier-change）" />
  </div>
</template>

<script setup>
/**
 * 15.5.3 配额管理（拆分自原限流熔断操作台）：档位卡片 / 配额调整。
 * 共享状态与调档动作见 useChannels.js；档位常量与标签为本页独有，留页内。
 */
import { onMounted } from 'vue'
import { useChannels } from './useChannels'

const { channels, loadChannels, setTier } = useChannels()

const TIERS = [
  { value: 'STRATEGIC', label: '战略配额', qps: 500, desc: '核心战略伙伴，全量保障' },
  { value: 'NORMAL', label: '普通配额', qps: 100, desc: '常规业务伙伴标准档' },
  { value: 'NEW', label: '新伙伴配额', qps: 30, desc: '接入观察期低配额' }
]
function tierLabel(v) { return (TIERS.find(t => t.value === v) || {}).label || v }
function tierCount(v) { return channels.value.filter(c => c.rateTier === v).length }

onMounted(loadChannels)
</script>

<style scoped>
.intf-page { padding: 12px; }
.tier-card { text-align: center; }
.tier-name { font-size: 13px; color: #909399; }
.tier-qps { font-size: 22px; font-weight: bold; color: #303133; margin: 6px 0; }
.tier-desc { font-size: 12px; color: #909399; }
.tier-used { font-size: 12px; color: #409eff; margin-top: 6px; }
</style>
