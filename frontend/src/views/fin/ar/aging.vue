<template>
  <div class="page ar-mgmt-page">
    <!-- ================= 8.2.2 账龄分析 ================= -->
    <el-form inline>
      <el-date-picker v-model="agBaseDate" type="date" value-format="YYYY-MM-DD" placeholder="基准日（默认今天）" />
      <el-button type="primary" @click="loadAging">分析</el-button>
    </el-form>
    <el-row :gutter="12" style="margin-bottom:12px">
      <el-col :span="4" v-for="b in agBuckets" :key="b.label">
        <el-card shadow="never" body-style="padding:10px">
          <div class="bucket-label" :class="{ over90: isOver90(b.label) }">{{ b.label }}</div>
          <div class="bucket-base">{{ b.base }}</div>
          <div class="bucket-ratio">{{ (Number(b.ratio) * 100).toFixed(1) }}%</div>
        </el-card>
      </el-col>
    </el-row>
    <el-alert v-if="agOver90Ratio" type="warning" :closable="false" show-icon style="margin-bottom:10px"
      :title="`超 90 天占比 ${(Number(agOver90Ratio) * 100).toFixed(1)}%（BR-4.3-14：>20% 需信用管理员审批）`" />
    <el-tabs type="border-card">
      <el-tab-pane label="客户分布">
        <el-table :data="agCustomers" border size="small">
          <el-table-column prop="customerName" label="客户" min-width="160" show-overflow-tooltip />
          <el-table-column prop="total" label="未核销合计" width="130" align="right" />
          <el-table-column label="超90占比" width="110" align="right">
            <template #default="{ row }">
              <span :style="Number(row.over90Ratio) > 0.2 ? 'color:#f56c6c;font-weight:600' : ''">
                {{ (Number(row.over90Ratio) * 100).toFixed(1) }}%
              </span>
            </template>
          </el-table-column>
          <el-table-column v-for="(b, i) in agBuckets" :key="i" :label="b.label" width="100" align="right">
            <template #default="{ row }">{{ row.buckets?.[String(i)] ?? 0 }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="环比上期">
        <el-table :data="agMom" border size="small">
          <el-table-column prop="bucket" label="账龄段" width="140" />
          <el-table-column prop="prev" label="上期" width="140" align="right" />
          <el-table-column prop="curr" label="本期" width="140" align="right" />
          <el-table-column label="环比" width="140" align="right">
            <template #default="{ row }">
              <span :style="Number(row.delta) > 0 ? 'color:#f56c6c' : ''">{{ row.delta }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
/**
 * 8.2.2 账龄分析（拆分自原应收管理多 tab 页，design D1）。
 * 本页独有逻辑，无跨页共享状态（D2：无共享未抽）。
 */
import { ref, onMounted } from 'vue'
import { aging } from '@/api/fin/ar-aging'

const agBaseDate = ref('')
const agBuckets = ref([])
const agCustomers = ref([])
const agMom = ref([])
const agOver90Ratio = ref(null)
const agTotal = ref(0)

function isOver90(label) {
  // 段起点 > 90（如 91-180 / 181-360 / 360+）
  const m = String(label).match(/^(\d+)-/)
  if (m) return Number(m[1]) > 90
  return String(label).endsWith('+') && Number(String(label).replace('+', '')) >= 90
}

async function loadAging() {
  const { data } = await aging(agBaseDate.value ? { baseDate: agBaseDate.value } : {})
  agBuckets.value = data.buckets || []
  agCustomers.value = data.customers || []
  agMom.value = data.mom || []
  agOver90Ratio.value = data.over90Ratio
  agTotal.value = Number(data.total || 0)
}

onMounted(() => {
  loadAging()
})
</script>

<style scoped>
.bucket-label { font-size: 12px; color: #909399; }
.bucket-label.over90 { color: #f56c6c; font-weight: 600; }
.bucket-base { font-size: 18px; font-weight: 600; margin: 4px 0 2px; }
.bucket-ratio { font-size: 12px; color: #606266; }
</style>
