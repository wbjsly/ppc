<template>
  <div class="page fx-reval-page">
    <el-tabs v-model="tab">
      <!-- ================= 8.5.2 期末重估 ================= -->
      <el-tab-pane label="期末重估" name="reval">
        <el-form inline>
          <el-form-item label="会计期间">
            <el-input v-model="period" placeholder="YYYYMM" style="width:120px" />
          </el-form-item>
          <el-button type="primary" @click="onTrial" :loading="loading">试算（不落库）</el-button>
          <el-button type="success" @click="onGenerate" :loading="generating">生成重估批次</el-button>
        </el-form>
        <el-alert type="info" :closable="false" show-icon style="margin-bottom:8px"
          title="重估范围：外币货币性项目（AP 未清 / 外币银行账户 / AR 未清）；差额 = 外币额 ×（期末中间价 − 原记账汇率）。生成将自动冲回上期并落 DRAFT 凭证，待财务主管审核后过账（C-4.6-07 制单≠审核）。" />

        <!-- 试算/生成结果汇总 -->
        <el-card v-if="trial" shadow="never" style="margin-bottom:12px">
          <template #header>
            <span>试算汇总（期间 {{ trial.period }}，期末 {{ trial.periodEnd }}）</span>
          </template>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="敞口笔数">{{ trial.items.length }}</el-descriptions-item>
            <el-descriptions-item label="汇兑差额合计">
              <span :style="{ color: Number(trial.totalDiff) >= 0 ? '#67c23a' : '#f56c6c' }">
                ¥{{ fmt(trial.totalDiff) }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="口径">正=收益 负=损失</el-descriptions-item>
          </el-descriptions>
          <el-table :data="trial.summary" border size="small" style="margin-top:8px">
            <el-table-column prop="currency" label="币种" width="80" />
            <el-table-column prop="itemType" label="项目类型" width="100" />
            <el-table-column prop="itemCount" label="笔数" width="70" align="center" />
            <el-table-column label="外币合计" width="130" align="right">
              <template #default="{ row }">{{ row.currency }} {{ fmt(row.fcTotal) }}</template>
            </el-table-column>
            <el-table-column label="汇兑差额" width="130" align="right">
              <template #default="{ row }">
                <span :style="{ color: Number(row.diffTotal) >= 0 ? '#67c23a' : '#f56c6c' }">
                  ¥{{ fmt(row.diffTotal) }}
                </span>
              </template>
            </el-table-column>
          </el-table>
          <el-table :data="trial.items" border size="small" style="margin-top:8px" max-height="260">
            <el-table-column prop="itemType" label="类型" width="80" />
            <el-table-column prop="itemNo" label="单号/账号" width="160" show-overflow-tooltip />
            <el-table-column prop="partyName" label="往来对象" min-width="130" show-overflow-tooltip />
            <el-table-column prop="currency" label="币种" width="70" />
            <el-table-column label="外币额" width="110" align="right">
              <template #default="{ row }">{{ fmt(row.fcAmount) }}</template>
            </el-table-column>
            <el-table-column label="记账汇率" width="100" align="right">
              <template #default="{ row }">{{ Number(row.bookingRate).toFixed(6) }}</template>
            </el-table-column>
            <el-table-column label="期末汇率" width="100" align="right">
              <template #default="{ row }">{{ Number(row.periodRate).toFixed(6) }}</template>
            </el-table-column>
            <el-table-column label="汇兑差额" width="120" align="right">
              <template #default="{ row }">
                <span :style="{ color: Number(row.diffAmount) >= 0 ? '#67c23a' : '#f56c6c' }">
                  ¥{{ fmt(row.diffAmount) }}
                </span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- 批次列表 -->
        <el-card shadow="never">
          <template #header><span>重估批次</span></template>
          <el-table :data="batches" border size="small" @row-click="onBatchRow"
                    style="cursor:pointer">
            <el-table-column prop="period" label="期间" width="90" />
            <el-table-column prop="itemCount" label="笔数" width="70" align="center" />
            <el-table-column label="本期重估" width="120" align="right">
              <template #default="{ row }">¥{{ fmt(row.revalTotal) }}</template>
            </el-table-column>
            <el-table-column label="上期冲回" width="120" align="right">
              <template #default="{ row }">
                <span v-if="Number(row.reversedTotal)">¥{{ fmt(row.reversedTotal) }}</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="净额" width="120" align="right">
              <template #default="{ row }">
                <span :style="{ color: Number(row.netTotal) >= 0 ? '#67c23a' : '#f56c6c' }">
                  ¥{{ fmt(row.netTotal) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="voucherNo" label="凭证号" width="150">
              <template #default="{ row }">{{ row.voucherNo || '—' }}</template>
            </el-table-column>
            <el-table-column label="凭证状态" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.voucherStatus" size="small"
                        :type="row.voucherStatus === 'POSTED' ? 'success'
                             : row.voucherStatus === 'DELETED' ? 'info' : 'warning'">
                  {{ row.voucherStatus }}
                </el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click.stop="onBatchRow(row)">下钻</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- 批次下钻抽屉 -->
        <el-drawer v-model="drawerVisible" :title="`批次明细 ${drawerPeriod}`" size="62%">
          <el-table :data="drawerData?.summary || []" border size="small">
            <el-table-column prop="currency" label="币种" width="80" />
            <el-table-column prop="itemType" label="类型" width="90" />
            <el-table-column prop="itemCount" label="笔数" width="70" align="center" />
            <el-table-column label="差额合计" width="130" align="right">
              <template #default="{ row }">¥{{ fmt(row.diffTotal) }}</template>
            </el-table-column>
          </el-table>
          <el-table :data="drawerData?.items || []" border size="small" style="margin-top:10px">
            <el-table-column prop="itemType" label="类型" width="70" />
            <el-table-column prop="itemNo" label="单号" width="150" show-overflow-tooltip />
            <el-table-column prop="currency" label="币种" width="65" />
            <el-table-column label="外币额" width="100" align="right">
              <template #default="{ row }">{{ fmt(row.fcAmount) }}</template>
            </el-table-column>
            <el-table-column label="记账/期末" width="150" align="center">
              <template #default="{ row }">
                {{ Number(row.bookingRate).toFixed(4) }} → {{ Number(row.periodRate).toFixed(4) }}
              </template>
            </el-table-column>
            <el-table-column label="差额" width="110" align="right">
              <template #default="{ row }">
                <span :style="{ color: Number(row.diffAmount) >= 0 ? '#67c23a' : '#f56c6c' }">
                  ¥{{ fmt(row.diffAmount) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="已冲回" width="80" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.reversedBy" type="info" size="small">已冲回</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-divider>凭证链</el-divider>
          <el-table :data="drawerData?.vouchers || []" border size="small">
            <el-table-column prop="role" label="角色" width="120" />
            <el-table-column prop="voucherNo" label="凭证号" width="160" />
            <el-table-column prop="voucherDate" label="凭证日期" width="110" />
            <el-table-column prop="status" label="状态" width="100" />
          </el-table>
        </el-drawer>
      </el-tab-pane>

      <!-- ================= 8.5.3 汇兑损益 ================= -->
      <el-tab-pane label="汇兑损益" name="gainloss">
        <el-form inline>
          <el-form-item label="会计期间">
            <el-input v-model="glPeriod" placeholder="YYYYMM（缺省当期）" style="width:150px" />
          </el-form-item>
          <el-button type="primary" @click="loadGainLoss" :loading="glLoading">查询</el-button>
        </el-form>
        <el-alert type="info" :closable="false" show-icon style="margin-bottom:8px"
          title="6604 汇兑损益科目本期发生额（利润表口径）；已实现/未实现拆分不在本期范围（spec Out of Scope）。" />
        <el-descriptions v-if="glSummary" :column="3" border size="small">
          <el-descriptions-item label="本期汇兑损失（借方）">¥{{ fmt(glSummary.periodDebit) }}</el-descriptions-item>
          <el-descriptions-item label="本期汇兑收益（贷方）">¥{{ fmt(glSummary.periodCredit) }}</el-descriptions-item>
          <el-descriptions-item label="本期净额">
            <span :style="{ color: Number(glSummary.periodNet) >= 0 ? '#67c23a' : '#f56c6c' }">
              ¥{{ fmt(glSummary.periodNet) }}
            </span>
          </el-descriptions-item>
        </el-descriptions>
        <el-empty v-else description="点击查询加载 6604 科目余额" :image-size="80" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { trialRun, generateReval, revalBatches, revalBatchDetails, gainLossSummary } from '@/api/fin/fx'

const route = useRoute()
const tab = ref(route.meta?.tab || 'reval')

// ---------- 8.5.2 期末重估 ----------
const now = new Date()
const curPeriod = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}`
const period = ref(curPeriod)
const loading = ref(false)
const generating = ref(false)
const trial = ref(null)
const batches = ref([])
const drawerVisible = ref(false)
const drawerPeriod = ref('')
const drawerData = ref(null)

const fmt = (v) => {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

async function onTrial() {
  if (!/^\d{6}$/.test(period.value)) {
    ElMessage.warning('期间格式须为 YYYYMM')
    return
  }
  loading.value = true
  try {
    const res = await trialRun(period.value)
    trial.value = res.data
  } finally {
    loading.value = false
  }
}

async function onGenerate() {
  if (!/^\d{6}$/.test(period.value)) {
    ElMessage.warning('期间格式须为 YYYYMM')
    return
  }
  try {
    await ElMessageBox.confirm(
      `生成 ${period.value} 重估批次？将自动冲回上期重估并生成 DRAFT 凭证（待审核）。`,
      '确认生成', { type: 'warning' })
  } catch { return }
  generating.value = true
  try {
    const res = await generateReval(period.value)
    const d = res.data
    ElMessage.success(`已生成 ${d.revalNo}：${d.itemCount} 笔，差额 ¥${fmt(d.totalDiff)}，凭证 ${d.voucherNo}（DRAFT）`)
    await loadBatches()
  } finally {
    generating.value = false
  }
}

async function loadBatches() {
  const res = await revalBatches()
  batches.value = res.data || []
}

async function onBatchRow(row) {
  drawerPeriod.value = row.period
  const res = await revalBatchDetails(row.period)
  drawerData.value = res.data
  drawerVisible.value = true
}

// ---------- 8.5.3 汇兑损益 ----------
const glPeriod = ref('')
const glLoading = ref(false)
const glSummary = ref(null)

async function loadGainLoss() {
  glLoading.value = true
  try {
    const res = await gainLossSummary(glPeriod.value || undefined)
    glSummary.value = res.data
  } finally {
    glLoading.value = false
  }
}

onMounted(loadBatches)
</script>

<style scoped>
.muted { color: #909399; }
</style>
