<template>
  <div class="page">
    <el-form :model="form" label-width="110px" style="max-width: 640px">
      <el-form-item label="变更标题" required>
        <el-input v-model="form.title" placeholder="如：升级订单导入接口至 v2.14.4" />
      </el-form-item>
      <el-form-item label="变更类型">
        <el-select v-model="form.changeType" style="width: 200px">
          <el-option label="普通变更" value="NORMAL" />
          <el-option label="紧急修复" value="EMERGENCY_FIX" />
          <el-option label="灰度发布" value="GRAY" />
        </el-select>
      </el-form-item>
      <el-form-item label="影响范围">
        <el-input v-model="form.affectScope" placeholder="如：销售订单导入接口" />
      </el-form-item>
      <el-form-item label="预计耗时(分)">
        <el-input-number v-model="form.estimateMin" :min="5" :max="720" />
      </el-form-item>
      <el-form-item label="回滚耗时(分)">
        <el-input-number v-model="form.rollbackMin" :min="0" :max="360" />
      </el-form-item>
      <el-form-item label="回滚脚本" required>
        <el-input v-model="plan.script" placeholder="回滚脚本/步骤（审批门禁 BR-5.8-01）" />
      </el-form-item>
      <el-form-item label="演练有效" required>
        <el-switch v-model="plan.drillValid" />
      </el-form-item>
      <el-form-item label="回滚耗时(预案)" required>
        <el-input-number v-model="plan.estimateMin" :min="1" :max="360" />
      </el-form-item>
      <el-button type="primary" @click="submit">提交申请</el-button>
      <span style="margin-left: 12px; color: #909399; font-size: 12px">
        结账保障期（1~5 日）非紧急变更将被阻断（BR-5.8-14）
      </span>
    </el-form>
  </div>
</template>

<script setup>
/**
 * 18.1.1 变更申请（拆分自原变更管理操作台）：变更单与回滚预案表单提交。
 * 提交后经 useChange.js 刷新共享变更单列表。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { submitChangeApi } from '@/api/ops/ops-sla'
import { useChange } from './useChange'

const { load } = useChange()

const form = ref({ title: '', changeType: 'NORMAL', affectScope: '', estimateMin: 60, rollbackMin: 15 })
const plan = ref({ script: '', drillValid: true, estimateMin: 10 })

async function submit() {
  const body = { ...form.value, rollbackPlanJson: JSON.stringify(plan.value) }
  await submitChangeApi(body)
  ElMessage.success('已提交')
  load()
}
</script>
