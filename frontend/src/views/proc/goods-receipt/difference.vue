<template>
  <div class="page">
    <div class="card-header">
      <span style="font-weight: bold; font-size: 16px;">{{ title }}</span>
      <span class="tip">2.4 收货管理 · FR-4.2-4-1 · C-4.2-04 容差 0.5% · BR-4.2-21/22/28/49</span>
    </div>

    <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
      title="差异对账台（L1064）：超交走收货调整单或拒收，短交待补齐或手动关闭"
      description="无「直接接受超收」出口（BR-4.2-49 专用流程，P-1 轻量链）。差异处置为采购员职责（ADMIN）。" />
    <div class="toolbar">
      <el-select v-model="diffQuery.status" placeholder="状态" clearable style="width: 140px;" @change="loadDiffs(1)">
        <el-option label="待处置" value="PENDING" />
        <el-option label="已转调整" value="ADJUSTED" />
        <el-option label="已拒收" value="RETURNED" />
        <el-option label="已关闭" value="CLOSED" />
      </el-select>
      <el-select v-model="diffQuery.diffType" placeholder="类型" clearable style="width: 110px;" @change="loadDiffs(1)">
        <el-option label="超交" value="OVER" />
        <el-option label="短交" value="SHORT" />
      </el-select>
      <el-button @click="loadDiffs(1)">查询</el-button>
    </div>
    <el-table :data="diffList" size="small" border v-loading="loading">
      <el-table-column prop="diffNo" label="差异单号" width="160" />
      <el-table-column prop="itemCode" label="物料" width="120" />
      <el-table-column label="类型" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="row.diffType === 'OVER' ? 'danger' : 'warning'" size="small">
            {{ row.diffType === 'OVER' ? '超交' : '短交' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="orderedQty" label="未清量" width="90" align="right" />
      <el-table-column prop="receivedQty" label="实收" width="90" align="right" />
      <el-table-column prop="diffQty" label="差异量" width="90" align="right" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="diffTag(row.status)" size="small">{{ diffName(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="disposeNote" label="处置说明" min-width="140" show-overflow-tooltip />
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING' && isAdmin">
            <el-button v-if="row.diffType === 'OVER'" link type="primary" @click="startAdjust(row)">发起调整单</el-button>
            <el-button link type="danger" @click="dispose(row, 'REJECT')">拒收</el-button>
            <el-button v-if="row.diffType === 'SHORT'" link type="warning" @click="dispose(row, 'CLOSE')">关闭</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top: 8px;" layout="total, prev, pager, next" :total="diffTotal"
                   :page-size="diffQuery.size" :current-page="diffQuery.current" @current-change="loadDiffs" />

    <el-divider content-position="left">收货调整单（BR-4.2-49，单节点审批）</el-divider>
    <el-table :data="adjList" size="small" border>
      <el-table-column prop="adjNo" label="调整单号" width="170" />
      <el-table-column prop="poNo" label="PO" width="150" />
      <el-table-column prop="itemCode" label="物料" width="120" />
      <el-table-column prop="addQty" label="追加数量" width="90" align="right" />
      <el-table-column label="数量变化" width="130" align="center">
        <template #default="{ row }">{{ row.oldQty }} → {{ row.newQty }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="adjTag(row.status)" size="small">{{ adjName(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="approveNote" label="审批意见" min-width="120" show-overflow-tooltip />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING_APPROVE' && canApproveAdj">
            <el-button link type="success" @click="approveAdj(row, true)">批准执行</el-button>
            <el-button link type="danger" @click="approveAdj(row, false)">驳回</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 2.4.3 容差校验 / 差异对账台（拆分自原收货管理操作台）：差异处置（拒收/关闭/转调整单）
 * 与收货调整单审批。共享状态与角色见 useGoodsReceipt.js。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDifferencePageApi, disposeDifferenceApi,
  getAdjustmentPageApi, approveAdjustmentApi
} from '@/api/proc/goods-receipt'
import { useGoodsReceipt } from './useGoodsReceipt'
import { diffName, diffTag, adjName, adjTag } from './gr-helpers'

const route = useRoute()
const title = computed(() => route.meta.title || '容差校验')
const { loading, isAdmin, canApproveAdj } = useGoodsReceipt()

// ---------- 差异与调整单 ----------
const diffList = ref([])
const diffTotal = ref(0)
const diffQuery = reactive({ status: 'PENDING', diffType: '', current: 1, size: 10 })
async function loadDiffs(page) {
  diffQuery.current = page || 1
  loading.value = true
  try {
    const { data } = await getDifferencePageApi({ current: diffQuery.current, size: diffQuery.size, status: diffQuery.status, diffType: diffQuery.diffType })
    diffList.value = data.records || []
    diffTotal.value = Number(data.total) || 0
  } finally { loading.value = false }
}

function startAdjust(row) {
  ElMessageBox.confirm(`对差异单 ${row.diffNo}（差异量 ${row.diffQty}）发起收货调整单？批准后 PO 数量追加、价格不变。`, 'BR-4.2-49 收货调整单', { type: 'warning' })
    .then(async () => {
      await disposeDifferenceApi(row.id, 'START_ADJUST', '超交转调整')
      ElMessage.success('调整单已发起，待采购经理审批')
      loadDiffs(diffQuery.current)
      loadAdjs()
    }).catch(() => {})
}

function dispose(row, action) {
  const label = action === 'REJECT' ? '拒收超量（该行不入待检不过账）' : '关闭短交差异'
  ElMessageBox.prompt(`${label}，原因（≥2 字）`, row.diffNo, { inputPattern: /^.{2,}$/, inputErrorMessage: '原因不少于 2 字' })
    .then(async ({ value }) => {
      await disposeDifferenceApi(row.id, action, value)
      ElMessage.success('处置完成')
      loadDiffs(diffQuery.current)
    }).catch(() => {})
}

const adjList = ref([])
async function loadAdjs() {
  const { data } = await getAdjustmentPageApi({ current: 1, size: 50 })
  adjList.value = data.records || []
}

function approveAdj(row, approved) {
  ElMessageBox.prompt(
    approved ? '审批意见（批准后立即执行：PO 数量追加、价格不动）' : '驳回意见（≥2 字）',
    `${row.adjNo} · ${approved ? '批准执行' : '驳回'}`,
    { inputPattern: /^.{2,}$/, inputErrorMessage: '意见不少于 2 字' })
    .then(async ({ value }) => {
      const res = await approveAdjustmentApi(row.id, approved, value)
      if (res.data?.status === 'EXECUTED') {
        ElMessage.success(`已执行：新收货单 ${res.data.newGrNo}（待过账）`)
      } else {
        ElMessage.success('已驳回')
      }
      loadAdjs()
      loadDiffs(diffQuery.current)
    }).catch(() => {})
}

onMounted(() => {
  loadDiffs(1)
  loadAdjs()
})
</script>

<style scoped>
.page { padding: 4px; }
.card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.tip { color: #909399; font-size: 12px; }
</style>
