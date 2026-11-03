<template>
  <div class="intf-page">
    <PageInfo title="认证鉴权（15.2）：令牌认证、HMAC 签名校验、SSO 单点登录（本系统作 IdP）" description="签名不匹配 401（C-5.5-05）、时间戳 ±5 分钟防重放（BR-5.5-04）。SSO 断言为限定 audience 的短令牌。" style="margin-bottom:12px" />

    <!-- 15.2.2 签名校验（观测面 = 拒绝分析 + 错误类型筛选） -->
    <el-alert type="warning" :closable="false" style="margin-bottom:10px"
              title="HMAC 签名与防重放观测"
              description="签名校验在网关侧执行：签名不匹配 401（C-5.5-05）、时间戳超出 ±5 分钟拒绝（BR-5.5-04）。本页按错误类型筛选拒绝记录定位问题调用方。" />
    <el-form inline size="small">
      <el-form-item label="错误类型">
        <el-select v-model="signFilter" clearable style="width: 220px" placeholder="全部">
          <el-option label="签名不匹配" value="SIGN_MISMATCH" />
          <el-option label="时间戳过期（防重放）" value="TIMESTAMP_SKEW" />
          <el-option label="令牌无效" value="INVALID_TOKEN" />
        </el-select>
      </el-form-item>
    </el-form>
    <el-table :data="signRows" border size="small">
      <el-table-column prop="callAt" label="时间" width="170" />
      <el-table-column prop="caller" label="调用方" width="140" />
      <el-table-column prop="errCode" label="错误码" width="150" />
      <el-table-column prop="apiPath" label="接口" min-width="200" show-overflow-tooltip />
    </el-table>
    <el-alert v-if="!signRows.length" type="success" :closable="false" style="margin-top:10px"
              title="无签名校验失败记录" />
  </div>
</template>

<script setup>
/**
 * 15.2.2 签名校验（拆分自原认证鉴权操作台）：按错误类型筛选拒绝记录。
 * 共享状态见 useAuth.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, computed, onMounted } from 'vue'
import { useAuth } from './useAuth'

const { rejections, loadRejections } = useAuth()

const signFilter = ref('')

/** 签名校验观测：按错误类型筛选（签名/时间戳/令牌） */
const signRows = computed(() => {
  if (!signFilter.value) return rejections.value
  const kw = { SIGN_MISMATCH: 'SIGN', TIMESTAMP_SKEW: 'TIMESTAMP', INVALID_TOKEN: 'TOKEN' }[signFilter.value]
  return rejections.value.filter(r => String(r.errCode || '').toUpperCase().includes(kw))
})

onMounted(loadRejections)
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
