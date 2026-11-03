<template>
  <div class="i18n-page">
    <PageInfo title="币种换算（17.3）：汇率换算、取整规则" description="复用汇率主数据（与 4.6 财务共用，erp_mdm_exchange_rate）；汇率缺失校验展示（C-5.7-03，不改过账链）。" style="margin-bottom:12px" />

    <!-- 17.3.1 汇率换算试算台 -->
    <el-form inline size="small">
      <el-form-item label="交易币种">
        <el-select v-model="trial.from" style="width:110px">
          <el-option v-for="c in ['JPY','USD','EUR','HKD']" :key="c" :label="c" :value="c" />
        </el-select>
      </el-form-item>
      <el-form-item label="记账币种">
        <el-select v-model="trial.to" style="width:110px">
          <el-option label="CNY" value="CNY" /><el-option label="USD" value="USD" />
        </el-select>
      </el-form-item>
      <el-form-item label="业务日期">
        <el-date-picker v-model="trial.date" type="date" value-format="YYYY-MM-DD" style="width:140px" />
      </el-form-item>
      <el-form-item label="金额">
        <el-input-number v-model="trial.amount" :min="0" :precision="2" style="width:160px" />
      </el-form-item>
      <el-button type="primary" size="small" @click="runTrial">试算</el-button>
    </el-form>

    <!-- 校验展示区（BR-5.7-08 / C-5.7-03） -->
    <el-alert v-if="rateMissing" type="error" :closable="false" style="margin-bottom:10px"
      title="汇率缺失：当日无有效汇率记录，不可折算（C-5.7-03）"
      description="校验展示区（不改过账链本体）；补录当日汇率后可重试。跳转：1.6 汇率维护" />
    <el-result v-if="trialResult" icon="success" :title="`折算结果 ¥${trialResult.result}`"
      :sub-rate="''">
      <template #sub-title>
        <div>
          <p>原值 {{ trialResult.raw }} × 汇率 {{ trialResult.rate }} = {{ trialResult.rawResult }}</p>
          <p>按 {{ trialResult.to }} 小数位 {{ trialResult.decimals }} 取整（ISO 4217 / BR-5.7-09）→
            <b>¥{{ trialResult.result }}</b>（取整差值 {{ trialResult.diff }}）</p>
        </div>
      </template>
    </el-result>

    <h4 style="margin-top:12px">期末重估参考（IAS 21，只读既有 FxReval 数据）</h4>
    <el-table :data="reval" border size="small" max-height="220">
      <el-table-column prop="period" label="期间" width="100" />
      <el-table-column prop="ccy" label="币种" width="80" />
      <el-table-column prop="rate" label="期末汇率" width="110" />
      <el-table-column prop="diff" label="重估差额" width="130" align="right" />
      <el-table-column prop="glEntry" label="汇兑损益凭证" min-width="160" show-overflow-tooltip />
    </el-table>
  </div>
</template>

<script setup>
/**
 * 17.3.1 汇率换算试算台（拆分自原币种换算页）：试算 / 汇率缺失校验展示 / 期末重估参考。
 * 与取整规则页无共享状态（design D2：单页逻辑留页内）。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, onMounted } from 'vue'
import request from '@/utils/request'

const trial = ref({ from: 'JPY', to: 'CNY', date: new Date().toISOString().slice(0, 10), amount: 1284650 })
const trialResult = ref(null)
const rateMissing = ref(false)
const reval = ref([])

onMounted(() => { loadReval() })

async function runTrial() {
  rateMissing.value = false
  trialResult.value = null
  try {
    const r = await request.get('/mdm/exchange-rates/trial', {
      params: { from: trial.value.from, to: trial.value.to,
        date: trial.value.date, amount: trial.value.amount }
    })
    const d = r.data || {}
    // 汇率缺失判定（C-5.7-03 展示区）
    if (d.rate == null || d.rate === 0) {
      rateMissing.value = true
      return
    }
    const decimals = trial.value.to === 'CNY' || trial.value.to === 'USD' ? 2 : 0
    const rawResult = trial.value.amount * d.rate
    const result = Number(rawResult.toFixed(decimals))
    trialResult.value = {
      rate: d.rate, raw: trial.value.amount, rawResult: rawResult.toFixed(4),
      result: result.toLocaleString('zh-CN', { minimumFractionDigits: decimals, maximumFractionDigits: decimals }),
      decimals, diff: (rawResult - result).toFixed(4), to: trial.value.to
    }
  } catch (e) {
    // 端点不可用时按缺失处理（展示区语义）
    rateMissing.value = true
  }
}

async function loadReval() {
  try {
    const r = await request.get('/fin/fx-reval/trials', { params: { size: 10 }, silent: true })
    reval.value = r.data?.records || r.data || []
  } catch { reval.value = [] }
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
</style>
