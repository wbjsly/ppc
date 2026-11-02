<template>
  <div class="eam-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px;"
      title="月度计提（7.2.1）：手动执行本月折旧，同事务出凭证+回写台账"
      description="PERIOD_TAG 幂等卡控（同月已提 422，先冲回）；三方法 SL/DDB/SYD；成本中心→科目分摊；BR-4.13-13 勾稽。" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="periodTag" placeholder="会计月 yyyyMM" style="width:140px;" clearable />
        <el-button type="primary" @click="loadPreview">应提预览</el-button>
        <el-button v-if="canRun" type="success" :loading="running" @click="doRun">执行本月计提</el-button>
        <span class="spacer" />
        <el-button v-if="canRun && detail.run" type="danger" plain size="small"
          @click="doReverse(detail.run)">冲回本单</el-button>
      </div>

      <el-alert v-if="preview.missingParams && preview.missingParams.length" type="warning"
        :closable="false" style="margin:8px 0;"
        :title="`参数缺失告警（${preview.missingParams.length} 项）：${preview.missingParams.join('；')}`" />
      <el-alert v-if="preview.alreadyRun" type="error" :closable="false" style="margin:8px 0;"
        title="该会计月已存在有效计提单，如需重算请先冲回（PERIOD_TAG 幂等卡控）" />

      <el-table :data="preview.cards || []" size="small" border v-loading="loading" max-height="320"
        v-if="preview.cards && preview.cards.length" style="margin-top:8px;">
        <el-table-column prop="cardNo" label="卡片号" width="150" />
        <el-table-column prop="assetName" label="资产名称" min-width="140" />
        <el-table-column prop="method" label="折旧方法" width="90" align="center">
          <template #default="{ row }">{{ methodLabel(row.method) }}</template>
        </el-table-column>
        <el-table-column label="期初NBV" width="120" align="right">
          <template #default="{ row }">¥ {{ fmt(row.beginNbv) }}</template>
        </el-table-column>
        <el-table-column label="本期折旧" width="120" align="right">
          <template #default="{ row }"><b>¥ {{ fmt(row.periodDepreciation) }}</b></template>
        </el-table-column>
        <el-table-column label="期末NBV" width="120" align="right">
          <template #default="{ row }">¥ {{ fmt(row.endNbv) }}</template>
        </el-table-column>
        <el-table-column label="残值预警" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.residualAlert" size="small" type="warning">预警</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="提足截减" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.capped" size="small" type="danger">截减</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="preview.cards && preview.cards.length" style="margin-top:8px;text-align:right;">
        预计折旧合计：<b style="color:#409EFF;">¥ {{ fmt(preview.totalDepreciation) }}</b>
      </div>
    </el-card>

    <el-card shadow="never" style="margin-top:12px;">
      <el-table :data="runRows" size="small" border v-loading="loading">
        <el-table-column prop="runNo" label="计提单号" width="150">
          <template #default="{ row }"><b>{{ row.runNo }}</b></template>
        </el-table-column>
        <el-table-column prop="periodTag" label="会计月" width="90" align="center" />
        <el-table-column label="本期折旧" width="120" align="right">
          <template #default="{ row }">¥ {{ fmt(row.totalDepr) }}</template>
        </el-table-column>
        <el-table-column label="勾稽差异" width="110" align="right">
          <template #default="{ row }">
            <span :style="{ color: Number(row.reconDiff) > 0 ? '#F56C6C' : '' }">¥ {{ fmt(row.reconDiff) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="warningCount" label="残值预警" width="90" align="center" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.reversedFlag === '1' ? 'info' : 'success'">
              {{ row.reversedFlag === '1' ? '已冲回' : '已过账' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openRunDetail(row)">明细</el-button>
            <el-button v-if="canRun && row.reversedFlag !== '1'" link type="danger" size="small"
              @click="doReverse(row)">冲回</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 计提单明细 -->
    <el-card shadow="never" style="margin-top:12px;" v-if="detail.run">
      <template #header><span>计提明细 —— {{ detail.run.runNo }}（{{ detail.run.periodTag }}）</span></template>
      <el-table :data="detail.details || []" size="small" border max-height="360">
        <el-table-column prop="cardNo" label="卡片号" width="150" />
        <el-table-column prop="assetName" label="资产名称" min-width="130" />
        <el-table-column label="本期折旧" width="110" align="right">
          <template #default="{ row }">¥ {{ fmt(row.periodDepr) }}</template>
        </el-table-column>
        <el-table-column label="期末累计" width="110" align="right">
          <template #default="{ row }">¥ {{ fmt(row.endAccumDepr) }}</template>
        </el-table-column>
        <el-table-column label="期末NBV" width="110" align="right">
          <template #default="{ row }">¥ {{ fmt(row.endNbv) }}</template>
        </el-table-column>
        <el-table-column prop="debitAccount" label="借方科目" width="100" align="center" />
        <el-table-column label="冲回" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.reversed === '1'" size="small" type="info">已冲回</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
/**
 * 7.2.1 月度计提（拆分自原折旧管理操作台）：应提预览 / 执行计提 / 计提单与明细 / 整单冲回。
 * 共享角色与格式化见 useDepRoles.js、dep-helpers.js。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { runDepreciationApi, getPreviewApi, getRunPageApi, getRunDetailApi, reverseRunApi } from '@/api/eam/depreciation'
import { fmt } from './dep-helpers'
import { useDepRoles } from './useDepRoles'

const { canRun } = useDepRoles()

const loading = ref(false)
const running = ref(false)

const periodTag = ref(new Date().toISOString().slice(0, 7).replace('-', ''))
const preview = reactive({ cards: [], totalDepreciation: 0, missingParams: [], alreadyRun: false })
const runRows = ref([])
const detail = reactive({ run: null, details: [] })

function methodLabel(m) {
  return { STRAIGHT_LINE: '直线法', DOUBLE_DECLINING: '双倍余额递减', SYD: '年数总和法' }[m] || m
}

async function loadPreview() {
  loading.value = true
  try {
    const res = await getPreviewApi(periodTag.value)
    Object.assign(preview, res.data || {})
  } finally { loading.value = false }
}

async function loadRuns() {
  const res = await getRunPageApi({ current: 1, size: 50, periodTag: periodTag.value })
  runRows.value = (res.data && res.data.records) || []
}

async function doRun() {
  running.value = true
  try {
    const res = await runDepreciationApi(periodTag.value)
    ElMessage.success(`计提完成：${res.data.cardCount} 张卡，折旧 ¥ ${fmt(res.data.totalDepreciation)}`
      + (res.data.policyApplied ? `；应用政策变更 ${res.data.policyApplied} 条` : ''))
    await Promise.all([loadPreview(), loadRuns()])
  } catch (e) { /* 422/409 由 request 层弹出 */ }
  finally { running.value = false }
}

async function openRunDetail(row) {
  const res = await getRunDetailApi(row.id)
  Object.assign(detail, res.data || {})
}

async function doReverse(row) {
  try {
    await ElMessageBox.confirm(`确认冲回计提单 ${row.runNo}？将红冲凭证并回滚台账，可重新计提。`,
      '整单冲回', { type: 'warning' })
  } catch { return }
  await reverseRunApi(row.id, '前端冲回')
  ElMessage.success('已冲回，期间已放行可重提')
  await Promise.all([loadPreview(), loadRuns()])
}

onMounted(async () => {
  await loadPreview()
  await loadRuns()
})
</script>

<style scoped>
.eam-page { padding: 12px; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.spacer { flex: 1; }
</style>
