<template>
  <div class="pr-page">
    <!-- ================= 16.3.2 职责预检 ================= -->
    <el-row :gutter="16">
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>预检沙盘（实时返回超基线 / 互斥 / 现有权限）</template>
          <el-form label-width="90px">
            <el-form-item label="被检用户">
              <el-select v-model="pcUserId" filterable clearable placeholder="默认当前用户"
                style="width: 100%">
                <el-option v-for="u in users" :key="u.id"
                  :label="u.username + (u.nickName ? '（' + u.nickName + '）' : '')"
                  :value="u.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="目标角色">
              <el-select v-model="pcRoles" multiple filterable placeholder="选择角色"
                style="width: 100%">
                <el-option v-for="r in allRoles" :key="r.roleCode"
                  :label="r.roleCode + '（' + r.roleName + '）'" :value="r.roleCode" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :disabled="!pcRoles.length" @click="runPrecheck">开始预检</el-button>
            </el-form-item>
          </el-form>
          <template v-if="pcResult">
            <el-alert v-if="!pcResult.conflicts.length && !pcResult.beyondBaseline.length"
              type="success" :closable="false" show-icon title="预检通过：无超基线、无互斥冲突" />
            <template v-else>
              <el-alert v-if="pcResult.beyondBaseline.length" type="warning" :closable="false"
                show-icon :title="'超基线角色：' + pcResult.beyondBaseline.join('、')" />
              <el-table v-if="pcResult.conflicts.length" :data="pcResult.conflicts" size="small"
                style="margin-top: 8px">
                <el-table-column prop="roleA" label="互斥角色 A" />
                <el-table-column prop="roleB" label="互斥角色 B" />
                <el-table-column prop="remark" label="说明" />
              </el-table>
            </template>
            <div class="muted" style="margin-top: 8px">
              现有权限：{{ (pcResult.existing || []).join('、') || '无' }}
            </div>
            <div class="muted">岗位基线：{{ (pcResult.baseline || []).join('、') || '无基线' }}</div>
          </template>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>
            <span>互斥规则矩阵</span>
            <el-button style="float: right" type="primary" size="small"
              @click="ruleDlg = true">新增规则</el-button>
          </template>
          <el-table :data="rules" v-loading="ruleLoading" border size="small">
            <el-table-column prop="roleA" label="角色 A" />
            <el-table-column prop="roleB" label="角色 B" />
            <el-table-column prop="remark" label="说明" min-width="180" />
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button link type="danger" @click="removeRule(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 互斥规则新增 -->
    <el-dialog v-model="ruleDlg" title="新增互斥规则" width="460px">
      <el-form label-width="80px">
        <el-form-item label="角色 A">
          <el-select v-model="ruleForm.roleA" filterable style="width: 100%">
            <el-option v-for="r in allRoles" :key="r.roleCode" :label="r.roleCode" :value="r.roleCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色 B">
          <el-select v-model="ruleForm.roleB" filterable style="width: 100%">
            <el-option v-for="r in allRoles" :key="r.roleCode" :label="r.roleCode" :value="r.roleCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="ruleForm.remark" placeholder="互斥业务含义" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDlg = false">取消</el-button>
        <el-button type="primary" @click="submitRule">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 16.3.2 职责预检（拆分自原权限申请操作台）：预检沙盘 + 互斥规则矩阵维护。
 * 公共数据（用户/角色下拉）见 usePermissionRequest.js。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/permission-request'
import { usePermissionRequest } from './usePermissionRequest'

const { loadCommon, users, allRoles } = usePermissionRequest()

const pcUserId = ref('')
const pcRoles = ref([])
const pcResult = ref(null)
const rules = ref([])
const ruleLoading = ref(false)
const ruleDlg = ref(false)
const ruleForm = ref({ roleA: '', roleB: '', remark: '' })

async function runPrecheck() {
  const { data } = await api.precheck({
    targetUserId: pcUserId.value || undefined,
    roleCodes: pcRoles.value
  })
  pcResult.value = data
}

async function loadRules() {
  ruleLoading.value = true
  try {
    const { data } = await api.sodRuleList()
    rules.value = data || []
  } finally {
    ruleLoading.value = false
  }
}
async function submitRule() {
  if (!ruleForm.value.roleA || !ruleForm.value.roleB) {
    ElMessage.error('互斥角色对必填')
    return
  }
  await api.addSodRule(ruleForm.value)
  ElMessage.success('规则已保存')
  ruleDlg.value = false
  ruleForm.value = { roleA: '', roleB: '', remark: '' }
  loadRules()
}
async function removeRule(row) {
  await ElMessageBox.confirm('删除该互斥规则？后续申请将不再命中', '确认', { type: 'warning' })
  await api.deleteSodRule(row.id)
  ElMessage.success('已删除')
  loadRules()
}

onMounted(async () => {
  await loadCommon()
  loadRules()
})
</script>

<style scoped>
.pr-page { padding: 4px; }
.muted { color: #909399; font-size: 12px; }
</style>
