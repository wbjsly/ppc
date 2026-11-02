<template>
  <!-- 签审弹窗（BR-4.11-45：密码二次确认 + 终端注册态），四页共用 -->
  <el-dialog v-model="signDlg" title="移动签审（双因子）" width="400px">
    <el-form label-width="80px" size="small">
      <el-form-item label="审批意见">
        <el-input v-model="signOpinion" placeholder="意见（通过/驳回说明）" />
      </el-form-item>
      <el-form-item label="登录密码">
        <el-input v-model="signPwd" type="password" show-password placeholder="二次输入登录密码" />
      </el-form-item>
      <el-form-item label="终端码">
        <el-input v-model="signDevice" placeholder="已注册设备编码" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="signDlg = false">取消</el-button>
      <el-button type="primary" :loading="signing" @click="submitSign">签审提交</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 13.7 移动签审双因子弹窗（拆分自原移动审批操作台，四页跨页共用对话框）。
 * 弹窗态与提交逻辑见 useApproval.js。
 */
import { useApproval } from '../useApproval'

const { signDlg, signing, signPwd, signDevice, signOpinion, submitSign } = useApproval()
</script>
