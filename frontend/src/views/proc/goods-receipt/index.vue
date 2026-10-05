<template>
  <div class="page">
    <div class="card-header">
      <span style="font-weight: bold; font-size: 16px;">{{ title }}</span>
      <span class="tip">2.4 收货管理 · FR-4.2-4-1 · C-4.2-04 容差 0.5% · BR-4.2-21/22/28/49</span>
    </div>

    <el-tabs :model-value="activeTab" @tab-change="onTabChange">
      <!-- ============ 2.4.1 到货登记 ============ -->
      <el-tab-pane label="到货登记" name="register">
        <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
          title="按 PO 登记（默认）：带出未清量逐行录实收，提交时按 0.5% 容差分流（BR-4.2-20/21/22）"
          description="无 PO 收货（FREE）为辅入口：免容差、不回写 PO，仍走待检与过账。核对送货单与 PO：预填值即 PO 未清量，实收与之对照着色。" />

        <div class="toolbar">
          <el-radio-group v-model="sourceType" size="small">
            <el-radio-button label="PO">按 PO 登记（默认）</el-radio-button>
            <el-radio-button label="FREE">无 PO 收货（FREE）</el-radio-button>
          </el-radio-group>
          <template v-if="sourceType === 'PO'">
            <el-select v-model="poId" filterable placeholder="选择已下达（APPROVED）PO"
                       style="width: 340px;" @change="loadPoLines">
              <el-option v-for="p in poCandidates" :key="p.id"
                         :label="`${p.poNo}（${p.supplierName}）`" :value="p.id" />
            </el-select>
          </template>
          <template v-else>
            <el-select v-model="freeForm.supplierId" filterable placeholder="选择供应商" style="width: 240px;">
              <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
            </el-select>
          </template>
        </div>

        <!-- PO 未清行 / FREE 手输行 -->
        <el-table :data="sourceType === 'PO' ? poRows : freeRows" size="small" border>
          <el-table-column label="物料" min-width="180">
            <template #default="{ row }">
              <template v-if="sourceType === 'PO'">{{ row.itemCode }} {{ row.itemName }}</template>
              <template v-else>
                <el-select v-model="row.itemCode" filterable placeholder="选择物料" style="width: 100%;">
                  <el-option v-for="o in itemOptions" :key="o.code" :label="`[${o.code}] ${o.name}`" :value="o.code" />
                </el-select>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="下单量" width="90" align="right" v-if="sourceType === 'PO'">
            <template #default="{ row }">{{ row.qty }}</template>
          </el-table-column>
          <el-table-column label="已收" width="80" align="right" v-if="sourceType === 'PO'">
            <template #default="{ row }">{{ row.receivedQty }}</template>
          </el-table-column>
          <el-table-column label="未清量（预填）" width="120" align="right" v-if="sourceType === 'PO'">
            <template #default="{ row }"><b>{{ row.openQty }}</b></template>
          </el-table-column>
          <el-table-column label="实收数量" width="150">
            <template #default="{ row }">
              <el-input-number v-model="row.receivedQty" :min="0" :precision="4" size="small" style="width: 130px;" />
            </template>
          </el-table-column>
          <el-table-column label="容差预演" width="170">
            <template #default="{ row }">
              <template v-if="sourceType === 'FREE'">
                <el-tag size="small" type="info">FREE 免容差</el-tag>
              </template>
              <template v-else>
                <el-tag size="small" :type="preview(row).type">{{ preview(row).text }}</el-tag>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="" width="70" v-if="sourceType === 'FREE'">
            <template #default="{ $index }">
              <el-button link type="danger" @click="freeRows.splice($index, 1)">删行</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button v-if="sourceType === 'FREE' && canCreate" size="small" style="margin-top: 6px;" @click="freeRows.push({ itemCode: '', receivedQty: null })">+ 加行</el-button>

        <!-- 头信息 -->
        <el-divider content-position="left">到货信息（FR-4.2-4-1 自动记录项）</el-divider>
        <el-form :inline="true" size="small" label-width="80px">
          <el-form-item label="到货日期" required>
            <el-date-picker v-model="head.arrivalDate" type="date" value-format="YYYY-MM-DD" style="width: 150px;" />
          </el-form-item>
          <el-form-item label="送货单号">
            <el-input v-model="head.deliveryNote" placeholder="送货单号" style="width: 150px;" />
          </el-form-item>
          <el-form-item label="批次号">
            <el-input v-model="head.batchNo" placeholder="到货批次号" style="width: 150px;" />
          </el-form-item>
          <el-form-item label="运输信息">
            <el-input v-model="head.transportInfo" placeholder="车牌号/快递单号" style="width: 170px;" />
          </el-form-item>
          <el-form-item label="外包装">
            <el-input v-model="head.packageCondition" placeholder="外包装状况" style="width: 140px;" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="head.remark" placeholder="可选" style="width: 180px;" />
          </el-form-item>
          <el-form-item>
            <el-button v-if="canCreate" type="primary" :loading="saving" @click="submitCreate">提交登记</el-button>
            <el-button @click="resetForm">重置</el-button>
          </el-form-item>
        </el-form>

        <el-divider content-position="left">收货单列表</el-divider>
        <div class="toolbar">
          <el-select v-model="grQuery.status" placeholder="状态" clearable style="width: 130px;" @change="loadGr(1)">
            <el-option label="待过账" value="CREATED" />
            <el-option label="已过账" value="POSTED" />
            <el-option label="已作废" value="CANCELLED" />
          </el-select>
          <el-input v-model="grQuery.keyword" placeholder="收货单号/PO号/供应商" clearable style="width: 200px;" @keyup.enter="loadGr(1)" />
          <el-button @click="loadGr(1)">查询</el-button>
        </div>
        <el-table :data="grList" size="small" border v-loading="loading">
          <el-table-column prop="grNo" label="收货单号" width="160" />
          <el-table-column label="来源" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.sourceType === 'FREE' ? 'warning' : 'primary'" size="small">{{ row.sourceType }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="poNo" label="PO" width="150" />
          <el-table-column prop="supplierName" label="供应商" min-width="130" />
          <el-table-column prop="arrivalDate" label="到货日期" width="105" />
          <el-table-column prop="batchNo" label="批次" width="110" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="postingDocNo" label="入库凭证" width="140" />
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button v-if="row.status === 'CREATED' && canCreate" link type="danger" @click="doCancel(row)">作废</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 8px;" layout="total, prev, pager, next" :total="grTotal"
                       :page-size="grQuery.size" :current-page="grQuery.current" @current-change="loadGr" />
      </el-tab-pane>

      <!-- ============ 2.4.2 待检管理 ============ -->
      <el-tab-pane label="待检管理" name="qc">
        <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
          title="待检看板（2.5 已接入）：数据源为检验批，时限自 GR 登记提交起算（偏差 D1），A/B/C = 24/48/72h"
          description="人工兜底放行已移除（qc-hold-area REMOVED）：检验录入/判定/放行在「来料检验」页完成；未放行的批次过账被 C-4.12-06 阻断。" />
        <el-table :data="qcList" size="small" border v-loading="loading">
          <el-table-column prop="lotNo" label="检验批号" width="170">
            <template #default="{ row }"><b>{{ row.lotNo }}</b></template>
          </el-table-column>
          <el-table-column prop="itemCode" label="物料" width="140" />
          <el-table-column prop="itemName" label="名称" min-width="130" />
          <el-table-column prop="supplierName" label="供应商" width="130" show-overflow-tooltip />
          <el-table-column prop="lotQty" label="批量" width="90" align="right" />
          <el-table-column label="风险等级" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="{ A: 'danger', B: 'warning', C: 'info' }[row.riskGrade] || 'info'" size="small">
                {{ row.riskGrade }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="检验状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'BLOCKED' ? 'danger' : 'primary'">
                {{ { BLOCKED: '无标准', PENDING: '待检', INPUTTING: '录入中',
                     REVIEWING: '复核', FROZEN: '冻结' }[row.status] || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="dueTime" label="截止时间" width="165" />
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.overdue" type="danger" size="small">超时</el-tag>
              <el-tag v-else type="success" size="small">进行中</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default>
              <el-button link type="primary" @click="$router.push('/m/2.5.1')">去检验</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!qcList.length" description="暂无在途检验批" :image-size="80" />
      </el-tab-pane>

      <!-- ============ 2.4.3 容差校验 / 差异对账台 ============ -->
      <el-tab-pane label="容差校验" name="difference">
        <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
          title="差异对账台（L1064）：超交走收货调整单或拒收，短交待补齐或手动关闭"
          description="无「直接接受超收」出口（BR-4.2-49 专用流程，P-1 轻量链）。差异处置为采购员职责（ADMIN）。" />
        <div class="toolbar">
          <el-select v-model="diffQuery.status" placeholder="状态" clearable style="width: 140px;" @change="loadDiffs(1)">
            <el-option label="待处置" value="PENDING" />
            <el-option label="已转调整" value="ADJUSTED" />
            <el-option label="已拒收" value="RETURNED" />
            <el-option label="已关闭" value="CLOSED" />
          </el-select>
          <el-select v-model="diffQuery.diffType" placeholder="类型" clearable style="width: 110px;" @change="loadDiffs(1)">
            <el-option label="超交" value="OVER" />
            <el-option label="短交" value="SHORT" />
          </el-select>
          <el-button @click="loadDiffs(1)">查询</el-button>
        </div>
        <el-table :data="diffList" size="small" border v-loading="loading">
          <el-table-column prop="diffNo" label="差异单号" width="160" />
          <el-table-column prop="itemCode" label="物料" width="120" />
          <el-table-column label="类型" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.diffType === 'OVER' ? 'danger' : 'warning'" size="small">
                {{ row.diffType === 'OVER' ? '超交' : '短交' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="orderedQty" label="未清量" width="90" align="right" />
          <el-table-column prop="receivedQty" label="实收" width="90" align="right" />
          <el-table-column prop="diffQty" label="差异量" width="90" align="right" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="diffTag(row.status)" size="small">{{ diffName(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="disposeNote" label="处置说明" min-width="140" show-overflow-tooltip />
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <template v-if="row.status === 'PENDING' && isAdmin">
                <el-button v-if="row.diffType === 'OVER'" link type="primary" @click="startAdjust(row)">发起调整单</el-button>
                <el-button link type="danger" @click="dispose(row, 'REJECT')">拒收</el-button>
                <el-button v-if="row.diffType === 'SHORT'" link type="warning" @click="dispose(row, 'CLOSE')">关闭</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 8px;" layout="total, prev, pager, next" :total="diffTotal"
                       :page-size="diffQuery.size" :current-page="diffQuery.current" @current-change="loadDiffs" />

        <el-divider content-position="left">收货调整单（BR-4.2-49，单节点审批）</el-divider>
        <el-table :data="adjList" size="small" border>
          <el-table-column prop="adjNo" label="调整单号" width="170" />
          <el-table-column prop="poNo" label="PO" width="150" />
          <el-table-column prop="itemCode" label="物料" width="120" />
          <el-table-column prop="addQty" label="追加数量" width="90" align="right" />
          <el-table-column label="数量变化" width="130" align="center">
            <template #default="{ row }">{{ row.oldQty }} → {{ row.newQty }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="adjTag(row.status)" size="small">{{ adjName(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="approveNote" label="审批意见" min-width="120" show-overflow-tooltip />
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <template v-if="row.status === 'PENDING_APPROVE' && canApproveAdj">
                <el-button link type="success" @click="approveAdj(row, true)">批准执行</el-button>
                <el-button link type="danger" @click="approveAdj(row, false)">驳回</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ============ 2.4.4 入库过账 ============ -->
      <el-tab-pane label="入库过账" name="posting">
        <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
          title="入库过账（FR-4.2-6-1）：回写 PO 已收货数量、行完成判定、更新库存（全部进待检锁定）、生成入库凭证"
          description="BR-4.2-28：PO 已关闭（未按 49 重开）或供应商冻结 → 硬阻断。应付暂估与三方匹配为 4.6 桩（TODO-NOTIFY）。" />
        <div class="toolbar">
          <el-select v-model="postQuery.status" placeholder="状态" clearable style="width: 130px;" @change="loadPosts(1)">
            <el-option label="待过账" value="CREATED" />
            <el-option label="已过账" value="POSTED" />
          </el-select>
          <el-button @click="loadPosts(1)">查询</el-button>
        </div>
        <el-table :data="postList" size="small" border v-loading="loading">
          <el-table-column prop="grNo" label="收货单号" width="160" />
          <el-table-column prop="poNo" label="PO" width="150" />
          <el-table-column prop="supplierName" label="供应商" min-width="130" />
          <el-table-column prop="arrivalDate" label="到货日期" width="105" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="postingDocNo" label="入库凭证" width="140" />
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'CREATED' && canPost" link type="primary"
                         :loading="postingId === row.id" @click="doPosting(row)">过账</el-button>
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 8px;" layout="total, prev, pager, next" :total="postTotal"
                       :page-size="postQuery.size" :current-page="postQuery.current" @current-change="loadPosts" />
      </el-tab-pane>
    </el-tabs>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" :title="`收货单：${detail.gr?.grNo || ''}`" size="70%">
      <template v-if="detail.gr">
        <el-descriptions :column="3" border size="small" style="margin-bottom: 12px;">
          <el-descriptions-item label="来源">{{ detail.gr.sourceType }}</el-descriptions-item>
          <el-descriptions-item label="PO">{{ detail.gr.poNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ detail.gr.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="到货日期">{{ detail.gr.arrivalDate }}</el-descriptions-item>
          <el-descriptions-item label="送货单/批次">{{ detail.gr.deliveryNote || '—' }} / {{ detail.gr.batchNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="运输/外包装">{{ detail.gr.transportInfo || '—' }} / {{ detail.gr.packageCondition || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusName(detail.gr.status) }}</el-descriptions-item>
          <el-descriptions-item label="入库凭证">{{ detail.gr.postingDocNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="过账时间">{{ detail.gr.postingDate || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="detail.lines || []" size="small" border>
          <el-table-column prop="lineNo" label="#" width="50" align="center" />
          <el-table-column prop="itemCode" label="物料" width="120" />
          <el-table-column prop="itemName" label="名称" min-width="120" />
          <el-table-column prop="orderedQty" label="下单量" width="85" align="right" />
          <el-table-column prop="openQty" label="未清量" width="85" align="right" />
          <el-table-column prop="receivedQty" label="实收" width="85" align="right" />
          <el-table-column label="容差判定" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="{ OK_OVER: 'success', OK_SHORT: 'success', OVER: 'danger', SHORT: 'warning', FREE: 'info' }[row.toleranceResult]">
                {{ tolName(row.toleranceResult) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="withinToleranceQty" label="核销量" width="85" align="right" />
          <el-table-column prop="frozenQty" label="冻结量" width="85" align="right" />
          <el-table-column label="行状态" width="90" align="center">
            <template #default="{ row }">{{ { PENDING: '待过账', POSTED: '已过账', REJECTED: '已拒收' }[row.status] }}</template>
          </el-table-column>
        </el-table>
        <template v-if="(detail.differences || []).length">
          <el-divider content-position="left">关联差异单</el-divider>
          <el-table :data="detail.differences" size="small" border>
            <el-table-column prop="diffNo" label="单号" width="150" />
            <el-table-column prop="diffType" label="类型" width="70" />
            <el-table-column prop="diffQty" label="差异量" width="90" align="right" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">{{ diffName(row.status) }}</template>
            </el-table-column>
            <el-table-column prop="disposeNote" label="处置" min-width="130" />
          </el-table>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getPoCandidatesApi, getPoLinesApi, createGrByPoApi, createGrFreeApi,
  getGrPageApi, getGrDetailApi, cancelGrApi,
  getDifferencePageApi, disposeDifferenceApi,
  getAdjustmentPageApi, approveAdjustmentApi,
  getQcHoldApi, postingGrApi
} from '@/api/proc/goods-receipt'
import { getSupplierPageApi } from '@/api/mdm/supplier-admission'
import { getItemOptionsApi } from '@/api/mdm/item'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const roles = computed(() => userStore.userInfo?.roles || [])
const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))
const canCreate = computed(() => isAdmin.value || roles.value.includes('ROLE_RECEIVER'))
const canPost = computed(() => isAdmin.value || roles.value.includes('ROLE_WAREHOUSE'))
const canApproveAdj = computed(() => isAdmin.value || roles.value.includes('ROLE_PM'))

const activeTab = ref(route.meta.tab || 'register')
const loading = ref(false)
const saving = ref(false)
const postingId = ref('')

const title = computed(() => route.meta.title || '收货管理')
function onTabChange(name) {
  const map = { register: '/m/2.4.1', qc: '/m/2.4.2', difference: '/m/2.4.3', posting: '/m/2.4.4' }
  if (map[name] && route.path !== map[name]) router.push(map[name])
}
watch(() => route.meta.tab, t => { if (t) activeTab.value = t })

// ---------- 登记表单 ----------
const sourceType = ref('PO')
const poId = ref('')
const poCandidates = ref([])
const poRows = ref([])
const freeRows = ref([{ itemCode: '', receivedQty: null }])
const suppliers = ref([])
const itemOptions = ref([])
const head = reactive({ arrivalDate: '', deliveryNote: '', batchNo: '', transportInfo: '', packageCondition: '', remark: '' })

const tol = 0.005
function preview(row) {
  const open = Number(row.openQty || 0)
  const r = Number(row.receivedQty || 0)
  if (!r || !open) return { type: 'info', text: '—' }
  if (r > open * (1 + tol)) {
    const frozen = r - open * (1 + tol)
    return { type: 'danger', text: `超交，冻结 ${frozen.toFixed(4)}` }
  }
  if (r < open * (1 - tol)) {
    return { type: 'warning', text: `短交 ${(open - r).toFixed(4)}` }
  }
  return { type: 'success', text: '容差内自动核销' }
}

async function loadOptions() {
  const [{ data: pos }, { data: sups }, { data: items }] = await Promise.all([
    getPoCandidatesApi(), getSupplierPageApi({ current: 1, size: 200 }), getItemOptionsApi()
  ])
  poCandidates.value = pos.records || []
  suppliers.value = sups.records || sups || []
  itemOptions.value = items || []
}

async function loadPoLines() {
  if (!poId.value) return
  const { data } = await getPoLinesApi(poId.value)
  poRows.value = (data.lines || []).map(l => ({ ...l, receivedQty: Number(l.openQty) }))
}

function resetForm() {
  poId.value = ''
  poRows.value = []
  freeRows.value = [{ itemCode: '', receivedQty: null }]
  Object.assign(head, { arrivalDate: '', deliveryNote: '', batchNo: '', transportInfo: '', packageCondition: '', remark: '' })
}

async function submitCreate() {
  if (!head.arrivalDate) { ElMessage.warning('到货日期必填'); return }
  let payload
  if (sourceType.value === 'PO') {
    if (!poId.value) { ElMessage.warning('请选择 PO'); return }
    const lines = poRows.value
      .filter(r => r.receivedQty != null && Number(r.receivedQty) > 0)
      .map(r => ({ poLineId: r.id, receivedQty: Number(r.receivedQty) }))
    if (!lines.length) { ElMessage.warning('至少一行实收 > 0'); return }
    payload = { poId: poId.value, ...head, lines }
  } else {
    if (!freeForm.value.supplierId) { ElMessage.warning('请选择供应商'); return }
    const lines = freeRows.value
      .filter(r => r.itemCode && r.receivedQty != null && Number(r.receivedQty) > 0)
      .map(r => ({ itemCode: r.itemCode, receivedQty: Number(r.receivedQty) }))
    if (!lines.length) { ElMessage.warning('至少一行有效物料与实收'); return }
    payload = { supplierId: freeForm.value.supplierId, ...head, lines }
  }
  saving.value = true
  try {
    const res = sourceType.value === 'PO' ? await createGrByPoApi(payload) : await createGrFreeApi(payload)
    const d = res.data || {}
    const diffs = (d.differences || []).length
    ElMessage.success(`登记成功：${d.gr?.grNo}（差异单 ${diffs} 张）`)
    resetForm()
    loadGr(1)
    loadDiffs(1)
    openDetail({ id: d.gr?.id })
  } catch (e) {
    ElMessage.error(e?.message || '登记失败')
  } finally {
    saving.value = false
  }
}

const freeForm = ref({ supplierId: '' })

// ---------- 收货单列表 ----------
const grList = ref([])
const grTotal = ref(0)
const grQuery = reactive({ status: '', keyword: '', current: 1, size: 10 })
async function loadGr(page) {
  grQuery.current = page || 1
  loading.value = true
  try {
    const { data } = await getGrPageApi({ current: grQuery.current, size: grQuery.size, status: grQuery.status, keyword: grQuery.keyword })
    grList.value = data.records || []
    grTotal.value = Number(data.total) || 0
  } finally { loading.value = false }
}

const detailVisible = ref(false)
const detail = ref({ gr: null, lines: [], differences: [] })
async function openDetail(row) {
  const { data } = await getGrDetailApi(row.id)
  detail.value = data
  detailVisible.value = true
}

function doCancel(row) {
  ElMessageBox.prompt('作废原因（≥2 字）', '作废收货单', { inputPattern: /^.{2,}$/, inputErrorMessage: '原因不少于 2 字' })
    .then(async ({ value }) => {
      await cancelGrApi(row.id, value)
      ElMessage.success('已作废')
      loadGr(grQuery.current)
    }).catch(() => {})
}

// ---------- 差异与调整单 ----------
const diffList = ref([])
const diffTotal = ref(0)
const diffQuery = reactive({ status: 'PENDING', diffType: '', current: 1, size: 10 })
async function loadDiffs(page) {
  diffQuery.current = page || 1
  loading.value = true
  try {
    const { data } = await getDifferencePageApi({ current: diffQuery.current, size: diffQuery.size, status: diffQuery.status, diffType: diffQuery.diffType })
    diffList.value = data.records || []
    diffTotal.value = Number(data.total) || 0
  } finally { loading.value = false }
}

function startAdjust(row) {
  ElMessageBox.confirm(`对差异单 ${row.diffNo}（差异量 ${row.diffQty}）发起收货调整单？批准后 PO 数量追加、价格不变。`, 'BR-4.2-49 收货调整单', { type: 'warning' })
    .then(async () => {
      await disposeDifferenceApi(row.id, 'START_ADJUST', '超交转调整')
      ElMessage.success('调整单已发起，待采购经理审批')
      loadDiffs(diffQuery.current)
      loadAdjs()
    }).catch(() => {})
}

function dispose(row, action) {
  const label = action === 'REJECT' ? '拒收超量（该行不入待检不过账）' : '关闭短交差异'
  ElMessageBox.prompt(`${label}，原因（≥2 字）`, row.diffNo, { inputPattern: /^.{2,}$/, inputErrorMessage: '原因不少于 2 字' })
    .then(async ({ value }) => {
      await disposeDifferenceApi(row.id, action, value)
      ElMessage.success('处置完成')
      loadDiffs(diffQuery.current)
    }).catch(() => {})
}

const adjList = ref([])
async function loadAdjs() {
  const { data } = await getAdjustmentPageApi({ current: 1, size: 50 })
  adjList.value = data.records || []
}

function approveAdj(row, approved) {
  ElMessageBox.prompt(
    approved ? '审批意见（批准后立即执行：PO 数量追加、价格不动）' : '驳回意见（≥2 字）',
    `${row.adjNo} · ${approved ? '批准执行' : '驳回'}`,
    { inputPattern: /^.{2,}$/, inputErrorMessage: '意见不少于 2 字' })
    .then(async ({ value }) => {
      const res = await approveAdjustmentApi(row.id, approved, value)
      if (res.data?.status === 'EXECUTED') {
        ElMessage.success(`已执行：新收货单 ${res.data.newGrNo}（待过账）`)
      } else {
        ElMessage.success('已驳回')
      }
      loadAdjs()
      loadDiffs(diffQuery.current)
    }).catch(() => {})
}

// ---------- 待检 ----------
const qcList = ref([])
async function loadQc() {
  const { data } = await getQcHoldApi({ current: 1, size: 100 })
  qcList.value = data.records || []
}

// ---------- 过账 ----------
const postList = ref([])
const postTotal = ref(0)
const postQuery = reactive({ status: 'CREATED', current: 1, size: 10 })
async function loadPosts(page) {
  postQuery.current = page || 1
  loading.value = true
  try {
    const { data } = await getGrPageApi({ current: postQuery.current, size: postQuery.size, status: postQuery.status })
    postList.value = data.records || []
    postTotal.value = Number(data.total) || 0
  } finally { loading.value = false }
}

async function doPosting(row) {
  postingId.value = row.id
  try {
    const res = await postingGrApi(row.id)
    ElMessage.success(`过账成功，入库凭证 ${res.data.gr?.postingDocNo}`)
    loadPosts(postQuery.current)
    loadQc()
    loadGr(1)
  } catch (e) {
    ElMessage.error(e?.message || '过账失败')
  } finally { postingId.value = '' }
}

// ---------- 展示辅助 ----------
const statusName = s => ({ CREATED: '待过账', POSTED: '已过账', CANCELLED: '已作废' }[s] || s)
const statusTag = s => ({ CREATED: 'warning', POSTED: 'success', CANCELLED: 'info' }[s])
const tolName = t => ({ OK_OVER: '容差内', OK_SHORT: '容差内', OVER: '超交', SHORT: '短交', FREE: '免容差' }[t] || t)
const diffName = s => ({ PENDING: '待处置', ADJUSTED: '已转调整', RETURNED: '已拒收', CLOSED: '已关闭' }[s] || s)
const diffTag = s => ({ PENDING: 'danger', ADJUSTED: 'success', RETURNED: 'info', CLOSED: 'info' }[s])
const adjName = s => ({ DRAFT: '草稿', PENDING_APPROVE: '待审批', APPROVED: '已批准', REJECTED: '已驳回', EXECUTED: '已执行' }[s] || s)
const adjTag = s => ({ PENDING_APPROVE: 'warning', REJECTED: 'danger', EXECUTED: 'success' }[s])

onMounted(async () => {
  await loadOptions()
  loadGr(1)
  loadDiffs(1)
  loadAdjs()
  loadQc()
  loadPosts(1)
})
</script>

<style scoped>
.page { padding: 4px; }
.card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.tip { color: #909399; font-size: 12px; }
</style>
