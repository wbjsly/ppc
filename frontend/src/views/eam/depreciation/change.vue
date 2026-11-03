<template>
  <div class="eam-page">
    <PageInfo title="变动处理（7.2.2）：政策变更下期生效不追溯 / 减值次月重算（BR-4.13-14/15）" description="政策变更/减值均单签 ROLE_FIN_ASSET；冲回为整单全冲（红冲凭证+台账回滚+放行重提）。" style="margin-bottom:12px;" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="changeType" placeholder="类型" clearable style="width:150px;" @change="loadChanges">
          <el-option label="政策变更" value="POLICY" />
          <el-option label="减值" value="IMPAIRMENT" />
          <el-option label="冲回" value="REVERSE" />
        </el-select>
        <el-button type="primary" @click="loadChanges">查询</el-button>
        <span class="spacer" />
        <el-button v-if="canChange" type="success" @click="policyVisible = true">政策变更</el-button>
        <el-button v-if="canChange" type="warning" plain @click="impairVisible = true">减值</el-button>
      </div>

      <el-table :data="changeRows" size="small" border v-loading="loading">
        <el-table-column prop="changeNo" label="变动单号" width="170">
          <template #default="{ row }"><b>{{ row.changeNo }}</b></template>
        </el-table-column>
        <el-table-column label="类型" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{ POLICY: 'primary', IMPAIRMENT: 'warning', REVERSE: 'info' }[row.changeType]">
              {{ { POLICY: '政策变更', IMPAIRMENT: '减值', REVERSE: '冲回' }[row.changeType] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="cardNo" label="卡片号" width="150" />
        <el-table-column label="变更前 → 后" min-width="170">
          <template #default="{ row }">
            <template v-if="row.changeType === 'POLICY'">
              年限 {{ row.beforeLifeYears }}→{{ row.afterLifeYears }}；
              残值率 {{ pct(row.beforeResidualRate) }}→{{ pct(row.afterResidualRate) }}
            </template>
            <template v-else-if="row.changeType === 'IMPAIRMENT'">减值 ¥ {{ fmt(row.impairAmount) }}</template>
            <template v-else>整单冲回</template>
          </template>
        </el-table-column>
        <el-table-column prop="effectivePeriod" label="生效期间" width="90" align="center" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{ PENDING_APPROVE: 'warning', APPROVED: 'success', REJECTED: 'danger', DONE: 'info' }[row.status]">
              {{ { PENDING_APPROVE: '待审批', APPROVED: '已通过', REJECTED: '已驳回', DONE: '已生效' }[row.status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" align="center">
          <template #default="{ row }">
            <template v-if="canApprove && row.status === 'PENDING_APPROVE'">
              <el-button link type="success" size="small" @click="doApprove(row)">通过</el-button>
              <el-button link type="danger" size="small" @click="doReject(row)">驳回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 政策变更对话框 -->
    <el-dialog v-model="policyVisible" title="折旧政策变更（BR-4.13-14：下期生效不追溯）" width="480px">
      <el-form label-width="110px" size="small">
        <el-form-item label="卡片ID" required>
          <el-input v-model="policyForm.cardId" placeholder="资产卡片 ID" />
        </el-form-item>
        <el-form-item label="变更后年限">
          <el-input-number v-model="policyForm.afterLifeYears" :min="1" :max="60" />
        </el-form-item>
        <el-form-item label="变更后残值率">
          <el-input-number v-model="policyForm.afterResidualRate" :min="0" :max="1" :step="0.01" :precision="4" />
        </el-form-item>
        <el-form-item label="变更后方法">
          <el-select v-model="policyForm.afterMethod" style="width:100%;">
            <el-option label="直线法" value="STRAIGHT_LINE" />
            <el-option label="双倍余额递减" value="DOUBLE_DECLINING" />
            <el-option label="年数总和法" value="SYD" />
          </el-select>
        </el-form-item>
        <el-form-item label="生效期间">
          <el-input v-model="policyForm.effectivePeriod" placeholder="yyyyMM，默认系统下期" />
        </el-form-item>
        <el-form-item label="原因" required>
          <el-input v-model="policyForm.reason" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="policyVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doChangePolicy">提交（单签）</el-button>
      </template>
    </el-dialog>

    <!-- 减值对话框 -->
    <el-dialog v-model="impairVisible" title="资产减值（BR-4.13-15：次月按减值后 NBV 重算）" width="440px">
      <el-form label-width="90px" size="small">
        <el-form-item label="卡片ID" required>
          <el-input v-model="impairForm.cardId" placeholder="资产卡片 ID" />
        </el-form-item>
        <el-form-item label="减值金额" required>
          <el-input-number v-model="impairForm.amount" :min="0.01" :precision="2" :step="1000" style="width:100%;" />
        </el-form-item>
        <el-form-item label="原因" required>
          <el-input v-model="impairForm.reason" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="impairVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doImpair">提交（单签）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 7.2.2 变动处理（拆分自原折旧管理操作台）：政策变更 / 减值 / 变动单审批。
 * 共享角色与格式化见 useDepRoles.js、dep-helpers.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getChangePageApi, changePolicyApi, impairmentApi, approveChangeApi, rejectChangeApi } from '@/api/eam/depreciation'
import { fmt } from './dep-helpers'
import { useDepRoles } from './useDepRoles'

const { canChange, canApprove } = useDepRoles()

const loading = ref(false)
const saving = ref(false)

const changeType = ref('')
const changeRows = ref([])
const policyVisible = ref(false)
const impairVisible = ref(false)
const policyForm = reactive({ cardId: '', afterLifeYears: null, afterResidualRate: 0.05,
  afterMethod: 'STRAIGHT_LINE', reason: '', effectivePeriod: '' })
const impairForm = reactive({ cardId: '', amount: null, reason: '' })

function pct(v) { return `${(Number(v || 0) * 100).toFixed(2)}%` }

async function loadChanges() {
  loading.value = true
  try {
    const res = await getChangePageApi({ current: 1, size: 50, changeType: changeType.value })
    changeRows.value = (res.data && res.data.records) || []
  } finally { loading.value = false }
}

async function doChangePolicy() {
  saving.value = true
  try {
    await changePolicyApi({ ...policyForm })
    ElMessage.success('已提交（财务资产会计单签）')
    policyVisible.value = false
    await loadChanges()
  } finally { saving.value = false }
}

async function doImpair() {
  saving.value = true
  try {
    await impairmentApi({ ...impairForm })
    ElMessage.success('已提交（财务资产会计单签）')
    impairVisible.value = false
    await loadChanges()
  } finally { saving.value = false }
}

async function doApprove(row) {
  await approveChangeApi(row.id)
  ElMessage.success('已通过')
  await loadChanges()
}

async function doReject(row) {
  let reason = '驳回'
  try {
    const r = await ElMessageBox.prompt('驳回原因', '驳回', { inputValue: reason })
    reason = r.value
  } catch { return }
  await rejectChangeApi(row.id, reason)
  ElMessage.success('已驳回')
  await loadChanges()
}

onMounted(() => loadChanges())
</script>

<style scoped>
.eam-page { padding: 12px; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.spacer { flex: 1; }
</style>
