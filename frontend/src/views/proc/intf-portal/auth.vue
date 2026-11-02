<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="认证鉴权（15.2）：令牌认证、HMAC 签名校验、SSO 单点登录（本系统作 IdP）"
      description="签名不匹配 401（C-5.5-05）、时间戳 ±5 分钟防重放（BR-5.5-04）。SSO 断言为限定 audience 的短令牌。" />

    <!-- 15.2.1 令牌认证 -->
    <el-table :data="rejections" border size="small">
      <el-table-column prop="callAt" label="时间" width="170" />
      <el-table-column prop="caller" label="调用方" width="140" />
      <el-table-column prop="errCode" label="错误码" width="130" />
      <el-table-column prop="apiPath" label="接口" min-width="200" show-overflow-tooltip />
    </el-table>
    <el-alert v-if="!rejections.length" type="info" :closable="false" style="margin-top:10px"
              title="近期无认证拒绝记录（令牌缺失/过期/无效的调用会被拒绝并记录在此）" />
  </div>
</template>

<script setup>
/**
 * 15.2.1 令牌认证（拆分自原认证鉴权操作台）：认证拒绝记录。
 * 共享状态见 useAuth.js。
 */
import { onMounted } from 'vue'
import { useAuth } from './useAuth'

const { rejections, loadRejections } = useAuth()

onMounted(loadRejections)
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
