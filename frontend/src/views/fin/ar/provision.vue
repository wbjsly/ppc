<template>
  <div class="page ar-mgmt-page">
    <!-- ================= 8.2.3 坏账计提 ================= -->
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
  </div>
</template>

<script setup>
/**
 * 8.2.3 坏账计提（拆分自原应收管理多 tab 页，design D1）。
 * 本页独有逻辑，无跨页共享状态（D2：无共享未抽）。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  provisionTrial, provisionConfirm, provisionPage, provisionDetail
} from '@/api/fin/ar-aging'

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
  loadProvisions()
})
</script>
