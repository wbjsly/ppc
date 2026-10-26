<template>
  <div class="change-apply">
    <el-card style="margin-bottom: 12px;">
      <div class="hint">
        项目变更申请（FR-4.8-8-1/2，C-4.8-09）：五类变更（范围/进度/预算/资源/组合）统一容器。
        须关联受影响 WBS 任务；期望生效日期不可早于今日（倒签 L1）。
        影响分析：范围/进度/资源三维人工填报，预算影响占比由系统自动计算（=追加金额/当前基线），
        作为分级审批路由依据。第三档（&gt;10% 或涉合同）须上传客户书面确认附件。
        涉合同：手动勾选，&gt;10% 时系统强制置位。
      </div>
    </el-card>

    <el-card style="margin-bottom: 12px;">
      <template #header><b>新建变更申请</b></template>
      <el-form label-width="110px" size="default">
        <el-row :gutter="16">
          <el-col :span="10">
            <el-form-item label="项目" required>
              <el-select v-model="form.projectId" filterable placeholder="选择执行中（ACTIVE）项目"
                style="width: 100%;" @change="onProject">
                <el-option v-for="p in projects" :key="p.id"
                  :label="`${p.projectNo || ''} ${p.name}`" :value="p.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="7">
            <el-form-item label="变更类型" required>
              <el-select v-model="form.changeType" style="width: 100%;">
                <el-option value="SCOPE" label="范围变更" />
                <el-option value="SCHEDULE" label="进度变更" />
                <el-option value="BUDGET" label="预算变更" />
                <el-option value="RESOURCE" label="资源变更" />
                <el-option value="COMBO" label="组合变更" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="7">
            <el-form-item label="变更原因" required>
              <el-select v-model="form.reason" style="width: 100%;">
                <el-option value="客户需求" label="客户需求" />
                <el-option value="技术风险" label="技术风险" />
                <el-option value="外部环境" label="外部环境" />
                <el-option value="内部优化" label="内部优化" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="10">
            <el-form-item label="期望生效日" required>
              <el-date-picker v-model="form.effectiveDate" type="date" value-format="YYYY-MM-DD"
                placeholder="不可早于今日" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="14">
            <el-form-item label="变更描述">
              <el-input v-model="form.description" type="textarea" :rows="2"
                placeholder="建议 20 字以上（<20 字 L4 提示，不阻断）" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="受影响 WBS" required>
          <el-select v-model="form.wbsIds" multiple filterable placeholder="选择受影响的 WBS 任务（必填 L1）"
            style="width: 100%;">
            <el-option v-for="t in taskOptions" :key="t.id" :label="`${t.wbsCode} ${t.name}`" :value="t.id" />
          </el-select>
          <div class="field-hint">变更期间，这些 WBS 的领料/外协付款/工时录入将被拦截（C-4.8-09）</div>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card style="margin-bottom: 12px;">
      <template #header><b>影响分析（四维度）</b></template>
      <el-form label-width="110px" size="default">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="进度影响(天)">
              <el-input-number v-model="form.scheduleImpactDays" :min="0" style="width: 100%;" />
              <div class="field-hint">&gt;5 天触发 PMO+财务双签</div>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="追加金额">
              <el-input-number v-model="form.budgetAdd" :min="0" :precision="2" style="width: 100%;" />
              <div class="field-hint">预算影响占比 = 追加 / 当前基线 × 100%（服务端算）</div>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="涉合同">
              <el-switch v-model="contractChecked" active-text="是" inactive-text="否" />
              <div class="field-hint">&gt;10% 时系统强制置位</div>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="范围影响">
              <el-input v-model="form.scopeImpact" type="textarea" :rows="2"
                placeholder="WBS 增删改清单（文字）" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="资源影响">
              <el-input v-model="form.resourceImpact" type="textarea" :rows="2"
                placeholder="人力/设备增减（文字）" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16" v-if="contractChecked">
          <el-col :span="12">
            <el-form-item label="关联合同ID">
              <el-input v-model="form.contractId" placeholder="销售域合同主键" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="合同编号">
              <el-input v-model="form.contractNo" placeholder="合同编号" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item v-if="contractChecked" label="客户书面确认" required>
          <el-upload :auto-upload="false" :limit="1" :on-change="onFileChange"
            :on-remove="() => (form.customerConfirmFlag = '0')" accept=".pdf,.png,.jpg">
            <el-button>上传客户书面确认附件（第三档 L1 必填）</el-button>
          </el-upload>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <el-button type="primary" :loading="submitting" :disabled="!form.projectId"
        @click="doSubmit">提交审批</el-button>
      <el-button :disabled="!form.projectId" @click="doDraft">存草稿</el-button>
    </el-card>

    <!-- 提交结果 -->
    <el-dialog v-model="resultVisible" title="提交结果" width="520px">
      <el-descriptions v-if="result" :column="1" border size="small">
        <el-descriptions-item label="变更单号">{{ result.changeNo }}</el-descriptions-item>
        <el-descriptions-item label="预算影响占比">{{ result.budgetRate }}%</el-descriptions-item>
        <el-descriptions-item label="涉合同">{{ result.isContractFlag === '1' ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="审批路由">{{ result.approvalTierName }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button type="primary" @click="resultVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as api from '@/api/pm/project-change'
import { projectList } from '@/api/pm/project'
import { wbsTree } from '@/api/pm/wbs'

const projects = ref([])
const taskOptions = ref([])
const submitting = ref(false)
const contractChecked = ref(false)
const resultVisible = ref(false)
const result = ref(null)

const form = ref({
  projectId: '', changeType: 'SCOPE', reason: '客户需求', effectiveDate: '',
  description: '', wbsIds: [], scopeImpact: '', scheduleImpactDays: 0, resourceImpact: '',
  budgetAdd: 0, contractId: '', contractNo: '', customerConfirmFlag: '0'
})

async function loadProjects() {
  const { data } = await projectList({ status: 'ACTIVE' })
  projects.value = (data || []).map(p => p.project || p)
}

async function onProject(pid) {
  form.value.wbsIds = []
  taskOptions.value = []
  if (!pid) return
  const { data } = await wbsTree(pid)
  // 仅任务级（level 3）可作为受影响 WBS
  taskOptions.value = (data || [])
    .map(w => w.wbs || w)
    .filter(w => w.level === 3)
}

function onFileChange() {
  form.value.customerConfirmFlag = '1'
}

async function doSubmit() {
  if (!form.value.wbsIds.length) { ElMessage.warning('变更须关联受影响 WBS（C-4.8-09）'); return }
  if (contractChecked.value && form.value.customerConfirmFlag !== '1') {
    ElMessage.warning('涉合同变更须上传客户书面确认附件（C-4.8-10）'); return
  }
  submitting.value = true
  try {
    const { data } = await api.submitChange({ ...form.value, isContractFlag: contractChecked.value ? '1' : '0' })
    result.value = data
    resultVisible.value = true
    ElMessage.success(`已提交：${data.changeNo}（${data.approvalTierName}）`)
  } finally {
    submitting.value = false
  }
}

async function doDraft() {
  submitting.value = true
  try {
    const { data } = await api.saveDraft({ ...form.value, isContractFlag: contractChecked.value ? '1' : '0' })
    ElMessage.success(`草稿已保存：${data.changeNo}${data.l4Warning ? '（' + data.l4Warning + '）' : ''}`)
  } finally {
    submitting.value = false
  }
}

onMounted(loadProjects)
</script>

<style scoped>
.hint { color: #606266; font-size: 12px; line-height: 1.8; }
.field-hint { color: #909399; font-size: 12px; line-height: 1.5; }
</style>
