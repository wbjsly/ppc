<template>
  <el-drawer v-model="visible" :title="`收货单：${detail.gr?.grNo || ''}`" size="70%">
    <template v-if="detail.gr">
      <el-descriptions :column="3" border size="small" style="margin-bottom: 12px;">
        <el-descriptions-item label="来源">{{ detail.gr.sourceType }}</el-descriptions-item>
        <el-descriptions-item label="PO">{{ detail.gr.poNo || '—' }}</el-descriptions-item>
        <el-descriptions-item label="供应商">{{ detail.gr.supplierName }}</el-descriptions-item>
        <el-descriptions-item label="到货日期">{{ detail.gr.arrivalDate }}</el-descriptions-item>
        <el-descriptions-item label="送货单/批次">{{ detail.gr.deliveryNote || '—' }} / {{ detail.gr.batchNo || '—' }}</el-descriptions-item>
        <el-descriptions-item label="运输/外包装">{{ detail.gr.transportInfo || '—' }} / {{ detail.gr.packageCondition || '—' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusName(detail.gr.status) }}</el-descriptions-item>
        <el-descriptions-item label="入库凭证">{{ detail.gr.postingDocNo || '—' }}</el-descriptions-item>
        <el-descriptions-item label="过账时间">{{ detail.gr.postingDate || '—' }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="detail.lines || []" size="small" border>
        <el-table-column prop="lineNo" label="#" width="50" align="center" />
        <el-table-column prop="itemCode" label="物料" width="120" />
        <el-table-column prop="itemName" label="名称" min-width="120" />
        <el-table-column prop="orderedQty" label="下单量" width="85" align="right" />
        <el-table-column prop="openQty" label="未清量" width="85" align="right" />
        <el-table-column prop="receivedQty" label="实收" width="85" align="right" />
        <el-table-column label="容差判定" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{ OK_OVER: 'success', OK_SHORT: 'success', OVER: 'danger', SHORT: 'warning', FREE: 'info' }[row.toleranceResult]">
              {{ tolName(row.toleranceResult) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="withinToleranceQty" label="核销量" width="85" align="right" />
        <el-table-column prop="frozenQty" label="冻结量" width="85" align="right" />
        <el-table-column label="行状态" width="90" align="center">
          <template #default="{ row }">{{ { PENDING: '待过账', POSTED: '已过账', REJECTED: '已拒收' }[row.status] }}</template>
        </el-table-column>
      </el-table>
      <template v-if="(detail.differences || []).length">
        <el-divider content-position="left">关联差异单</el-divider>
        <el-table :data="detail.differences" size="small" border>
          <el-table-column prop="diffNo" label="单号" width="150" />
          <el-table-column prop="diffType" label="类型" width="70" />
          <el-table-column prop="diffQty" label="差异量" width="90" align="right" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">{{ diffName(row.status) }}</template>
          </el-table-column>
          <el-table-column prop="disposeNote" label="处置" min-width="130" />
        </el-table>
      </template>
    </template>
  </el-drawer>
</template>

<script setup>
/**
 * 收货单详情抽屉（拆分自原收货管理操作台）：到货登记与入库过账两页共用。
 * 父组件通过 ref 调用 open(id) 打开。
 */
import { ref } from 'vue'
import { getGrDetailApi } from '@/api/proc/goods-receipt'
import { statusName, tolName, diffName } from '../gr-helpers'

const visible = ref(false)
const detail = ref({ gr: null, lines: [], differences: [] })

async function open(id) {
  const { data } = await getGrDetailApi(id)
  detail.value = data
  visible.value = true
}

defineExpose({ open })
</script>
