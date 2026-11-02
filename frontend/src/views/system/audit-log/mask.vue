<template>
  <div class="app-container">
    <!-- ==================== 16.7.3 脱敏策略 ==================== -->
    <el-button size="small" type="primary" @click="openRule()">新建规则</el-button>
    <el-table :data="rules" size="small" border style="margin-top:8px">
      <el-table-column prop="code" label="编码" width="130" />
      <el-table-column prop="name" label="名称" width="120" />
      <el-table-column prop="pattern" label="正则" show-overflow-tooltip />
      <el-table-column prop="maskTemplate" label="掩码模板" width="140" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="mini" :type="row.status === '1' ? 'success' : 'info'">
            {{ row.status === '1' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" show-overflow-tooltip />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button size="mini" @click="openRule(row)">编辑</el-button>
          <el-button size="mini" type="danger" @click="doDeleteRule(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog :title="ruleForm.id ? '编辑规则' : '新建规则'" v-model="ruleDialog" width="560px">
      <el-form :model="ruleForm" label-width="90px" size="small">
        <el-form-item label="编码" required>
          <el-input v-model="ruleForm.code" placeholder="唯一编码，如 ID_CARD" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="ruleForm.name" />
        </el-form-item>
        <el-form-item label="正则" required>
          <el-input v-model="ruleForm.pattern" placeholder="Java 正则，如 (1[3-9]\d)\d{4}(\d{4})" />
        </el-form-item>
        <el-form-item label="掩码模板" required>
          <el-input v-model="ruleForm.maskTemplate" placeholder="支持 $1/$2 捕获组引用，如 $1****$2" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="ruleForm.status" active-value="1" inactive-value="0" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="ruleForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialog = false">取消</el-button>
        <el-button type="primary" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 16.7.3 脱敏策略（拆分自原审计日志操作台）：脱敏规则台账 / 新建·编辑·删除。
 * 本页逻辑为单页独有，无跨页共享状态。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listMaskRules, saveMaskRule, deleteMaskRule } from '@/api/system/audit-log'

const rules = ref([])
const ruleDialog = ref(false)
const ruleForm = reactive({ id: '', code: '', name: '', pattern: '', maskTemplate: '', status: '1', remark: '' })

const loadRules = async () => {
  const { data } = await listMaskRules()
  rules.value = data || []
}
const openRule = (row) => {
  Object.assign(ruleForm, row
    ? { id: row.id, code: row.code, name: row.name, pattern: row.pattern, maskTemplate: row.maskTemplate, status: row.status, remark: row.remark }
    : { id: '', code: '', name: '', pattern: '', maskTemplate: '', status: '1', remark: '' })
  ruleDialog.value = true
}
const saveRule = async () => {
  await saveMaskRule({ ...ruleForm })
  ElMessage.success('规则已保存（即刻生效）')
  ruleDialog.value = false
  loadRules()
}
const doDeleteRule = async (row) => {
  await ElMessageBox.confirm(`删除规则「${row.name}」？删除后该模式不再掩码。`, '确认删除', { type: 'warning' })
  await deleteMaskRule(row.id)
  ElMessage.success('已删除')
  loadRules()
}

onMounted(loadRules)
</script>
