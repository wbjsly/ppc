<template>
  <div class="app-container">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="采购订单（2.3.1~2.3.4，FR-4.2-3-1/2 / FR-4.2-9）"
      description="三入口：从协议（带出锁定价、余量校验 S-4.2-03）/ RFQ 中选 / 手工。状态机 DRAFT→APPROVING→APPROVED(已下达)→CLOSED；三档分级审批（FR-4.2-3-2）；变更走版本快照与分级审批（BR-4.2-03/04/42）。绩效与库存水位模块未上线处显示占位（偏差 D3）。" />

    <el-tabs v-model="tab">
      <!-- ================= 2.3.1 订单创建 ================= -->
      <el-tab-pane label="订单" name="orders">
        <div class="filter-bar">
          <el-select v-model="filters.status" placeholder="状态" clearable style="width: 150px;" @change="loadOrders">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="待审批" value="APPROVING" />
            <el-option label="已批准" value="APPROVED" />
            <el-option label="已关闭" value="CLOSED" />
          </el-select>
          <el-select v-model="filters.source" placeholder="来源" clearable style="width: 150px;" @change="loadOrders">
            <el-option label="协议" value="AGREEMENT" />
            <el-option label="RFQ 中选" value="RFQ" />
            <el-option label="手工" value="MANUAL" />
          </el-select>
          <el-input v-model="filters.keyword" placeholder="PO号/供应商/PR号" clearable style="width: 220px;" @keyup.enter="loadOrders" />
          <el-button type="primary" @click="loadOrders">查询</el-button>
          <el-button type="success" @click="openCreate">创建订单</el-button>
        </div>

        <el-table :data="orders" v-loading="loading" stripe>
          <el-table-column prop="poNo" label="订单编号" width="160" />
          <el-table-column label="来源" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="{ AGREEMENT: 'success', RFQ: 'warning', MANUAL: 'info' }[row.source]">
                {{ { AGREEMENT: '协议', RFQ: 'RFQ', MANUAL: '手工' }[row.source] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="supplierName" label="供应商" min-width="140" show-overflow-tooltip />
          <el-table-column prop="totalAmt" label="总金额" width="110" align="right" />
          <el-table-column prop="poType" label="类型" width="80" align="center">
            <template #default="{ row }">{{ row.poType === 'SPECIAL' ? '特殊' : '常规' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="{ DRAFT: 'info', APPROVING: 'warning', APPROVED: 'success', CLOSED: 'danger' }[row.status]">
                {{ { DRAFT: '草稿', APPROVING: '待审批', APPROVED: '已批准', CLOSED: '已关闭' }[row.status] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="currVersion" label="版本" width="60" align="center" />
          <el-table-column prop="createDate" label="创建时间" width="160" />
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button link type="primary" :disabled="row.status !== 'DRAFT'" @click="doSubmit(row)">提交</el-button>
              <el-button link type="danger" :disabled="row.status === 'CLOSED' || row.status === 'APPROVING'"
                @click="doClose(row)">关闭</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination background layout="total, prev, pager, next" :total="total"
          v-model:current-page="filters.current" :page-size="filters.size" @current-change="loadOrders" />
      </el-tab-pane>

      <!-- ================= 2.3.2 订单审批 ================= -->
      <el-tab-pane label="审批待办" name="approval">
        <div class="filter-bar">
          <el-button type="primary" @click="loadTodo">刷新待办</el-button>
          <span class="hint">三档判级：常规=采购经理；超预算=采购经理→采购总监；特殊=采购总监→分管副总裁；价控命中追加采购总监（BR-4.2-15/17）</span>
        </div>
        <el-table :data="todo" v-loading="loading" stripe>
          <el-table-column prop="poNo" label="订单编号" width="160" />
          <el-table-column prop="supplierName" label="供应商" min-width="130" show-overflow-tooltip />
          <el-table-column prop="totalAmt" label="金额" width="100" align="right" />
          <el-table-column label="判级档位" width="150">
            <template #default="{ row }">
              <el-tag size="small" :type="row.nodeRole === 'PURCHASE_MANAGER' ? 'info' : 'warning'">
                {{ roleLabel(row.nodeRole) }}
              </el-tag>
              <el-tag v-if="row.escalateFlag === '1'" size="small" type="danger" style="margin-left:4px;">加签</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="路由链" min-width="200">
            <template #default="{ row }">
              <el-steps :active="activeStep(row)" size="mini" style="flex:1;">
                <el-step v-for="c in row.chain" :key="c.taskId" :title="c.nodeLabel"
                  :status="stepStatus(c)" />
              </el-steps>
            </template>
          </el-table-column>
          <el-table-column label="价控结果" width="120" align="center">
            <template #default="{ row }">
              <el-tag v-for="p in (row.priceControl || []).slice(0, 1)" :key="p.lineNo" size="small"
                :type="pcType(p.priceCtrlResult)">{{ pcLabel(p.priceCtrlResult) }}</el-tag>
              <span v-if="!(row.priceControl || []).length">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="doPass(row)">批准</el-button>
              <el-button link type="warning" @click="doConditional(row)">条件批准</el-button>
              <el-button link type="danger" @click="doReject(row)">驳回</el-button>
              <el-button link @click="showLogs(row)">日志</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-alert type="warning" :closable="false" show-icon style="margin-top: 10px;"
          title="审批展示降级（偏差 D3）：供应商绩效、库存水位 = 模块未上线占位；比价分析与价控结果为实际数据" />
      </el-tab-pane>

      <!-- ================= 2.3.3 订单变更 ================= -->
      <el-tab-pane label="订单变更" name="change">
        <div class="filter-bar">
          <el-select v-model="changeTargetId" placeholder="选择已批准订单" filterable style="width: 320px;" @change="loadChangeTarget">
            <el-option v-for="o in approvedOrders" :key="o.id" :label="`${o.poNo} ${o.supplierName} ¥${o.totalAmt}`" :value="o.id" />
          </el-select>
          <el-button type="primary" :disabled="!changeTargetId" @click="loadChangeTarget">载入</el-button>
          <span class="hint">变更类型：数量 / 交期 / 价格 / 行取消 / 行新增；金额未增直接留痕（BR-4.2-04），超 5% 升级采购总监（C-4.2-07）</span>
        </div>

        <template v-if="changeTarget">
          <el-descriptions :column="4" border size="small" style="margin-bottom: 10px;">
            <el-descriptions-item label="订单编号">{{ changeTarget.po.poNo }}</el-descriptions-item>
            <el-descriptions-item label="当前版本">v{{ changeTarget.po.currVersion }}</el-descriptions-item>
            <el-descriptions-item label="总金额">¥{{ changeTarget.po.totalAmt }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ changeTarget.po.status }}</el-descriptions-item>
          </el-descriptions>

          <el-table :data="changeTarget.lines" size="small" border>
            <el-table-column prop="lineNo" label="#" width="50" align="center" />
            <el-table-column prop="itemCode" label="物料" width="150" />
            <el-table-column prop="itemName" label="名称" min-width="120" />
            <el-table-column label="数量" width="130">
              <template #default="{ row }">
                <el-input-number v-model="row.qty" :min="0" :precision="4" size="small" style="width: 110px;" />
              </template>
            </el-table-column>
            <el-table-column label="单价" width="140">
              <template #default="{ row }">
                <el-input-number v-model="row.unitPrice" :min="0" :precision="4" size="small" style="width: 120px;" />
              </template>
            </el-table-column>
            <el-table-column label="交期" width="160">
              <template #default="{ row }">
                <el-date-picker v-model="row.reqDate" type="date" value-format="YYYY-MM-DD" size="small" style="width: 140px;" />
              </template>
            </el-table-column>
            <el-table-column prop="amount" label="金额" width="100" align="right" />
            <el-table-column label="操作" width="90" align="center">
              <template #default="{ row, $index }">
                <el-button link type="danger" @click="cancelLine(row, $index)">取消行</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div style="margin: 10px 0; display: flex; gap: 8px; align-items: center;">
            <el-button size="small" @click="addChangeLine">+ 新增行</el-button>
            <el-input v-model="changeReason" placeholder="变更原因（必填，≥2 字）" style="width: 320px;" />
            <el-button type="primary" @click="doChange">提交变更</el-button>
          </div>

          <h4>待审批的变更</h4>
          <el-table :data="pendingVersions" size="small" border>
            <el-table-column prop="versionNo" label="版本" width="70" align="center" />
            <el-table-column prop="chgType" label="类型" width="110" />
            <el-table-column prop="chgReason" label="原因" min-width="160" show-overflow-tooltip />
            <el-table-column label="要求角色" width="130">
              <template #default="{ row }">{{ roleLabel(row.reqRole) }}</template>
            </el-table-column>
            <el-table-column prop="reqReason" label="说明" min-width="200" show-overflow-tooltip />
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="approveChange(row, true)">通过</el-button>
                <el-button link type="danger" @click="approveChange(row, false)">驳回(回退)</el-button>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </el-tab-pane>

      <!-- ================= 2.3.4 版本管理 ================= -->
      <el-tab-pane label="版本管理" name="versions">
        <div class="filter-bar">
          <el-select v-model="verTargetId" placeholder="选择订单" filterable style="width: 320px;" @change="loadVersions">
            <el-option v-for="o in allForVersion" :key="o.id" :label="`${o.poNo} ${o.supplierName}`" :value="o.id" />
          </el-select>
          <el-button type="primary" :disabled="!verTargetId" @click="loadVersions">载入</el-button>
          <span class="hint">旧版本永久只读；回滚 = 按旧快照生成新版本（BR-4.2-03）</span>
        </div>
        <el-table :data="versions" v-loading="loading" size="small" border>
          <el-table-column prop="versionNo" label="版本" width="70" align="center" />
          <el-table-column prop="chgType" label="变更类型" width="120">
            <template #default="{ row }">{{ chgTypeLabel(row.chgType) }}</template>
          </el-table-column>
          <el-table-column prop="chgReason" label="变更原因" min-width="180" show-overflow-tooltip />
          <el-table-column label="审批状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="{ APPROVED: 'success', PENDING: 'warning', REJECTED: 'danger' }[row.approvalStatus] || 'info'">
                {{ { APPROVED: '已生效', PENDING: '待审批', REJECTED: '已驳回' }[row.approvalStatus] || row.approvalStatus }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reqReason" label="审批说明" min-width="200" show-overflow-tooltip />
          <el-table-column prop="createBy" label="操作人" width="110" />
          <el-table-column prop="createDate" label="时间" width="160" />
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="warning" @click="doRollback(row)">回滚到此</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- ============ 创建订单对话框（三入口） ============ -->
    <el-dialog v-model="createVisible" title="创建采购订单" width="760px" destroy-on-close>
      <el-tabs v-model="createTab">
        <!-- 入口一：从协议 -->
        <el-tab-pane label="从协议下单" name="agreement">
          <el-select v-model="form.agreementId" placeholder="选择生效中/临期协议" filterable style="width: 100%; margin-bottom: 10px;" @change="loadAgreementLines">
            <el-option v-for="a in usableAgreements" :key="a.id" :label="`${a.agreementNo} ${a.title}（${statusLabel(a.status)}）`" :value="a.id" />
          </el-select>
          <el-table :data="agreementLines" size="small" border>
            <el-table-column label="#" width="50" align="center">
              <template #default="{ $index }">{{ $index + 1 }}</template>
            </el-table-column>
            <el-table-column prop="itemCode" label="物料" width="140" />
            <el-table-column prop="awardSupplierName" label="中标方" width="120" show-overflow-tooltip />
            <el-table-column prop="unitPrice" label="协议价(锁定)" width="110" align="right" />
            <el-table-column label="承诺量/已下单" width="130" align="center">
              <template #default="{ row }">
                {{ row.commitQty == null ? '不限量' : row.commitQty }} / {{ row.orderedQty || 0 }}
              </template>
            </el-table-column>
            <el-table-column label="下单数量" width="150">
              <template #default="{ row }">
                <el-input-number v-model="row.orderQty" :min="0" :precision="4" size="small" style="width: 130px;"
                  :disabled="row.commitQty != null && (row.orderedQty || 0) >= row.commitQty" />
              </template>
            </el-table-column>
          </el-table>
          <div class="hint" style="margin-top: 6px;">单价由协议自动带出不可修改（FR-4.2-3-1）；余量 = 承诺量 − 已下单量（S-4.2-03）</div>
        </el-tab-pane>

        <!-- 入口二：RFQ 中选 -->
        <el-tab-pane label="RFQ 中选转单" name="rfq">
          <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px;"
            title="输入已中选的 PR 编号，自动带出中选供应商与中选价，并回写下达量（BR-4.2-51）" />
          <el-input v-model="form.prNo" placeholder="如 PR-THRESH-S" style="width: 300px;" />
        </el-tab-pane>

        <!-- 入口三：手工 -->
        <el-tab-pane label="手工创建" name="manual">
          <div style="display: flex; gap: 10px; margin-bottom: 10px;">
            <el-select v-model="form.supplierId" placeholder="选择供应商" filterable style="width: 260px;">
              <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
            </el-select>
            <el-select v-model="form.poType" style="width: 140px;">
              <el-option label="常规 PO" value="NORMAL" />
              <el-option label="特殊 PO" value="SPECIAL" />
            </el-select>
            <el-input v-model="form.taxCode" placeholder="税码" style="width: 100px;" />
            <el-input-number v-model="form.taxRate" :min="0" :max="1" :precision="4" placeholder="税率" style="width: 130px;" />
          </div>
          <el-table :data="form.lines" size="small" border>
            <el-table-column label="#" width="50" align="center">
              <template #default="{ $index }">{{ $index + 1 }}</template>
            </el-table-column>
            <el-table-column label="物料编码" width="180">
              <template #default="{ row }">
                <el-select v-model="row.itemCode" filterable allow-create default-first-option placeholder="选择或输入" style="width: 100%;">
                  <el-option v-for="o in itemOptions" :key="o.code" :label="`[${o.code}] ${o.name}`" :value="o.code" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="140">
              <template #default="{ row }">
                <el-input-number v-model="row.qty" :min="0" :precision="4" size="small" style="width: 120px;" />
              </template>
            </el-table-column>
            <el-table-column label="单价" width="140">
              <template #default="{ row }">
                <el-input-number v-model="row.unitPrice" :min="0" :precision="4" size="small" style="width: 120px;" />
              </template>
            </el-table-column>
            <el-table-column label="交期" width="160">
              <template #default="{ row }">
                <el-date-picker v-model="row.reqDate" type="date" value-format="YYYY-MM-DD" size="small" style="width: 140px;" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ $index }">
                <el-button link type="danger" @click="form.lines.splice($index, 1)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button size="small" style="margin-top: 8px;" @click="form.lines.push({ itemCode: '', qty: 1, unitPrice: 0, reqDate: null })">+ 加行</el-button>
          <div class="hint" style="margin-top: 6px;">供应商须合格（BR-4.2-18）；价控三重在提交审批时执行（2.3.5）</div>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- ============ 详情对话框 ============ -->
    <el-dialog v-model="detailVisible" :title="`订单 ${detail.po.poNo || ''}`" width="900px" destroy-on-close>
      <el-descriptions :column="4" border size="small" style="margin-bottom: 10px;">
        <el-descriptions-item label="供应商">{{ detail.po.supplierName }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ detail.po.source }}</el-descriptions-item>
        <el-descriptions-item label="总金额">¥{{ detail.po.totalAmt }}</el-descriptions-item>
        <el-descriptions-item label="版本">v{{ detail.po.currVersion }}</el-descriptions-item>
        <el-descriptions-item label="状态" :span="2">{{ detail.po.status }}</el-descriptions-item>
        <el-descriptions-item label="PR">{{ detail.po.prNo || '—' }}</el-descriptions-item>
        <el-descriptions-item label="批次">{{ detail.po.approvalBatch }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="detail.lines" size="small" border>
        <el-table-column prop="lineNo" label="#" width="50" align="center" />
        <el-table-column prop="itemCode" label="物料" width="140" />
        <el-table-column prop="itemName" label="名称" min-width="110" show-overflow-tooltip />
        <el-table-column prop="qty" label="数量" width="90" align="right" />
        <el-table-column prop="unitPrice" label="单价" width="90" align="right" />
        <el-table-column prop="amount" label="金额" width="100" align="right" />
        <el-table-column label="价控" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="pcType(row.priceCtrlResult)">{{ pcLabel(row.priceCtrlResult) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="agreementLineId" label="协议溯源" width="100" align="center">
          <template #default="{ row }">{{ row.agreementLineId ? '协议' : '—' }}</template>
        </el-table-column>
      </el-table>
      <h4 style="margin: 10px 0 6px;">价控日志</h4>
      <el-table :data="detail.priceLogs" size="small" border>
        <el-table-column label="级别" width="90" align="center">
          <template #default="{ row }">{{ { CONTRACT: '合同价', HISTORY: '历史价', BUDGET: '预算' }[row.checkLevel] }}</template>
        </el-table-column>
        <el-table-column prop="baseValue" label="基准" width="100" align="right" />
        <el-table-column prop="actualValue" label="实际" width="100" align="right" />
        <el-table-column label="结论" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="pcType(row.result)">{{ pcLabel(row.result) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="说明" min-width="200" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getPoPageApi, getPoDetailApi, createFromAgreementApi, createFromRfqApi,
  createManualPoApi, submitPoApi, closePoApi, getApprovalTodoApi,
  passTaskApi, passConditionalApi, rejectTaskApi, getApprovalLogsApi,
  changePoApi, getVersionsApi, approveChangeApi, rollbackApi, getPriceLogsApi
} from '@/api/proc/purchase-order'
import { getBudgetListApi } from '@/api/proc/purchase-budget'
import { getAgreementsApi, getAgreementDetailApi } from '@/api/proc/framework-agreement'
import { getSupplierPageApi } from '@/api/mdm/supplier-admission'
import { getItemOptionsApi } from '@/api/mdm/item'

const route = useRoute()
const router = useRouter()

const tab = ref(route.meta.tab || 'orders')
watch(() => route.meta.tab, v => { if (v) tab.value = v })
watch(tab, v => { if (route.meta.tab !== v) router.replace({ query: { tab: v } }) })

const loading = ref(false)
const saving = ref(false)
const orders = ref([])
const total = ref(0)
const filters = reactive({ current: 1, size: 10, status: '', source: '', keyword: '' })

async function loadOrders() {
  loading.value = true
  try {
    const { data } = await getPoPageApi(filters)
    orders.value = data.records || []
    total.value = Number(data.total) || 0
  } finally {
    loading.value = false
  }
}

// ---------- 创建（三入口） ----------
const createVisible = ref(false)
const createTab = ref('agreement')
const usableAgreements = ref([])
const agreementLines = ref([])
const suppliers = ref([])
const itemOptions = ref([])
const form = reactive({
  agreementId: '', prNo: '', supplierId: '', poType: 'NORMAL',
  taxRate: null, taxCode: '',
  lines: [{ itemCode: '', qty: 1, unitPrice: 0, reqDate: null }]
})

function openCreate() {
  form.agreementId = ''; form.prNo = ''; form.supplierId = ''
  form.lines = [{ itemCode: '', qty: 1, unitPrice: 0, reqDate: null }]
  agreementLines.value = []
  createVisible.value = true
  loadOptions()
}

async function loadOptions() {
  const [{ data: ags }, { data: sups }, { data: items }] = await Promise.all([
    getAgreementsApi({ keyword: '' }),
    getSupplierPageApi({ current: 1, size: 100 }),
    getItemOptionsApi()
  ])
  usableAgreements.value = (Array.isArray(ags) ? ags : (ags.records || []))
    .filter(a => a.usable !== false && ['1', '2'].includes(String(a.status)))
  suppliers.value = sups.records || sups || []
  itemOptions.value = items || []
}

async function loadAgreementLines() {
  if (!form.agreementId) return
  const { data } = await getAgreementDetailApi(form.agreementId)
  agreementLines.value = (data.lines || []).map(l => ({ ...l, orderQty: null }))
}

async function doCreate() {
  saving.value = true
  try {
    if (createTab.value === 'agreement') {
      const lines = agreementLines.value
        .filter(l => l.orderQty != null && l.orderQty > 0)
        .map(l => ({ agreementLineId: l.id, qty: l.orderQty }))
      if (!lines.length) { ElMessage.warning('请填写至少一行下单数量'); return }
      const { data } = await createFromAgreementApi({ agreementId: form.agreementId, lines })
      ElMessage.success(`已创建 ${data.po.poNo}（协议带价，余量已回写）`)
    } else if (createTab.value === 'rfq') {
      if (!form.prNo) { ElMessage.warning('请填写 PR 编号'); return }
      const { data } = await createFromRfqApi(form.prNo)
      ElMessage.success(`已创建 ${data.po.poNo}（中选价转单，PR 回写完成）`)
    } else {
      if (!form.supplierId) { ElMessage.warning('请选择供应商'); return }
      const lines = form.lines.filter(l => l.itemCode)
      if (!lines.length) { ElMessage.warning('请填写明细行'); return }
      const { data } = await createManualPoApi({
        supplierId: form.supplierId, poType: form.poType,
        taxRate: form.taxRate, taxCode: form.taxCode, lines
      })
      ElMessage.success(`已创建 ${data.po.poNo}`)
    }
    createVisible.value = false
    loadOrders()
  } finally {
    saving.value = false
  }
}

// ---------- 详情 / 提交 / 关闭 ----------
const detailVisible = ref(false)
const detail = reactive({ po: {}, lines: [], priceLogs: [] })

async function openDetail(row) {
  const { data } = await getPoDetailApi(row.id)
  detail.po = data.po || {}
  detail.lines = data.lines || []
  try {
    const { data: logs } = await getPriceLogsApi(row.id)
    detail.priceLogs = logs || []
  } catch { detail.priceLogs = [] }
  detailVisible.value = true
}

async function doSubmit(row) {
  try {
    await ElMessageBox.confirm(`提交 ${row.poNo} 进入审批？将先执行价控三重校验。`, '提交审批', { type: 'info' })
  } catch { return }
  try {
    const { data } = await submitPoApi(row.id)
    ElMessage.success(`已提交：判级 ${data.chain?.length || 1} 节点，价控日志 ${(data.priceControl || []).length} 条`)
    loadOrders()
  } catch (e) {
    const msg = e?.response?.data?.message || e.message
    if (String(msg).includes('特批')) {
      ElMessageBox.prompt(msg, '申请特批', {
        inputPattern: /^.{2,}$/, inputErrorMessage: '特批原因不少于 2 字'
      }).then(async ({ value }) => {
        const { data } = await submitPoApi(row.id, value)
        ElMessage.success(`特批提交成功，升级链 ${(data.chain || []).length} 节点`)
        loadOrders()
      }).catch(() => {})
    }
  }
}

async function doClose(row) {
  try {
    const { value } = await ElMessageBox.prompt('关闭原因（必填）', `关闭 ${row.poNo}`,
      { inputPattern: /^.{2,}$/, inputErrorMessage: '原因不少于 2 字' })
    await closePoApi(row.id, value)
    ElMessage.success('已关闭')
    loadOrders()
  } catch { /* 取消 */ }
}

// ---------- 审批 ----------
const todo = ref([])
const loadingTodo = ref(false)

async function loadTodo() {
  loadingTodo.value = true
  try {
    const { data } = await getApprovalTodoApi()
    todo.value = data || []
  } finally {
    loadingTodo.value = false
  }
}

async function doPass(row) {
  await passTaskApi(row.taskId)
  ElMessage.success('已通过')
  refreshApproval()
}

async function doConditional(row) {
  try {
    const { value } = await ElMessageBox.prompt('附加条件（必填）', '条件批准',
      { inputPattern: /^.{2,}$/, inputErrorMessage: '条件不少于 2 字' })
    await passConditionalApi(row.taskId, value)
    ElMessage.success('条件批准已记录')
    refreshApproval()
  } catch { /* 取消 */ }
}

async function doReject(row) {
  try {
    const { value } = await ElMessageBox.prompt('驳回原因（必填）', '驳回',
      { inputPattern: /^.{2,}$/, inputErrorMessage: '原因不少于 2 字' })
    await rejectTaskApi(row.taskId, value)
    ElMessage.success('已驳回，订单退回草稿')
    refreshApproval()
  } catch { /* 取消 */ }
}

function refreshApproval() { loadTodo(); loadOrders() }

async function showLogs(row) {
  const { data } = await getApprovalLogsApi(row.poId)
  ElMessageBox.alert(
    (data || []).map(l =>
      `批次${l.submitBatch} 节点${l.nodeNo}[${roleLabel(l.nodeRole)}] ${l.action} ${l.actedByName || ''} ${l.actionReason || ''}`
    ).join('<br>'), `${row.poNo} 审批日志`, { dangerouslyUseHTMLString: true })
}

// ---------- 变更 ----------
const changeTargetId = ref('')
const changeTarget = ref(null)
const changeReason = ref('')
const approvedOrders = computed(() => orders.value.filter(o => o.status === 'APPROVED'))
const pendingVersions = ref([])

async function loadChangeTarget() {
  if (!changeTargetId.value) return
  const { data } = await getPoDetailApi(changeTargetId.value)
  changeTarget.value = data
  changeReason.value = ''
  const { data: vs } = await getVersionsApi(changeTargetId.value)
  pendingVersions.value = (vs || []).filter(v => v.approvalStatus === 'PENDING')
}

function addChangeLine() {
  changeTarget.value.lines.push({
    lineNo: (changeTarget.value.lines.length + 1), itemCode: '', itemName: '',
    qty: 1, unitPrice: 0, reqDate: null, _new: true
  })
}

function cancelLine(row, index) {
  row._cancel = true
  changeTarget.value.lines.splice(index, 1)
  ElMessage.info('已移除该行（提交变更时生效行取消）')
}

async function doChange() {
  if (!changeReason.value || changeReason.value.trim().length < 2) {
    ElMessage.warning('变更原因必填（≥2 字）'); return
  }
  const t = changeTarget.value
  const payload = {
    chgType: guessChgType(t.lines),
    chgReason: changeReason.value.trim(),
    lines: t.lines.filter(l => l.id && !l._new && !l._cancel)
      .map(l => ({ lineId: l.id, qty: l.qty, unitPrice: l.unitPrice, reqDate: l.reqDate })),
    newLines: t.lines.filter(l => l._new)
      .map(l => ({ itemCode: l.itemCode, itemName: l.itemName, qty: l.qty, unitPrice: l.unitPrice, reqDate: l.reqDate }))
  }
  if (payload.lines.length && !payload.newLines.length) {
    // 粗判类型：仅交期差异 → DATE；仅价格 → PRICE；否则 QTY
    payload.chgType = guessDetail(t.lines)
  }
  try {
    const { data } = await changePoApi(t.po.id, payload)
    const v = data.version
    if (v.approvalStatus === 'PENDING') {
      ElMessage.warning(`变更已提交待审批：${roleLabel(v.reqRole)}（${v.reqReason}）`)
    } else {
      ElMessage.success('变更已生效（金额未增加，留痕 BR-4.2-04）')
    }
    loadChangeTarget()
    loadOrders()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '变更失败')
  }
}

function guessChgType(lines) {
  if (lines.some(l => l._new)) return 'LINE_ADD'
  if (lines.some(l => l._cancel)) return 'LINE_CANCEL'
  return 'QTY'
}
function guessDetail(lines) {
  return 'PRICE'
}

async function approveChange(row, approved) {
  try {
    const { value } = await ElMessageBox.prompt(
      approved ? '审批意见（必填）' : '驳回意见（必填，驳回后自动回退）',
      approved ? '通过变更' : '驳回变更',
      { inputPattern: /^.{2,}$/, inputErrorMessage: '意见不少于 2 字' })
    const { data } = await approveChangeApi(changeTargetId.value, row.versionNo, approved, value)
    ElMessage.success(approved ? '变更已批准生效' : '已驳回并自动回退至变更前状态')
    loadChangeTarget()
    loadOrders()
    console.log(data)
  } catch { /* 取消 */ }
}

// ---------- 版本 ----------
const verTargetId = ref('')
const versions = ref([])
const allForVersion = ref([])

async function loadVersions() {
  if (!verTargetId.value) return
  loading.value = true
  try {
    const { data } = await getVersionsApi(verTargetId.value)
    versions.value = data || []
  } finally {
    loading.value = false
  }
}

async function doRollback(row) {
  try {
    await ElMessageBox.confirm(`回滚到 v${row.versionNo}？将按该版本快照生成新版本（BR-4.2-03）。`, '回滚', { type: 'warning' })
    const { value } = await ElMessageBox.prompt('回滚原因（必填）', '回滚',
      { inputPattern: /^.{2,}$/, inputErrorMessage: '原因不少于 2 字' })
    const { data } = await rollbackApi(verTargetId.value, row.versionNo, value)
    ElMessage.success(`已回滚，当前版本 v${data.currVersion}`)
    loadVersions()
    loadOrders()
  } catch { /* 取消 */ }
}

// ---------- 展示辅助 ----------
function roleLabel(r) {
  return { PURCHASE_MANAGER: '采购经理', PURCHASE_DIRECTOR: '采购总监',
    VICE_PRESIDENT: '分管副总裁' }[r] || r || '—'
}
function chgTypeLabel(t) {
  return { QTY: '数量调整', DATE: '交期变更', PRICE: '价格调整',
    LINE_CANCEL: '行取消', LINE_ADD: '行新增', ROLLBACK: '回滚' }[t] || t
}
function statusLabel(s) {
  return { '1': '生效中', '2': '临期', '3': '已到期', '4': '已终止' }[s] || s
}
function pcLabel(r) {
  return { PASS: '通过', ESCALATE: '升级', BLOCK: '阻断',
    NO_HISTORY: '无历史', NO_BUDGET: '无预算', PENDING: '待检' }[r] || r || '—'
}
function pcType(r) {
  return { PASS: 'success', ESCALATE: 'warning', BLOCK: 'danger',
    NO_HISTORY: 'info', NO_BUDGET: 'info', PENDING: 'info' }[r] || 'info'
}
function activeStep(row) {
  const chain = row.chain || []
  const idx = chain.findIndex(c => c.status === 'ACTIVE')
  return idx >= 0 ? idx : chain.length
}
function stepStatus(c) {
  if (c.status === 'APPROVED') return 'success'
  if (c.status === 'REJECTED') return 'error'
  if (c.status === 'ACTIVE') return 'process'
  return 'wait'
}

onMounted(async () => {
  loadOrders()
  if (tab.value === 'approval') loadTodo()
  // 版本/变更页需要订单池
  const { data } = await getPoPageApi({ current: 1, size: 100 })
  allForVersion.value = data.records || []
  if (tab.value === 'versions' && allForVersion.value.length && !verTargetId.value) {
    verTargetId.value = allForVersion.value[0].id
    loadVersions()
  }
})
watch(tab, v => {
  if (v === 'approval') loadTodo()
  if (v === 'orders') loadOrders()
})
</script>

<style scoped>
.filter-bar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; flex-wrap: wrap; }
.hint { color: #909399; font-size: 12px; }
.el-pagination { margin-top: 12px; justify-content: flex-end; }
</style>
