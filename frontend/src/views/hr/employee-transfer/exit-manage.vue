<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>离职管理（10.7.4）—— 交接清单 / 结算 / 离场凭证（C-4.7-04）</span>
          <el-button type="primary" size="small" @click="openApply">发起离职</el-button>
        </div>
      </template>
      <el-alert :closable="false" type="info" show-icon class="mb8"
        title="四类交接（资产归还联动 EAM 卡片回写 / 权限移交联动账号禁用 / 知识文档 / 在办工作）全齐才放行离场凭证；未满通知期 L4 提示不阻断" />

      <el-table :data="rows" size="small" border>
        <el-table-column prop="exitNo" label="离职单号" width="120" />
        <el-table-column prop="empName" label="员工" width="90" />
        <el-table-column prop="empNo" label="工号" width="100" />
        <el-table-column label="来源" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.source === 'SELF'" type="primary" size="small">自助</el-tag>
            <span v-else class="muted">HR</span>
          </template>
        </el-table-column>
        <el-table-column prop="quitDate" label="拟离职日" width="100" />
        <el-table-column label="通知期" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.noticeOk === '0'" type="warning" size="small">未满 L4</el-tag>
            <span v-else>满足</span>
          </template>
        </el-table-column>
        <el-table-column label="交接" min-width="180">
          <template #default="{ row }">
            <el-tag :type="row.assetDone === '1' ? 'success' : 'info'" size="small" class="mr4">资产</el-tag>
            <el-tag :type="row.accountDone === '1' ? 'success' : 'info'" size="small" class="mr4">权限</el-tag>
            <el-tag :type="row.docDone === '1' ? 'success' : 'info'" size="small" class="mr4">文档</el-tag>
            <el-tag :type="row.workDone === '1' ? 'success' : 'info'" size="small">工作</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">{{ statusName(row.status) }}</template>
        </el-table-column>
        <el-table-column label="经济补偿金" width="100">
          <template #default="{ row }">{{ row.settleEcomp ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="减员" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.reduceWaived === '1'" type="warning" size="small">已豁免</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260">
          <template #default="{ row }">
            <el-button v-if="row.status === 'APPLYING'" link type="primary" size="small" @click="startApproval(row)">提交审批</el-button>
            <el-button v-if="row.status === 'HANDOVER' || row.status === 'SETTLED'" link type="primary" size="small" @click="openChecklist(row)">交接清单</el-button>
            <el-button v-if="['APPROVING','HANDOVER'].includes(row.status)" link type="warning" size="small" @click="retain(row)">挽留</el-button>
            <el-button v-if="row.status === 'SETTLED' && row.reduceWaived !== '1'" link type="warning" size="small" @click="waive(row)">减员豁免</el-button>
            <el-button v-if="row.status === 'SETTLED'" link type="danger" size="small" @click="issueCert(row)">离场凭证</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 发起离职 -->
    <el-dialog v-model="applyVisible" title="发起离职申请" width="480px">
      <el-form label-width="90px">
        <el-form-item label="员工">
          <el-select v-model="applyForm.empId" filterable style="width:100%">
            <el-option v-for="e in emps" :key="e.id" :label="`${e.empNo} ${e.name}`" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="拟离职日"><el-date-picker v-model="applyForm.quitDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="离职原因"><el-input v-model="applyForm.reason" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyVisible = false">取消</el-button>
        <el-button type="primary" @click="doApply">提交</el-button>
      </template>
    </el-dialog>

    <!-- 交接清单 -->
    <el-dialog v-model="ckVisible" :title="`交接清单 - ${ckData?.exitNo || ''}`" width="720px">
      <template v-if="ckData">
        <div class="sec">① 资产归还（EAM 卡片联动回写持有人清空）</div>
        <el-table :data="ckData.assets || []" size="small" max-height="160" class="mb8">
          <el-table-column prop="cardNo" label="卡号" width="130" />
          <el-table-column prop="assetName" label="资产" min-width="140" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="returnAsset(row)">确认归还</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button size="small" :disabled="ckData.assetDone" @click="returnAllAssets">名下已无卡片/全部归还</el-button>

        <div class="sec">② 权限移交（账号禁用）</div>
        <div class="mb8">账号：{{ ckData.account ? ckData.account.username + '（状态 ' + ckData.account.status + '）' : '未开通' }}
          <el-button size="small" :disabled="ckData.accountDone" @click="handoverAccount">确认移交（禁用）</el-button>
        </div>

        <div class="sec">③④ 知识文档 / 在办工作（手工登记 + 接收人）</div>
        <el-form inline size="small">
          <el-form-item label="类别"><el-radio-group v-model="itemForm.category"><el-radio value="doc">文档</el-radio><el-radio value="work">工作</el-radio></el-radio-group></el-form-item>
          <el-form-item label="交接项"><el-input v-model="itemForm.item" style="width:180px" /></el-form-item>
          <el-form-item label="接收人"><el-input v-model="itemForm.receiver" style="width:100px" /></el-form-item>
          <el-form-item><el-button size="small" @click="addItems">登记</el-button></el-form-item>
        </el-form>

        <div class="sec">薪酬结算 + 社保减员登记</div>
        <el-form inline size="small">
          <el-form-item label="当月工资"><el-input v-model="settleForm.settleWage" style="width:100px" /></el-form-item>
          <el-form-item label="年假折算"><el-input v-model="settleForm.settleAnnual" style="width:100px" /></el-form-item>
          <el-form-item label="调休折算"><el-input v-model="settleForm.settleComp" style="width:100px" /></el-form-item>
          <el-form-item label="经济补偿金">
            <el-input v-model="settleForm.settleEcomp" style="width:110px" placeholder="留空自动试算" />
          </el-form-item>
          <el-form-item label="竞业补偿"><el-input v-model="settleForm.settleNoncomp" style="width:100px" /></el-form-item>
          <el-form-item label="减员日期"><el-date-picker v-model="settleForm.reduceDate" type="date" value-format="YYYY-MM-DD" style="width:130px" /></el-form-item>
          <el-form-item><el-button size="small" @click="doSettle">登记结算</el-button></el-form-item>
        </el-form>
        <div class="muted mb8">经济补偿金留空时按 N 法定口径自动试算（年限折算 × 月薪，满 6 个月按 1 年）预填，可修改；结算登记将自动开立社保减员单（待申报）</div>
        <el-alert v-if="ckRow?.reduceWaived === '1'" :closable="false" type="warning" show-icon class="mb8"
          :title="`社保减员已手工豁免：${ckRow.reduceWaiveNote || ''}`" />

        <el-alert v-if="ckData.missing" :closable="false" type="error" show-icon
          :title="`离场凭证阻断（C-4.7-04）：未完成 ${ckData.missing.join('、')}`" />
        <el-alert v-else :closable="false" type="success" show-icon title="四类交接已完成，可生成离场凭证（自动生成将禁用账号）" />
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { exitApply, exitStartApproval, exitChecklist, exitAssetsReturn, exitAccountHandover,
  exitHandoverItems, exitSettle, exitIssueCert, exitList, empList,
  exitRetainNote, exitWaiveReduce } from '@/api/hr/employee-archive'

const rows = ref([])
const emps = ref([])
const applyVisible = ref(false)
const applyForm = ref({})
const ckVisible = ref(false)
const ckData = ref(null)
const ckRow = ref(null)
const itemForm = ref({ category: 'doc', item: '', receiver: '' })
const settleForm = ref({})

const statusName = (s) => ({ APPLYING: '申请中', APPROVING: '审批中', HANDOVER: '交接中',
  SETTLED: '已结算', EXITED: '已离场', REJECTED: '驳回' }[s] || s)

async function loadAll() {
  try { rows.value = (await exitList({})).data || [] } catch (e) { /* ignore */ }
  try {
    const { data } = await empList({})
    emps.value = (data || []).map(r => r.employee)
  } catch (e) { /* ignore */ }
}

async function openApply() {
  applyForm.value = { quitDate: new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10) }
  applyVisible.value = true
}

async function doApply() {
  try {
    const { data } = await exitApply(applyForm.value)
    if (data.noticeOk === '0') {
      ElMessage.warning('已提交（L4：未满通知期，记录留痕不阻断）')
    } else {
      ElMessage.success('离职申请已提交')
    }
    applyVisible.value = false
    loadAll()
  } catch (e) { /* ignore */ }
}

async function startApproval(row) {
  try { await exitStartApproval(row.id); ElMessage.success('已提交审批'); loadAll() } catch (e) { /* ignore */ }
}

async function openChecklist(row) {
  ckRow.value = row
  ckVisible.value = true
  await refreshChecklist()
}

async function refreshChecklist() {
  try { ckData.value = (await exitChecklist(ckRow.value.id)).data } catch (e) { /* ignore */ }
}

async function returnAsset(card) {
  try { await exitAssetsReturn(ckRow.value.id, [card.id]); ElMessage.success('已归还（卡片持有人已清空）'); refreshChecklist() } catch (e) { /* ignore */ }
}
async function returnAllAssets() {
  try { await exitAssetsReturn(ckRow.value.id, []); ElMessage.success('资产项完成'); refreshChecklist() } catch (e) { /* ignore */ }
}
async function handoverAccount() {
  try { await exitAccountHandover(ckRow.value.id); ElMessage.success('权限移交确认'); refreshChecklist() } catch (e) { /* ignore */ }
}
async function addItems() {
  if (!itemForm.value.item || !itemForm.value.receiver) { ElMessage.warning('交接项与接收人必填'); return }
  try {
    await exitHandoverItems(ckRow.value.id, itemForm.value.category,
      [{ item: itemForm.value.item, receiver: itemForm.value.receiver }])
    ElMessage.success('已登记')
    itemForm.value = { category: itemForm.value.category, item: '', receiver: '' }
    refreshChecklist()
  } catch (e) { /* ignore */ }
}
async function doSettle() {
  try { await exitSettle(ckRow.value.id, settleForm.value); ElMessage.success('结算已登记'); refreshChecklist(); loadAll() } catch (e) { /* ignore */ }
}

async function issueCert(row) {
  await ElMessageBox.confirm('生成离场凭证将置主档「已离职」并自动禁用系统账号，确认？', '离场凭证',
    { type: 'warning' })
  try {
    const { data } = await exitIssueCert(row.id)
    ElMessage.success('离场凭证已生成：' + data.exitCertNo)
    ckVisible.value = false
    loadAll()
  } catch (e) { /* ignore */ }
}

async function retain(row) {
  const { value } = await ElMessageBox.prompt('挽留意见（非阻断，随审批轨迹留痕）', '挽留', {
    inputValidator: (v) => (v && v.trim()) || '意见必填'
  })
  try { await exitRetainNote(row.id, value); ElMessage.success('挽留意见已留痕'); loadAll() } catch (e) { /* ignore */ }
}

async function waive(row) {
  const { value } = await ElMessageBox.prompt('减员豁免原因（如 已确认线下减员/政策豁免情形）——将记录豁免人与原因', '社保减员豁免', {
    inputValidator: (v) => (v && v.trim()) || '原因必填'
  })
  try { await exitWaiveReduce(row.id, value); ElMessage.success('已豁免（离场凭证放行）'); loadAll(); refreshChecklist() } catch (e) { /* ignore */ }
}

onMounted(loadAll)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.mb8 { margin-bottom: 8px; }
.sec { font-weight: 600; margin: 10px 0 6px; }
.mr4 { margin-right: 4px; }
</style>
