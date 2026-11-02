<template>
  <div class="adhoc-wrap">
    <!-- ========== 14.6.2 查询治理 ========== -->
    <el-row :gutter="12">
      <el-col :span="6">
        <el-card shadow="never">
          <div class="quota-card">
            <div class="quota-num">{{ gov.remaining }} / {{ gov.quota }}</div>
            <div class="quota-label">今日剩余配额</div>
            <div class="quota-sub">已用 {{ gov.usedToday }} 次</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="18">
        <el-card shadow="never">
          <template #header><span class="card-title">用量汇总（按用户）</span></template>
          <el-table :data="gov.usage || []" size="small" max-height="420">
            <el-table-column prop="userName" label="用户" />
            <el-table-column prop="total" label="总次数" width="90" />
            <el-table-column prop="scanned" label="扫描行合计" width="110" />
            <el-table-column prop="queued" label="转后台" width="90" />
            <el-table-column prop="rejected" label="被拦截" width="90" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
/**
 * 14.6.2 查询治理（拆分自原即席查询操作台）：今日配额与按用户用量汇总。
 * 三页逻辑互不共享，全部留在页内（design D2：单 tab 独有逻辑不强抽）。
 */
import { ref, onMounted } from 'vue'
import { getGovernanceApi } from '@/api/bi/adhoc-query'

const gov = ref({})

async function loadGov() {
  try { gov.value = (await getGovernanceApi()).data || {} } catch (e) { /* 非治理角色静默 */ }
}

onMounted(() => {
  loadGov()
})
</script>

<style scoped>
.adhoc-wrap { padding: 4px; }
.card-title { font-weight: 600; }
.quota-card { text-align: center; padding: 20px 0; }
.quota-num { font-size: 32px; font-weight: 700; color: #409eff; }
.quota-label { color: #909399; margin-top: 6px; }
.quota-sub { color: #c0c4cc; font-size: 12px; margin-top: 4px; }
</style>
