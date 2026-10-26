<template>
  <div>
    <el-page-header content="外协建议（5.6.3）— 超载工作中心下钻占用工序，按定额矩阵推荐可替代外协资源（含协议价参考），改派走 L2 审批（BR-4.5-21）"
                    style="margin-bottom: 8px" />

    <!-- 超载工作中心 -->
    <div class="toolbar">
      <el-date-picker v-model="range" type="daterange" size="small" value-format="YYYY-MM-DD"
                      start-placeholder="开始" end-placeholder="结束" :clearable="false"
                      style="width: 250px" />
      <el-button size="small" type="primary" @click="load">查询超载</el-button>
      <span class="tip">数据与 5.6.2 同一读口径（只列超限行）；点行下钻占用工序</span>
    </div>

    <el-table :data="overRows" v-loading="loading" border stripe size="small" max-height="220"
              highlight-current-row @current-change="onPickWc">
      <el-table-column prop="wcCode" label="超载工作中心" width="160" />
      <el-table-column prop="date" label="日期" width="120" align="center" />
      <el-table-column prop="assignedHours" label="已分配(h)" width="110" align="right" />
      <el-table-column prop="availableHours" label="可用(h)" width="110" align="right" />
      <el-table-column label="负荷率" width="110" align="right">
        <template #default="{ row }">
          <span style="color: #f56c6c; font-weight: 700">{{ row.loadRate }}%</span>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && overRows.length === 0" description="当前无超限工作中心" :image-size="50" />

    <!-- 超载占用工序 -->
    <template v-if="pickedWc">
      <div class="toolbar" style="margin-top: 14px">
        <strong>{{ pickedWc.wcCode }} 占用工序（{{ pickedDate }}）</strong>
        <span class="tip">选中工序行 → 右侧推荐可替代外协资源</span>
      </div>
      <el-table :data="occupyRows" v-loading="oLoading" border stripe size="small" max-height="220"
                highlight-current-row @current-change="onPickDispatch">
        <el-table-column prop="dispatchNo" label="派工单号" width="160" />
        <el-table-column prop="moNo" label="工单号" width="150" />
        <el-table-column prop="opSeq" label="序" width="50" align="center" />
        <el-table-column prop="opName" label="工序" width="130" />
        <el-table-column label="计划时段" width="180" align="center">
          <template #default="{ row }">{{ row.planStart }} ~ {{ row.planEnd }}</template>
        </el-table-column>
        <el-table-column label="标记" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.riskFlag === '1'" type="warning" size="small">交期风险</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!oLoading && occupyRows.length === 0" description="该 WC 区间内无 RELEASED 派工"
                :image-size="40" />
    </template>

    <!-- 外协候选 -->
    <template v-if="pickedDispatch">
      <div class="toolbar" style="margin-top: 14px">
        <strong>{{ pickedDispatch.dispatchNo }} 可替代外协资源</strong>
        <span class="tip">候选 = 定额矩阵存在该工序定额 + 外协 + 启用 + 供应商 QUALIFIED（C-4.5-05 前置过滤）；协议价仅参考不参与计算</span>
      </div>
      <el-table :data="candidates" v-loading="cLoading" border stripe size="small" max-height="240">
        <el-table-column prop="wcCode" label="外协工作中心" width="150" />
        <el-table-column prop="wcName" label="名称" min-width="130" show-overflow-tooltip />
        <el-table-column label="供应商" width="190" show-overflow-tooltip>
          <template #default="{ row }">{{ row.supplierCode }}（{{ row.supplierName }}）</template>
        </el-table-column>
        <el-table-column label="当前负荷" width="100" align="right">
          <template #default="{ row }">{{ row.loadRate }}%</template>
        </el-table-column>
        <el-table-column label="参考报价" width="130" align="center">
          <template #default="{ row }">
            <span v-if="row.agreementPrice != null" style="color: #e6a23c">{{ row.priceText }}</span>
            <span v-else style="color: #909399">{{ row.priceText }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="center">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="applyOutsource(row)">改派此家</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!cLoading && candidates.length === 0"
                description="无可用外协资源（该工序无外协定额，或候选均被停用/无供应商过滤）" :image-size="50" />
    </template>

    <!-- 回联状态总览 -->
    <div class="toolbar" style="margin-top: 16px">
      <strong>外协派工回联</strong>
      <el-button size="small" type="primary" @click="loadOutsource">刷新</el-button>
      <span class="tip">PENDING_PR（PR 已建待转单）→ ORDERED（PO 已下达）→ RECEIVED（GR 已过账）；状态推进由采购域钩子自动回写并通知申请人</span>
    </div>
    <el-table :data="outsourceRows" border stripe size="small" max-height="220">
      <el-table-column prop="dispatchNo" label="派工单号" width="160" />
      <el-table-column prop="moNo" label="工单号" width="150" />
      <el-table-column prop="wcCode" label="外协 WC" width="130" />
      <el-table-column prop="prNo" label="请购单" width="150">
        <template #default="{ row }">{{ row.prNo || '-' }}</template>
      </el-table-column>
      <el-table-column prop="poNo" label="采购订单" width="150">
        <template #default="{ row }">{{ row.poNo || '-' }}</template>
      </el-table-column>
      <el-table-column label="回联状态" width="120" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.receiptFlag === '1'" type="success" size="small">RECEIVED</el-tag>
          <el-tag v-else-if="row.poNo" type="primary" size="small">ORDERED</el-tag>
          <el-tag v-else-if="row.prNo" type="warning" size="small">PENDING_PR</el-tag>
          <el-tag v-else type="info" size="small">未生 PR</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'RELEASED' ? 'success' : 'warning'">
            {{ row.status === 'RELEASED' ? '已确认' : row.status === 'CANCELLED' ? '已取消' : '待确认' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="outsourceRows.length === 0" description="尚无外协派工单" :image-size="50" />
  </div>
</template>

<script setup>
import { ref, onMounted, onActivated } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getLoadsApi, getDispatchListApi, getOutsourceCandidatesApi, applyOutsourceApi
} from '@/api/mrp/dispatch'

const route = useRoute()
const loading = ref(false)
const oLoading = ref(false)
const cLoading = ref(false)
const overRows = ref([])
const occupyRows = ref([])
const candidates = ref([])
const outsourceRows = ref([])
const range = ref([])
const pickedWc = ref(null)
const pickedDate = ref('')
const pickedDispatch = ref(null)

function defaultRange() {
  const fmt = d => d.toISOString().slice(0, 10)
  const from = new Date()
  const to = new Date()
  to.setDate(to.getDate() + 6)
  return [fmt(from), fmt(to)]
}

async function load() {
  loading.value = true
  try {
    const [from, to] = range.value.length === 2 ? range.value : defaultRange()
    const res = await getLoadsApi(from, to, undefined)
    overRows.value = (res.data || []).filter(r => r.overLimit)
  } finally {
    loading.value = false
  }
}

async function onPickWc(row) {
  pickedWc.value = row
  pickedDate.value = row ? row.date : ''
  pickedDispatch.value = null
  candidates.value = []
  if (!row) { occupyRows.value = []; return }
  oLoading.value = true
  try {
    const res = await getDispatchListApi('RELEASED', row.wcCode)
    // list 的 keyword 是单号/工单/WC 模糊——WC 命中后再按日期区间过滤（跨日均摊口径）
    occupyRows.value = (res.data || []).filter(d =>
      d.wcCode === row.wcCode && d.planStart <= row.date && d.planEnd >= row.date)
  } finally {
    oLoading.value = false
  }
}

async function onPickDispatch(row) {
  pickedDispatch.value = row
  candidates.value = []
  if (!row) return
  cLoading.value = true
  try {
    const res = await getOutsourceCandidatesApi({ dispatchId: row.id })
    candidates.value = res.data || []
  } finally {
    cLoading.value = false
  }
}

async function applyOutsource(cand) {
  const d = pickedDispatch.value
  if (!d) return
  try {
    await ElMessageBox.confirm(
      `将 ${d.dispatchNo}（${d.moNo} 序 ${d.opSeq}）改派至外协 ${cand.wcCode}`
      + `（供应商 ${cand.supplierCode}，参考 ${cand.priceText}）？提交计划主管审批。`,
      '外协改派申请', { type: 'warning' })
  } catch { return }
  try {
    await applyOutsourceApi(d.id, cand.wcCode)
    ElMessage.success('外协改派申请已提交（计划主管审批），通过后自动生 PR 进入采购链')
    await loadOutsource()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '提交失败')
  }
}

async function loadOutsource() {
  const res = await getDispatchListApi(undefined, undefined)
  outsourceRows.value = (res.data || []).filter(r => r.outsourceFlag === '1')
}

onMounted(() => {
  range.value = defaultRange()
  load()
  loadOutsource()
  if (route.query.wcCode) {
    // 从 5.6.2 超限行跳转：按 query 预选
    setTimeout(() => {
      const hit = overRows.value.find(r => r.wcCode === route.query.wcCode
        && (!route.query.date || r.date === route.query.date))
      if (hit) onPickWc(hit)
    }, 400)
  }
})
onActivated(() => {
  if (range.value.length !== 2) range.value = defaultRange()
  load()
  loadOutsource()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
</style>
