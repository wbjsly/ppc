<template>
  <div>
    <el-page-header content="收货检验（5.10.2）— 外协 PO 来源检验批：供应商/派工单/工序/供料上下文；检验动作复用既有检验批链（录入→判定→放行），不合格联动 SCAR"
                    style="margin-bottom: 8px" />

    <div class="toolbar">
      <el-select v-model="status" size="small" clearable placeholder="批次状态" style="width: 150px">
        <el-option label="待检" value="PENDING" />
        <el-option label="检验中" value="INPUTTING" />
        <el-option label="待复核" value="REVIEWING" />
        <el-option label="已判定" value="JUDGED" />
        <el-option label="已放行" value="RELEASED" />
      </el-select>
      <el-button size="small" type="primary" @click="load">查询</el-button>
      <span class="tip">检验录入/判定/放行请在批号上点「去检验」进入既有检验台；本页提供外协上下文与快捷动作</span>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe size="small" max-height="560">
      <el-table-column prop="lotNo" label="检验批号" width="150" />
      <el-table-column prop="itemName" label="加工物料" min-width="130" show-overflow-tooltip />
      <el-table-column prop="supplierName" label="供应商" width="150" show-overflow-tooltip />
      <el-table-column prop="poNo" label="外协 PO" width="130" show-overflow-tooltip />
      <el-table-column prop="dispatchNo" label="派工单号" width="140" />
      <el-table-column label="工单/工序" width="150">
        <template #default="{ row }">
          <span v-if="row.moNo">{{ row.moNo }} / {{ row.opSeq }} {{ row.opName }}</span>
          <span v-else style="color: #909399">-</span>
        </template>
      </el-table-column>
      <el-table-column label="供料" width="80" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.osSupplyType === 'COMPANY_SUPPLY'" type="warning" size="small">甲供</el-tag>
          <el-tag v-else-if="row.osSupplyType" type="info" size="small">自购</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="lotQty" label="数量" width="80" align="right" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="stTag(row.status)" size="small">{{ stText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="判定" width="80" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.result === 'PASS'" type="success" size="small">合格</el-tag>
          <el-tag v-else-if="row.result === 'FAIL'" type="danger" size="small">不合格</el-tag>
          <span v-else style="color: #909399">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" align="center" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="goInspect(row)">去检验</el-button>
          <el-button v-if="row.status === 'PENDING' || row.status === 'BLOCKED'" size="small"
                     type="warning" link @click="release(row)">放行</el-button>
          <el-button v-if="row.scarHint" size="small" type="danger" link @click="goScar(row)">SCAR 索赔</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="暂无外协检验批（外协 PO 收货登记后自动生成）"
              :image-size="60" />

    <el-pagination v-model:current-page="current" :page-size="size" :total="total"
                   layout="total, prev, pager, next" style="margin-top: 10px"
                   @current-change="load" />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getOutsourceBoardApi } from '@/api/qms/inspection'
import { releaseLotApi } from '@/api/qms/inspection'

const router = useRouter()
const loading = ref(false)
const rows = ref([])
const status = ref('')
const current = ref(1)
const size = 20
const total = ref(0)

const stText = (s) => ({
  BLOCKED: '无标准', PENDING: '待检', INPUTTING: '检验中',
  REVIEWING: '待复核', JUDGED: '已判定', RELEASED: '已放行', SKIPPED: '免检'
}[s] || s)
const stTag = (s) => ({
  BLOCKED: 'danger', PENDING: 'warning', INPUTTING: 'warning',
  REVIEWING: 'warning', JUDGED: 'info', RELEASED: 'success', SKIPPED: 'info'
}[s] || 'info')

async function load() {
  loading.value = true
  try {
    const params = { current: current.value, size }
    if (status.value) params.status = status.value
    const res = await getOutsourceBoardApi(params)
    rows.value = res.data?.records || []
    total.value = Number(res.data?.total || 0)
  } finally {
    loading.value = false
  }
}

function goInspect(row) {
  // 复用既有检验台（6.2.1 来料检验 board），带批号定位
  router.push({ path: '/m/6.2.1', query: { lotNo: row.lotNo } })
}

async function release(row) {
  try {
    await ElMessageBox.confirm(
      `确认放行外协检验批 ${row.lotNo}？放行不可撤回（C-4.12-06 前置校验将自动执行）。`,
      '合格放行', { type: 'warning' })
  } catch { return }
  await releaseLotApi(row.id)
  ElMessage.success('已放行')
  await load()
}

function goScar(row) {
  router.push({ path: '/m/6.8.1', query: { supplierName: row.supplierName } })
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.tip {
  color: #909399;
  font-size: 12px;
}
</style>
