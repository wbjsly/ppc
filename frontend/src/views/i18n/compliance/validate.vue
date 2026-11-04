<template>
  <div class="i18n-page">
    <PageInfo title="本地合规：字段扩展、格式校验、字段加载" description="引擎+复用既有列（决策5）：包定义/正则规则集中配置禁硬编码（BR-5.7-13）；字段挂 TAXPAYER_NO 等既有列；注册地→包映射。" style="margin-bottom:12px" />

    <!-- 17.5.2 格式校验 -->
    <el-form inline size="small">
      <el-form-item label="注册地">
        <el-select v-model="vv.regPlace" style="width:120px">
          <el-option label="JP 日本" value="JP" /><el-option label="CN 中国" value="CN" />
          <el-option label="EU 欧盟" value="EU" />
        </el-select>
      </el-form-item>
      <el-form-item label="登录番号/税号">
        <el-input v-model="vv.invoiceRegNo" placeholder="T1234567890123" style="width:220px" />
      </el-form-item>
      <el-button type="primary" size="small" @click="doValidate">校验（C-5.7-09）</el-button>
    </el-form>
    <el-alert v-if="vResult" :type="vResult.pass ? 'success' : 'error'" :closable="false"
      :title="vResult.pass
        ? `校验通过（包字段 ${vResult.packCount} 个）`
        : `阻断：${(vResult.errors || []).map(e => e.hint).join('；')}`" />
    <el-alert type="info" :closable="false" style="margin-top:10px"
      title="S-5.7-06：日本登录番号格式 T+13 位数字（T\\d{13}）；校验失败 L1 阻断保存与过账，逐字段提示（C-5.7-09）" />
  </div>
</template>

<script setup>
/**
 * 17.5.2 格式校验（拆分自原本地合规页）：登录番号/税号格式校验展示。
 * 与字段扩展、字段加载页无共享状态（design D2：单页逻辑留页内）。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { validateComplianceApi } from '@/api/i18n/i18n'

const vv = ref({ regPlace: 'JP', invoiceRegNo: '' })
const vResult = ref(null)

async function doValidate() {
  try {
    const r = await validateComplianceApi(vv.value.regPlace, { invoiceRegNo: vv.value.invoiceRegNo })
    vResult.value = r.data
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '校验失败')
  }
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
</style>
