<template>
  <div class="portal">
    <div class="portal-header">
      <div class="brand">供应商门户</div>
      <div class="who">
        <span>{{ userStore.userInfo?.nickName || userStore.userInfo?.username }}</span>
        <el-button link type="primary" @click="logout">退出登录</el-button>
      </div>
    </div>

    <el-tabs v-model="tab" class="portal-tabs">
      <!-- ============ 我的 PO ============ -->
      <el-tab-pane label="我的 PO" name="po">
        <div class="toolbar">
          <el-input v-model="poFilters.keyword" placeholder="PO 单号" clearable style="width: 200px;"
            @keyup.enter="loadPos" />
          <el-button type="primary" @click="loadPos">查询</el-button>
        </div>
        <el-table :data="poRows" size="small" border v-loading="loading" @expand-change="loadPoTimeline">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="timeline-box">
                <el-timeline v-if="timelineCache[row.id] && timelineCache[row.id].length">
                  <el-timeline-item v-for="t in timelineCache[row.id]" :key="t.id"
                    :timestamp="t.actionAt" :type="t.actionType === 'LOCK' ? 'danger' : 'primary'">
                    【{{ actionLabel(t.actionType) }}】{{ t.detail || '' }}
                  </el-timeline-item>
                </el-timeline>
                <el-empty v-else description="暂无协同动作" :image-size="40" />
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="poNo" label="PO 单号" width="150" />
          <el-table-column prop="totalAmt" label="金额" width="110" />
          <el-table-column label="交期确认" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="confirmTag(row.confirmStatus)">{{ confirmLabel(row.confirmStatus) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="promiseDate" label="承诺交期" width="110" />
          <el-table-column label="超时(时)" width="86">
            <template #default="{ row }">
              <span :style="{ color: row.overdueHours > 0 ? '#F56C6C' : '' }">{{ row.overdueHours || 0 }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <template v-if="!row.locked && row.confirmStatus !== 'CONFIRMED'">
                <el-button link type="primary" @click="openConfirm(row)">确认交期</el-button>
                <el-button link type="warning" @click="openChange(row)">申请改期</el-button>
              </template>
              <span v-if="row.locked" class="locked-tip">确认入口已锁定</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 10px;" layout="total, prev, pager, next" :total="poTotal"
          :page-size="poFilters.size" v-model:current-page="poFilters.current" @current-change="loadPos" />
      </el-tab-pane>

      <!-- ============ 我的 ASN ============ -->
      <el-tab-pane label="我的 ASN" name="asn">
        <div class="toolbar">
          <el-button type="primary" :disabled="!confirmedPos.length" @click="openAsnCreate">创建发货通知（ASN）</el-button>
          <span class="hint">ASN 须基于交期已确认的 PO；发货量超出容差部分由采购与仓库审批</span>
        </div>
        <el-table :data="asnRows" size="small" border v-loading="loading">
          <el-table-column prop="asnNo" label="ASN 单号" width="160" />
          <el-table-column prop="poNo" label="PO" width="140" />
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'CLOSED' ? 'success' : 'primary'">
                {{ row.status === 'CLOSED' ? '已核销' : '待到货' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="超收" width="140">
            <template #default="{ row }">
              <el-tag v-if="row.overStatus === 'PENDING'" type="danger" size="small">待审批 {{ row.overToleranceQty }}</el-tag>
              <el-tag v-else-if="row.overStatus === 'RELEASED'" type="success" size="small">已放行</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column prop="expectArrival" label="预计到货" width="110" />
          <el-table-column prop="logisticsNo" label="物流单号" width="130" />
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openAsnDetail(row)">明细</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 10px;" layout="total, prev, pager, next" :total="asnTotal"
          :page-size="20" v-model:current-page="asnPage" @current-change="loadAsns" />
      </el-tab-pane>

      <!-- ============ 我的 VMI ============ -->
      <el-tab-pane label="我的 VMI" name="vmi">
        <div class="toolbar">
          <el-button type="primary" @click="loadWater">刷新水位同步</el-button>
          <span v-if="waterInfo" class="hint">同步 {{ waterInfo.syncTime }} · {{ waterInfo.written ? '已推送' : '无变化' }}</span>
        </div>
        <el-table :data="waterItems" size="small" border>
          <el-table-column prop="itemCode" label="物料" width="160" />
          <el-table-column prop="itemName" label="名称" min-width="140" show-overflow-tooltip />
          <el-table-column prop="qty" label="寄售库存" width="100" />
          <el-table-column prop="min" label="最低" width="90" />
          <el-table-column prop="max" label="最高" width="90" />
          <el-table-column label="水位" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'LOW' ? 'danger' : (row.status === 'HIGH' ? 'warning' : 'success')">
                {{ row.needReplenish ? 'LOW·请补货' : row.status }}</el-tag>
            </template>
          </el-table-column>
        </el-table>

        <div class="sub-title" style="margin-top: 14px;">补货建议</div>
        <el-table :data="alertRows" size="small" border>
          <el-table-column prop="alertNo" label="告警号" width="150" />
          <el-table-column prop="itemName" label="物料" min-width="140" show-overflow-tooltip />
          <el-table-column prop="currentQty" label="当前库存" width="100" />
          <el-table-column prop="limitQty" label="补货点" width="90" />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'OPEN' ? 'danger' : (row.status === 'CONFIRMED' ? 'warning' : 'success')">
                {{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'OPEN'" link type="primary" @click="openReplenish(row)">确认补货</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="sub-title" style="margin-top: 14px;">待确认结算单</div>
        <el-table :data="settleRows" size="small" border>
          <el-table-column prop="settleNo" label="结算单号" width="150" />
          <el-table-column label="期间" width="200">
            <template #default="{ row }">{{ row.periodStart }} ~ {{ row.periodEnd }}</template>
          </el-table-column>
          <el-table-column prop="calcAmount" label="结算金额" width="110" />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'CONFIRMED' ? 'success' : (row.status === 'ON_HOLD' ? 'warning' : 'info')">
                {{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status !== 'CONFIRMED' && row.status !== 'INVOICED'" link type="primary"
                @click="signSettlement(row)">确认结算</el-button>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ============ 我的对账 ============ -->
      <el-tab-pane label="我的对账" name="recon">
        <el-table :data="stmtRows" size="small" border v-loading="loading">
          <el-table-column prop="stmtNo" label="对账单号" width="150" />
          <el-table-column prop="stmtDate" label="对账日期" width="110" />
          <el-table-column prop="stmtAmount" label="对账金额" width="110" />
          <el-table-column label="差异率" width="90">
            <template #default="{ row }">{{ pct(row.diffRate) }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'EXCEPTION' ? 'danger' : (row.status === 'CLOSED' ? 'success' : 'info')">
                {{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="匹配结果" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.matchAt" size="small" type="success">已匹配</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openStmtDetail(row)">差异清单</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 记分卡（spec supplier-scorecard：仅见自身，C-4.9-06） -->
      <el-tab-pane label="记分卡" name="scorecard">
        <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
          title="供应商绩效记分卡（月度公示）"
          description="质量/交付/成本/响应四维得分与 ABCD 分级；公示后 7 个工作日内可对得分发起申诉，复核成立将生成修正版并重新公示（原版本保留可查）。" />
        <el-table :data="scorecards" size="mini" border v-loading="scLoading">
          <el-table-column prop="monthTag" label="月份" width="80" />
          <el-table-column label="质量" width="70" align="center"><template #default="{row}">{{ row.qScore ?? '-' }}</template></el-table-column>
          <el-table-column label="交付" width="70" align="center"><template #default="{row}">{{ row.dScore ?? '-' }}</template></el-table-column>
          <el-table-column label="成本" width="70" align="center"><template #default="{row}">{{ row.cScore ?? '-' }}</template></el-table-column>
          <el-table-column label="响应" width="70" align="center"><template #default="{row}">{{ row.rScore ?? '-' }}</template></el-table-column>
          <el-table-column prop="totalScore" label="总分" width="70" align="center" />
          <el-table-column label="等级" width="70" align="center">
            <template #default="{row}">
              <el-tag size="mini" :type="{ A: 'success', B: '', C: 'warning', D: 'danger' }[row.grade] || 'info'">{{ row.grade || '-' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="versionTag" label="版本" width="90" />
          <el-table-column prop="publishAt" label="公示时间" width="160" />
          <el-table-column label="操作" width="120">
            <template #default="{row}">
              <el-button type="text" size="mini" @click="openScorecard(row)">查看明细</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!scLoading && !scorecards.length" description="暂无已公示的记分卡" />

        <el-dialog v-model="scDlg" title="记分卡明细" width="720px">
          <el-descriptions :column="2" size="mini" border v-if="scDetail.result">
            <el-descriptions-item label="评分月份">{{ scDetail.result.monthTag }}</el-descriptions-item>
            <el-descriptions-item label="版本">{{ scDetail.result.versionTag }}</el-descriptions-item>
            <el-descriptions-item label="总分">{{ scDetail.result.totalScore }}</el-descriptions-item>
            <el-descriptions-item label="等级">{{ scDetail.result.grade }}</el-descriptions-item>
            <el-descriptions-item label="数据状态">{{ scDetail.result.dataStatus }}</el-descriptions-item>
            <el-descriptions-item label="公示时间">{{ scDetail.result.publishAt }}</el-descriptions-item>
          </el-descriptions>
          <pre style="max-height:240px;overflow:auto;background:#f5f7fa;padding:8px;font-size:12px;margin-top:8px">{{ JSON.stringify(scDetail.source, null, 2) }}</pre>
          <template #footer>
            <el-button @click="scDlg = false">关闭</el-button>
            <el-button type="warning" @click="openAppeal">对得分发起申诉</el-button>
          </template>
        </el-dialog>

        <el-dialog v-model="appealDlg" title="发起申诉（公示后 7 个工作日内）" width="520px">
          <el-input v-model="appealForm.reason" type="textarea" :rows="4"
            placeholder="申诉理由（必填，说明得分异议与依据）" />
          <el-input v-model="appealForm.evidence" style="margin-top:8px"
            placeholder="证据说明（选填，如运输破损记录编号）" />
          <template #footer>
            <el-button @click="appealDlg = false">取消</el-button>
            <el-button type="primary" @click="submitAppeal">提交申诉</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>
    </el-tabs>

    <!-- 交期确认 -->
    <el-dialog v-model="confirmDlg" title="确认交期" width="440px">
      <el-form label-width="90px">
        <el-form-item label="PO"><span>{{ currentPo.poNo }}</span></el-form-item>
        <el-form-item label="承诺交期">
          <el-date-picker v-model="confirmForm.promiseDate" type="date" value-format="YYYY-MM-DD"
            placeholder="默认接受原交期" style="width: 100%;" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="confirmDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitConfirm">确认交期</el-button>
      </template>
    </el-dialog>

    <!-- 改期申请 -->
    <el-dialog v-model="changeDlg" title="申请变更交期" width="440px">
      <el-form label-width="90px">
        <el-form-item label="PO"><span>{{ currentPo.poNo }}</span></el-form-item>
        <el-form-item label="新交期" required>
          <el-date-picker v-model="changeForm.promiseDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="数量建议"><el-input v-model="changeForm.qtyNote" placeholder="可留空" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="changeDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitChange">提交申请</el-button>
      </template>
    </el-dialog>

    <!-- ASN 创建 -->
    <el-dialog v-model="asnCreateDlg" title="创建发货通知（ASN）" width="720px">
      <el-form label-width="90px">
        <el-form-item label="PO" required>
          <el-select v-model="asnForm.poId" filterable placeholder="选择交期已确认的 PO" style="width: 100%;"
            @change="onAsnPoChange">
            <el-option v-for="p in confirmedPos" :key="p.id" :label="p.poNo" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="预计到货">
          <el-date-picker v-model="asnForm.expectArrival" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="物流单号"><el-input v-model="asnForm.logisticsNo" /></el-form-item>
      </el-form>
      <el-table :data="asnForm.lines" size="small" border>
        <el-table-column label="物料" width="260">
          <template #default="{ row }">
            <el-select v-model="row.itemCode" filterable style="width: 100%;" placeholder="选择物料">
              <el-option v-for="l in asnPoLines" :key="l.itemCode" :label="`${l.itemCode}（${l.itemName}）`"
                :value="l.itemCode" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="发货数量" width="170">
          <template #default="{ row }">
            <el-input-number v-model="row.qty" :min="0.001" :precision="3" controls-position="right" style="width: 100%;" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ $index }">
            <el-button link type="danger" @click="asnForm.lines.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button link type="primary" style="margin-top: 6px;" @click="asnForm.lines.push({ itemCode: '', qty: 1 })">+ 加一行</el-button>
      <template #footer>
        <el-button @click="asnCreateDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitAsnCreate">创建 ASN</el-button>
      </template>
    </el-dialog>

    <!-- ASN 明细 -->
    <el-dialog v-model="asnDlg" :title="`ASN 明细 - ${currentAsn.asnNo || ''}`" width="700px">
      <el-table :data="currentAsn.lines || []" size="small" border>
        <el-table-column prop="itemCode" label="物料" width="150" />
        <el-table-column prop="itemName" label="名称" min-width="130" show-overflow-tooltip />
        <el-table-column prop="qty" label="发货量" width="90" />
        <el-table-column prop="overQty" label="超收" width="80" />
        <el-table-column prop="receivedQty" label="已收" width="90" />
        <el-table-column prop="lineStatus" label="状态" width="90" />
      </el-table>
      <template #footer>
        <el-button @click="asnDlg = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 补货确认 -->
    <el-dialog v-model="replenishDlg" title="确认补货（将生成发货通知 ASN）" width="440px">
      <el-form label-width="96px">
        <el-form-item label="物料"><span>{{ currentAlert.itemName || currentAlert.itemCode }}</span></el-form-item>
        <el-form-item label="当前/补货点">{{ currentAlert.currentQty }} / {{ currentAlert.limitQty }}</el-form-item>
        <el-form-item label="确认量">
          <el-input-number v-model="replenishQty" :min="0.001" :precision="3" style="width: 100%;" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="replenishDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitReplenish">确认</el-button>
      </template>
    </el-dialog>

    <!-- 对账差异清单 -->
    <el-dialog v-model="stmtDlg" :title="`对账差异清单 - ${currentStmt.stmtNo || ''}`" width="780px">
      <template v-if="matchView">
        <el-descriptions :column="4" border size="small" style="margin-bottom: 10px;">
          <el-descriptions-item label="ERP 侧">{{ matchView.erpTotal }}</el-descriptions-item>
          <el-descriptions-item label="贵司申报">{{ matchView.supplierTotal }}</el-descriptions-item>
          <el-descriptions-item label="差异">{{ matchView.diffAmount }}</el-descriptions-item>
          <el-descriptions-item label="差异率">{{ pct(matchView.diffRate) }}</el-descriptions-item>
        </el-descriptions>
        <div class="sub-title" style="color: #F56C6C;">差异明细（标红 {{ matchView.redCount || 0 }} 笔）</div>
        <el-table :data="redStmtRows" size="small" border max-height="300">
          <el-table-column prop="flag" label="类型" width="140">
            <template #default="{ row }"><el-tag type="danger" size="small">{{ row.flag }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="desc" label="说明" min-width="160">
            <template #default="{ row }">{{ row.desc || row.accrualNo }}</template>
          </el-table-column>
          <el-table-column prop="amount" label="金额" width="110">
            <template #default="{ row }">{{ row.amount != null ? row.amount : row.openAmount }}</template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty v-else description="尚无匹配结果（等待采购执行系统匹配）" />
      <template #footer>
        <el-button @click="stmtDlg = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { getPortalScorecardsApi, getPortalScorecardApi, portalAppealApi } from '@/api/proc/analysis'
import {
  getMyPosApi, getMyPoDetailApi, getMyPoTimelineApi, confirmMyPoApi, changeMyPoApi,
  getMyAsnsApi, getMyAsnDetailApi, createMyAsnApi,
  getMyWaterApi, getMyAlertsApi, confirmReplenishApi,
  getMySettlementsApi, signMySettlementApi,
  getMyStatementsApi, getMyStatementDetailApi
} from '@/api/portal/supplier'

const router = useRouter()
const userStore = useUserStore()

const tab = ref('po')
const loading = ref(false)
const submitting = ref(false)

// ---------- 我的 PO ----------
const poRows = ref([])
const poTotal = ref(0)
const poFilters = reactive({ current: 1, size: 20, keyword: '' })
const timelineCache = ref({})
const confirmDlg = ref(false)
const changeDlg = ref(false)
const currentPo = ref({})
const confirmForm = reactive({ promiseDate: '' })
const changeForm = reactive({ promiseDate: '', qtyNote: '' })

async function loadPos() {
  loading.value = true
  try {
    const { data } = await getMyPosApi({
      current: poFilters.current, size: poFilters.size, keyword: poFilters.keyword || undefined
    })
    poRows.value = data.records || []
    poTotal.value = Number(data.total) || 0
  } finally {
    loading.value = false
  }
}

async function loadPoTimeline(row) {
  if (timelineCache.value[row.id]) return
  const { data } = await getMyPoTimelineApi(row.id)
  timelineCache.value[row.id] = data || []
}

function openConfirm(row) {
  currentPo.value = row
  confirmForm.promiseDate = ''
  confirmDlg.value = true
}

async function submitConfirm() {
  submitting.value = true
  try {
    await confirmMyPoApi(currentPo.value.id, { ...confirmForm })
    ElMessage.success('交期已确认')
    confirmDlg.value = false
    delete timelineCache.value[currentPo.value.id]
    await loadPos()
  } finally {
    submitting.value = false
  }
}

function openChange(row) {
  currentPo.value = row
  changeForm.promiseDate = ''
  changeForm.qtyNote = ''
  changeDlg.value = true
}

async function submitChange() {
  if (!changeForm.promiseDate) {
    ElMessage.warning('请选择新交期')
    return
  }
  submitting.value = true
  try {
    await changeMyPoApi(currentPo.value.id, { ...changeForm })
    ElMessage.success('改期申请已提交，待采购员处理')
    changeDlg.value = false
    await loadPos()
  } finally {
    submitting.value = false
  }
}

// ---------- 我的 ASN ----------
const asnRows = ref([])
const asnTotal = ref(0)
const asnPage = ref(1)
const asnDlg = ref(false)
const asnCreateDlg = ref(false)
const currentAsn = ref({})
const confirmedPos = ref([])
const asnPoLines = ref([])
const asnForm = reactive({ poId: '', expectArrival: '', logisticsNo: '', lines: [{ itemCode: '', qty: 1 }] })

async function loadAsns() {
  loading.value = true
  try {
    const { data } = await getMyAsnsApi({ current: asnPage.value, size: 20 })
    asnRows.value = data.records || []
    asnTotal.value = Number(data.total) || 0
  } finally {
    loading.value = false
  }
}

async function openAsnCreate() {
  if (!confirmedPos.value.length) {
    const { data } = await getMyPosApi({ current: 1, size: 100, status: 'CONFIRMED' })
    confirmedPos.value = data.records || []
  }
  if (!confirmedPos.value.length) {
    ElMessage.warning('暂无交期已确认的 PO，请先确认交期')
    return
  }
  asnForm.poId = ''
  asnForm.expectArrival = ''
  asnForm.logisticsNo = ''
  asnForm.lines = [{ itemCode: '', qty: 1 }]
  asnPoLines.value = []
  asnCreateDlg.value = true
}

async function onAsnPoChange(poId) {
  const { data } = await getMyPoDetailApi(poId)
  asnPoLines.value = (data && data.lines) || []
  asnForm.lines = [{ itemCode: '', qty: 1 }]
}

async function submitAsnCreate() {
  const lines = asnForm.lines.filter(l => l.itemCode && l.qty > 0)
  if (!asnForm.poId || !lines.length) {
    ElMessage.warning('请选择 PO 并填写有效明细')
    return
  }
  submitting.value = true
  try {
    await createMyAsnApi({
      poId: asnForm.poId, lines,
      expectArrival: asnForm.expectArrival || undefined,
      logisticsNo: asnForm.logisticsNo || undefined
    })
    ElMessage.success('ASN 创建成功')
    asnCreateDlg.value = false
    await loadAsns()
  } finally {
    submitting.value = false
  }
}

async function openAsnDetail(row) {
  const { data } = await getMyAsnDetailApi(row.id)
  currentAsn.value = data
  asnDlg.value = true
}

// ---------- 我的 VMI ----------
const waterInfo = ref(null)
const waterItems = ref([])
const alertRows = ref([])
const settleRows = ref([])
const replenishDlg = ref(false)
const currentAlert = ref({})
const replenishQty = ref(1)

async function loadWater() {
  const { data } = await getMyWaterApi()
  waterInfo.value = data
  const sups = data.suppliers || []
  waterItems.value = sups.length ? sups[0].items || [] : []
}

async function loadAlerts() {
  const { data } = await getMyAlertsApi({ current: 1, size: 50, alertType: 'REPLENISH' })
  alertRows.value = data.records || []
}

async function loadSettlements() {
  const { data } = await getMySettlementsApi({ current: 1, size: 50 })
  settleRows.value = data.records || []
}

function openReplenish(row) {
  currentAlert.value = row
  replenishQty.value = Math.max(0.001, Number(row.limitQty || 1) - Number(row.currentQty || 0))
  replenishDlg.value = true
}

async function submitReplenish() {
  submitting.value = true
  try {
    const { data } = await confirmReplenishApi(currentAlert.value.id, { confirmQty: replenishQty.value })
    await ElMessageBox.alert(`已确认，生成发货通知 ${data.asnNo}`, '补货确认成功', { type: 'success' })
    replenishDlg.value = false
    await Promise.all([loadAlerts(), loadAsns(), loadWater()])
  } finally {
    submitting.value = false
  }
}

async function signSettlement(row) {
  try {
    await ElMessageBox.confirm(`确认结算单 ${row.settleNo}（金额 ${row.calcAmount}）？确认后不可撤销。`,
      '确认结算', { type: 'warning' })
  } catch { return }
  submitting.value = true
  try {
    await signMySettlementApi(row.id, { side: 'supplier' })
    ElMessage.success('结算单已确认')
    await loadSettlements()
  } finally {
    submitting.value = false
  }
}

// ---------- 我的对账 ----------
const stmtRows = ref([])
const stmtDlg = ref(false)
const currentStmt = ref({})
const matchView = ref(null)
const redStmtRows = computed(() => [
  ...(matchView.value?.supplierOnly || []),
  ...(matchView.value?.erpOnly || [])
])

async function loadStatements() {
  loading.value = true
  try {
    const { data } = await getMyStatementsApi({ current: 1, size: 50 })
    stmtRows.value = data.records || []
  } finally {
    loading.value = false
  }
}

async function openStmtDetail(row) {
  currentStmt.value = row
  const { data } = await getMyStatementDetailApi(row.id)
  matchView.value = null
  if (data && data.matchResult) {
    try { matchView.value = JSON.parse(data.matchResult) } catch { matchView.value = null }
  }
  stmtDlg.value = true
}

// ---------- 工具 ----------
function confirmTag(s) {
  return s === 'CONFIRMED' ? 'success' : (s === 'ESCATED' ? 'danger' : (s === 'CHANGE_PENDING' ? 'warning' : 'info'))
}
function confirmLabel(s) {
  return { PUSHED: '待确认', CONFIRMED: '已确认', CHANGE_PENDING: '改期待处理', ESCATED: '已超时' }[s] || (s || '未推送')
}
function actionLabel(t) {
  return { PUSHED: '下达推送', REMIND: '催办', ESCALATE: '升级', CONFIRM: '交期确认',
    CHANGE_REQUEST: '改期申请', CHANGE_ACCEPTED: '改期接受', LOCK: '锁定', UNLOCK: '解锁' }[t] || t
}
function pct(v) {
  if (v == null) return '—'
  return (Number(v) * 100).toFixed(2) + '%'
}
function logout() {
  userStore.logout && userStore.logout()
  router.push('/login')
}

// ---- 记分卡（门户公示） ----
const scorecards = ref([])
const scLoading = ref(false)
const scDlg = ref(false)
const scDetail = ref({})
const currentSc = ref(null)
const appealDlg = ref(false)
const appealForm = reactive({ reason: '', evidence: '' })
async function loadScorecards() {
  scLoading.value = true
  try {
    const { data } = await getPortalScorecardsApi()
    scorecards.value = data || []
  } finally {
    scLoading.value = false
  }
}
async function openScorecard(row) {
  currentSc.value = row
  const { data } = await getPortalScorecardApi(row.id)
  scDetail.value = data
  scDlg.value = true
}
function openAppeal() {
  appealForm.reason = ''
  appealForm.evidence = ''
  appealDlg.value = true
}
async function submitAppeal() {
  if (!appealForm.reason || appealForm.reason.trim().length < 2) {
    ElMessage.warning('申诉理由必填（不少于 2 字）')
    return
  }
  await portalAppealApi(currentSc.value.id, { ...appealForm })
  ElMessage.success('申诉已提交，等待数据治理复核')
  appealDlg.value = false
  scDlg.value = false
}

onMounted(async () => {
  await Promise.all([loadPos(), loadAsns(), loadAlerts(), loadSettlements(), loadStatements()])
  loadWater()
  loadScorecards()
  // 预取已确认 PO 供 ASN 创建
  const { data } = await getMyPosApi({ current: 1, size: 100, status: 'CONFIRMED' })
  confirmedPos.value = data.records || []
})
</script>

<style scoped>
.portal { padding: 12px 16px; }
.portal-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 10px 16px; margin-bottom: 12px;
  background: linear-gradient(90deg, #1d39c4, #4096ff);
  color: #fff; border-radius: 8px;
}
.brand { font-size: 18px; font-weight: 600; }
.who { display: flex; gap: 12px; align-items: center; }
.who .el-button { color: #fff; }
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.hint { color: #909399; font-size: 12px; }
.sub-title { font-size: 13px; font-weight: 600; color: #303133; margin-bottom: 6px; }
.timeline-box { padding: 8px 16px; }
.locked-tip { color: #F56C6C; font-size: 12px; }
</style>
