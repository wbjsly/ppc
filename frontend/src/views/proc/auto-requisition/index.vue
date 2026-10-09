<template>
  <div class="auto-requisition">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">自动请购（MRP）</span>
          <el-radio-group v-model="tab">
            <el-radio-button v-if="isAdmin" value="mrp">模拟净算</el-radio-button>
            <el-radio-button value="list">请购单</el-radio-button>
          </el-radio-group>
        </div>
      </template>

      <!-- ① 模拟净算 -->
      <template v-if="tab === 'mrp'">
        <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
          title="模拟 MRP 净算（FR-4.5-2-3）：NetReq = 需求 − 现有库存 − 在制 − 在途；真实需求/供给源（4.5/WMS）未落地，输入为模拟值。"
          description="列序：物料编码,需求日期,需求量,现有库存,在制,在途,来源类型,来源单号,建议供应商编码（前 6 列必需，来源类型缺省 SIMULATED）" />
        <div class="toolbar">
          <el-button @click="downloadMrpTemplate">下载模板 CSV</el-button>
          <el-upload :auto-upload="false" :show-file-list="false" accept=".csv,text/csv" :on-change="onFileChange">
            <el-button type="primary" plain>上传 .csv 文件</el-button>
          </el-upload>
          <span class="tip">或直接粘贴表格文本（Excel 复制，Tab/逗号分隔）</span>
        </div>
        <el-input v-model="pasteText" type="textarea" :rows="5"
          placeholder="物料编码,需求日期,需求量,现有库存,在制,在途,来源类型,来源单号,建议供应商编码" @input="dirty = true" />
        <div class="toolbar" style="margin-top: 10px;">
          <el-button type="primary" :loading="loading" @click="runPreview">解析并净算预检</el-button>
          <el-button v-if="previewRows.length" type="danger" :loading="saving"
                     :disabled="!previewRows.some(r => r.generatable)" @click="doGenerate">
            {{ genBlocked ? '无可生成行' : `生成 PR（${previewRows.filter(r => r.generatable).length} 行）` }}
          </el-button>
          <span v-if="parsedInfo" class="tip">{{ parsedInfo }}</span>
        </div>
        <el-table v-if="previewRows.length" :data="previewRows" size="small" border
                  :row-class-name="rowClass" style="margin-top: 12px;">
          <el-table-column prop="rowNo" label="行号" width="60" />
          <el-table-column prop="itemCode" label="物料" width="150" />
          <el-table-column prop="reqDate" label="需求日期" width="110" />
          <el-table-column prop="netReq" label="净需求" width="90" align="right" />
          <el-table-column label="标记" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="flagTag(row.flag)">{{ flagName(row.flag) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="校验" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.valid && row.generatable ? 'success' : (row.valid ? 'warning' : 'danger')" size="small">
                {{ row.valid && row.generatable ? '可生成' : (row.valid ? '跳过' : '失败') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="240">
            <template #default="{ row }">{{ row.reason || '—' }}</template>
          </el-table-column>
        </el-table>
        <template v-if="report">
          <el-divider content-position="left">生成结果（{{ report.prNo || '未生成' }}）</el-divider>
          <el-table :data="report.details" size="small" border>
            <el-table-column prop="rowNo" label="行号" width="60" />
            <el-table-column prop="itemCode" label="物料" width="150" />
            <el-table-column label="结果" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" size="small">
                  {{ row.result === 'SUCCESS' ? '成功' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="原因" min-width="260">
              <template #default="{ row }">
                <span :style="row.result === 'FAILED' ? 'color:#f56c6c' : ''">{{ row.reason || '—' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </template>

      <!-- ② PR 列表 -->
      <template v-else>
        <div class="toolbar">
          <el-select v-model="listQuery.status" placeholder="状态" clearable style="width: 150px;" @change="loadList(1)">
            <el-option v-for="(n, v) in statusNames" :key="v" :label="n" :value="v" />
          </el-select>
          <el-select v-model="listQuery.sourceType" placeholder="来源" clearable style="width: 110px;" @change="loadList(1)">
            <el-option label="MRP" value="MRP" />
            <el-option label="手工" value="MANUAL" />
          </el-select>
          <el-input v-model="listQuery.keyword" placeholder="PR 单号" clearable style="width: 180px;" @keyup.enter="loadList(1)" />
          <el-button type="primary" @click="loadList(1)">查询</el-button>
        </div>
        <el-table :data="listRows" v-loading="loading" stripe>
          <el-table-column prop="prNo" label="PR 单号" width="170">
            <template #default="{ row }"><b>{{ row.prNo }}</b></template>
          </el-table-column>
          <el-table-column prop="sourceType" label="来源" width="80" />
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="approvalAmount" label="判级金额" width="110" align="right" />
          <el-table-column prop="lineCount" label="行数" width="60" align="center" />
          <el-table-column label="催办/升级" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.remindFlag === '1'" type="warning" size="small">催办</el-tag>
              <el-tag v-if="row.escalateFlag === '1'" type="danger" size="small" style="margin-left:4px;">升级</el-tag>
              <span v-if="row.remindFlag !== '1' && row.escalateFlag !== '1'" style="color:#c0c4cc;">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="createBy" label="创建人" width="110" />
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination layout="total, prev, pager, next" style="margin-top: 12px; justify-content: flex-end;"
          :total="listTotal" :page-size="listQuery.size" :current-change="loadList" />
      </template>
    </el-card>

    <!-- PR 详情抽屉 -->
    <el-drawer v-model="dtVisible" :title="`请购单：${dt.pr.prNo || ''}`" size="80%">
      <el-descriptions :column="4" border size="small" style="margin-bottom: 10px;">
        <el-descriptions-item label="状态">
          <el-tag :type="statusTag(dt.pr.status)" size="small">{{ statusName(dt.pr.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="来源">{{ dt.pr.sourceType }}</el-descriptions-item>
        <el-descriptions-item label="判级金额">{{ dt.pr.approvalAmount ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="催办/升级">
          <el-tag v-if="dt.pr.remindFlag === '1'" type="warning" size="small">催办</el-tag>
          <el-tag v-if="dt.pr.escalateFlag === '1'" type="danger" size="small">升级</el-tag>
          <span v-if="dt.pr.remindFlag !== '1' && dt.pr.escalateFlag !== '1'">—</span>
        </el-descriptions-item>
        <el-descriptions-item label="需求理由" :span="3">{{ dt.pr.reqReason || '—' }}</el-descriptions-item>
        <el-descriptions-item label="驳回原因">{{ dt.pr.rejectReason || '—' }}</el-descriptions-item>
      </el-descriptions>

      <div class="toolbar" v-if="isAdmin">
        <el-button v-if="dt.pr.status === 'PENDING_CONFIRM'" type="primary" @click="doConfirm">整单确认（80% 校验）</el-button>
        <el-button v-if="dt.pr.status === 'CONFIRMED'" type="primary" @click="doSubmit">提交审批</el-button>
        <el-button v-if="dt.pr.status === 'APPROVED'" type="warning" @click="doRoute">流转询价</el-button>
        <el-button v-if="!['CLOSED','PENDING_RFQ'].includes(dt.pr.status)" type="danger" plain @click="doClosePr">整单关闭</el-button>
      </div>

      <el-table :data="dt.lines" size="small" border>
        <el-table-column prop="lineNo" label="#" width="45" />
        <el-table-column prop="itemCode" label="物料" width="140" />
        <el-table-column label="数量/建议" width="130" align="right">
          <template #default="{ row }">
            {{ row.qty }}<span v-if="row.mrpSuggestedQty" style="color:#909399;"> / {{ row.mrpSuggestedQty }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="reqDate" label="需求日期" width="105" />
        <el-table-column prop="estUnitPrice" label="预估单价" width="90" align="right" />
        <el-table-column label="逾期" width="70" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.overdueFlag === '1'" type="danger" size="small">异常</el-tag>
            <span v-else style="color:#c0c4cc;">—</span>
          </template>
        </el-table-column>
        <el-table-column label="建议供应商" width="180">
          <template #default="{ row }">
            <el-select v-if="isAdmin && ['CONFIRMED','APPROVED','PENDING_RFQ'].includes(dt.pr.status)"
                       :model-value="row.suggestedSupplierId" size="small" clearable filterable
                       placeholder="选择供应商" style="width: 160px;"
                       @change="(v) => setSupplier(row, v)">
              <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
            </el-select>
            <span v-else>{{ row.supplierName || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="行状态/下达" width="120">
          <template #default="{ row }">
            <el-tag :type="row.lineStatus === 'OPEN' ? 'success' : 'info'" size="small">
              {{ row.lineStatus === 'OPEN' ? '未关闭' : '已关闭' }}
            </el-tag>
            <span style="font-size:12px;color:#909399;"> {{ row.allocQty }}/{{ row.qty }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <template v-if="isAdmin">
              <el-button v-if="['PENDING_CONFIRM','PENDING_MODIFY'].includes(dt.pr.status) && row.lineStatus === 'OPEN'"
                         link type="primary" @click="editLine(row)">行编辑</el-button>
              <el-button v-if="row.lineStatus === 'OPEN'" link type="primary" @click="editDelivery(row)">交付计划</el-button>
              <el-button v-if="row.lineStatus === 'OPEN'" link type="warning" @click="allocLine(row)">PO回写</el-button>
              <el-button v-if="row.lineStatus === 'OPEN'" link type="danger" @click="closeLine(row)">关闭行</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 行编辑弹窗 -->
    <el-dialog v-model="lineDlg" title="行编辑（确认前保存）" width="520px">
      <el-form label-width="110px">
        <el-form-item label="数量">
          <el-input-number v-model="lineForm.qty" :min="0.0001" :precision="4" style="width: 100%;" />
          <div v-if="lineForm.mrpSuggestedQty" class="tip">
            MRP 建议 {{ lineForm.mrpSuggestedQty }}，80% 下限 {{ (lineForm.mrpSuggestedQty * 0.8).toFixed(4) }}（BR-4.2-08）
          </div>
        </el-form-item>
        <el-form-item label="需求日期">
          <el-date-picker v-model="lineForm.reqDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="调减理由">
          <el-input v-model="lineForm.reduceReason" maxlength="255" placeholder="确认量 < 80% 建议量时必填" />
        </el-form-item>
        <el-form-item label="计划员复核">
          <el-input v-model="lineForm.reviewer" maxlength="64" placeholder="复核人（与调减理由同时必填）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="lineDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveLine">保存</el-button>
      </template>
    </el-dialog>

    <!-- 交付计划弹窗 -->
    <el-dialog v-model="delDlg" title="交付计划行（Σ ≤ 行需求量）" width="560px">
      <el-table :data="delRows" size="small">
        <el-table-column label="交付日期" width="200">
          <template #default="{ row }">
            <el-date-picker v-model="row.deliveryDate" type="date" value-format="YYYY-MM-DD" size="small" style="width: 170px;" />
          </template>
        </el-table-column>
        <el-table-column label="数量" width="160">
          <template #default="{ row }">
            <el-input-number v-model="row.qty" :min="0.0001" :precision="4" size="small" style="width: 140px;" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="{ $index }">
            <el-button link type="danger" @click="delRows.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button link type="primary" style="margin-top: 8px;" @click="delRows.push({ deliveryDate: '', qty: 1 })">+ 增加交付行</el-button>
      <div class="tip">合计 {{ delSum }} / 行需求 {{ delLineQty }}</div>
      <template #footer>
        <el-button @click="delDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveDelivery">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getRequisitionPageApi, getRequisitionDetailApi, confirmPrApi, setLineSupplierApi,
  routePrApi, closePrApi, closeLineApi, saveDeliveryLinesApi, allocationApi,
  updateLinesApi, mrpPreviewApi, mrpGenerateApi
} from '@/api/proc/requisition'
import { submitApprovalApi } from '@/api/proc/approval'
import { getSupplierPageApi } from '@/api/mdm/supplier-admission'

const userStore = useUserStore()
const isAdmin = computed(() => (userStore.userInfo?.roles || []).includes('ROLE_ADMIN'))
const tab = ref('mrp')
const loading = ref(false)
const saving = ref(false)

const statusNames = {
  PENDING_CONFIRM: '待确认', PENDING_BUDGET: '待预算确认', PENDING_APPROVAL: '待审批',
  PENDING_MODIFY: '已驳回', CONFIRMED: '已确认', APPROVING: '审批中',
  APPROVED: '已批准', PENDING_RFQ: '待询价', CLOSED: '已关闭'
}
function statusName(s) { return statusNames[s] || s }
function statusTag(s) {
  return { PENDING_CONFIRM: 'warning', PENDING_BUDGET: 'danger', PENDING_APPROVAL: 'primary',
    PENDING_MODIFY: 'danger', CONFIRMED: 'success', APPROVING: 'primary',
    APPROVED: 'success', PENDING_RFQ: 'info', CLOSED: 'info' }[s]
}
function flagName(f) { return { OK: '正常', OVER_SUPPLY: '过量供给', OVERDUE: '逾期需求' }[f] || f }
function flagTag(f) { return { OK: 'success', OVER_SUPPLY: 'info', OVERDUE: 'danger' }[f] }

// ---------- 模拟净算 ----------
const pasteText = ref('')
const previewRows = ref([])
const parsedInfo = ref('')
const report = ref(null)
const dirty = ref(false)

function parseMrpCsv(text) {
  if (!text || !text.trim()) return []
  const clean = text.replace(/^﻿/, '').replace(/\r\n?/g, '\n')
  const lines = clean.split('\n')
  const sep = lines.find(l => l.trim() && !l.trim().startsWith('#'))?.includes('\t') ? '\t' : ','
  const rows = []
  let rowNo = 1
  for (const line of lines) {
    if (!line.trim() || line.trim().startsWith('#')) { rowNo++; continue }
    const c = line.split(sep).map(x => x.trim())
    if (/^(物料编码|itemCode)/i.test(c[0] || '')) { rowNo++; continue }
    if (c.length < 6) {
      rows.push({ rowNo, _short: true, itemCode: c[0] || '' })
      rowNo++; continue
    }
    rows.push({
      rowNo, itemCode: (c[0] || '').toUpperCase(), reqDate: c[1] || '',
      demand: c[2] || '', onHand: c[3] || '', inProcess: c[4] || '', inTransit: c[5] || '',
      sourceEnum: c[6] || '', sourceDocNo: c[7] || '', supplierCode: c[8] || ''
    })
    rowNo++
  }
  return rows
}

function downloadMrpTemplate() {
  const header = '物料编码,需求日期,需求量,现有库存,在制,在途,来源类型,来源单号,建议供应商编码'
  const comments = [
    '# 模拟 MRP 净算输入模板（FR-4.5-2-3：NetReq = 需求 − 库存 − 在制 − 在途）',
    '# 前 6 列必需；来源类型：WO/PLAN_ORDER/SO/SAFETY_STOCK/SIMULATED（缺省 SIMULATED）',
    '# 建议供应商编码可空；需求日期为历史日期将标记逾期需求'
  ]
  const sample = 'RM0001000001,2026-11-01,100,30,10,20,PLAN_ORDER,MO-2026-001,'
  const csv = '﻿' + [header, ...comments, sample].join('\r\n') + '\r\n'
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = '模拟MRP净算输入模板.csv'
  a.click()
  URL.revokeObjectURL(a.href)
}

function onFileChange(file) {
  const reader = new FileReader()
  reader.onload = () => { pasteText.value = String(reader.result || ''); dirty.value = true }
  reader.readAsText(file.raw, 'utf-8')
}

const genBlocked = computed(() => !previewRows.value.some(r => r.generatable))
function rowClass({ row }) { return row.valid && row.generatable ? '' : 'row-invalid' }

async function runPreview() {
  const rows = parseMrpCsv(pasteText.value)
  if (!rows.length) { ElMessage.warning('未解析到任何数据行'); return }
  loading.value = true
  try {
    const shortRows = rows.filter(r => r._short)
      .map(r => ({ ...r, valid: false, generatable: false, flag: 'OK', reason: '列数不足（前 6 列必需）' }))
    const good = rows.filter(r => !r._short)
    let goodResults = []
    if (good.length) {
      const res = await mrpPreviewApi(good)
      goodResults = res.data.results
    }
    previewRows.value = [...shortRows, ...goodResults].sort((a, b) => a.rowNo - b.rowNo)
    parsedInfo.value = `解析 ${rows.length} 行；失败 ${previewRows.value.filter(r => !r.valid).length} 行，可生成 ${previewRows.value.filter(r => r.generatable).length} 行`
    report.value = null
    dirty.value = false
  } finally {
    loading.value = false
  }
}

async function doGenerate() {
  if (dirty.value) { ElMessage.warning('输入已修改，请先重新净算预检'); return }
  saving.value = true
  try {
    const rows = previewRows.value.filter(r => !r._short).map(r => ({
      rowNo: r.rowNo, itemCode: r.itemCode, reqDate: r.reqDate,
      demand: r.demand, onHand: r.onHand, inProcess: r.inProcess, inTransit: r.inTransit,
      sourceEnum: r.sourceEnum, sourceDocNo: r.sourceDocNo,
      supplierCode: r.supplierCode, estUnitPrice: r.estUnitPrice
    }))
    const res = await mrpGenerateApi(rows)
    report.value = res.data
    if (res.data.succeeded > 0) {
      ElMessage.success(`已生成 ${res.data.prNo}（待确认），成功 ${res.data.succeeded} 行`)
      loadList(1)
    } else {
      ElMessage.error('无成功行，未生成 PR')
    }
  } finally {
    saving.value = false
  }
}

// ---------- PR 列表与详情 ----------
const listQuery = ref({ status: '', sourceType: '', keyword: '', current: 1, size: 10 })
const listRows = ref([])
const listTotal = ref(0)
const dtVisible = ref(false)
const dt = ref({ pr: {}, lines: [] })
const suppliers = ref([])

async function loadList(page) {
  if (page) listQuery.value.current = page
  loading.value = true
  try {
    const res = await getRequisitionPageApi(listQuery.value)
    listRows.value = res.data.records
    listTotal.value = res.data.total
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  const res = await getRequisitionDetailApi(row.id)
  dt.value = res.data
  dtVisible.value = true
}

async function loadSuppliers() {
  const res = await getSupplierPageApi({ current: 1, size: 200 })
  suppliers.value = res.data.records
}

async function doConfirm() {
  try {
    await ElMessageBox.confirm('整单确认将执行 80% 卡控（BR-4.2-08），低于下限须行编辑填调减理由+复核', '确认', { type: 'warning' })
  } catch (e) { return }
  try {
    await confirmPrApi(dt.value.pr.id)
    ElMessage.success('已确认')
    openDetail({ id: dt.value.pr.id })
    loadList()
  } catch (e) {
    ElMessage.error(e?.message || '确认失败')
  }
}

async function doSubmit() {
  try {
    await submitApprovalApi(dt.value.pr.id)
    ElMessage.success('已提交审批（2.1.4 可见待办）')
    openDetail({ id: dt.value.pr.id })
    loadList()
  } catch (e) {
    ElMessage.error(e?.message || '提交失败')
  }
}

async function doRoute() {
  try {
    await routePrApi(dt.value.pr.id)
    ElMessage.success('已流转至待询价')
    openDetail({ id: dt.value.pr.id })
    loadList()
  } catch (e) {
    ElMessage.error(e?.message || '流转失败')
  }
}

async function doClosePr() {
  let reason
  try {
    reason = await ElMessageBox.prompt('整单关闭原因（必填，≥2 字）', '关闭确认', { type: 'warning' })
  } catch (e) { return }
  try {
    await closePrApi(dt.value.pr.id, reason.value)
    ElMessage.success('已关闭')
    openDetail({ id: dt.value.pr.id })
    loadList()
  } catch (e) {
    ElMessage.error(e?.message || '关闭失败')
  }
}

async function setSupplier(line, supplierId) {
  try {
    await setLineSupplierApi(line.id, supplierId || '')
    line.suggestedSupplierId = supplierId
    ElMessage.success('供应商已更新')
  } catch (e) {
    ElMessage.error(e?.message || '更新失败')
  }
}

// ---------- 行编辑 ----------
const lineDlg = ref(false)
const lineForm = ref({})

function editLine(row) {
  lineForm.value = { ...row }
  lineDlg.value = true
}
async function saveLine() {
  saving.value = true
  try {
    await updateLinesApi(dt.value.pr.id, [{
      lineId: lineForm.value.id, qty: lineForm.value.qty, reqDate: lineForm.value.reqDate,
      reduceReason: lineForm.value.reduceReason, reviewer: lineForm.value.reviewer
    }])
    ElMessage.success('行已保存')
    lineDlg.value = false
    openDetail({ id: dt.value.pr.id })
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

// ---------- 交付计划 ----------
const delDlg = ref(false)
const delRows = ref([])
const delLineQty = ref(0)
const delLine = ref(null)
const delSum = computed(() => delRows.value.reduce((s, r) => s + (Number(r.qty) || 0), 0))

async function editDelivery(row) {
  delLine.value = row
  delLineQty.value = row.qty
  const full = await getRequisitionDetailApi(dt.value.pr.id)
  const line = full.data.lines.find(l => l.id === row.id)
  delRows.value = (line.deliveryLines || []).map(d => ({ ...d }))
  delDlg.value = true
}
async function saveDelivery() {
  saving.value = true
  try {
    await saveDeliveryLinesApi(delLine.value.id, delRows.value)
    ElMessage.success('交付计划已保存')
    delDlg.value = false
    openDetail({ id: dt.value.pr.id })
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

// ---------- PO 回写（桩）/ 行关闭 ----------
async function allocLine(row) {
  let qty
  try {
    const r = await ElMessageBox.prompt(`PO 下达回写（桩）：行需求 ${row.qty}，已下达 ${row.allocQty}`, '回写数量', {
      type: 'info', inputValue: String(row.qty)
    })
    qty = r.value
  } catch (e) { return }
  try {
    const res = await allocationApi(row.id, qty)
    ElMessage.success(`回写成功：${res.data.allocQty}/${row.qty}，行状态 ${res.data.lineStatus}`)
    openDetail({ id: dt.value.pr.id })
  } catch (e) {
    ElMessage.error(e?.message || '回写失败')
  }
}

async function closeLine(row) {
  let reason
  try {
    reason = await ElMessageBox.prompt('行关闭原因（必填）', '关闭行', { type: 'warning' })
  } catch (e) { return }
  try {
    await closeLineApi(row.id, reason.value)
    ElMessage.success('行已关闭')
    openDetail({ id: dt.value.pr.id })
    loadList()
  } catch (e) {
    ElMessage.error(e?.message || '关闭失败')
  }
}

onMounted(() => {
  // 净算预检/生成为 POST（ADMIN）→ 非管理员默认且仅可见请购单列表
  if (!isAdmin.value) tab.value = 'list'
  loadList(1)
  loadSuppliers()
})
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; flex-wrap: wrap; }
.tip { font-size: 12px; color: #909399; }
:deep(.row-invalid) { background: #fef0f0; }
</style>
