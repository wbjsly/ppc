<template>
  <div class="page">
    <div class="head">
      <span class="title">9.2.1 销项开票（销项台账税务视角）</span>
      <div>
        <el-input v-model="query.period" placeholder="YYYYMM" style="width: 120px; margin-right: 8px" size="small" />
        <el-button type="primary" size="small" @click="load">查询</el-button>
        <el-button size="small" @click="$router.push('/m/5.8.7')">开票申请链（既有）</el-button>
      </div>
    </div>

    <el-row :gutter="12" class="mb12">
      <el-col :span="8">
        <el-card shadow="never"><div class="stat">开票张数 <b>{{ data.count }}</b></div></el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never"><div class="stat">金额合计 <b>{{ data.totalAmount }}</b></div></el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never"><div class="stat">税额合计 <b>{{ data.totalTax }}</b></div></el-card>
      </el-col>
    </el-row>

    <el-table :data="data.rows" size="small" border>
      <el-table-column prop="invoiceNo" label="发票号" width="150" />
      <el-table-column prop="customerName" label="客户" min-width="140" show-overflow-tooltip />
      <el-table-column prop="soNo" label="SO" width="130" />
      <el-table-column prop="invoiceDate" label="开票日" width="110" />
      <el-table-column prop="amount" label="金额" width="110" />
      <el-table-column prop="taxAmount" label="税额" width="100" />
      <el-table-column prop="status" label="状态" width="90" />
      <el-table-column label="限额提示" width="180">
        <template #default="{ row }">
          <template v-if="limitHints[row.invoiceNo]">
            <el-tag type="warning" size="small">{{ limitHints[row.invoiceNo] }}</el-tag>
          </template>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="!data.rows.length" class="muted mt8">该期间无销项发票</div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { salesLedger, limitCheck } from '@/api/fin/invoice-management'

const query = ref({ period: '' })
const data = ref({ rows: [], count: 0, totalAmount: 0, totalTax: 0 })
const limitHints = ref({})

async function load() {
  try {
    const params = {}
    if (query.value.period) params.period = query.value.period
    const { data: d } = await salesLedger(params)
    data.value = d
    // 逐行限额提示（BR-4.14-15 L4）
    limitHints.value = {}
    for (const row of d.rows || []) {
      if (row.amount) {
        const { data: lc } = await limitCheck(row.amount)
        if (lc.overLimit) limitHints.value[row.invoiceNo] = lc.hint
      }
    }
  } catch (e) { /* 422 已提示 */ }
}

onMounted(() => { load() })
</script>

<style scoped>
.page { padding: 12px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-size: 16px; font-weight: 600; }
.mb12 { margin-bottom: 12px; }
.mt8 { margin-top: 8px; }
.stat { font-size: 13px; }
.muted { color: #909399; font-size: 12px; }
</style>
