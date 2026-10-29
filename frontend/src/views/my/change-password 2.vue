<template>
  <div class="pwd-page" style="max-width:480px;margin:40px auto;">
    <el-alert type="warning" :closable="false" style="margin-bottom:16px;"
      title="修改密码（首次登录/密码重置后强制）"
      description="新密码须 ≥8 位且含大小写字母、数字、特殊字符；修改成功前无法使用系统其他功能。" />
    <el-form :model="form" label-width="90px" @keyup.enter="submit">
      <el-form-item label="原密码" required>
        <el-input v-model="form.oldPwd" type="password" show-password />
      </el-form-item>
      <el-form-item label="新密码" required>
        <el-input v-model="form.newPwd" type="password" show-password
          placeholder="≥8 位，含大小写字母+数字+特殊字符" />
      </el-form-item>
      <el-form-item label="确认密码" required>
        <el-input v-model="form.confirmPwd" type="password" show-password />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="submitting" @click="submit">确认修改</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { useUserStore } from '@/store/user'

const router = useRouter()
const userStore = useUserStore()
const submitting = ref(false)
const form = reactive({ oldPwd: '', newPwd: '', confirmPwd: '' })

async function submit() {
  if (!form.oldPwd || !form.newPwd) { ElMessage.warning('请填写完整'); return }
  if (form.newPwd !== form.confirmPwd) { ElMessage.warning('两次输入的新密码不一致'); return }
  submitting.value = true
  try {
    await request({ url: '/api/auth/change-password', method: 'post', data: { ...form } })
    ElMessage.success('密码已修改，请使用新密码继续')
    // 刷新用户信息清除强制改密标记后回工作台
    await userStore.getUserInfo()
    router.push('/dashboard')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message)
  } finally {
    submitting.value = false
  }
}
</script>
