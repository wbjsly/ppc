<template>
  <div class="i18n-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="币种换算（17.3）：汇率换算、取整规则"
      description="复用汇率主数据（与 4.6 财务共用，erp_mdm_exchange_rate）；汇率缺失校验展示（C-5.7-03，不改过账链）。" />

    <!-- 17.3.2 取整规则 -->
    <h4>① 币种小数位（BR-5.7-09，ISO 4217）</h4>
    <el-table :data="ccyRules" border size="small" style="margin-bottom:14px">
      <el-table-column prop="ccy" label="币种" width="100" />
      <el-table-column prop="decimals" label="小数位" width="100" />
      <el-table-column prop="sample" label="取整示例（1284650.456）" min-width="200" />
      <el-table-column prop="rule" label="规则" min-width="240" />
    </el-table>

    <h4>② 多行单据计税口径（BR-5.7-10）</h4>
    <el-form inline size="small">
      <el-form-item label="TAX_ROUNDING_SCOPE">
        <el-select v-model="taxScope" style="width:200px">
          <el-option label="LINE 单行计税后汇总（默认）" value="LINE" />
          <el-option label="AGG 汇总后计税" value="AGG" />
        </el-select>
      </el-form-item>
      <el-button type="primary" size="small" @click="saveScope">固化口径</el-button>
    </el-form>
    <el-table :data="taxDemo" border size="small" style="margin-top:10px">
      <el-table-column prop="line" label="行" width="60" />
      <el-table-column prop="amount" label="原额（JPY）" width="130" align="right" />
      <el-table-column prop="lineTax" label="单行计税" width="130" align="right" />
      <el-table-column prop="aggTax" label="汇总计税（参考）" width="150" align="right" />
    </el-table>
    <el-alert type="info" :closable="false" style="margin-top:10px"
      title="S-5.7-04：3 行明细 LINE 口径合计 10,817 日元 vs AGG 口径 10,816（差 1 日元）；口径固化于参数保证同一单据重算一致" />
  </div>
</template>

<script setup>
/**
 * 17.3.2 取整规则（拆分自原币种换算页）：币种小数位 / 多行单据计税口径固化。
 * 与汇率换算页无共享状态（design D2：单页逻辑留页内）。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'

const taxScope = ref('LINE')

const ccyRules = [
  { ccy: 'JPY', decimals: 0, sample: '1,284,650', rule: 'BR-5.7-09：0 位小数四舍五入，保留取整差值' },
  { ccy: 'CNY', decimals: 2, sample: '1,284,650.46', rule: 'ISO 4217：2 位小数' },
  { ccy: 'USD', decimals: 2, sample: '1,284,650.46', rule: 'ISO 4217：2 位小数' },
  { ccy: 'EUR', decimals: 2, sample: '1,284,650.46', rule: 'ISO 4217：2 位小数' }
]
const taxDemo = [
  { line: 1, amount: '100,000', lineTax: '3,092', aggTax: '—' },
  { line: 2, amount: '50,000', lineTax: '1,545', aggTax: '—' },
  { line: 3, amount: '200,000', lineTax: '6,180', aggTax: '—' },
  { line: '合计', amount: '350,000', lineTax: '10,817', aggTax: '10,816' }
]

async function saveScope() {
  try {
    await request.post('/system/params', {
      paramKey: 'TAX_ROUNDING_SCOPE', paramValue: taxScope.value,
      valueType: 'STRING', paramGroup: 'I18N', remark: '多行计税口径'
    })
    ElMessage.success('计税口径已固化（BR-5.7-10）')
  } catch (e) {
    // 参数端点形态差异：走通用更新
    try {
      await request.put('/system/params', {
        paramKey: 'TAX_ROUNDING_SCOPE', paramValue: taxScope.value
      })
      ElMessage.success('计税口径已固化')
    } catch (e2) {
      ElMessage.error(e2?.response?.data?.message || '保存失败')
    }
  }
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
</style>
