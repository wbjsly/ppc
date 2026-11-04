<template>
  <div class="i18n-page">
    <PageInfo title="区域配置：日期格式、数字格式、货币格式" description="三语 Locale 自动应用（S-5.7-02）；输入格式不符阻断保存（C-5.7-08）；Locale 未配置回退 zh-CN（C-5.7-02）。" style="margin-bottom:12px" />

    <!-- 17.2.3 货币格式 -->
    <el-table :data="rows" border size="small">
      <el-table-column prop="locale" label="Locale" width="100" />
      <el-table-column label="货币符号" width="110">
        <template #default="{ row }"><b>{{ row.format.currencySymbol }}</b></template>
      </el-table-column>
      <el-table-column label="小数位（ISO 4217）" width="150">
        <template #default="{ row }">{{ row.format.decimalPlaces }} 位</template>
      </el-table-column>
      <el-table-column label="示例（JPY 1284650）" min-width="180">
        <template #default="{ row }">
          <b>{{ row.format.currencySymbol }}{{ curSample(row.format) }}</b>
        </template>
      </el-table-column>
    </el-table>
    <el-alert type="info" :closable="false" style="margin-top:10px"
      title="BR-5.7-09：JPY=0 位小数、CNY/USD=2 位；折算结果按币种主数据小数位四舍五入并保留取整差值" />
  </div>
</template>

<script setup>
/**
 * 17.2.3 货币格式（拆分自原区域配置页）：货币符号与 ISO 4217 小数位列表。
 * Locale 配置列表共享状态见 useRegionFormat.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { onMounted } from 'vue'
import { useRegionFormat } from './useRegionFormat'

const { rows, load } = useRegionFormat()

onMounted(load)

function curSample(f) {
  const d = parseInt(f.decimalPlaces || '2', 10)
  return (1284650).toLocaleString('en-US', { minimumFractionDigits: d, maximumFractionDigits: d })
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
</style>
