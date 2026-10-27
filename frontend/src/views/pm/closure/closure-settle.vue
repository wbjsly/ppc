<template>
  <div class="closure-settle">
    <el-card style="margin-bottom: 12px;">
      <div class="hint">
        财务成本结算（FR-4.8-7-4 / BR-4.8-29）：财务确认结算 → 解除项目级成本冻结 + 生成不可变成本快照
        （归集总额/基线额/偏差率/确认人/时间，十年归档锚点）。快照一经生成不可修改（二次确认 409）。
        结算确认是结项归档审批的前置条件；本页不做账务结转（研发费用化/资本化属会计政策范畴）。
      </div>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-head">
          <b>成本结算确认</b>
          <div>
            <el-input v-model="projectId" placeholder="项目 ID" style="width: 260px;" clearable />
            <el-button type="primary" style="margin-left: 8px;" @click="load">查询</el-button>
          </div>
        </div>
      </template>

      <template v-if="loaded">
        <el-descriptions :column="3" size="large" border style="margin-bottom: 16px;">
          <el-descriptions-item label="项目编号">{{ summary.projectNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="成本归集总额">¥ {{ fmt(summary.collectTotal) }}</el-descriptions-item>
          <el-descriptions-item label="预算基线总额">¥ {{ fmt(summary.baselineAmt) }}</el-descriptions-item>
          <el-descriptions-item label="偏差率">
            <el-tag :type="Math.abs(Number(summary.deviationRate || 0)) > 0.05 ? 'danger' : 'success'"
              size="small">{{ pct(summary.deviationRate) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="成本冻结">
            <el-tag :type="summary.freezeFlag === '1' ? 'danger' : 'info'" size="small">
              {{ summary.freezeFlag === '1' ? '已冻结（' + (summary.freezeReason || '') + '）' : '未冻结' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="结算状态">
            <el-tag :type="settled ? 'success' : 'warning'" size="small">
              {{ settled ? '已确认' : '未确认' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <el-alert v-if="settled" type="success" :closable="false" show-icon
          :title="`快照已固化：归集 ¥${fmt(summary.collectTotal)} / 基线 ¥${fmt(summary.baselineAmt)}
            / 偏差 ${pct(summary.deviationRate)}，确认人 ${settled.confirmBy} 于 ${fmtTs(settled.confirmDate)}`" />
        <el-alert v-else type="warning" :closable="false" show-icon
          title="确认后将解除项目成本冻结并生成不可变快照——该操作是结项归档审批的前置条件。">
          <template #default>
            <el-button type="primary" size="small" style="margin-top: 8px;" @click="doConfirm">
              确认结算（FINANCE/FIN_MGR）
            </el-button>
          </template>
        </el-alert>
      </template>
      <el-empty v-else description="输入项目 ID 后查询结算汇总" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/pm/closure'

const projectId = ref('')
const summary = ref({})
const loaded = ref(false)

const settled = computed(() => summary.value.settled || null)

async function load() {
  if (!projectId.value) return
  const { data } = await api.settlementSummary(projectId.value)
  summary.value = data
  loaded.value = true
}

async function doConfirm() {
  await ElMessageBox.confirm(
    '确认后解除项目成本冻结并生成不可变结算快照（不可撤销），继续？',
    '确认成本结算', { type: 'warning' })
  const { data } = await api.confirmSettlement(projectId.value)
  summary.value = data
  ElMessage.success('结算已确认，快照已固化')
}

function fmt(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function pct(v) {
  return (Number(v || 0) * 100).toFixed(2) + '%'
}

function fmtTs(v) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : ''
}
</script>

<style scoped>
.hint { color: #909399; font-size: 12px; line-height: 1.7; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
</style>
