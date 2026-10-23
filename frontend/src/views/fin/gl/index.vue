<template>
  <div class="page gl-page">
    <el-tabs v-model="tab">
      <!-- ================= 8.1.1 科目管理 ================= -->
      <el-tab-pane label="科目管理" name="accounts">
        <el-form inline>
          <el-input v-model="accQuery.keyword" clearable placeholder="编码/名称" style="width:180px" />
          <el-select v-model="accQuery.status" clearable placeholder="状态" style="width:120px">
            <el-option label="启用" value="ACTIVE" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
          <el-button type="primary" @click="loadAccounts">查询</el-button>
          <el-button type="success" @click="accCreateVisible = true">新增科目</el-button>
        </el-form>
        <el-table :data="accRows" border size="small">
          <el-table-column prop="accountCode" label="编码" width="110" />
          <el-table-column prop="accountName" label="名称" min-width="180" show-overflow-tooltip />
          <el-table-column prop="direction" label="方向" width="70">
            <template #default="{ row }">{{ row.direction === 'DR' ? '借' : '贷' }}</template>
          </el-table-column>
          <el-table-column prop="category" label="类别" width="90" />
          <el-table-column prop="auxSet" label="辅助核算" min-width="150">
            <template #default="{ row }">{{ row.auxSet || '—' }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button size="small" :type="row.status === 'ACTIVE' ? 'danger' : 'success'"
                         @click="toggleAccount(row)">
                {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination layout="total, prev, pager, next" :total="accTotal"
                       :page-size="accQuery.size" :current-page="accQuery.current"
                       @current-change="onAccPage" style="margin-top:10px" />
      </el-tab-pane>

      <!-- ================= 8.1.2 凭证录入 ================= -->
      <el-tab-pane label="凭证录入" name="entry">
        <el-form inline>
          <el-input v-model="manQuery.keyword" clearable placeholder="凭证号/摘要" style="width:180px" />
          <el-button type="primary" @click="loadManual('DRAFT')">查询草稿</el-button>
          <el-button type="success" @click="openEntry()">新建凭证</el-button>
          <el-button @click="tplVisible = true">模板管理</el-button>
        </el-form>
        <el-table :data="manRows" border size="small">
          <el-table-column prop="voucherNo" label="凭证号" width="160" />
          <el-table-column prop="voucherDate" label="日期" width="110" />
          <el-table-column prop="period" label="期间" width="80" />
          <el-table-column prop="summary" label="摘要" min-width="180" show-overflow-tooltip />
          <el-table-column prop="totalDr" label="借方合计" width="110" />
          <el-table-column prop="totalCr" label="贷方合计" width="110" />
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button size="small" @click="openDetail(row.id)">明细</el-button>
              <el-button size="small" type="danger" link @click="doVoid(row.id)">作废</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ================= 8.1.3 凭证审核 ================= -->
      <el-tab-pane label="凭证审核" name="audit">
        <el-form inline>
          <el-button type="primary" @click="loadManual('DRAFT')">刷新待审</el-button>
        </el-form>
        <el-table :data="auditRows" border size="small">
          <el-table-column prop="voucherNo" label="凭证号" width="160" />
          <el-table-column prop="voucherDate" label="日期" width="110" />
          <el-table-column prop="createBy" label="制单人" width="110" />
          <el-table-column prop="summary" label="摘要" min-width="180" show-overflow-tooltip />
          <el-table-column prop="totalDr" label="金额" width="110" />
          <el-table-column label="操作" width="220">
            <template #default="{ row }">
              <el-button size="small" @click="openDetail(row.id)">明细</el-button>
              <el-button size="small" type="success" :disabled="row.createBy === currentUser"
                         :title="row.createBy === currentUser ? '制单人与审核人不能为同一人（C-4.6-07）' : ''"
                         @click="doAudit(row.id)">审核</el-button>
              <el-button size="small" type="warning" @click="doReject(row.id)">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ================= 8.1.4 凭证过账 ================= -->
      <el-tab-pane label="凭证过账" name="post">
        <el-form inline>
          <el-button type="primary" @click="loadManual('AUDITED')">刷新待过账</el-button>
          <el-divider direction="vertical" />
          <el-input v-model="balQuery.period" placeholder="YYYYMM" style="width:110px" />
          <el-input v-model="balQuery.accountCode" clearable placeholder="科目编码" style="width:130px" />
          <el-button type="warning" @click="loadBalances">查询余额</el-button>
        </el-form>
        <el-table :data="postRows" border size="small" style="margin-bottom:16px">
          <el-table-column prop="voucherNo" label="凭证号" width="160" />
          <el-table-column prop="voucherDate" label="日期" width="110" />
          <el-table-column prop="auditBy" label="审核人" width="110" />
          <el-table-column prop="summary" label="摘要" min-width="180" show-overflow-tooltip />
          <el-table-column prop="totalDr" label="金额" width="110" />
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <el-button size="small" type="warning" @click="doPost(row.id)">过账</el-button>
            </template>
          </el-table-column>
        </el-table>
        <h4 v-if="balRows.length" style="margin:8px 0">科目余额（{{ balQuery.period }}）</h4>
        <el-table v-if="balRows.length" :data="balRows" border size="small">
          <el-table-column prop="ACCOUNT_CODE" label="科目" width="100" />
          <el-table-column prop="ACCOUNT_NAME" label="名称" min-width="150" />
          <el-table-column prop="AUX_KEY" label="辅助维度" width="150">
            <template #default="{ row }">{{ row.AUX_KEY || '—' }}</template>
          </el-table-column>
          <el-table-column prop="openBalance" label="期初" width="110" />
          <el-table-column prop="DR_TOTAL" label="本期借" width="110" />
          <el-table-column prop="CR_TOTAL" label="本期贷" width="110" />
          <el-table-column prop="closeBalance" label="期末" width="110" />
        </el-table>
      </el-tab-pane>

      <!-- ================= 8.1.5 期末结账 ================= -->
      <el-tab-pane label="期末结账" name="close">
        <el-form inline>
          <el-button type="primary" @click="loadPeriods">刷新</el-button>
          <el-button v-if="curPeriod" type="danger" @click="doChecklist">执行结账检查</el-button>
        </el-form>
        <el-alert v-if="curPeriod" :title="`当前期间 ${curPeriod.period}（${curPeriod.status === 'CLOSED' ? '已关闭' : '开放中'}）`"
                  :type="curPeriod.status === 'CLOSED' ? 'info' : 'success'" :closable="false"
                  style="margin-bottom:12px" />
        <el-table :data="periodRows" border size="small" style="margin-bottom:16px">
          <el-table-column prop="period" label="期间" width="110" />
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'CLOSED' ? 'info' : 'success'" size="small">
                {{ row.status === 'CLOSED' ? '已结账' : '开放' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="closeBy" label="结账人" width="110" />
          <el-table-column prop="closeAt" label="结账时间" width="170" />
        </el-table>
        <template v-if="checkResult">
          <h4 style="margin:8px 0">结账检查清单</h4>
          <el-table :data="checkResult.items" border size="small">
            <el-table-column label="结果" width="80">
              <template #default="{ row }">
                <el-tag :type="row.ok ? 'success' : 'danger'" size="small">{{ row.ok ? '通过' : '失败' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="name" label="检查项" width="280" />
            <el-table-column prop="detail" label="明细" min-width="260" show-overflow-tooltip />
          </el-table>
          <el-button type="danger" style="margin-top:12px" :disabled="!checkResult.allOk"
                     @click="doClose">执行结账（CLOSE）</el-button>
          <span v-if="!checkResult.allOk" style="margin-left:10px;color:#f56c6c">检查项未全绿，不可结账</span>
        </template>
      </el-tab-pane>
    </el-tabs>

    <!-- ============ 新增科目弹窗 ============ -->
    <el-dialog v-model="accCreateVisible" title="新增科目" width="480px">
      <el-form :model="accForm" label-width="90px">
        <el-form-item label="编码"><el-input v-model="accForm.accountCode" placeholder="如 660202" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="accForm.accountName" /></el-form-item>
        <el-form-item label="方向">
          <el-radio-group v-model="accForm.direction">
            <el-radio value="DR">借</el-radio>
            <el-radio value="CR">贷</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="accForm.category" style="width:100%">
            <el-option label="资产" value="ASSET" /><el-option label="负债" value="LIAB" />
            <el-option label="权益" value="EQUITY" /><el-option label="成本" value="COST" />
            <el-option label="损益" value="PROFIT" /><el-option label="现金流" value="CASH" />
          </el-select>
        </el-form-item>
        <el-form-item label="辅助核算">
          <el-select v-model="accAuxList" multiple style="width:100%" placeholder="该科目要求的辅助维度">
            <el-option label="客户 CUST" value="CUST" /><el-option label="供应商 SUPP" value="SUPP" />
            <el-option label="项目 PROJ" value="PROJ" /><el-option label="员工 EMP" value="EMP" />
            <el-option label="部门 DEPT" value="DEPT" /><el-option label="利润中心 PROFIT" value="PROFIT" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="accCreateVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreateAccount">创建</el-button>
      </template>
    </el-dialog>

    <!-- ============ 凭证录入抽屉 ============ -->
    <el-drawer v-model="entryVisible" title="手工凭证录入" size="820px">
      <el-form inline>
        <el-form-item label="日期">
          <el-date-picker v-model="entryForm.voucherDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="模板">
          <el-select v-model="entryTplId" clearable placeholder="一键调用" style="width:200px" @change="applyTpl">
            <el-option v-for="t in tplRows" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-input v-model="entryForm.summary" placeholder="凭证摘要" style="margin-bottom:10px" />
      <el-table :data="entryLines" border size="small">
        <el-table-column label="科目" width="180">
          <template #default="{ row, $index }">
            <el-select v-model="row.accountCode" filterable placeholder="选择科目" style="width:100%"
                       @change="(v) => onLineAccount(v, $index)">
              <el-option v-for="a in accOptions" :key="a.accountCode"
                         :label="`${a.accountCode} ${a.accountName}`" :value="a.accountCode" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="方向" width="90">
          <template #default="{ row }">
            <el-radio-group v-model="row.direction" size="small">
              <el-radio-button value="DR">借</el-radio-button>
              <el-radio-button value="CR">贷</el-radio-button>
            </el-radio-group>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.amount" :min="0" :precision="2" size="small" style="width:110px" />
          </template>
        </el-table-column>
        <el-table-column label="摘要" width="140">
          <template #default="{ row }"><el-input v-model="row.summary" size="small" /></template>
        </el-table-column>
        <!-- 辅助维度：按科目 AUX_SET 动态 -->
        <el-table-column label="辅助维度（按科目标记）" min-width="220">
          <template #default="{ row, $index }">
            <div style="display:flex;gap:4px;flex-wrap:wrap">
              <el-input v-if="auxShown($index,'CUST')" v-model="row.auxCustomer" size="small" placeholder="客户" style="width:80px" />
              <el-input v-if="auxShown($index,'SUPP')" v-model="row.auxSupplier" size="small" placeholder="供应商" style="width:80px" />
              <el-input v-if="auxShown($index,'PROJ')" v-model="row.auxProject" size="small" placeholder="项目" style="width:80px" />
              <el-input v-if="auxShown($index,'EMP')" v-model="row.auxEmployee" size="small" placeholder="员工" style="width:80px" />
              <el-input v-if="auxShown($index,'DEPT')" v-model="row.auxDept" size="small" placeholder="部门" style="width:80px" />
              <el-input v-model="row.costCenter" size="small" placeholder="成本中心" style="width:90px" />
              <span v-if="!hasAnyAux($index)" style="color:#c0c4cc;font-size:12px">该科目无辅助要求</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="" width="50">
          <template #default="{ $index }">
            <el-button type="danger" link @click="entryLines.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button link type="primary" style="margin:8px 0" @click="addLine()">+ 加一行</el-button>
      <!-- 实时平衡条 -->
      <el-alert :type="balanceDiff === 0 ? 'success' : 'warning'" :closable="false" style="margin-top:8px"
                :title="balanceDiff === 0
                  ? `借贷平衡 ✓ 借方合计 ${entryDr}`
                  : `借贷不平：借方 ${entryDr} / 贷方 ${entryCr}，差额 ${balanceDiff}`" />
      <el-button type="primary" style="margin-top:12px" @click="doSaveEntry">保存草稿（DRAFT）</el-button>
    </el-drawer>

    <!-- ============ 凭证详情/打印抽屉 ============ -->
    <el-drawer v-model="detailVisible" title="凭证详情" size="720px">
      <template v-if="detail">
        <div class="print-area">
          <div style="text-align:center;font-size:18px;font-weight:bold;margin-bottom:12px">记 账 凭 证</div>
          <el-descriptions :column="3" border size="small" class="no-print">
            <el-descriptions-item label="凭证号">{{ detail.voucherNo }}</el-descriptions-item>
            <el-descriptions-item label="日期">{{ detail.voucherDate }}</el-descriptions-item>
            <el-descriptions-item label="期间">{{ detail.period }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ detail.status }}</el-descriptions-item>
            <el-descriptions-item label="制单人">{{ detail.createBy }}</el-descriptions-item>
            <el-descriptions-item label="审核人">{{ detail.auditBy || '—' }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="detail.lines || []" border size="small" style="margin-top:10px">
            <el-table-column type="index" label="行" width="50" />
            <el-table-column prop="accountCode" label="科目" width="90" />
            <el-table-column prop="accountName" label="科目名称" min-width="140" />
            <el-table-column prop="direction" label="方向" width="60" />
            <el-table-column prop="amount" label="金额" width="110" />
            <el-table-column prop="summary" label="摘要" min-width="120" />
          </el-table>
          <div style="margin-top:10px;display:flex;justify-content:space-between">
            <span>合计：借 {{ detail.totalDr }} / 贷 {{ detail.totalCr }}</span>
            <span>制单：{{ detail.createBy }}　　审核：{{ detail.auditBy || '________' }}</span>
          </div>
        </div>
        <el-button type="primary" style="margin-top:14px" @click="printVoucher">打印（另存 PDF）</el-button>
        <template v-if="detail.status === 'DRAFT' && detail.sourceType === 'MANUAL'">
          <el-button type="success" style="margin-top:14px" @click="doAudit(detail.id)">审核通过</el-button>
        </template>
        <template v-if="detail.status === 'AUDITED'">
          <el-button type="warning" style="margin-top:14px" @click="doPost(detail.id)">过账</el-button>
        </template>
      </template>
    </el-drawer>

    <!-- ============ 模板管理弹窗 ============ -->
    <el-dialog v-model="tplVisible" title="凭证模板" width="560px">
      <el-form inline>
        <el-input v-model="tplForm.name" placeholder="模板名称（如：月末结转损益）" style="width:260px" />
        <el-button type="primary" @click="saveTplFromCurrent">从当前录入页快照保存</el-button>
      </el-form>
      <el-table :data="tplRows" border size="small">
        <el-table-column prop="name" label="模板名" min-width="160" />
        <el-table-column prop="voucherType" label="类型" width="100" />
        <el-table-column prop="createBy" label="创建人" width="100" />
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button size="small" @click="tplVisible = false; openEntry(); entryTplId = row.id; applyTpl(row.id)">调用</el-button>
            <el-button size="small" type="danger" link @click="doDeleteTpl(row.id)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  accountPage, accountCreate, accountSetStatus,
  manualPage, manualDetail, manualCreate, manualAudit, manualReject, manualPost, manualVoid,
  templatePage, templateSave, templateDelete, templateApply,
  balanceQuery, periodCurrent, periodList, periodChecklist, periodClose
} from '@/api/fin/gl'

const route = useRoute()
const tab = ref(route.meta?.tab || 'accounts')
const currentUser = ref('')   // 由登录态推导（C-4.6-07 前端置灰提示；后端硬卡控兜底）
try {
  const raw = localStorage.getItem('user')
  currentUser.value = raw ? (JSON.parse(raw).username || '') : ''
} catch (e) { currentUser.value = '' }

// ---------- 8.1.1 科目 ----------
const accQuery = reactive({ current: 1, size: 50, keyword: '', status: '' })
const accRows = ref([])
const accTotal = ref(0)
const accOptions = ref([])
const accCreateVisible = ref(false)
const accAuxList = ref([])
const accForm = reactive({ accountCode: '', accountName: '', direction: 'DR', category: 'ASSET' })

async function loadAccounts() {
  const { data } = await accountPage({ ...accQuery, keyword: accQuery.keyword || undefined, status: accQuery.status || undefined })
  accRows.value = data.records || []
  accTotal.value = Number(data.total || 0)
  accOptions.value = accRows.value.filter((a) => a.status === 'ACTIVE')
}
const onAccPage = (p) => { accQuery.current = p; loadAccounts() }
async function doCreateAccount() {
  await accountCreate({ ...accForm, auxSet: accAuxList.value.join(',') || null })
  ElMessage.success('科目已创建')
  accCreateVisible = false
  loadAccounts()
}
async function toggleAccount(row) {
  const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  await accountSetStatus(row.accountCode, next)
  ElMessage.success(`已${next === 'ACTIVE' ? '启用' : '停用'}`)
  loadAccounts()
}

// ---------- 手工凭证 ----------
const manQuery = reactive({ keyword: '' })
const manRows = ref([])
const auditRows = ref([])
const postRows = ref([])
async function loadManual(status) {
  const { data } = await manualPage({ current: 1, size: 50, status, keyword: manQuery.keyword || undefined })
  const rows = data.records || []
  if (status === 'DRAFT') { manRows.value = rows; auditRows.value = rows }
  else if (status === 'AUDITED') { postRows.value = rows }
}

// 录入
const entryVisible = ref(false)
const entryForm = reactive({ voucherDate: new Date().toISOString().slice(0, 10), summary: '' })
const entryLines = ref([])
const entryTplId = ref('')
function openEntry() {
  entryForm.summary = ''
  entryLines.value = [blankLine(), blankLine()]
  entryTplId.value = ''
  entryVisible.value = true
  if (!accOptions.value.length) loadAccounts()
}
function blankLine() {
  return { accountCode: '', direction: 'DR', amount: 0, summary: '', costCenter: '',
    auxCustomer: '', auxSupplier: '', auxProject: '', auxEmployee: '', auxDept: '' }
}
const addLine = () => entryLines.value.push(blankLine())
function auxReq(code) {
  const a = accOptions.value.find((x) => x.accountCode === code)
  return a?.auxSet || ''
}
const auxShown = (i, dim) => auxReq(entryLines.value[i]?.accountCode).includes(dim)
const hasAnyAux = (i) => auxShown(i, 'CUST') || auxShown(i, 'SUPP') || auxShown(i, 'PROJ')
  || auxShown(i, 'EMP') || auxShown(i, 'DEPT')
const entryDr = computed(() => entryLines.value.filter((l) => l.direction === 'DR')
  .reduce((s, l) => s + (l.amount || 0), 0).toFixed(2))
const entryCr = computed(() => entryLines.value.filter((l) => l.direction === 'CR')
  .reduce((s, l) => s + (l.amount || 0), 0).toFixed(2))
const balanceDiff = computed(() => (Number(entryDr.value) - Number(entryCr.value)).toFixed(2))

async function doSaveEntry() {
  await manualCreate({
    order: { voucherDate: entryForm.voucherDate, summary: entryForm.summary, voucherType: 'MANUAL' },
    lines: entryLines.value.filter((l) => l.accountCode)
  })
  ElMessage.success('已保存 DRAFT')
  entryVisible.value = false
  loadManual('DRAFT')
}
async function doVoid(id) {
  await ElMessageBox.confirm('作废该草稿凭证？', '作废', { type: 'warning' })
  await manualVoid(id)
  ElMessage.success('已作废')
  loadManual('DRAFT')
}
async function doAudit(id) {
  await manualAudit(id)
  ElMessage.success('审核通过（AUDITED）')
  detailVisible.value = false
  loadManual('DRAFT'); loadManual('AUDITED')
}
async function doReject(id) {
  const { value } = await ElMessageBox.prompt('驳回原因', '驳回')
  await manualReject(id, value)
  ElMessage.success('已驳回留痕')
  loadManual('DRAFT')
}
async function doPost(id) {
  await manualPost(id)
  ElMessage.success('已过账（余额已回写）')
  detailVisible.value = false
  loadManual('AUDITED')
}

// 详情/打印
const detailVisible = ref(false)
const detail = ref(null)
async function openDetail(id) {
  const { data } = await manualDetail(id)
  detail.value = data
  detailVisible.value = true
}
function printVoucher() { window.print() }

// 模板
const tplVisible = ref(false)
const tplForm = reactive({ name: '' })
const tplRows = ref([])
async function loadTpls() {
  const { data } = await templatePage({ current: 1, size: 50 })
  tplRows.value = data.records || []
}
async function saveTplFromCurrent() {
  if (!entryLines.value.filter((l) => l.accountCode).length) {
    ElMessage.warning('录入页无有效行可快照')
    return
  }
  const rows = entryLines.value.filter((l) => l.accountCode).map((l) => ({
    accountCode: l.accountCode, direction: l.direction, summary: l.summary,
    costCenter: l.costCenter, auxCustomer: l.auxCustomer, auxSupplier: l.auxSupplier,
    auxProject: l.auxProject, auxEmployee: l.auxEmployee, auxDept: l.auxDept
  }))
  await templateSave({ name: tplForm.name, voucherType: 'MANUAL', rowsJson: JSON.stringify(rows) })
  ElMessage.success('模板已保存')
  loadTpls()
}
async function applyTpl(tid) {
  if (!tid) return
  const { data } = await templateApply(tid)
  const rows = (data.rows || []).map((r) => ({
    accountCode: r.accountCode || '', direction: r.direction || 'DR', amount: 0,
    summary: r.summary || '', costCenter: r.costCenter || '',
    auxCustomer: r.auxCustomer || '', auxSupplier: r.auxSupplier || '',
    auxProject: r.auxProject || '', auxEmployee: r.auxEmployee || '', auxDept: r.auxDept || '',
    _blocked: !!r.blocked
  }))
  entryLines.value = rows
  if (rows.some((r) => r._blocked)) {
    ElMessage.warning('模板中含已停用科目行（已标注），请调整后再保存')
  } else {
    ElMessage.success(`模板「${data.name}」已调用（金额待填）`)
  }
}
async function doDeleteTpl(id) {
  await templateDelete(id)
  ElMessage.success('已删除')
  loadTpls()
}

// ---------- 8.1.4 余额 ----------
const balQuery = reactive({ period: new Date().toISOString().slice(0, 7).replace('-', ''), accountCode: '' })
const balRows = ref([])
async function loadBalances() {
  const { data } = await balanceQuery({
    period: balQuery.period, accountCode: balQuery.accountCode || undefined
  })
  balRows.value = data || []
}

// ---------- 8.1.5 结账 ----------
const curPeriod = ref(null)
const periodRows = ref([])
const checkResult = ref(null)
async function loadPeriods() {
  const c = await periodCurrent()
  curPeriod.value = c.data
  const { data } = await periodList()
  periodRows.value = data || []
}
async function doChecklist() {
  const { data } = await periodChecklist(curPeriod.value.period)
  checkResult.value = data
}
async function doClose() {
  await ElMessageBox.confirm(`确认结账 ${curPeriod.value.period}？结账后该期间禁止新过账。`, '期末结账', { type: 'warning' })
  const { data } = await periodClose(curPeriod.value.period)
  ElMessage.success(`期间 ${data.period} 已结账`)
  checkResult.value = null
  loadPeriods()
}

onMounted(() => {
  loadAccounts()
  loadManual('DRAFT')
  loadTpls()
  loadPeriods()
})
</script>

<style scoped>
@media print {
  .no-print, .el-drawer__header, .el-drawer__footer { display: none !important; }
  .print-area { padding: 20px; }
  body * { visibility: hidden; }
  .print-area, .print-area * { visibility: visible; }
  .print-area { position: absolute; left: 0; top: 0; width: 100%; }
}
</style>
