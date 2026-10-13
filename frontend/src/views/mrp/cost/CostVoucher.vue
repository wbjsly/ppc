<template>
  <div class="cost-voucher">
    <el-card shadow="never">
      <div class="head">
        <span class="title">5.11.4 完工凭证</span>
        <div class="filters">
          <el-select v-model="status" placeholder="状态" clearable style="width: 130px">
            <el-option label="已过账" value="POSTED" />
            <el-option label="待过账" value="DRAFT" />
          </el-select>
          <el-date-picker v-model="period" type="month" placeholder="会计期间"
            value-format="YYYYMM" style="width: 130px" />
          <el-button type="primary" @click="load">查询</el-button>
          <el-button type="warning" :disabled="!canWrite" :loading="transferring"
            @click="doTransfer">月度差异转销</el-button>
        </div>
      </div>

      <el-table :data="rows" border stripe v-loading="loading" @row-click="openDetail">
        <el-table-column prop="voucherNo" label="凭证号" width="150" />
        <el-table-column prop="voucherDate" label="凭证日期" width="110" />
        <el-table-column prop="period" label="会计期间" width="90" />
        <el-table-column prop="sourceDocNo" label="工单号" width="140" />
        <el-table-column prop="summary" label="摘要" min-width="200" />
        <el-table-column label="借方合计" width="120" align="right">
          <template #default="{ row }">{{ fmt(row.totalDr) }}</template>
        </el-table-column>
        <el-table-column label="贷方合计" width="120" align="right">
          <template #default="{ row }">{{ fmt(row.totalCr) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'POSTED' ? 'success' : 'warning'" size="small">
              {{ row.status === 'POSTED' ? '已过账' : '待过账' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click.stop="openDetail(row)">明细</el-button>
            <el-button v-if="row.status === 'DRAFT'" size="small" type="primary"
              :disabled="!canWrite" @click.stop="doPost(row)">过账</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-alert v-if="drafts.length" type="warning" :closable="false" style="margin-top: 12px"
        :title="`存在 ${drafts.length} 张待过账凭证（会计期间已关 → 下期过账）`" />
      <div v-if="!rows.length && !loading" class="empty">暂无完工凭证——工单完工归集后自动生成</div>
    </el-card>

    <el-drawer v-model="drawer" title="凭证明细" size="55%">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="凭证号">{{ detail.v?.voucherNo || detail.voucherNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.v?.status || detail.status }}</el-descriptions-item>
          <el-descriptions-item label="会计期间">{{ detail.v?.period || detail.period }}</el-descriptions-item>
          <el-descriptions-item label="工单">{{ detail.v?.sourceDocNo || detail.sourceDocNo }}</el-descriptions-item>
          <el-descriptions-item label="借方合计">{{ fmt(detail.v?.totalDr || detail.totalDr) }}</el-descriptions-item>
          <el-descriptions-item label="贷方合计">{{ fmt(detail.v?.totalCr || detail.totalCr) }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="detailLines" border stripe size="small" style="margin-top: 12px">
          <el-table-column prop="lineNo" label="行号" width="60" />
          <el-table-column prop="accountCode" label="科目" width="90" />
          <el-table-column prop="accountName" label="科目名称" min-width="150" />
          <el-table-column label="方向" width="60" align="center">
            <template #default="{ row }">
              <el-tag :type="row.direction === 'DR' ? 'primary' : 'warning'" size="small">
                {{ row.direction === 'DR' ? '借' : '贷' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="金额" width="120" align="right">
            <template #default="{ row }">{{ fmt(row.amount) }}</template>
          </el-table-column>
          <el-table-column prop="summary" label="行摘要" min-width="160" />
        </el-table>
        <div class="tip">结构：借 1405 产成品 = 标准成本 × 合格产出；贷 500101/2/3 生产成本三明细 = 实际归集；
          差额行入 500191 生产成本差异补平。已过账凭证仅可红字冲销（BR-4.6-09）。</div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  listVouchersApi, voucherDraftsApi, voucherDetailApi, postVoucherApi, varianceTransferApi
} from '@/api/mrp/cost'

const userStore = useUserStore()
const rows = ref([])
const drafts = ref([])
const loading = ref(false)
const transferring = ref(false)
const status = ref('')
const period = ref('')
const drawer = ref(false)
const detail = ref(null)

const roles = computed(() => userStore.userInfo?.roles || [])
const canWrite = computed(() =>
  roles.value.includes('ROLE_COST_ACCOUNTANT') || roles.value.includes('ROLE_ADMIN'))

const detailLines = computed(() => {
  const d = detail.value
  if (!d) return []
  return d.lines || d.v?.lines || []
})

function fmt(v) {
  if (v === null || v === undefined) return '—'
  return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

async function load() {
  loading.value = true
  try {
    const params = {}
    if (status.value) params.status = status.value
    if (period.value) params.period = period.value
    rows.value = (await listVouchersApi(params)).data || []
    drafts.value = (await voucherDraftsApi()).data || []
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  detail.value = (await voucherDetailApi(row.id)).data
  drawer.value = true
}

async function doPost(row) {
  try {
    await postVoucherApi(row.id)
    ElMessage.success('已过账')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '过账失败')
  }
}

async function doTransfer() {
  const p = period.value || new Date().toISOString().slice(0, 7).replace('-', '')
  try {
    await ElMessageBox.confirm(
      `将对 ${p} 期完工凭证的生产成本差异净额执行月度转销（转主营业务成本），同期不可重复。继续？`,
      '月度差异转销', { type: 'warning' })
  } catch { return }
  transferring.value = true
  try {
    const res = await varianceTransferApi(p)
    if (res.data?.transferred) {
      ElMessage.success(`转销完成：${res.data.voucherNo}`)
    } else {
      ElMessage.info('该期无差异净额，未生成转销凭证')
    }
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '转销失败')
  } finally {
    transferring.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-size: 16px; font-weight: 600; }
.filters { display: flex; gap: 8px; }
.empty { text-align: center; color: #909399; padding: 32px 0; }
.tip { margin-top: 12px; color: #909399; font-size: 12px; line-height: 1.6; }
:deep(.el-table__row) { cursor: pointer; }
</style>
