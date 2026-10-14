<template>
  <el-dialog :model-value="modelValue" title="检验报告（只读）" width="860px" top="4vh"
             class="report-dialog" @update:model-value="$emit('update:modelValue', $event)"
             @open="load" :close-on-click-modal="false">
    <div v-loading="loading">
      <div v-if="lot" class="report" id="inspection-report">
        <!-- ① 报告头 -->
        <div class="rp-head">
          <div class="rp-title">检验报告</div>
          <div class="rp-meta">
            <span>报告编号：{{ lot.lotNo }}</span>
            <span>检验类型：{{ lot.lotType }}</span>
            <span>打印时间：{{ now }}</span>
          </div>
        </div>

        <!-- ② 批次信息 -->
        <h4 class="rp-sec">一、批次信息</h4>
        <table class="rp-table">
          <tr><th>物料编码</th><td>{{ lot.itemCode }}</td><th>物料名称</th><td>{{ lot.itemName }}</td></tr>
          <tr><th>供应商</th><td>{{ lot.supplierName || '—' }}</td><th>批次号</th><td>{{ lot.batchNo || '—' }}</td></tr>
          <tr><th>批量</th><td>{{ lot.lotQty }}</td><th>风险等级</th><td>{{ lot.riskGrade || '—' }}</td></tr>
        </table>

        <!-- ③ 标准与抽样 -->
        <h4 class="rp-sec">二、标准与抽样方案</h4>
        <table class="rp-table">
          <tr><th>检验标准</th><td>{{ lot.standardCode || '—' }} V{{ lot.standardVersion || '—' }}</td>
              <th>严格度</th><td>{{ lot.strictness || '—' }}</td></tr>
          <tr><th>抽样依据</th><td colspan="3">{{ lot.sampleBasis || '—' }}</td></tr>
          <tr><th>理论样本量</th><td>{{ lot.sampleQty }}</td>
              <th>Ac / Re</th><td>{{ lot.acValue ?? '—' }} / {{ lot.reValue ?? '—' }}</td></tr>
        </table>

        <!-- ④ 抽样执行记录 -->
        <h4 class="rp-sec">三、抽样执行记录</h4>
        <table v-if="samplingRecords.length" class="rp-table">
          <tr><th>#</th><th>方法</th><th>实际数量</th><th>触发原因</th><th>抽样人</th><th>抽样时间</th><th>备注</th></tr>
          <tr v-for="r in samplingRecords" :key="r.id">
            <td>{{ r.seq }}</td>
            <td>{{ methodName(r.sampleMethod) }}</td>
            <td>{{ r.actualSampleQty }}</td>
            <td>{{ reasonName(r.triggerReason) }}</td>
            <td>{{ r.samplerName || '—' }}</td>
            <td>{{ r.sampledTime || '—' }}</td>
            <td>{{ r.remark || '—' }}</td>
          </tr>
        </table>
        <div v-else class="rp-none">未记录抽样</div>

        <!-- ⑤ 检验项明细 -->
        <h4 class="rp-sec">四、检验项明细</h4>
        <table class="rp-table">
          <tr><th>#</th><th>检验项</th><th>CTQ</th><th>规格</th><th>实测值</th><th>判定</th><th>检验人</th></tr>
          <tr v-for="it in items" :key="it.id">
            <td>{{ it.sequenceNo }}</td>
            <td>{{ it.characteristicName }}</td>
            <td>{{ it.ctqFlag === '1' ? '★' : '' }}</td>
            <td>{{ specText(it) }}</td>
            <td>{{ it.measuredValue ?? (it.judge === 'PASS' ? '合格' : it.judge === 'FAIL' ? '不合格' : '—') }}</td>
            <td>{{ judgeName(it.judge) }}</td>
            <td>{{ it.inspectBy || '—' }}</td>
          </tr>
        </table>

        <!-- ⑥ 判定结论 -->
        <h4 class="rp-sec">五、判定结论</h4>
        <table class="rp-table">
          <tr><th>整批判定</th>
              <td><b :style="lot.result === 'PASS' ? 'color:#67c23a' : lot.result === 'FAIL' ? 'color:#f56c6c' : ''">
                {{ resultName(lot.result) }}</b></td>
              <th>复核</th><td>{{ lot.reviewBy ? `${lot.reviewBy} ${lot.reviewDate || ''}` : '—' }}</td></tr>
          <tr v-if="lot.remark"><th>复核意见/备注</th><td colspan="3">{{ lot.remark }}</td></tr>
        </table>

        <!-- ⑦ 放行信息 -->
        <h4 class="rp-sec">六、放行信息</h4>
        <table class="rp-table">
          <tr><th>放行人</th><td>{{ lot.releasedBy || '—' }}</td>
              <th>放行时间</th><td>{{ lot.releasedDate || '—' }}</td></tr>
          <tr><th>合格证号</th><td colspan="3">{{ certificate?.certNo || '—' }}</td></tr>
        </table>
      </div>
      <el-empty v-else-if="!loading" description="批次不存在" :image-size="80" />
    </div>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">关闭</el-button>
      <el-button type="primary" :disabled="!lot" @click="print">打印</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue'
import { getLotDetailApi, getCertificateApi } from '@/api/qms/inspection'

const props = defineProps({
  modelValue: Boolean,
  lotId: String
})
defineEmits(['update:modelValue'])

const loading = ref(false)
const lot = ref(null)
const items = ref([])
const samplingRecords = ref([])
const certificate = ref(null)
const now = new Date().toLocaleString()

async function load() {
  if (!props.lotId) return
  loading.value = true
  try {
    const res = await getLotDetailApi(props.lotId)
    lot.value = res.data?.lot || null
    items.value = res.data?.items || []
    samplingRecords.value = res.data?.samplingRecords || []
    try {
      const c = await getCertificateApi(props.lotId)
      certificate.value = c.data || null
    } catch (e) { certificate.value = null }
  } finally { loading.value = false }
}

function methodName(m) {
  return { RANDOM: '随机抽样', NINE_POINT: '九点取样', FULL: '逐件全检', OTHER: '其他' }[m] || m
}
function reasonName(r) {
  return { INITIAL: '首次', ESCALATE: '加大抽样', RESAMPLE: '补抽' }[r] || r
}
function judgeName(j) {
  return { PASS: '合格', FAIL: '不合格', PENDING: '待检' }[j] || '—'
}
function resultName(r) {
  return { PASS: '合格', FAIL: '不合格', PENDING: '待定' }[r] || '—'
}
function specText(it) {
  if (it.specType === 'NUMERIC') return `${it.lowerLimit ?? '-∞'} ~ ${it.upperLimit ?? '+∞'} ${it.unit || ''}`
  return it.specType === 'LOOK' ? '外观目视' : (it.methodName || '—')
}

function print() {
  window.print()
}
</script>

<style scoped>
.rp-head { text-align: center; margin-bottom: 8px; }
.rp-title { font-size: 20px; font-weight: bold; letter-spacing: 8px; }
.rp-meta { display: flex; justify-content: space-between; font-size: 12px; color: #606266; margin-top: 6px; }
.rp-sec { margin: 12px 0 6px; font-size: 13px; border-left: 3px solid #409eff; padding-left: 6px; }
.rp-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.rp-table th, .rp-table td { border: 1px solid #dcdfe6; padding: 4px 8px; text-align: left; }
.rp-table th { background: #f5f7fa; width: 90px; font-weight: normal; color: #606266; }
.rp-none { color: #909399; font-size: 12px; padding: 6px 0; }

@media print {
  body * { visibility: hidden; }
  #inspection-report, #inspection-report * { visibility: visible; }
  #inspection-report { position: absolute; left: 0; top: 0; width: 100%; }
  .rp-table tr { break-inside: avoid; }
}
</style>
