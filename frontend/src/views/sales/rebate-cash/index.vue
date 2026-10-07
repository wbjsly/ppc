<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.9.3 返利兑现（执行方式与凭证）"
              description="审批通过的结算单支持两种执行方式：应付冲抵（FIFO 自动关联客户未清应收，余额不足阻断）与现金兑现登记；客户确认的兑现方式留痕；执行完成生成结算凭证（借 6601 销售费用 / 贷 1122 应收账款或 1002 银行存款）并更新客户应收余额（FR-4.3-8-5/6）。" />

    <el-row :gutter="12" class="mb12">
      <el-col :span="6" v-for="k in kpis" :key="k.label">
        <div class="kpi-box">
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value" :style="{ color: k.color || '#303133' }">{{ k.value }}</div>
        </div>
      </el-col>
    </el-row>

    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <b>待执行（已通过审批）</b>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>
      <el-empty v-if="!approvedRows.length" description="暂无待执行结算单" :image-size="70" />
      <el-table v-else :data="approvedRows" size="small" border>
        <el-table-column prop="settleNo" label="结算单号" width="170" />
        <el-table-column prop="customerName" label="客户" min-width="150" show-overflow-tooltip />
        <el-table-column prop="quarter" label="季度" width="90" />
        <el-table-column label="返利金额" width="130" align="right">
          <template #default="{ row }">¥{{ fmt(row.rebateAmt) }}</template>
        </el-table-column>
        <el-table-column label="超预算" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.overBudget === '1'" size="small" type="danger">是</el-tag>
            <el-tag v-else size="small" type="success">否</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openExec(row)">执行</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <b>已执行（冲抵明细 / 兑现登记 / 凭证）</b>
          <div>
            <el-select v-model="filterType" clearable placeholder="执行方式" size="small"
                       style="width:150px">
              <el-option label="应付冲抵 OFFSET" value="OFFSET" />
              <el-option label="现金兑现 CASH" value="CASH" />
            </el-select>
            <el-button size="small" style="margin-left:8px" @click="load">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table :data="executedRows" size="small" border @row-click="openDetail" highlight-current-row>
        <el-table-column prop="settleNo" label="结算单号" width="170" />
        <el-table-column prop="customerName" label="客户" min-width="140" show-overflow-tooltip />
        <el-table-column prop="quarter" label="季度" width="90" />
        <el-table-column label="返利金额" width="120" align="right">
          <template #default="{ row }">¥{{ fmt(row.rebateAmt) }}</template>
        </el-table-column>
        <el-table-column label="执行方式" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.execType === 'OFFSET' ? 'primary' : 'success'">
              {{ row.execType === 'OFFSET' ? '应付冲抵' : '现金兑现' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="confirmBy" label="客户确认" width="120" show-overflow-tooltip />
        <el-table-column label="结算凭证" width="160">
          <template #default="{ row }">
            <el-link type="primary" @click.stop="openDetail(row)">{{ row.voucherNo || '—' }}</el-link>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" @click.stop="openDetail(row)">明细</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 执行弹窗 -->
    <el-dialog v-model="execVisible" title="返利执行" width="560px">
      <el-descriptions v-if="execRow" :column="2" size="small" border class="mb12">
        <el-descriptions-item label="结算单">{{ execRow.settleNo }}</el-descriptions-item>
        <el-descriptions-item label="客户">{{ execRow.customerName }}</el-descriptions-item>
        <el-descriptions-item label="返利金额" :span="2">
          <b>¥{{ fmt(execRow.rebateAmt) }}</b>
        </el-descriptions-item>
      </el-descriptions>
      <el-form label-width="110px" size="small">
        <el-form-item label="执行方式" required>
          <el-radio-group v-model="execForm.execType">
            <el-radio value="OFFSET">应付冲抵（FIFO 冲抵未清应收，余额不足阻断）</el-radio>
            <el-radio value="CASH">现金兑现登记</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="执行日期">
          <el-date-picker v-model="execForm.execDate" type="date" value-format="YYYY-MM-DD"
                          style="width:100%" />
        </el-form-item>
        <el-form-item label="客户确认方式" required>
          <el-input v-model="execForm.confirmBy" placeholder="如 客户盖章回执 / 邮件确认（必填留痕）" />
        </el-form-item>
        <el-form-item label="确认说明">
          <el-input v-model="execForm.confirmNote" type="textarea" :rows="2" placeholder="可空" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="execVisible = false">取消</el-button>
        <el-button type="primary" :loading="executing" @click="doExec">执行并生成凭证</el-button>
      </template>
    </el-dialog>

    <!-- 明细抽屉 -->
    <el-drawer v-model="drawerVisible" size="640px"
               :title="detail.settlement ? `返利执行明细 ${detail.settlement.settleNo}` : '明细'">
      <template v-if="detail.settlement">
        <el-descriptions :column="2" size="small" border class="mb12">
          <el-descriptions-item label="客户">{{ detail.settlement.customerName }}</el-descriptions-item>
          <el-descriptions-item label="季度">{{ detail.settlement.quarter }}</el-descriptions-item>
          <el-descriptions-item label="执行方式">
            {{ detail.settlement.execType === 'OFFSET' ? '应付冲抵' : '现金兑现' }}
          </el-descriptions-item>
          <el-descriptions-item label="执行人">{{ detail.settlement.execBy }}</el-descriptions-item>
          <el-descriptions-item label="客户确认方式">{{ detail.settlement.confirmBy }}</el-descriptions-item>
          <el-descriptions-item label="确认说明">{{ detail.settlement.confirmNote || '—' }}</el-descriptions-item>
          <el-descriptions-item label="冲抵金额">¥{{ fmt(detail.settlement.offsetAmt) }}</el-descriptions-item>
          <el-descriptions-item label="现金金额">¥{{ fmt(detail.settlement.cashAmt) }}</el-descriptions-item>
        </el-descriptions>

        <template v-if="detail.voucher">
          <h4>结算凭证（借 6601 销售费用 / 贷 {{ detail.settlement.execType === 'OFFSET' ? '1122 应收账款' : '1002 银行存款' }}）</h4>
          <el-descriptions :column="2" size="small" border class="mb12">
            <el-descriptions-item label="凭证号">{{ detail.voucher.voucherNo || detail.settlement.voucherNo }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ detail.voucher.status }}</el-descriptions-item>
            <el-descriptions-item label="借方合计">¥{{ fmt(detail.voucher.totalDr) }}</el-descriptions-item>
            <el-descriptions-item label="贷方合计">¥{{ fmt(detail.voucher.totalCr) }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="detail.voucher.lines || detail.voucher.lineList || []" size="small"
                    border class="mb12">
            <el-table-column prop="accountCode" label="科目" width="90" />
            <el-table-column prop="accountName" label="科目名" min-width="130" />
            <el-table-column prop="direction" label="方向" width="70" align="center">
              <template #default="{ row }">{{ row.direction === 'DR' ? '借' : '贷' }}</template>
            </el-table-column>
            <el-table-column label="金额" align="right">
              <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
            </el-table-column>
          </el-table>
        </template>

        <template v-if="(detail.writeoffs || []).length">
          <h4>冲抵核销明细（FIFO）</h4>
          <el-table :data="detail.writeoffs" size="small" border>
            <el-table-column prop="woNo" label="核销单号" width="170" />
            <el-table-column prop="arNo" label="应收单号" min-width="150" />
            <el-table-column label="冲抵金额" width="120" align="right">
              <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
            </el-table-column>
            <el-table-column prop="payDate" label="冲抵日期" width="110" />
          </el-table>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getSettlementsApi, getSettlementDetailApi, executeSettlementApi } from '@/api/sd/rebate'

const rows = ref([])
const filterType = ref('')
const execVisible = ref(false)
const execRow = ref(null)
const executing = ref(false)
const drawerVisible = ref(false)
const detail = ref({})
const execForm = ref({
  execType: 'OFFSET',
  execDate: new Date().toISOString().slice(0, 10),
  confirmBy: '',
  confirmNote: ''
})

const approvedRows = computed(() => rows.value.filter(r => r.status === 'APPROVED'))
const executedRows = computed(() => rows.value.filter(r =>
  r.status === 'EXECUTED'
  && (!filterType.value || r.execType === filterType.value)))

const kpis = computed(() => {
  const approved = approvedRows.value.reduce((s, r) => s + Number(r.rebateAmt || 0), 0)
  const offset = executedRows.value.filter(r => r.execType === 'OFFSET')
    .reduce((s, r) => s + Number(r.rebateAmt || 0), 0)
  const cash = executedRows.value.filter(r => r.execType === 'CASH')
    .reduce((s, r) => s + Number(r.rebateAmt || 0), 0)
  return [
    { label: '待执行返利', value: `¥${fmt(approved)}`, color: '#409eff' },
    { label: '已冲抵应收', value: `¥${fmt(offset)}`, color: '#e6a23c' },
    { label: '已现金兑现', value: `¥${fmt(cash)}`, color: '#67c23a' },
    { label: '待执行单数', value: approvedRows.value.length }
  ]
})

function fmt(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

async function load() {
  const res = await getSettlementsApi({ current: 1, size: 200 })
  rows.value = res.data.records || res.data || []
}

function openExec(row) {
  execRow.value = row
  execForm.value = {
    execType: 'OFFSET',
    execDate: new Date().toISOString().slice(0, 10),
    confirmBy: '',
    confirmNote: ''
  }
  execVisible.value = true
}

async function doExec() {
  if (!execForm.value.confirmBy || execForm.value.confirmBy.trim().length < 1) {
    return ElMessage.warning('客户确认方式必填（FR-4.3-8-5 留痕）')
  }
  executing.value = true
  try {
    const res = await executeSettlementApi(execRow.value.id, execForm.value)
    ElMessage.success(`执行完成，结算凭证 ${res.data.voucherNo || ''}`
      + (res.data.writeoffCount ? `，冲抵 ${res.data.writeoffCount} 笔应收` : ''))
    execVisible.value = false
    load()
  } finally {
    executing.value = false
  }
}

async function openDetail(row) {
  const res = await getSettlementDetailApi(row.id)
  detail.value = res.data || {}
  drawerVisible.value = true
}

onMounted(load)
</script>

<style scoped>
.page-wrap { padding: 12px; }
.mb12 { margin-bottom: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.kpi-box { background: #f5f7fa; border-radius: 4px; padding: 10px 12px; }
.kpi-label { font-size: 12px; color: #909399; margin-bottom: 4px; }
.kpi-value { font-size: 18px; font-weight: 600; }
</style>
