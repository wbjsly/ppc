<template>
  <div>
    <el-page-header content="指标口径（14.3）— 口径定义/口径变更/口径校验三叶；十二要素（6.3.1）、变更两段式治理（BR-4.10-04 / C-4.10-08）、血缘四层图谱（S-4.10-15）"
                    style="margin-bottom: 8px" />

    <!-- ==================== 14.3.3 口径校验 ==================== -->
    <div class="toolbar">
      <el-button size="small" type="primary" @click="onCheckAll">执行批量校验</el-button>
      <span class="hint">遍历已发布模板指标引用 × 字典当前版（现算，C-4.10-01）</span>
    </div>
    <el-table :data="checkReport" v-loading="checkLoading" border stripe size="small" max-height="420">
      <el-table-column prop="templateCode" label="模板编码" width="130" />
      <el-table-column prop="templateName" label="模板名称" min-width="140" show-overflow-tooltip />
      <el-table-column prop="metricKey" label="指标编码" width="120" />
      <el-table-column label="注册" width="70" align="center">
        <template #default="{ row }">
          <el-tag :type="row.registered ? 'success' : 'danger'" size="small">
            {{ row.registered ? '已注册' : '未注册' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="结论" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.consistent ? 'success' : 'danger'" size="small">
            {{ row.consistent ? '一致' : '差异' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="差异明细" min-width="220">
        <template #default="{ row }">
          <template v-if="!row.consistent">
            <div v-for="(d, i) in asArray(row.report)" :key="i" class="diff-line">
              {{ d.path }} · {{ d.kind }} · {{ d.detail }}
            </div>
          </template>
          <span v-else>—</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 14.3.3 口径校验（拆分自原口径字典操作台）：模板指标引用 × 字典当前版批量 diff 校验。
 * 三页逻辑互不共享，全部留在页内（design D2：单 tab 独有逻辑不强抽）。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { checkAllApi } from '@/api/bi/metric-dict'

const checkLoading = ref(false)
const checkReport = ref([])

async function onCheckAll() {
  checkLoading.value = true
  try {
    const res = await checkAllApi()
    checkReport.value = res.data || []
    const diffs = checkReport.value.filter(r => !r.consistent).length
    ElMessage(diffs === 0
      ? { type: 'success', message: `校验完成：${checkReport.value.length} 项全部一致` }
      : { type: 'warning', message: `校验完成：${checkReport.value.length} 项中 ${diffs} 项差异` })
  } finally {
    checkLoading.value = false
  }
}

function asArray(v) {
  return Array.isArray(v) ? v : (v ? [v] : [])
}
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap; }
.hint { color: #909399; font-size: 12px; }
.diff-line { font-size: 12px; color: #e6a23c; line-height: 1.5; }
</style>
