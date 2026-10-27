<template>
  <div class="trial-manage">
    <el-card style="margin-bottom: 12px;">
      <div class="hint">
        研发物料试制领用（FR-4.8-10-1~4，C-4.8-12）：试制领料须勾"研发试制"并关联试制类（TRIAL）WBS 任务，未关联 L1 硬阻断；
        与量产分账（BR-4.8-37）。试制完成后做消耗确认（差异>10% L4 核实）并登记样品台账（BR-4.8-39 批次可追溯）；
        结余物料按原单价退库，可再用→入库+冲减成本，报废→成本保留；跨项目挪用 L1（BR-4.8-04）。
      </div>
    </el-card>

    <el-tabs v-model="tab">
      <!-- Tab1 试制领料 -->
      <el-tab-pane label="试制领料" name="issue">
        <el-form label-width="110px" size="default">
          <el-row :gutter="16">
            <el-col :span="10">
              <el-form-item label="项目" required>
                <el-select v-model="issueForm.projectId" filterable placeholder="选择项目" style="width: 100%;" @change="onIssueProject">
                  <el-option v-for="p in projects" :key="p.id" :label="`${p.projectNo || ''} ${p.name}`" :value="p.id" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="7">
              <el-form-item label="领用日期" required>
                <el-date-picker v-model="issueForm.issueDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
              </el-form-item>
            </el-col>
            <el-col :span="7">
              <el-form-item label="研发试制">
                <el-switch v-model="trialOn" active-text="试制" inactive-text="量产" />
                <div class="field-hint">开启后须关联 TRIAL 任务（C-4.8-12），审核人限研发负责人</div>
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item v-if="trialOn" label="试制用途">
            <el-input v-model="issueForm.trialPurpose" placeholder="试制用途说明（样件制作/验证等）" />
          </el-form-item>
          <el-form-item label="物料行" required>
            <el-table :data="issueForm.lines" size="small" border>
              <el-table-column label="TRIAL 任务" min-width="200">
                <template #default="{ row }">
                  <el-select v-model="row.wbsId" filterable placeholder="选择试制任务" style="width: 100%;">
                    <el-option v-for="t in trialTaskOptions" :key="t.id" :label="`${t.wbsCode} ${t.name}`" :value="t.id" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="物料编码" width="150">
                <template #default="{ row }"><el-input v-model="row.itemId" /></template>
              </el-table-column>
              <el-table-column label="数量" width="110">
                <template #default="{ row }"><el-input-number v-model="row.qty" :min="0" :precision="3" size="small" style="width:100%" /></template>
              </el-table-column>
              <el-table-column label="单价快照" width="120">
                <template #default="{ row }"><el-input-number v-model="row.unitPrice" :min="0" :precision="2" size="small" style="width:100%" /></template>
              </el-table-column>
              <el-table-column label="仓库" width="120">
                <template #default="{ row }"><el-input v-model="row.warehouseCode" placeholder="WH-02" /></template>
              </el-table-column>
              <el-table-column label="操作" width="80">
                <template #default="{ $index }">
                  <el-button link type="danger" size="small" @click="issueForm.lines.splice($index,1)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button size="small" style="margin-top:8px" @click="issueForm.lines.push({ wbsId:'', itemId:'', qty:1, unitPrice:0, warehouseCode:'WH-02' })">加行</el-button>
          </el-form-item>
          <el-button type="primary" :loading="submitting" :disabled="!issueForm.projectId" @click="doSubmitIssue">提交试制领料</el-button>
        </el-form>
      </el-tab-pane>

      <!-- Tab2 消耗确认 -->
      <el-tab-pane label="消耗确认" name="consumption">
        <el-form label-width="110px" size="default">
          <el-form-item label="试制领料单" required>
            <el-select v-model="consumptionForm.issueId" filterable placeholder="选择已发料试制领料单" style="width: 100%;" @change="onIssueSelect">
              <el-option v-for="it in approvedIssues" :key="it.id" :label="`${it.issueNo} ${it.trialPurpose || ''}`" :value="it.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="试制报告">
            <el-upload :auto-upload="false" :limit="1" :on-change="() => (consumptionForm.reportAttached = true)" accept=".pdf,.doc,.docx">
              <el-button>上传试制报告（可选）</el-button>
            </el-upload>
          </el-form-item>
          <el-form-item label="实际消耗" required>
            <el-table :data="consumptionForm.lines" size="small" border>
              <el-table-column prop="itemId" label="物料" min-width="140" />
              <el-table-column prop="issuedQty" label="领用数" width="100" />
              <el-table-column label="实耗数" width="120">
                <template #default="{ row }"><el-input-number v-model="row.actualQty" :min="0" :precision="3" size="small" style="width:100%" /></template>
              </el-table-column>
              <el-table-column label="差异说明" min-width="160">
                <template #default="{ row }"><el-input v-model="row.deviationNote" placeholder="差异>10% 时建议填写" /></template>
              </el-table-column>
            </el-table>
          </el-form-item>
          <el-form-item label="产出样品">
            <el-table :data="consumptionForm.samples" size="small" border>
              <el-table-column label="样品名称" min-width="140">
                <template #default="{ row }"><el-input v-model="row.name" /></template>
              </el-table-column>
              <el-table-column label="物料" width="140">
                <template #default="{ row }">
                  <el-select v-model="row.itemId" size="small" style="width:100%">
                    <el-option v-for="l in consumptionForm.lines" :key="l.itemId" :label="l.itemId" :value="l.itemId" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="数量" width="100">
                <template #default="{ row }"><el-input-number v-model="row.qty" :min="1" size="small" style="width:100%" /></template>
              </el-table-column>
              <el-table-column label="去向" width="130">
                <template #default="{ row }">
                  <el-select v-model="row.destination" size="small" style="width:100%">
                    <el-option value="TEST" label="测试" /><el-option value="CUSTOMER" label="送客户" />
                    <el-option value="RETAIN" label="留样" /><el-option value="SCRAP" label="报废" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="80">
                <template #default="{ $index }">
                  <el-button link type="danger" size="small" @click="consumptionForm.samples.splice($index,1)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button size="small" style="margin-top:8px" @click="consumptionForm.samples.push({ name:'', itemId:'', qty:1, destination:'TEST' })">加样品</el-button>
          </el-form-item>
          <el-button type="primary" :disabled="!consumptionForm.issueId" @click="doConfirmConsumption">提交消耗确认</el-button>
        </el-form>
      </el-tab-pane>

      <!-- Tab3 样品台账 -->
      <el-tab-pane label="样品台账" name="samples">
        <el-form inline size="default">
          <el-form-item label="批次">
            <el-input v-model="sampleFilter.batchNo" placeholder="按领料批次追溯" style="width:180px;" clearable />
          </el-form-item>
          <el-form-item label="去向">
            <el-select v-model="sampleFilter.destination" clearable placeholder="全部" style="width:130px;">
              <el-option value="TEST" label="测试" /><el-option value="CUSTOMER" label="送客户" />
              <el-option value="RETAIN" label="留样" /><el-option value="SCRAP" label="报废" />
            </el-select>
          </el-form-item>
          <el-button type="primary" @click="loadSamples">查询</el-button>
        </el-form>
        <el-table :data="samples" size="small" border>
          <el-table-column prop="sampleNo" label="样品编号" width="150" />
          <el-table-column prop="name" label="样品名称" min-width="150" />
          <el-table-column prop="batchNo" label="领料批次" width="140" />
          <el-table-column prop="qty" label="数量" width="80" />
          <el-table-column label="去向" width="100">
            <template #default="{ row }">{{ destName(row.destination) }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100" />
        </el-table>
      </el-tab-pane>

      <!-- Tab4 结余退库 -->
      <el-tab-pane label="结余退库" name="return">
        <el-form label-width="110px" size="default">
          <el-form-item label="试制领料单" required>
            <el-select v-model="returnForm.issueId" filterable placeholder="选择已确认消耗的领料单" style="width: 100%;">
              <el-option v-for="it in approvedIssues" :key="it.id" :label="it.issueNo" :value="it.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="退库行" required>
            <el-table :data="returnForm.lines" size="small" border>
              <el-table-column label="物料" width="150">
                <template #default="{ row }"><el-input v-model="row.itemId" /></template>
              </el-table-column>
              <el-table-column label="批次" width="140">
                <template #default="{ row }"><el-input v-model="row.batchNo" /></template>
              </el-table-column>
              <el-table-column label="退库数" width="120">
                <template #default="{ row }"><el-input-number v-model="row.returnQty" :min="0" :precision="3" size="small" style="width:100%" /></template>
              </el-table-column>
              <el-table-column label="质检结论" width="140">
                <template #default="{ row }">
                  <el-select v-model="row.qcResult" size="small" style="width:100%">
                    <el-option value="REUSABLE" label="可再用" /><el-option value="SCRAP" label="报废" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="80">
                <template #default="{ $index }">
                  <el-button link type="danger" size="small" @click="returnForm.lines.splice($index,1)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button size="small" style="margin-top:8px" @click="returnForm.lines.push({ itemId:'', batchNo:'', returnQty:1, qcResult:'REUSABLE' })">加行</el-button>
            <div class="field-hint">可再用→入库+冲减成本；报废→成本保留；跨项目挪用 L1；退库数不得超过结余</div>
          </el-form-item>
          <el-button type="primary" :disabled="!returnForm.issueId" @click="doSubmitReturn">提交退料单</el-button>
        </el-form>

        <el-divider content-position="left">退料单台账</el-divider>
        <el-table :data="returns" size="small" border>
          <el-table-column prop="returnNo" label="退料单号" width="160" />
          <el-table-column prop="issueId" label="领料单" min-width="120" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status==='APPROVED'?'success':row.status==='REJECTED'?'danger':'info'">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button v-if="row.status==='SUBMITTED'" link type="success" size="small" @click="doReviewReturn(row,true)">通过</el-button>
              <el-button v-if="row.status==='SUBMITTED'" link type="warning" size="small" @click="doReviewReturn(row,false)">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as api from '@/api/pm/trial-material'
import { projectList } from '@/api/pm/project'
import { wbsTree } from '@/api/pm/wbs'

const tab = ref('issue')
const projects = ref([])
const trialTaskOptions = ref([])
const approvedIssues = ref([])
const samples = ref([])
const returns = ref([])
const submitting = ref(false)
const trialOn = ref(true)

const issueForm = ref({ projectId: '', issueDate: '', trialPurpose: '', lines: [{ wbsId: '', itemId: '', qty: 1, unitPrice: 0, warehouseCode: 'WH-02' }] })
const consumptionForm = ref({ issueId: '', reportAttached: false, lines: [], samples: [] })
const sampleFilter = ref({ batchNo: '', destination: '' })
const returnForm = ref({ issueId: '', lines: [{ itemId: '', batchNo: '', returnQty: 1, qcResult: 'REUSABLE' }] })

const DEST_NAMES = { TEST: '测试', CUSTOMER: '送客户', RETAIN: '留样', SCRAP: '报废' }
const destName = d => DEST_NAMES[d] || d

async function loadProjects() {
  const { data } = await projectList({})
  projects.value = (data || []).map(p => p.project || p)
}

async function onIssueProject(pid) {
  trialTaskOptions.value = []
  if (!pid) return
  const { data } = await wbsTree(pid)
  const walk = nodes => (nodes || []).forEach(n => {
    if (n.level === 3 && n.taskType === 'TRIAL') trialTaskOptions.value.push(n)
    if (n.children) walk(n.children)
  })
  walk(data)
}

async function loadIssues() {
  // 已发料试制领料单（复用现有领料列表端点）
  const { data } = await (await import('@/api/cost/index')).matIssueList({})
  approvedIssues.value = (data?.items || data || []).filter(it => it.trialFlag === '1' && it.status === 'APPROVED')
}

async function loadSamples() {
  const { data } = await api.sampleLedger(sampleFilter.value)
  samples.value = data?.items || []
}

async function loadReturns() {
  const { data } = await api.returnList({})
  returns.value = data?.items || []
}

async function onIssueSelect(issueId) {
  const { data } = await (await import('@/api/cost/index')).matIssueDetail(issueId)
  consumptionForm.value.lines = (data?.lines || []).map(l => ({ itemId: l.itemId, issuedQty: l.qty, actualQty: l.qty, deviationNote: '' }))
  consumptionForm.value.samples = []
}

async function doSubmitIssue() {
  submitting.value = true
  try {
    await api.submitTrialIssue({ ...issueForm.value, trialFlag: trialOn.value ? '1' : '0' })
    ElMessage.success('试制领料已提交')
  } finally {
    submitting.value = false
  }
}

async function doConfirmConsumption() {
  const { data } = await api.confirmConsumption(consumptionForm.value)
  if (data?.l4Warning) ElMessage.warning(data.l4Warning)
  else ElMessage.success('消耗确认完成，样品台账已登记')
  await loadSamples()
}

async function doSubmitReturn() {
  await api.submitReturn(returnForm.value)
  ElMessage.success('退料单已提交，待审核')
  await loadReturns()
}

async function doReviewReturn(row, approved) {
  await api.reviewReturn(row.id, { approved, opinion: approved ? '同意' : '不符合退库条件' })
  ElMessage.success(approved ? '已通过（可再用入库+冲减，报废保留）' : '已驳回')
  await loadReturns()
}

onMounted(async () => { await loadProjects(); await loadIssues(); await loadSamples(); await loadReturns() })
</script>

<style scoped>
.hint { color: #909399; font-size: 13px; line-height: 1.7; }
.field-hint { color: #c0c4cc; font-size: 12px; margin-top: 4px; }
</style>
