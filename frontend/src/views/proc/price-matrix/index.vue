<template>
  <div class="page">
    <div class="card-header">
      <span style="font-weight: bold; font-size: 16px;">比价矩阵</span>
      <span class="tip">2.2.4 · FR-4.2-2-2 四维矩阵 · BR-4.2-12 异常 · BR-4.2-14 双轨归档</span>
    </div>

    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="比价矩阵工作台（菜单 2.2.4，独立界面要素）"
      description="单据矩阵：四维结构（价格/交付实算，质量维待 2.4/2.5 接入故锁定 0），权重可调、±20% 异常确认/剔除、谈判双轨、定标与快照归档集中本页；品类/供应商双透视为跨 RFQ 聚合视图。询价流程（创建/发送/报价录入）仍在 2.2.1。" />

    <el-tabs v-model="activeTab">
      <!-- ============ Tab1 单据矩阵 ============ -->
      <el-tab-pane label="单据矩阵" name="doc">
        <div class="toolbar">
          <span class="tip">询价单</span>
          <el-select v-model="rfqId" filterable placeholder="选择 RFQ" style="width: 320px;" @change="loadMatrix">
            <el-option v-for="r in rfqRecords" :key="r.id"
                       :label="`${r.rfqNo}（${rfqStatusName(r.status)}）`" :value="r.id" />
          </el-select>
          <template v-if="matrix.rows && matrix.rows.length">
            <span class="tip" style="margin-left: 12px;">价格权重</span>
            <el-input-number v-model="weightPrice" :min="0" :max="100" :precision="0" size="small"
                             style="width: 90px;" @change="onWeights" />
            <span class="tip">交付权重</span>
            <el-input-number v-model="weightDelivery" :min="0" :max="100" :precision="0" size="small"
                             style="width: 90px;" @change="onWeights" />
            <span class="tip">质量权重</span>
            <el-input-number :model-value="0" :min="0" :max="100" :precision="0" size="small"
                             style="width: 90px;" disabled />
            <el-tag type="info" size="small" style="margin-left: 4px;">待接入 2.4/2.5，锁定 0</el-tag>
            <span class="tip" style="margin-left: 12px;">（价格+交付合计须 100）</span>
          </template>
        </div>

        <template v-if="matrix.rows && matrix.rows.length">
          <!-- 四维结构说明块 -->
          <div class="dim-bar">
            <div class="dim-card">
              <div class="dim-title">价格维度</div>
              <div class="dim-body">单价 · 含税单价<br />权重 <b>{{ matrix.weightPrice }}</b>%（实算）</div>
            </div>
            <div class="dim-card">
              <div class="dim-title">交付维度</div>
              <div class="dim-body">交货期（准时率待接入）<br />权重 <b>{{ matrix.weightDelivery }}</b>%（实算）</div>
            </div>
            <div class="dim-card dim-placeholder">
              <div class="dim-title">质量维度</div>
              <div class="dim-body">来料合格率 · 质量等级<br /><b>待接入 2.4/2.5</b> · 权重 0 锁定</div>
            </div>
            <div class="dim-card">
              <div class="dim-title">综合维度</div>
              <div class="dim-body">加权总分（可得维度归一）<br />下方「综合分」列</div>
            </div>
          </div>

          <div class="toolbar">
            <span class="tip">均值 {{ matrix.mean }} · {{ matrix.taxNote }}</span>
            <span v-if="matrix.anomalyOpenCount > 0" style="color: #f56c6c; margin-left: 12px;">
              {{ matrix.anomalyOpenCount }} 条异常未处理，定标将被阻断
            </span>
          </div>

          <el-table :data="matrix.rows" size="small" border>
            <el-table-column prop="supplierName" label="供应商" min-width="140" />
            <el-table-column prop="unitPrice" label="原始单价" width="100" align="right" />
            <el-table-column label="谈判后" width="100" align="right">
              <template #default="{ row }">{{ row.negotiatedPrice ?? '—' }}</template>
            </el-table-column>
            <el-table-column prop="taxIncluded" label="含税单价" width="100" align="right">
              <template #default="{ row }">{{ row.taxIncluded ?? '待税率' }}</template>
            </el-table-column>
            <el-table-column prop="leadTimeDays" label="交期(天)" width="80" align="center" />
            <el-table-column prop="moq" label="MOQ" width="80" align="right" />
            <el-table-column prop="paymentTerms" label="付款" width="90" />
            <el-table-column prop="quoteValidDate" label="有效期" width="105" />
            <el-table-column label="偏离/异常" width="130">
              <template #default="{ row }">
                <template v-if="row.excluded">
                  <el-tag type="info" size="small">已剔除</el-tag>
                </template>
                <template v-else-if="row.anomaly">
                  <el-tag type="danger" size="small">异常 {{ (row.deviation * 100).toFixed(1) }}%</el-tag>
                  <el-tag v-if="row.anomalyConfirmed === '1' || row.anomalyConfirmed === 1"
                          type="success" size="small" style="margin-left: 2px;">已确认</el-tag>
                </template>
                <span v-else-if="row.deviation != null" style="color: #909399;">{{ (row.deviation * 100).toFixed(1) }}%</span>
                <span v-else style="color: #c0c4cc;">—</span>
              </template>
            </el-table-column>
            <el-table-column label="四维得分" width="200">
              <template #default="{ row }">
                <template v-if="!row.excluded">
                  价 {{ row.priceScore }} · 交 {{ row.deliveryScore }} · 质 — → <b>{{ row.totalScore }}</b>
                </template>
                <span v-else style="color: #c0c4cc;">不计分</span>
              </template>
            </el-table-column>
            <el-table-column label="质量维" width="110">
              <template #default>
                <el-tag type="info" size="small">待接入</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="{ row }">
                <template v-if="isAdmin && !row.excluded && !isAwarded">
                  <el-button v-if="row.anomaly && row.anomalyConfirmed !== '1' && row.anomalyConfirmed !== 1"
                             link type="warning" @click="doConfirmAnomaly(row)">确认</el-button>
                  <el-tag v-else-if="row.anomaly" type="success" size="small">异常已确认</el-tag>
                  <el-button link type="danger" @click="doExclude(row)">剔除</el-button>
                  <el-button link type="primary" @click="doNegotiate(row)">谈判</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>

          <div class="toolbar" style="margin-top: 12px;">
            <el-button type="success" :disabled="!isAdmin || isAwarded" @click="openAward">
              {{ isAwarded ? '已定标（可查比价记录）' : '定标（登记比价分析表并归档快照）' }}
            </el-button>
            <span class="tip" style="margin-left: 8px;">定标动作与 2.2.1 API 同契约；成功后自动生成比价结果快照</span>
          </div>
        </template>
        <el-empty v-else description="该 RFQ 暂无报价或未选择" :image-size="80" />
      </el-tab-pane>

      <!-- ============ Tab2 品类视角 ============ -->
      <el-tab-pane label="品类视角" name="category">
        <div class="toolbar">
          <span class="tip">物料品类</span>
          <el-select v-model="catCode" filterable placeholder="选择品类" style="width: 260px;">
            <el-option v-for="c in categories" :key="c.categoryCode"
                       :label="`[${c.categoryCode}] ${c.categoryName}（${c.rfqCount} 个 RFQ）`"
                       :value="c.categoryCode" />
          </el-select>
          <el-button type="primary" style="margin-left: 8px;" :loading="catLoading" @click="loadCategoryView">查询</el-button>
        </div>

        <el-alert v-if="catView.crossCategoryRfqNos && catView.crossCategoryRfqNos.length"
          type="warning" :closable="false" style="margin-bottom: 10px;"
          :title="`含跨品类 RFQ：${catView.crossCategoryRfqNos.join('、')}（多行多品类，其报价同时计入各品类）`" />

        <el-table v-if="(catView.suppliers || []).length" :data="catView.suppliers" size="small" border>
          <el-table-column prop="supplierName" label="供应商" min-width="140" />
          <el-table-column label="最新报价" width="110" align="right" sortable :sort-method="sortByLatest">
            <template #default="{ row }"><b>{{ row.latestPrice ?? '—' }}</b></template>
          </el-table-column>
          <el-table-column label="历史走势（时间序）" min-width="220">
            <template #default="{ row }">
              <span v-for="(h, i) in row.history" :key="i" style="margin-right: 6px; white-space: nowrap;">
                <span :style="{ color: h.crossCategory ? '#e6a23c' : '#303133' }">{{ h.price }}</span>
                <el-tag v-if="h.crossCategory" size="small" type="warning" style="margin-left: 2px;">跨品类</el-tag>
                <span v-if="i < row.history.length - 1" style="color: #c0c4cc;">→</span>
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="rfqCount" label="参与RFQ" width="90" align="center" />
          <el-table-column label="中标次数" width="90" align="center">
            <template #default="{ row }"><b>{{ row.awardCount }}</b></template>
          </el-table-column>
          <el-table-column label="平均谈判让价" width="120" align="center">
            <template #default="{ row }">
              {{ row.avgNegotiateDiscount != null ? (Number(row.avgNegotiateDiscount) * 100).toFixed(2) + '%' : '—' }}
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="该品类暂无比价数据（点击查询）" :image-size="80" />
      </el-tab-pane>

      <!-- ============ Tab3 供应商视角 ============ -->
      <el-tab-pane label="供应商视角" name="supplier">
        <div class="toolbar">
          <span class="tip">物料品类</span>
          <el-select v-model="supCatCode" filterable placeholder="选择品类" style="width: 240px;" @change="onSupCatChange">
            <el-option v-for="c in categories" :key="c.categoryCode"
                       :label="`[${c.categoryCode}] ${c.categoryName}`" :value="c.categoryCode" />
          </el-select>
          <span class="tip" style="margin-left: 8px;">供应商</span>
          <el-select v-model="supId" filterable placeholder="先选品类" style="width: 220px;">
            <el-option v-for="s in supOptions" :key="s.supplierId" :label="s.supplierName" :value="s.supplierId" />
          </el-select>
          <el-button type="primary" style="margin-left: 8px;" :loading="supLoading" @click="loadSupplierView">查询</el-button>
        </div>

        <template v-if="supView.rows">
          <el-alert v-if="supView.summary" type="info" :closable="false" style="margin-bottom: 10px;"
            :title="`报价 ${supView.summary.quoteCount} 次 · 中标 ${supView.summary.awardCount} 次`"
            :description="`价格走势：${(supView.summary.trend || []).join(' → ') || '—'}　平均谈判让价：${supView.summary.avgNegotiateDiscount != null ? (Number(supView.summary.avgNegotiateDiscount) * 100).toFixed(2) + '%' : '—'}`" />
          <el-table :data="supView.rows" size="small" border>
            <el-table-column prop="rfqNo" label="RFQ 单号" width="160" />
            <el-table-column prop="quoteDate" label="报价时间" width="160" />
            <el-table-column prop="unitPrice" label="原始报价" width="100" align="right" />
            <el-table-column label="谈判后" width="100" align="right">
              <template #default="{ row }">{{ row.negotiatedPrice ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="有效价" width="100" align="right">
              <template #default="{ row }"><b>{{ row.price ?? '—' }}</b></template>
            </el-table-column>
            <el-table-column label="结果" width="110" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.awarded" type="success" size="small">中标</el-tag>
                <el-tag v-else-if="row.excluded" type="info" size="small">已剔除</el-tag>
                <span v-else style="color: #c0c4cc;">未中</span>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="140">
              <template #default="{ row }">
                <el-tag v-if="row.crossCategory" type="warning" size="small">跨品类 RFQ</el-tag>
                <span v-if="row.excludedReason" style="color: #909399; margin-left: 4px;">{{ row.excludedReason }}</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else description="选择品类与供应商后查询" :image-size="80" />
      </el-tab-pane>

      <!-- ============ Tab4 比价记录 ============ -->
      <el-tab-pane label="比价记录" name="records">
        <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
          title="比价结果快照（BR-4.2-14）"
          description="定标时自动归档：四维权重、全部报价原始/谈判双轨价、异常确认与剔除记录、均值与定标结论。可回看、导出 CSV（浏览器打印另行触发）。" />
        <el-table :data="snapshots" size="small" border v-loading="snapLoading">
          <el-table-column prop="rfqNo" label="RFQ 单号" width="160" />
          <el-table-column prop="analysisNo" label="分析表编号" width="160" />
          <el-table-column prop="awardSupplierName" label="中选供应商" min-width="130" />
          <el-table-column prop="awardPrice" label="成交价" width="100" align="right" />
          <el-table-column prop="updateDate" label="归档时间" width="170" />
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openSnapshot(row)">回看</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 原因弹窗（剔除/谈判共用） -->
    <el-dialog v-model="reasonVisible" :title="reasonTitle" width="480px">
      <el-input v-model="reasonText" type="textarea" :rows="3" maxlength="255" :placeholder="reasonPlaceholder" />
      <template #footer>
        <el-button @click="reasonVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitReason">确定</el-button>
      </template>
    </el-dialog>

    <!-- 定标 -->
    <el-dialog v-model="awardVisible" title="定标（比价分析表登记 + 快照归档）" width="560px">
      <el-alert v-if="awardMsg" type="error" show-icon :closable="false" :title="awardMsg" style="margin-bottom: 10px;" />
      <el-form label-width="130px">
        <el-form-item label="中选供应商" required>
          <el-select v-model="awardForm.supplierId" placeholder="选择中选（须有未剔除报价）" style="width: 100%;">
            <el-option v-for="q in awardCandidates" :key="q.supplierId" :label="q.supplierName" :value="q.supplierId" />
          </el-select>
        </el-form-item>
        <el-form-item label="分析表编号" required>
          <el-input v-model="awardForm.analysisNo" maxlength="64" placeholder="如 BJFX-2026-001（≥4 字符）" />
        </el-form-item>
        <el-form-item label="分析结论" required>
          <el-input v-model="awardForm.conclusion" type="textarea" :rows="2" maxlength="500" placeholder="≥2 字" />
        </el-form-item>
        <el-form-item label="权重快照">
          价格 <el-input-number v-model="awardForm.weightPrice" :min="0" :max="100" :precision="0" style="width: 90px;" />
          交付 <el-input-number v-model="awardForm.weightDelivery" :min="0" :max="100" :precision="0" style="width: 90px;" />
          质量 <el-input-number :model-value="0" disabled style="width: 90px;" />
          <span class="tip">合计须 100；质量维锁定 0</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="awardVisible = false">取消</el-button>
        <el-button type="success" :loading="saving" @click="submitAward">定标并归档</el-button>
      </template>
    </el-dialog>

    <!-- 快照回看 -->
    <el-dialog v-model="snapVisible" :title="`比价记录回看 — ${snapMeta.rfqNo || ''}`" width="860px" top="4vh">
      <template v-if="snapData">
        <div class="toolbar">
          <el-button size="small" type="primary" plain @click="exportCsv">导出 CSV</el-button>
          <el-button size="small" @click="printSnap">打印</el-button>
          <span class="tip" style="margin-left: 8px;">
            分析表 {{ snapData.award?.analysisNo }} · schema v{{ snapData.schemaVersion }} · {{ snapData.snapshotDate }}
          </span>
        </div>
        <el-descriptions :column="2" border size="small" style="margin: 8px 0;">
          <el-descriptions-item label="中选供应商">{{ snapData.award?.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="成交价">{{ snapData.award?.awardPrice }}</el-descriptions-item>
          <el-descriptions-item label="权重快照">
            价格 {{ snapData.weights?.price }}% · 交付 {{ snapData.weights?.delivery }}% · 质量 {{ snapData.weights?.quality }}%（锁定）
          </el-descriptions-item>
          <el-descriptions-item label="均值 / 税">{{ snapData.mean }} · {{ snapData.taxNote }}</el-descriptions-item>
          <el-descriptions-item label="分析结论" :span="2">{{ snapData.award?.conclusion }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="snapData.rows || []" size="small" border max-height="420">
          <el-table-column prop="supplierName" label="供应商" min-width="120" />
          <el-table-column prop="unitPrice" label="原始价" width="90" align="right" />
          <el-table-column label="谈判后" width="90" align="right">
            <template #default="{ row }">{{ row.negotiatedPrice ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="leadTimeDays" label="交期" width="70" align="center" />
          <el-table-column label="异常处理" width="130">
            <template #default="{ row }">
              <el-tag v-if="row.excluded" type="info" size="small">已剔除</el-tag>
              <el-tag v-else-if="row.anomaly" type="warning" size="small">
                异常{{ row.anomalyConfirmed === '1' || row.anomalyConfirmed === 1 ? '已确认' : '' }}
              </el-tag>
              <span v-else style="color: #c0c4cc;">—</span>
            </template>
          </el-table-column>
          <el-table-column label="四维得分" width="170">
            <template #default="{ row }">
              <template v-if="!row.excluded">价 {{ row.priceScore }} · 交 {{ row.deliveryScore }} → <b>{{ row.totalScore }}</b></template>
              <span v-else style="color: #c0c4cc;">不计分</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getRfqPageApi, getMatrixApi, confirmAnomalyApi, excludeQuoteApi,
  negotiateQuoteApi, awardRfqApi
} from '@/api/proc/rfq'
import {
  getMatrixCategoriesApi, getCategoryViewApi, getSupplierViewApi,
  getSnapshotsApi, getSnapshotApi
} from '@/api/proc/price-matrix'

const route = useRoute()
const userStore = useUserStore()
const isAdmin = (userStore.userInfo?.roles || []).includes('ROLE_ADMIN')

const activeTab = ref('doc')
const saving = ref(false)
const rfqId = ref('')
const rfqRecords = ref([])
const matrix = ref({ rows: [] })
const weightPrice = ref(60)
const weightDelivery = ref(40)

const rfqStatusName = s => ({
  DRAFT: '草稿', SENT: '已发出', QUOTING: '报价中',
  QUOTED_CLOSED: '已锁价', AWARDED: '已定标', CLOSED: '已关闭'
}[s] || s)

const isAwarded = computed(() => {
  const r = rfqRecords.value.find(x => x.id === rfqId.value)
  return r?.status === 'AWARDED'
})

async function loadRfqs() {
  try {
    const res = await getRfqPageApi({ current: 1, size: 200 })
    rfqRecords.value = res.data.records || []
    if (!rfqId.value && rfqRecords.value.length) {
      const target = route.query.rfqId
      rfqId.value = target && rfqRecords.value.some(r => r.id === target)
        ? target : rfqRecords.value[0].id
      await loadMatrix()
    }
  } catch (e) {
    ElMessage.error(e?.message || '询价单加载失败')
  }
}

async function loadMatrix() {
  if (!rfqId.value) return
  try {
    const res = await getMatrixApi(rfqId.value, weightPrice.value, weightDelivery.value)
    matrix.value = res.data || { rows: [] }
    weightPrice.value = res.data.weightPrice ?? weightPrice.value
    weightDelivery.value = res.data.weightDelivery ?? weightDelivery.value
  } catch (e) {
    matrix.value = { rows: [] }
    ElMessage.error(e?.message || '矩阵计算失败')
  }
}

function onWeights() {
  if (weightPrice.value + weightDelivery.value !== 100) {
    ElMessage.warning('价格 + 交付权重合计须为 100（质量维锁定 0 不参与）')
    return
  }
  loadMatrix()
}

// ---------- 异常/剔除/谈判（与 2.2.1 同 API，矩阵侧操作入口） ----------
async function doConfirmAnomaly(row) {
  try {
    await confirmAnomalyApi(row.quoteId)
    ElMessage.success('已确认保留该异常报价')
    loadMatrix()
  } catch (e) {
    ElMessage.error(e?.message || '确认失败')
  }
}

const reasonVisible = ref(false)
const reasonTitle = ref('')
const reasonPlaceholder = ref('')
const reasonText = ref('')
let reasonAction = null

function askReason(title, placeholder, action) {
  reasonTitle.value = title
  reasonPlaceholder.value = placeholder
  reasonText.value = ''
  reasonAction = action
  reasonVisible.value = true
}

async function submitReason() {
  if (!reasonText.value || reasonText.value.trim().length < 2) {
    ElMessage.warning('原因必填（≥2 字）')
    return
  }
  saving.value = true
  try {
    await reasonAction(reasonText.value.trim())
    reasonVisible.value = false
  } finally {
    saving.value = false
  }
}

function doExclude(row) {
  askReason('剔除报价（不参与均值/加权/定标）', '剔除原因（≥2 字）', async (reason) => {
    try {
      await excludeQuoteApi(row.quoteId, reason)
      ElMessage.success('已剔除')
      loadMatrix()
    } catch (e) {
      ElMessage.error(e?.message || '剔除失败')
    }
  })
}

function doNegotiate(row) {
  askReason('谈判改价（原始报价保留，双轨留存）', '格式：新单价,谈判说明（如 95.5,年度量换价）', async (text) => {
    const [price, ...rest] = text.split(',')
    try {
      await negotiateQuoteApi(row.quoteId, Number(price), rest.join(',').trim())
      ElMessage.success('已记录谈判后报价')
      loadMatrix()
    } catch (e) {
      ElMessage.error(e?.message || '谈判记录失败')
    }
  })
}

// ---------- 定标 ----------
const awardVisible = ref(false)
const awardMsg = ref('')
const awardForm = ref({})
const awardCandidates = computed(() => (matrix.value.rows || []).filter(r => !r.excluded))

function openAward() {
  awardMsg.value = ''
  const c = awardCandidates.value
  awardForm.value = {
    supplierId: c.length === 1 ? c[0].supplierId : '',
    analysisNo: '', conclusion: '',
    weightPrice: weightPrice.value, weightDelivery: weightDelivery.value
  }
  awardVisible.value = true
}

async function submitAward() {
  const f = awardForm.value
  if (!f.supplierId) { ElMessage.warning('请选择中选供应商'); return }
  if (!f.analysisNo || f.analysisNo.trim().length < 4) { ElMessage.warning('分析表编号须 ≥4 字符'); return }
  if (!f.conclusion || f.conclusion.trim().length < 2) { ElMessage.warning('分析结论须 ≥2 字'); return }
  if (Number(f.weightPrice) + Number(f.weightDelivery) !== 100) { ElMessage.warning('权重合计须 100'); return }
  saving.value = true
  try {
    await awardRfqApi(rfqId.value, {
      supplierId: f.supplierId,
      analysisNo: f.analysisNo.trim(),
      conclusion: f.conclusion.trim(),
      weightPrice: Number(f.weightPrice),
      weightDelivery: Number(f.weightDelivery)
      // weightQuality 不传：服务端强制 0（质量维锁定）
    })
    ElMessage.success('定标成功，比价结果快照已归档')
    awardVisible.value = false
    await loadRfqs()
    await loadMatrix()
    activeTab.value = 'records'
    loadSnapshots()
  } catch (e) {
    awardMsg.value = e?.message || '定标失败'
  } finally {
    saving.value = false
  }
}

// ---------- 品类视角 ----------
const categories = ref([])
const catCode = ref('')
const catView = ref({ suppliers: [], crossCategoryRfqNos: [] })
const catLoading = ref(false)

async function loadCategories() {
  try {
    const res = await getMatrixCategoriesApi()
    categories.value = res.data || []
    if (!catCode.value && categories.value.length) {
      catCode.value = categories.value[0].categoryCode
    }
  } catch (e) {
    ElMessage.error(e?.message || '品类加载失败')
  }
}

async function loadCategoryView() {
  if (!catCode.value) { ElMessage.warning('请选择品类'); return }
  catLoading.value = true
  try {
    const res = await getCategoryViewApi(catCode.value)
    catView.value = res.data || { suppliers: [] }
  } catch (e) {
    ElMessage.error(e?.message || '品类视角加载失败')
  } finally {
    catLoading.value = false
  }
}

function sortByLatest(a, b) {
  return (Number(a.latestPrice) || 0) - (Number(b.latestPrice) || 0)
}

// ---------- 供应商视角 ----------
const supCatCode = ref('')
const supId = ref('')
const supOptions = ref([])
const supView = ref({})
const supLoading = ref(false)

async function onSupCatChange() {
  supId.value = ''
  supView.value = {}
  if (!supCatCode.value) return
  try {
    const res = await getCategoryViewApi(supCatCode.value)
    supOptions.value = (res.data || {}).suppliers || []
  } catch (e) {
    ElMessage.error(e?.message || '供应商候选加载失败')
  }
}

async function loadSupplierView() {
  if (!supCatCode.value || !supId.value) { ElMessage.warning('请先选择品类与供应商'); return }
  supLoading.value = true
  try {
    const res = await getSupplierViewApi(supCatCode.value, supId.value)
    supView.value = res.data || {}
  } catch (e) {
    ElMessage.error(e?.message || '供应商视角加载失败')
  } finally {
    supLoading.value = false
  }
}

// ---------- 比价记录（快照） ----------
const snapshots = ref([])
const snapLoading = ref(false)
const snapVisible = ref(false)
const snapData = ref(null)
const snapMeta = ref({})

async function loadSnapshots() {
  snapLoading.value = true
  try {
    const res = await getSnapshotsApi()
    snapshots.value = res.data || []
  } catch (e) {
    ElMessage.error(e?.message || '比价记录加载失败')
  } finally {
    snapLoading.value = false
  }
}

async function openSnapshot(row) {
  try {
    const res = await getSnapshotApi(row.rfqId)
    snapMeta.value = { rfqNo: row.rfqNo }
    snapData.value = res.data.snapshot
    snapVisible.value = true
  } catch (e) {
    ElMessage.error(e?.message || '快照回看失败')
  }
}

function exportCsv() {
  const d = snapData.value
  if (!d) return
  const esc = v => `"${String(v ?? '').replace(/"/g, '""')}"`
  const lines = []
  lines.push(['比价分析表快照'].map(esc).join(','))
  lines.push(['RFQ', snapMeta.value.rfqNo, '分析表', d.award?.analysisNo,
    '中选', d.award?.supplierName, '成交价', d.award?.awardPrice].map(esc).join(','))
  lines.push(['权重', `价格${d.weights?.price}%`, `交付${d.weights?.delivery}%`,
    `质量${d.weights?.quality}%(锁定)`, '均值', d.mean].map(esc).join(','))
  lines.push(['结论', d.award?.conclusion].map(esc).join(','))
  lines.push('')
  const cols = ['supplierName', 'unitPrice', 'negotiatedPrice', 'taxIncluded', 'leadTimeDays',
    'moq', 'paymentTerms', 'quoteValidDate', 'anomaly', 'anomalyConfirmed', 'excluded',
    'priceScore', 'deliveryScore', 'totalScore']
  const heads = ['供应商', '原始单价', '谈判后', '含税单价', '交期', 'MOQ', '付款',
    '有效期', '异常', '异常已确认', '已剔除', '价格分', '交付分', '综合分']
  lines.push(heads.map(esc).join(','))
  for (const r of d.rows || []) {
    lines.push(cols.map(c => esc(r[c])).join(','))
  }
  // BOM 头：Excel 直接打开不乱码
  const blob = new Blob(['\ufeff' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `比价分析_${snapMeta.value.rfqNo || 'snapshot'}_${d.award?.analysisNo || ''}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
  ElMessage.success('CSV 已导出')
}

function printSnap() {
  window.print()
}

onMounted(async () => {
  if (route.query.rfqId) activeTab.value = 'doc'
  await Promise.all([loadRfqs(), loadCategories(), loadSnapshots()])
})
</script>

<style scoped>
.page { padding: 4px; }
.card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.toolbar { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 10px; }
.tip { color: #909399; font-size: 12px; }
.dim-bar { display: flex; gap: 10px; margin-bottom: 10px; }
.dim-card { flex: 1; border: 1px solid #ebeef5; border-radius: 6px; padding: 8px 10px; background: #fafafa; }
.dim-card.dim-placeholder { background: #f4f4f5; border-style: dashed; }
.dim-title { font-weight: bold; font-size: 13px; margin-bottom: 4px; }
.dim-body { font-size: 12px; color: #606266; line-height: 1.6; }
@media print {
  .toolbar, .card-header, .el-tabs__nav-wrap { display: none !important; }
}
</style>
