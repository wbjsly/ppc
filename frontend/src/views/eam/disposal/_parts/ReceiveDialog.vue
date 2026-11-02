<template>
  <el-dialog v-model="receiveVisible" title="收款登记（BR-35）" width="460px">
    <el-form label-width="90px">
      <el-form-item label="收款金额">
        <el-input-number v-model="receiveForm.amount" :min="0" :precision="2" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="receiveForm.note" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="receiveVisible = false">取消</el-button>
      <el-button type="success" @click="doReceive">登记收款</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/** 收款登记对话框（拆分自原资产处置操作台，资产出售与损益处理两页共用）。 */
import { useDisposal } from '../useDisposal'
const { receiveVisible, receiveForm, doReceive } = useDisposal()
</script>
