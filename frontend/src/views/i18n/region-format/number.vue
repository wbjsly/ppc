<template>
  <div class="i18n-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="区域配置（17.2）：日期格式、数字格式、货币格式"
      description="三语 Locale 自动应用（S-5.7-02）；输入格式不符阻断保存（C-5.7-08）；Locale 未配置回退 zh-CN（C-5.7-02）。" />

    <!-- 17.2.2 数字格式 -->
    <el-table :data="rows" border size="small">
      <el-table-column prop="locale" label="Locale" width="100" />
      <el-table-column label="数字模板" width="160">
        <template #default="{ row }"><code>{{ row.format.numberPattern }}</code></template>
      </el-table-column>
      <el-table-column label="示例（1284650.123）" min-width="180">
        <template #default="{ row }"><b>{{ numSample(row.format) }}</b></template>
      </el-table-column>
      <el-table-column label="小数位" width="90">
        <template #default="{ row }">{{ row.format.decimalPlaces }}</template>
      </el-table-column>
    </el-table>
    <el-alert type="info" :closable="false" style="margin-top:10px"
      title="千分位与小数位按 Locale 模板渲染（S-5.7-02：数量 1,200.000 三位小数）" />
  </div>
</template>

<script setup>
/**
 * 17.2.2 数字格式（拆分自原区域配置页）：数字模板与小数位列表。
 * Locale 配置列表共享状态见 useRegionFormat.js。
 */
import { onMounted } from 'vue'
import { useRegionFormat } from './useRegionFormat'

const { rows, load } = useRegionFormat()

onMounted(load)

function numSample(f) {
  const n = 1284650.123
  const d = parseInt(f.decimalPlaces || '2', 10)
  return n.toLocaleString('en-US', { minimumFractionDigits: d, maximumFractionDigits: d })
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
</style>
