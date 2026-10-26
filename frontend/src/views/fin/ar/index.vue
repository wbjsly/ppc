<template>
  <div class="page ar-mgmt-page">
    <el-tabs v-model="tab">
      <!-- ================= 8.2.1 应收确认 ================= -->
      <el-tab-pane label="应收确认" name="confirm">
        <el-form inline>
          <el-radio-group v-model="cfConfirmed" @change="loadConfirm">
            <el-radio-button :value="false">未确认</el-radio-button>
            <el-radio-button :value="true">已确认</el-radio-button>
          </el-radio-group>
          <el-input v-model="cfQuery.keyword" clearable placeholder="单号/客户" style="width:180px;margin-left:12px" />
          <el-button type="primary" @click="loadConfirm">查询</el-button>
          <el-button type="success" :disabled="cfSelection.length === 0" @click="onBatchConfirm">
            批量确认（{{ cfSelection.length }}）
          </el-button>
        </el-form>
        <el-table :data="cfRows" border size="small" @selection-change="(v) => (cfSelection = v)">
          <el-table-column v-if="!cfConfirmed" type="selection" width="40" />
          <el-table-column prop="arNo" label="应收单号" width="160" />
          <el-table-column prop="customerName" label="客户" min-width="160" show-overflow-tooltip />
          <el-table-column prop="invoiceDate" label="开票日" width="110" />
          <el-table-column prop="dueDate" label="到期日" width="110" />
          <el-table-column label="未核销余额" width="120" align="right">
            <template #default="{ row }">{{ balOf(row) }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column v-if="cfConfirmed" prop="confirmBy" label="确认人" width="110" />
          <el-table-column v-if="cfConfirmed" prop="confirmDate" label="确认时间" width="160">
            <template #default="{ row }">{{ fmtDt(row.confirmDate) }}</template>
          </el-table-column>
          <el-table-column v-if="!cfConfirmed" label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="onConfirm(row)">确认</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="cfQuery.current" v-model:page-size="cfQuery.size"
          :total="cfTotal" layout="total, prev, pager, next" @change="loadConfirm"
          style="margin-top:8px;justify-content:flex-end" />
        <el-alert type="info" :closable="false" show-icon style="margin-top:8px"
          title="确认标记是账龄起算锚点：未确认的应收不计入账龄分析与坏账计提基数；已确认不可撤销。" />
      </el-tab-pane>

      <!-- ================= 8.2.2 账龄分析 ================= -->
      <el-tab-pane label="账龄分析" name="aging">
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
      </el-tab-pane>

      <!-- ================= 8.2.3 坏账计提 ================= -->
      <el-tab-pane label="坏账计提" name="provision">
        <el-form inline>
          <el-input v-model="pvCustomerId" placeholder="客户 ID" style="width:200px" />
          <el-input v-model="pvPeriod" placeholder="期间 yyyyMM" maxlength="6" style="width:130px" />
          <el-button type="primary" @click="onTrial">试算</el-button>
          <el-button type="success" :disabled="!pvTrial" @click="onConfirmProvision">确认计提</el-button>
        </el-form>
        <template v-if="pvTrial">
          <el-descriptions :column="4" border size="small" style="margin-bottom:12px">
            <el-descriptions-item label="目标余额">{{ pvTrial.target }}</el-descriptions-item>
            <el-descriptions-item label="已提余额">{{ pvTrial.booked }}</el-descriptions-item>
            <el-descriptions-item label="本期应计提">
              <span :style="Number(pvTrial.periodAccrual) < 0 ? 'color:#67c23a' : 'color:#f56c6c'">
                {{ pvTrial.periodAccrual }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="凭证科目">借 6702 / 贷 1232</el-descriptions-item>
          </el-descriptions>
          <el-table :data="pvTrial.segs" border size="small">
            <el-table-column prop="bucket" label="账龄段" width="140" />
            <el-table-column prop="base" label="段基数" width="140" align="right" />
            <el-table-column prop="rate" label="比例" width="100" align="right" />
            <el-table-column prop="accrual" label="计提额" width="140" align="right" />
          </el-table>
        </template>
        <el-divider />
        <el-form inline>
          <el-input v-model="pvPageQuery.period" placeholder="期间筛选 yyyyMM" maxlength="6" style="width:130px" />
          <el-button @click="loadProvisions">查询台账</el-button>
        </el-form>
        <el-table :data="pvRows" border size="small">
          <el-table-column prop="provisionNo" label="计提单号" width="220" show-overflow-tooltip />
          <el-table-column prop="period" label="期间" width="90" />
          <el-table-column prop="customerName" label="客户" min-width="140" show-overflow-tooltip />
          <el-table-column prop="targetBalance" label="目标余额" width="120" align="right" />
          <el-table-column prop="bookedBalance" label="已提余额" width="120" align="right" />
          <el-table-column prop="periodAccrual" label="本期计提" width="120" align="right" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" @click="onDetail(row)">明细</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="pvPageQuery.current" v-model:page-size="pvPageQuery.size"
          :total="pvTotal" layout="total, prev, pager, next" @change="loadProvisions"
          style="margin-top:8px;justify-content:flex-end" />
        <el-dialog v-model="pvDetailVisible" title="计提台账明细（六段快照）" width="640">
          <el-table :data="pvDetailSegs" border size="small">
            <el-table-column prop="bucket" label="账龄段" width="140" />
            <el-table-column prop="base" label="段基数" width="140" align="right" />
            <el-table-column prop="rate" label="比例" width="100" align="right" />
            <el-table-column prop="accrual" label="计提额" width="140" align="right" />
          </el-table>
        </el-dialog>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  confirmPage, confirmAr, batchConfirm,
  aging,
  provisionTrial, provisionConfirm, provisionPage, provisionDetail
} from '@/api/fin/ar-aging'

const route = useRoute()
const tab = ref(route.meta?.tab || 'confirm')

// ---------- 8.2.1 确认 ----------
const cfConfirmed = ref(false)
const cfQuery = reactive({ current: 1, size: 20, keyword: '' })
const cfRows = ref([])
const cfTotal = ref(0)
const cfSelection = ref([])

function balOf(row) {
  const a = Number(row.amount || 0)
  const p = Number(row.paidAmount || 0)
  const r = Number(row.redAmount || 0)
  return (a - p - r).toFixed(2)
}
function fmtDt(v) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : ''
}

async function loadConfirm() {
  const { data } = await confirmPage({
    ...cfQuery, confirmed: cfConfirmed.value, keyword: cfQuery.keyword || undefined
  })
  cfRows.value = data.records || []
  cfTotal.value = Number(data.total || 0)
}

async function onConfirm(row) {
  await ElMessageBox.confirm(`确认应收 ${row.arNo}？确认后不可撤销，且作为账龄起算锚点。`, '确认', { type: 'warning' })
  await confirmAr(row.id)
  ElMessage.success('已确认')
  loadConfirm()
}

async function onBatchConfirm() {
  await ElMessageBox.confirm(`批量确认选中的 ${cfSelection.length} 张应收？确认后不可撤销。`, '批量确认', { type: 'warning' })
  const { data } = await batchConfirm(cfSelection.map((r) => r.id))
  ElMessage.success(`已确认 ${data.confirmed} 张`)
  if (data.skipped?.length) {
    ElMessage.warning(`跳过 ${data.skipped.length} 张：${data.skipped[0]}`)
  }
  loadConfirm()
}

// ---------- 8.2.2 账龄 ----------
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

// ---------- 8.2.3 坏账计提 ----------
const pvCustomerId = ref('')
const pvPeriod = ref('')
const pvTrial = ref(null)
const pvPageQuery = reactive({ current: 1, size: 20, period: '' })
const pvRows = ref([])
const pvTotal = ref(0)
const pvDetailVisible = ref(false)
const pvDetailSegs = ref([])

async function onTrial() {
  if (!pvCustomerId.value || !pvPeriod.value) {
    ElMessage.warning('客户与期间必填')
    return
  }
  const { data } = await provisionTrial(pvCustomerId.value, pvPeriod.value)
  pvTrial.value = data
}

async function onConfirmProvision() {
  await ElMessageBox.confirm(
    `确认期间 ${pvPeriod.value} 坏账计提？将冲回上期计提并按本期目标重提（借 6702 / 贷 1232）。`,
    '确认计提', { type: 'warning' }
  )
  const { data } = await provisionConfirm(pvCustomerId.value, pvPeriod.value)
  ElMessage.success(`已确认 ${data.provisionNo}，本期计提 ${data.periodAccrual}`)
  pvTrial.value = null
  loadProvisions()
}

async function loadProvisions() {
  const { data } = await provisionPage({
    ...pvPageQuery, period: pvPageQuery.period || undefined
  })
  pvRows.value = data.records || []
  pvTotal.value = Number(data.total || 0)
}

async function onDetail(row) {
  const { data } = await provisionDetail(row.id)
  pvDetailSegs.value = data.segs || []
  pvDetailVisible.value = true
}

onMounted(() => {
  loadConfirm()
  loadAging()
  loadProvisions()
})
</script>

<style scoped>
.bucket-label { font-size: 12px; color: #909399; }
.bucket-label.over90 { color: #f56c6c; font-weight: 600; }
.bucket-base { font-size: 18px; font-weight: 600; margin: 4px 0 2px; }
.bucket-ratio { font-size: 12px; color: #606266; }
</style>
