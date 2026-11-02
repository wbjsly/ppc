<template>
  <div class="page gl-page">
    <!-- ================= 8.1.2 凭证录入 ================= -->
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

    <!-- 凭证详情/打印抽屉（录入/审核两页共用） -->
    <VoucherDetailDrawer />
  </div>
</template>

<script setup>
/**
 * 8.1.2 凭证录入（拆分自原总账多 tab 页，design D1）。
 * 科目下拉/草稿列表/详情抽屉见 useGl.js；详情抽屉见 _parts/VoucherDetailDrawer.vue。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  manualCreate, manualVoid,
  templatePage, templateSave, templateDelete, templateApply
} from '@/api/fin/gl'
import { useGl } from './useGl'
import VoucherDetailDrawer from './_parts/VoucherDetailDrawer.vue'

const { accOptions, loadAccounts, manQuery, manRows, loadManual, openDetail } = useGl()

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

onMounted(() => {
  loadAccounts()
  loadManual('DRAFT')
  loadTpls()
})
</script>
