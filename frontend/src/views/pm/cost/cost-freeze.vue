<template>
  <div class="cost-freeze">
    <el-card style="margin-bottom: 12px;">
      <div class="hint">
        冻结触发（BR-4.8-21）：实际累计成本 &gt; 预算基线 → 系统自动冻结（拦截领料/外协付款/工时录入）。
        解冻须提交《超预算审批申请》，按超支比例分级审批（BR-4.8-22）：
        ≤5% PMO / 5–10% 财务 / &gt;10% 分管领导；通过后解冻，含追加需求时同步更新预算基线并留痕。
        审批待办走统一审批中心。
      </div>
    </el-card>

    <el-card style="margin-bottom: 12px;">
      <template #header><b>冻结项目清单</b></template>
      <el-table :data="frozen" v-loading="loading" stripe>
        <el-table-column prop="project.projectNo" label="项目号" width="140" />
        <el-table-column prop="project.name" label="项目" min-width="180" show-overflow-tooltip />
        <el-table-column prop="baseline" label="预算基线" width="110" :precision="2" />
        <el-table-column prop="actual" label="实际累计" width="110" :precision="2" />
        <el-table-column prop="overAmount" label="超支金额" width="110" :precision="2">
          <template #default="{ row }">
            <span style="color: #f56c6c;">{{ row.overAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="overRate" label="超支比例" width="100">
          <template #default="{ row }">
            <span style="color: #f56c6c;">{{ row.overRate }}%</span>
          </template>
        </el-table-column>
        <el-table-column prop="project.frozenAt" label="冻结时间" width="160" />
        <el-table-column prop="project.freezeReason" label="冻结原因" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openApply(row)">提交超预算申请</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !frozen.length" description="当前无冻结项目" />
    </el-card>

    <el-card>
      <template #header><b>超预算申请记录</b></template>
      <el-table :data="applies" v-loading="loading" stripe>
        <el-table-column prop="apply.applyNo" label="申请单号" width="160" />
        <el-table-column prop="projectName" label="项目" min-width="160" show-overflow-tooltip />
        <el-table-column prop="apply.overAmount" label="超支金额" width="110" :precision="2" />
        <el-table-column prop="apply.overRate" label="超支比例(%)" width="110" />
        <el-table-column prop="apply.extraBudget" label="追加预算" width="100" :precision="2" />
        <el-table-column label="审批路由" width="110">
          <template #default="{ row }">
            <el-tag size="small">{{ roleName(row.apply.approvalRole) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.apply.status === 'APPROVED' ? 'success' : row.apply.status === 'REJECTED' ? 'danger' : 'warning'">
              {{ row.apply.status === 'PENDING' ? '审批中' : row.apply.status === 'APPROVED' ? '已通过' : '已驳回' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="apply.pmOpinion" label="审批意见" min-width="160" show-overflow-tooltip />
        <el-table-column prop="apply.reason" label="超支原因" min-width="180" show-overflow-tooltip />
      </el-table>
    </el-card>

    <!-- 超预算申请对话框 -->
    <el-dialog v-model="applyVisible" title="超预算审批申请" width="620px" :close-on-click-modal="false">
      <el-alert v-if="applyRow" type="info" :closable="false" style="margin-bottom: 12px;"
        :title="`项目：${applyRow.project.name}　超支 ${applyRow.overAmount} 元（${applyRow.overRate}%）`"
        :description="`按 BR-4.8-22 将路由至：${roleName(routeRole(applyRow.overRate))}`" />
      <el-form label-width="110px" size="default">
        <el-form-item label="超支原因" required>
          <el-input v-model="applyForm.reason" type="textarea" :rows="3"
            placeholder="分析超支原因（估算不准/需求变更/资源不足/技术难点等）" />
        </el-form-item>
        <el-form-item label="节约措施">
          <el-input v-model="applyForm.savingMeasure" type="textarea" :rows="2"
            placeholder="已采取的节约措施（可选）" />
        </el-form-item>
        <el-form-item label="追加预算需求">
          <el-input-number v-model="applyForm.extraBudget" :min="0" :precision="2" style="width: 200px;" />
          <span class="field-hint">0 = 纯解冻；&gt;0 审批通过后自动更新预算基线并留痕</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitApply">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as api from '@/api/cost'

const loading = ref(false)
const frozen = ref([])
const applies = ref([])
const applyVisible = ref(false)
const submitting = ref(false)
const applyRow = ref(null)
const applyForm = ref({ reason: '', savingMeasure: '', extraBudget: 0 })

const roleName = r => ({ ROLE_PMO: 'PMO', ROLE_FINANCE: '财务', ROLE_EXEC: '分管领导' }[r] || r)
const routeRole = rate => rate > 10 ? 'ROLE_EXEC' : rate > 5 ? 'ROLE_FINANCE' : 'ROLE_PMO'

async function load() {
  loading.value = true
  try {
    const [f, a] = await Promise.all([api.frozenProjects(), api.overBudgetList({})])
    frozen.value = f.data || []
    applies.value = a.data || []
  } finally {
    loading.value = false
  }
}

function openApply(row) {
  applyRow.value = row
  applyForm.value = { reason: '', savingMeasure: '', extraBudget: 0 }
  applyVisible.value = true
}

async function submitApply() {
  if (!applyForm.value.reason.trim()) { ElMessage.warning('超支原因必填'); return }
  submitting.value = true
  try {
    const { data } = await api.submitOverBudget({
      projectId: applyRow.value.project.id,
      ...applyForm.value
    })
    ElMessage.success(`申请已提交（${data.applyNo}），路由至 ${roleName(data.approvalRole)} 审批`)
    applyVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.hint { color: #606266; font-size: 12px; line-height: 1.8; }
.field-hint { margin-left: 10px; color: #909399; font-size: 12px; }
</style>
