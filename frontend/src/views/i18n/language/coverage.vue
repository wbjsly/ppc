<template>
  <div class="i18n-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="语言管理（17.1）：键值维护、缺口报告、版本发布"
      description="后端表为源（决策2）；占位符不一致拒绝导入（BR-5.7-03）；预检 P0/P1=100%+总体≥99% 阻断发布（BR-5.7-01/02）。" />

    <!-- 17.1.2 缺口报告 -->
    <el-form inline size="small">
      <el-form-item label="语种">
        <el-select v-model="covLocale" style="width:120px" @change="loadCoverage">
          <el-option label="en-US" value="en-US" /><el-option label="ja-JP" value="ja-JP" />
        </el-select>
      </el-form-item>
    </el-form>
    <el-row :gutter="12" style="margin-bottom:12px" v-if="coverage">
      <el-col :span="6"><el-card shadow="never"><div class="stat-label">总体覆盖率</div><div class="stat-num">{{ coverage.coverage }}%</div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="stat-label">已翻译 / 总键</div><div class="stat-num">{{ coverage.translated }}/{{ coverage.totalKeys }}</div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="stat-label">缺口数</div><div class="stat-num warn">{{ (coverage.gaps || []).length }}</div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="stat-label">门槛</div><div class="stat-num">{{ coverageMin }}%</div></el-card></el-col>
    </el-row>
    <el-table v-if="coverage" :data="coverage.items || []" border size="small" style="margin-bottom:12px">
      <el-table-column prop="priority" label="优先级" width="90" />
      <el-table-column prop="total" label="总键数" width="100" />
      <el-table-column prop="translated" label="已翻译" width="100" />
      <el-table-column prop="coverage" label="覆盖率%" width="110" />
    </el-table>
    <h4>缺口键清单（按优先级）</h4>
    <el-table :data="(coverage && coverage.gaps) || []" border size="small" max-height="300">
      <el-table-column prop="keyName" label="键名" min-width="220" show-overflow-tooltip />
      <el-table-column prop="module" label="模块" width="100" />
      <el-table-column prop="priority" label="优先级" width="80" />
    </el-table>
    <h4 style="margin-top:14px">运行时缺口台账（i18n.missing 批量回传累计）</h4>
    <el-table :data="gaps" border size="small" max-height="260">
      <el-table-column prop="keyName" label="键名" min-width="220" show-overflow-tooltip />
      <el-table-column prop="module" label="模块" width="90" />
      <el-table-column prop="priority" label="优先级" width="80" />
      <el-table-column prop="hitCount" label="命中次数" width="100" />
      <el-table-column prop="firstHitAt" label="首次命中" width="160" />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button v-if="row.status==='OPEN'" link type="primary" size="small" @click="closeGap(row)">闭环</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 17.1.2 缺口报告（拆分自原语言管理页）：覆盖率概览 / 缺口键清单 / 运行时缺口台账。
 * 覆盖率共享状态见 useLanguage.js（键值维护页导入后同步刷新）。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getGapsApi, closeGapApi } from '@/api/i18n/i18n'
import { useLanguage } from './useLanguage'

const { covLocale, coverage, loadCoverage } = useLanguage()

const coverageMin = 99
const gaps = ref([])

onMounted(() => { loadCoverage(); loadGaps() })

async function loadGaps() {
  try {
    const r = await getGapsApi({ limit: 100 })
    gaps.value = r.data || []
  } catch { gaps.value = [] }
}
async function closeGap(row) {
  await closeGapApi(row.id)
  ElMessage.success('缺口已闭环')
  loadGaps()
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
.stat-label { font-size: 12px; color: #909399; }
.stat-num { font-size: 22px; font-weight: bold; color: #303133; margin-top: 4px; }
.stat-num.warn { color: #e6a23c; }
</style>
