<template>
  <el-dialog v-model="execVisible" title="执行确认" width="560px">
    <el-form label-width="130px">
      <el-form-item :label="execType === 'SCRAP' ? '报废证明编号' : '买卖合同编号'" required>
        <el-input v-model="execForm.proofNo" />
      </el-form-item>
      <el-form-item label="环保安全评估号">
        <el-input v-model="execForm.envAssessNo" placeholder="分类 ENV_REVIEW_FLAG=1 时必填（BR-36）" />
      </el-form-item>
      <el-form-item label="数据销毁证明号">
        <el-input v-model="execForm.dataDestroyNo" placeholder="分类 DATA_DESTROY_FLAG=1 时必填（BR-36）" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="execVisible = false">取消</el-button>
      <el-button type="warning" @click="doExec">确认执行（生成段2/3凭证+卡片已处置）</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/** 执行确认对话框（拆分自原资产处置操作台，报废申请与资产出售两页共用）。 */
import { useDisposal } from '../useDisposal'
const { execVisible, execType, execForm, doExec } = useDisposal()
</script>
