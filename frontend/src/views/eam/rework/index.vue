<template>
  <div class="page">
    <!-- ============ 7.7.1 资本确认：技改大修单列表 ============ -->
    <el-form inline>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" style="width:170px">
          <el-option v-for="s in STATUSES" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="关键字">
        <el-input v-model="query.keyword" clearable placeholder="单号/卡号" style="width:180px" />
      </el-form-item>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button type="success" @click="openCreate">新建技改单</el-button>
      <el-button @click="doOverdueScan">超期扫描</el-button>
    </el-form>

    <el-table :data="rows" border>
      <el-table-column prop="rwfNo" label="技改单号" width="150" />
      <el-table-column prop="cardNo" label="原卡号" width="130" />
      <el-table-column prop="assetName" label="资产名称" min-width="150" show-overflow-tooltip />
      <el-table-column prop="budgetAmount" label="预算" width="100" />
      <el-table-column prop="reworkStatus" label="状态" width="130">
        <template #default="{ row }">
          <el-tag :type="statusType(row.reworkStatus)">{{ statusLabel(row.reworkStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="capJudge" label="判定" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.capJudge === 'CAP'" type="success" size="small">资本化</el-tag>
          <el-tag v-else-if="row.capJudge === 'EXPENSE'" type="info" size="small">费用化</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="newCost" label="新原值" width="100" />
      <el-table-column label="操作" width="300">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row.id)">明细</el-button>
          <el-button v-if="['DRAFT','REJECTED'].includes(row.reworkStatus)" size="small"
                     type="primary" @click="doSubmit(row.id)">提交判定</el-button>
          <el-button v-if="row.reworkStatus === 'JUDGED' && row.capJudge === 'CAP'" size="small"
                     type="warning" @click="doToInRework(row.id)">转在建</el-button>
          <el-button v-if="row.reworkStatus === 'JUDGED' && row.capJudge === 'EXPENSE'" size="small"
                     type="info" @click="doExpense(row.id)">费用化</el-button>
          <el-button v-if="['REBUILT','EXPENSED'].includes(row.reworkStatus)" size="small"
                     type="success" @click="doClose(row.id)">结案</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="total, prev, pager, next" :total="total"
                   :page-size="query.size" :current-page="query.current"
                   @current-change="onPage" style="margin-top:12px" />

    <!-- ============ 新建技改单 ============ -->
    <el-dialog v-model="createVisible" title="新建技改大修单" width="640px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="原卡号">
          <el-select v-model="form.cardId" filterable remote :remote-method="searchCards"
                     placeholder="输入卡号搜索" style="width:100%">
            <el-option v-for="c in cardOptions" :key="c.id"
                       :label="`${c.cardNo} ${c.assetName}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="技改内容">
          <el-input v-model="form.content" type="textarea" :rows="3" placeholder="改造/大修内容与目标" />
        </el-form-item>
        <el-form-item label="预算金额">
          <el-input-number v-model="form.budgetAmount" :min="0" :precision="2" style="width:200px" />
        </el-form-item>
        <el-form-item label="预期效果">
          <el-input v-model="form.expectedEffect" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="判定依据">
          <div v-for="(it, i) in form.items" :key="i" style="display:flex;gap:8px;margin-bottom:8px;width:100%">
            <el-select v-model="it.condType" style="width:170px">
              <el-option label="寿命延长（≥1年）" value="LIFT_LIFE" />
              <el-option label="产能提升（≥10%）" value="LIFT_CAPACITY" />
              <el-option label="精度/性能提升" value="LIFT_QUALITY" />
            </el-select>
            <el-input-number v-model="it.quantValue" :min="0" placeholder="量化值"
                             style="width:140px" />
            <el-input v-model="it.evidenceNo" placeholder="证据附件锚" style="flex:1" />
            <el-button type="danger" link @click="form.items.splice(i, 1)">删</el-button>
          </div>
          <el-button link type="primary" @click="form.items.push({ condType: 'LIFT_LIFE' })">+ 加一条</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- ============ 详情：步骤条 + 动作 + 归集抽屉 ============ -->
    <el-drawer v-model="detailVisible" title="技改大修详情" size="720px">
      <template v-if="detail">
        <el-steps :active="stepActive" finish-status="success" simple style="margin-bottom:16px">
          <el-step title="申请" />
          <el-step title="判定" />
          <el-step title="在建·归集" />
          <el-step title="验收·转资" />
          <el-step title="结案" />
        </el-steps>

        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="单号">{{ detail.order.rwfNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusLabel(detail.order.reworkStatus) }}</el-descriptions-item>
          <el-descriptions-item label="原卡">{{ detail.order.cardNo }} {{ detail.order.assetName }}</el-descriptions-item>
          <el-descriptions-item label="预算">{{ detail.order.budgetAmount }}</el-descriptions-item>
          <el-descriptions-item label="技改内容" :span="2">{{ detail.order.content }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.order.capJudge" label="判定结果">
            {{ detail.order.capJudge === 'CAP' ? '资本化' : '费用化' }}
            <span v-if="detail.order.judgeNote">（{{ detail.order.judgeNote }}）</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.order.origNbv" label="转入时 NBV">{{ detail.order.origNbv }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.order.newCost" label="新原值">
            {{ detail.order.newCost }}（年限 {{ detail.order.newLifeYears }} 年）
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.order.newCardId" label="新卡">{{ detail.newCardNo || detail.order.newCardId }}</el-descriptions-item>
        </el-descriptions>

        <h4 style="margin:14px 0 6px">判定依据（BR-37）</h4>
        <el-table :data="detail.items" border size="small">
          <el-table-column prop="condType" label="条件" width="150">
            <template #default="{ row }">{{ condLabel(row.condType) }}</template>
          </el-table-column>
          <el-table-column prop="quantValue" label="量化值" width="100" />
          <el-table-column prop="evidenceNo" label="证据" />
        </el-table>

        <template v-if="detail.cipProject">
          <h4 style="margin:14px 0 6px">在建工程项目（1:1）</h4>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="项目号">{{ detail.cipProject.projectNo }}</el-descriptions-item>
            <el-descriptions-item label="项目状态">{{ detail.cipProject.status }}</el-descriptions-item>
            <el-descriptions-item label="预算">{{ detail.cipProject.budgetAmount }}</el-descriptions-item>
            <el-descriptions-item label="已结算">{{ detail.cipProject.costTotal }}</el-descriptions-item>
          </el-descriptions>
          <div style="margin-top:8px">
            <el-button v-if="detail.order.reworkStatus === 'IN_REWORK'" size="small" type="primary"
                       @click="costVisible = true">归集支出</el-button>
          </div>
        </template>

        <!-- 状态相关动作 -->
        <div style="margin-top:18px;display:flex;gap:8px;flex-wrap:wrap">
          <template v-if="detail.order.reworkStatus === 'PENDING_JUDGE' && !detail.order.apprId">
            <el-input v-model="judgeNote" placeholder="判定说明" style="width:220px" />
            <el-select v-model="capJudge" style="width:120px">
              <el-option label="资本化" value="CAP" />
              <el-option label="费用化" value="EXPENSE" />
            </el-select>
            <el-button type="primary" @click="doJudge">提交判定结果</el-button>
          </template>
          <template v-if="detail.order.reworkStatus === 'IN_REWORK'">
            <el-button v-if="detail.order.continueDeprFlag !== '1'" size="small" type="warning"
                       @click="doContinue">特批续提申请</el-button>
            <span v-else style="color:#67c23a">已特批续提（期间继续计提）</span>
            <el-button size="small" type="success" @click="acceptVisible = true">完工验收</el-button>
            <el-button size="small" type="danger" @click="doReverse">整单冲回</el-button>
          </template>
          <template v-if="detail.order.reworkStatus === 'COMPLETED'">
            <el-input-number v-model="newLifeYears" :min="1" placeholder="技术评估年限"
                             style="width:160px" />
            <el-button type="success" @click="doRebuild">转资重建</el-button>
            <span v-if="detail.overdueDays > 30" style="color:#e6a23c">
              验收后 {{ detail.overdueDays }} 天未转资（C-4.13-06 预警）
            </span>
          </template>
        </div>
      </template>
    </el-drawer>

    <!-- 验收日期 -->
    <el-dialog v-model="acceptVisible" title="完工验收" width="360px">
      <el-date-picker v-model="acceptDate" type="date" value-format="YYYY-MM-DD" placeholder="验收日" />
      <template #footer>
        <el-button @click="acceptVisible = false">取消</el-button>
        <el-button type="primary" @click="doAccept">确认验收</el-button>
      </template>
    </el-dialog>

    <!-- 归集抽屉（调 CIP 现有接口） -->
    <el-dialog v-model="costVisible" title="归集支出（在建工程）" width="480px">
      <el-form label-width="90px">
        <el-form-item label="费用类型">
          <el-select v-model="costForm.costType" style="width:100%">
            <el-option label="工程款" value="ENGINGEE" />
            <el-option label="安装费" value="INSTALL" />
            <el-option label="资本化利息" value="INTEREST" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额">
          <el-input-number v-model="costForm.amount" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="来源单号">
          <el-input v-model="costForm.sourceDocNo" placeholder="发票/合同号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="costVisible = false">取消</el-button>
        <el-button type="primary" @click="doAddCost">归集</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  reworkPage, reworkDetail, reworkCreate, reworkSubmit, reworkJudge,
  reworkContinueDepr, reworkToInRework, reworkAccept, reworkRebuild,
  reworkExpense, reworkClose, reworkReverse, reworkOverdueScan, reworkCardSearch
} from '@/api/eam/rework'
import { addCipCostApi } from '@/api/eam/asset'

const STATUSES = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'PENDING_JUDGE', label: '待判定' },
  { value: 'JUDGED', label: '已判定' },
  { value: 'IN_REWORK', label: '在建-技改' },
  { value: 'COMPLETED', label: '已验收' },
  { value: 'REBUILT', label: '已重建' },
  { value: 'EXPENSED', label: '已费用化' },
  { value: 'CLOSED', label: '已结案' }
]
const STEP_OF = { DRAFT: 0, PENDING_JUDGE: 1, JUDGED: 1, IN_REWORK: 2, COMPLETED: 3, REBUILT: 4, EXPENSED: 4, CLOSED: 4 }

const query = reactive({ current: 1, size: 20, status: '', keyword: '' })
const rows = ref([])
const total = ref(0)

const createVisible = ref(false)
const form = reactive({ cardId: '', content: '', budgetAmount: 0, expectedEffect: '', items: [{ condType: 'LIFT_LIFE' }] })
const cardOptions = ref([])

const detailVisible = ref(false)
const detail = ref(null)
const judgeNote = ref('')
const capJudge = ref('CAP')
const newLifeYears = ref(null)
const acceptVisible = ref(false)
const acceptDate = ref('')
const costVisible = ref(false)
const costForm = reactive({ costType: '', amount: 0, sourceDocNo: '' })

const stepActive = computed(() => (detail.value ? (STEP_OF[detail.value.order.reworkStatus] ?? 0) : 0))

const statusType = (s) => ({ DRAFT: 'info', PENDING_JUDGE: 'warning', JUDGED: 'primary', IN_REWORK: 'warning', COMPLETED: 'primary', REBUILT: 'success', EXPENSED: 'info', CLOSED: 'info' }[s] || 'info')
const statusLabel = (s) => (STATUSES.find((x) => x.value === s)?.label || s)
const condLabel = (c) => ({ LIFT_LIFE: '寿命延长', LIFT_CAPACITY: '产能提升', LIFT_QUALITY: '精度/性能' }[c] || c)

async function load() {
  const { data } = await reworkPage(query)
  rows.value = data.records || []
  total.value = Number(data.total || 0)
}
const onPage = (p) => { query.current = p; load() }

function openCreate() {
  form.cardId = ''; form.content = ''; form.budgetAmount = 0; form.expectedEffect = ''
  form.items = [{ condType: 'LIFT_LIFE' }]
  createVisible.value = true
}
async function searchCards(kw) {
  if (!kw) return
  const { data } = await reworkCardSearch(kw)
  cardOptions.value = data.records || []
}
async function doCreate() {
  await reworkCreate({ order: form, items: form.items })
  ElMessage.success('技改单已创建')
  createVisible.value = false
  load()
}
async function doSubmit(id) {
  await reworkSubmit(id)
  ElMessage.success('已提交判定')
  load()
}
async function openDetail(id) {
  const { data } = await reworkDetail(id)
  detail.value = data
  newLifeYears.value = null
  detailVisible.value = true
}
async function doJudge() {
  await reworkJudge(detail.value.order.id, { capJudge: capJudge.value, judgeNote: judgeNote.value })
  ElMessage.success('判定已提交')
  await openDetail(detail.value.order.id)
  load()
}
async function doContinue() {
  const { value } = await ElMessageBox.prompt('特批续提原因（继续运行说明）', '特批续提')
  await reworkContinueDepr(detail.value.order.id, value)
  ElMessage.success('已发起特批续提审批')
}
async function doToInRework(id) {
  await ElMessageBox.confirm('转在建将自动生成转移凭证并停止计提，确认？', '转在建')
  await reworkToInRework(id)
  ElMessage.success('已转在建')
  await openDetail(id); load()
}
async function doAccept() {
  await reworkAccept(detail.value.order.id, acceptDate.value || null)
  ElMessage.success('验收完成（超期监控已起算）')
  acceptVisible.value = false
  await openDetail(detail.value.order.id); load()
}
async function doRebuild() {
  await reworkRebuild(detail.value.order.id, newLifeYears.value)
  ElMessage.success('转资重建完成（新卡已生成）')
  await openDetail(detail.value.order.id); load()
}
async function doExpense() {
  await ElMessageBox.confirm('费用化将自动生成维修费凭证（借660201/贷2202），确认？', '费用化')
  await reworkExpense(detail.value.order.id)
  ElMessage.success('已费用化')
  load()
}
async function doClose() {
  await reworkClose(detail.value.order.id)
  ElMessage.success('已结案')
  load()
}
async function doReverse() {
  await ElMessageBox.confirm('整单冲回将冲销凭证并恢复卡片，确认？', '冲回', { type: 'warning' })
  await reworkReverse(detail.value.order.id)
  ElMessage.success('已冲回')
  await openDetail(detail.value.order.id); load()
}
async function doAddCost() {
  await addCipCostApi({
    projectId: detail.value.cipProject.projectId,
    costType: costForm.costType, amount: costForm.amount, sourceDocNo: costForm.sourceDocNo
  })
  ElMessage.success('归集成功')
  costVisible.value = false
  await openDetail(detail.value.order.id)
}
async function doOverdueScan() {
  const { data } = await reworkOverdueScan()
  ElMessage.info(`超期 ${data.overdueCount} 单（宽限 ${data.graceDays} 天）`)
}

onMounted(load)
</script>
