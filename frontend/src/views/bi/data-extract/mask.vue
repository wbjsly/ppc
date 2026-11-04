<template>
  <div>

    <!-- ==================== 14.2.2 脱敏规则 ==================== -->
    <div class="toolbar">
      <el-select v-model="maskTypeFilter" placeholder="规则类型" size="small" clearable
                 style="width: 170px" @change="loadMaskRules">
        <el-option label="列掩码 COL_MASK" value="COL_MASK" />
        <el-option label="敏感登记 COL_SENSITIVE" value="COL_SENSITIVE" />
      </el-select>
      <el-button size="small" type="success" @click="openMaskEdit(null)">新建规则</el-button>
      <PageInfo class="page-info--inline" title="类型化掩码（PHONE 前3后4 / ID_CARD 前3后4 / SALARY *** / BANK 尾4 / GENERIC 首尾）；变更须数据治理专员审批后生效（仅 ENABLED 参与查询层判定）" />
    </div>
    <el-table :data="maskRules" border stripe size="small" max-height="380">
      <el-table-column prop="targetKey" label="目标字段" width="160" />
      <el-table-column label="类型" width="130" align="center">
        <template #default="{ row }">
          {{ row.ruleType === 'COL_MASK' ? '列掩码' : '敏感登记' }}
        </template>
      </el-table-column>
      <el-table-column label="样式" width="110" align="center">
        <template #default="{ row }">
          <el-tag size="small">{{ row.maskStyle }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="roleCode" label="适用角色" width="150">
        <template #default="{ row }">{{ row.roleCode || '全体' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="maskTag(row.status)" size="small">{{ maskStatus(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="updateBy" label="更新人" width="110" />
      <el-table-column label="操作" width="220" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openMaskEdit(row)">编辑</el-button>
          <el-button v-if="row.status === 'DRAFT' || row.status === 'DISABLED'" size="small"
                    type="warning" link @click="onMaskSubmit(row)">提交审批</el-button>
          <el-button v-if="row.status === 'ENABLED'" size="small" type="warning" link
                    @click="onMaskDisable(row)">停用</el-button>
          <el-button size="small" type="primary" link @click="openMaskLogs(row)">历史</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="maskVisible" :title="maskId ? '编辑规则（仅草稿）' : '新建规则'"
               width="520px" :close-on-click-modal="false">
      <el-form label-width="100px" size="small">
        <el-form-item label="目标字段" required>
          <el-input v-model="maskForm.targetKey" :disabled="!!maskId"
                    placeholder="字段名或通配 *" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="maskForm.ruleType" :disabled="!!maskId" style="width: 200px">
            <el-option label="列掩码 COL_MASK" value="COL_MASK" />
            <el-option label="敏感登记 COL_SENSITIVE" value="COL_SENSITIVE" />
          </el-select>
        </el-form-item>
        <el-form-item label="掩码样式">
          <el-select v-model="maskForm.maskStyle" style="width: 200px">
            <el-option label="GENERIC 首尾保留" value="GENERIC" />
            <el-option label="PHONE 前3后4" value="PHONE" />
            <el-option label="ID_CARD 前3后4" value="ID_CARD" />
            <el-option label="SALARY 固定掩码" value="SALARY" />
            <el-option label="BANK 保留后4位" value="BANK" />
          </el-select>
          <div class="mask-preview">预览：{{ maskPreview }}</div>
        </el-form-item>
        <el-form-item label="适用角色">
          <el-input v-model="maskForm.roleCode" placeholder="空 = 全体用户" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="maskForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="maskVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="saveMask">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="logsVisible" title="变更历史（审计只增）" size="420px">
      <el-table :data="maskLogs" border stripe size="small">
        <el-table-column prop="action" label="动作" width="110" />
        <el-table-column prop="oldVal" label="旧值" width="120" show-overflow-tooltip />
        <el-table-column prop="newVal" label="新值" width="120" show-overflow-tooltip />
        <el-table-column prop="operator" label="操作人" width="100" />
        <el-table-column prop="opAt" label="时间" width="150" />
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * 14.2.2 脱敏规则（拆分自原数据抽取操作台）：规则 CRUD / 审批流转 / 变更历史。
 * 三页逻辑互不共享，全部留在页内（design D2：单 tab 独有逻辑不强抽）。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getMaskRulesApi, createMaskRuleApi, updateMaskRuleApi, disableMaskRuleApi,
  submitMaskRuleApi, getMaskRuleLogsApi
} from '@/api/bi/data-extract'

const maskRules = ref([])
const maskTypeFilter = ref('')
const maskVisible = ref(false)
const maskId = ref('')
const maskForm = ref({})
const logsVisible = ref(false)
const maskLogs = ref([])

const PREVIEWS = {
  GENERIC: 'a***g（首尾保留）',
  PHONE: '138****1234（前3后4）',
  ID_CARD: '110****1234（前3后4）',
  SALARY: '***（固定掩码）',
  BANK: '****6789（保留后4位）'
}
const maskPreview = computed(() => PREVIEWS[maskForm.value.maskStyle] || '')

async function loadMaskRules() {
  const res = await getMaskRulesApi(maskTypeFilter.value || undefined)
  maskRules.value = res.data || []
}
function openMaskEdit(row) {
  maskId.value = row ? row.id : ''
  maskForm.value = row
    ? { targetKey: row.targetKey, ruleType: row.ruleType, maskStyle: row.maskStyle,
        roleCode: row.roleCode || '', remark: row.remark || '' }
    : { targetKey: '', ruleType: 'COL_MASK', maskStyle: 'GENERIC', roleCode: '', remark: '' }
  maskVisible.value = true
}
async function saveMask() {
  const payload = { ...maskForm.value }
  if (maskId.value) {
    await updateMaskRuleApi(maskId.value, { maskStyle: payload.maskStyle,
      roleCode: payload.roleCode, targetKey: payload.targetKey, remark: payload.remark })
  } else {
    await createMaskRuleApi(payload)
  }
  ElMessage.success('已保存（草稿态，提交审批后生效）')
  maskVisible.value = false
  loadMaskRules()
}
async function onMaskSubmit(row) {
  await ElMessageBox.confirm(`提交「${row.targetKey}（${row.maskStyle}）」至数据治理专员审批？`,
    '提交审批', { type: 'info' })
  await submitMaskRuleApi(row.id)
  ElMessage.success('已提交，等待数据治理专员单签')
  loadMaskRules()
}
async function onMaskDisable(row) {
  await ElMessageBox.confirm(`停用「${row.targetKey}」？停用后查询层立即不再掩码该字段。`,
    '停用', { type: 'warning' })
  await disableMaskRuleApi(row.id)
  ElMessage.success('已停用')
  loadMaskRules()
}
async function openMaskLogs(row) {
  const res = await getMaskRuleLogsApi(row.id)
  maskLogs.value = res.data || []
  logsVisible.value = true
}
function maskStatus(s) {
  return { DRAFT: '草稿', PENDING_APPROVAL: '审批中', ENABLED: '已生效', DISABLED: '已停用' }[s] || s
}
function maskTag(s) {
  return { DRAFT: 'info', PENDING_APPROVAL: 'warning', ENABLED: 'success', DISABLED: 'info' }[s] || 'info'
}

onMounted(() => {
  loadMaskRules()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap; }
.mask-preview { color: #909399; font-size: 12px; margin-left: 8px; }
</style>
