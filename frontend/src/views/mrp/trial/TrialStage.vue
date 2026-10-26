<template>
  <div class="trial-stage">
    <el-card shadow="never">
      <template #header>
        <span>5.9 {{ stageTitle }}（{{ stage }}）</span>
        <span class="tip">申请 → 转开 TP- 工单 → 备料派工报工（5.5-5.7 复用）→ 采集 → 总结评审 → 转量产/成本关单</span>
      </template>
      <el-tabs v-model="tab">
        <!-- ============ Tab1 申请与建单 ============ -->
        <el-tab-pane label="申请与建单" name="apply">
          <el-form :inline="true" class="filter-bar" @submit.prevent>
            <el-form-item label="阶段">
              <el-tag type="warning" size="large">{{ stage }}</el-tag>
            </el-form-item>
            <el-form-item>
              <el-button size="small" @click="loadApplies">刷新</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="applies" border stripe size="small" v-loading="applyLoading" max-height="200">
            <el-table-column prop="trialNo" label="申请单号" width="170" />
            <el-table-column prop="productCode" label="产品" width="140" />
            <el-table-column prop="productName" label="名称" min-width="120" />
            <el-table-column prop="qty" label="数量" width="80" align="right" />
            <el-table-column prop="goal" label="验证目标" min-width="160" show-overflow-tooltip />
            <el-table-column label="状态" width="110" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="applyTag(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="210">
              <template #default="{ row }">
                <el-button v-if="row.status === 'DRAFT'" size="small" text type="primary"
                           @click="submitApply(row)">提交审批</el-button>
                <el-button v-if="row.status === 'APPROVED'" size="small" text type="success"
                           @click="convert(row)">转开工单</el-button>
                <span v-if="row.status === 'CONVERTED'" class="tip">→ {{ row.moNo }}</span>
              </template>
            </el-table-column>
            <template #empty>本阶段暂无试产申请（先新建申请提交工艺主管审批）</template>
          </el-table>

          <el-divider content-position="left">新建申请</el-divider>
          <el-form :model="applyForm" label-width="100px" size="small" :inline="true">
            <el-form-item label="产品编码" required>
              <el-input v-model="applyForm.productCode" placeholder="自制件 MAKE" style="width: 200px" />
            </el-form-item>
            <el-form-item label="数量" required>
              <el-input-number v-model="applyForm.qty" :min="1" :precision="0" style="width: 140px" />
            </el-form-item>
            <el-form-item label="验证目标" required>
              <el-input v-model="applyForm.goal" placeholder="缺目标将被退回补充（FR-4.5-8-1）"
                        style="width: 320px" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" size="small" :loading="saving" @click="createApply">建档</el-button>
            </el-form-item>
          </el-form>
          <span class="tip">阶段 {{ stage }} 自动带出；DVT/PVT 须上一阶段总结评审 APPROVED（阶段闸口）</span>
        </el-tab-pane>

        <!-- ============ Tab2 试产工单 ============ -->
        <el-tab-pane label="试产工单" name="mo">
          <el-form :inline="true" class="filter-bar" @submit.prevent>
            <el-form-item label="状态">
              <el-select v-model="moStatus" clearable placeholder="全部" style="width: 150px"
                         @change="loadMos">
                <el-option v-for="s in moStatuses" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button size="small" @click="loadMos">刷新</el-button>
            </el-form-item>
          </el-form>
          <el-table :data="mos" border stripe size="small" v-loading="moLoading">
            <el-table-column prop="moNo" label="试产单号" width="170">
              <template #default="{ row }">
                <el-tag type="warning" size="small">TP</el-tag> {{ row.moNo }}
              </template>
            </el-table-column>
            <el-table-column prop="trialStage" label="阶段" width="70" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="stageTag(row.trialStage)">{{ row.trialStage }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="productCode" label="产品" width="130" />
            <el-table-column prop="qty" label="数量" width="80" align="right" />
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="statusTag(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="阶段推导" width="120" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.phase" size="small" :type="phaseTag(row.phase)">{{ phaseText(row.phase) }}</el-tag>
                <span v-else class="tip">-</span>
              </template>
            </el-table-column>
            <el-table-column prop="shortageFlag" label="齐套" width="70" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.shortageFlag === '1' ? 'danger' : 'success'">
                  {{ row.shortageFlag === '1' ? '缺料' : '齐套' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200">
              <template #default="{ row }">
                <el-button size="small" text type="primary"
                           @click="$router.push({ path: '/m/5.6.1', query: { moNo: row.moNo } })">派工</el-button>
                <el-button size="small" text type="primary" @click="openData(row)">采集</el-button>
                <el-button size="small" text type="primary" @click="openCost(row)">成本</el-button>
              </template>
            </el-table-column>
            <template #empty>本阶段暂无试产工单（在「申请与建单」转开）</template>
          </el-table>
        </el-tab-pane>

        <!-- ============ Tab3 数据采集 ============ -->
        <el-tab-pane label="数据采集" name="data">
          <el-alert v-if="dataMo" :title="`工单 ${dataMo.moNo}（${dataMo.trialStage}）逐工序采集`"
                    type="info" :closable="false" style="margin-bottom: 10px" />
          <el-form v-else label-width="100px" size="small">
            <el-form-item label="工单">
              <span class="tip">先在「试产工单」页点「采集」选入</span>
            </el-form-item>
          </el-form>

          <template v-if="dataMo">
            <el-form :inline="true" size="small" @submit.prevent>
              <el-form-item label="工序号">
                <el-input-number v-model="dataForm.opSeq" :min="1" :controls="false"
                                 style="width: 100px" @change="loadDataForm" />
              </el-form-item>
              <el-form-item>
                <el-button size="small" @click="loadDataForm">带出工时</el-button>
              </el-form-item>
            </el-form>
            <el-alert v-if="dataPrefilled" type="success" :closable="false"
                      title="已带出 5.7 报工工时（setup+run），可覆盖修改" style="margin-bottom: 8px" />
            <el-form :model="dataForm" label-width="110px" size="small">
              <el-row :gutter="12">
                <el-col :span="8">
                  <el-form-item label="实测工时(h)">
                    <el-input-number v-model="dataForm.actualHours" :min="0" :precision="2"
                                     :step="0.5" style="width: 100%" />
                  </el-form-item>
                </el-col>
                <el-col :span="16">
                  <el-form-item label="工艺参数">
                    <el-input v-model="dataForm.params" placeholder="参数名=实测值（多行）" />
                  </el-form-item>
                </el-col>
              </el-row>
              <el-form-item label="不良模式">
                <el-input v-model="dataForm.defectModes" placeholder="如：散热不良 2 台" />
              </el-form-item>
              <el-form-item label="备注">
                <el-input v-model="dataForm.remark" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" size="small" :loading="saving" @click="saveData">保存采集</el-button>
                <span class="tip" style="margin-left: 10px">缺记录将阻断结案（FR-4.5-8-5，关单卡控）</span>
              </el-form-item>
            </el-form>

            <el-divider content-position="left">已采集行</el-divider>
            <el-table :data="dataRows" border size="small" max-height="200">
              <el-table-column prop="opSeq" label="工序" width="70" align="center" />
              <el-table-column prop="opName" label="名称" width="150" />
              <el-table-column prop="actualHours" label="实测工时" width="100" align="right" />
              <el-table-column prop="params" label="参数" min-width="160" show-overflow-tooltip />
              <el-table-column prop="defectModes" label="不良模式" min-width="140" show-overflow-tooltip />
              <template #empty>尚无采集行（已完工工序缺记录将无法结案）</template>
            </el-table>
          </template>
        </el-tab-pane>

        <!-- ============ Tab4 总结评审与转量产 ============ -->
        <el-tab-pane label="总结评审" name="summary">
          <el-table :data="summaries" border stripe size="small" v-loading="sumLoading">
            <el-table-column prop="summaryNo" label="总结单号" width="170" />
            <el-table-column prop="moNo" label="试产单号" width="170" />
            <el-table-column label="良率%" width="90" align="right">
              <template #default="{ row }">
                <span :class="{ warn: row.yieldPct != null && Number(row.yieldPct) < 95 }">
                  {{ fmtPct(row.yieldPct) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="工时达成%" width="110" align="right">
              <template #default="{ row }">{{ fmtPct(row.hoursAchievePct) }}</template>
            </el-table-column>
            <el-table-column prop="qualifiedQty" label="合格" width="70" align="right" />
            <el-table-column prop="issues" label="问题清单" min-width="160" show-overflow-tooltip />
            <el-table-column label="评审" width="110" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="sumTag(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="230">
              <template #default="{ row }">
                <el-button v-if="row.status === 'DRAFT' || row.status === 'REJECTED'"
                           size="small" text type="primary" @click="submitSummary(row)">发起评审</el-button>
                <el-button v-if="row.status === 'APPROVED'" size="small" text type="success"
                           @click="promote(row)">转量产</el-button>
              </template>
            </el-table-column>
            <template #empty>本阶段暂无总结单（完工后在「试产工单-成本」处建档）</template>
          </el-table>

          <el-divider content-position="left">新建/刷新总结（指标服务端计算，客户端值忽略）</el-divider>
          <el-form :inline="true" size="small" @submit.prevent>
            <el-form-item label="试产单">
              <el-select v-model="sumForm.moId" filterable placeholder="选择已完工试产单"
                         style="width: 220px">
                <el-option v-for="m in mos" :key="m.id" :label="m.moNo" :value="m.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="问题清单">
              <el-input v-model="sumForm.issues" style="width: 340px"
                        placeholder="每行一条：现象；建议" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" size="small" :loading="saving" @click="saveSummary">保存总结</el-button>
            </el-form-item>
          </el-form>
          <span class="tip">评审 = 工艺/质量/计划三并行会签；PVT 须良率 ≥ TRIAL_PVT_YIELD_MIN（默认 95%）方可提交</span>
        </el-tab-pane>

        <!-- ============ Tab5 成本与关单 ============ -->
        <el-tab-pane label="成本与关单" name="cost">
          <el-form :inline="true" size="small" @submit.prevent>
            <el-form-item label="试产单">
              <el-select v-model="costMoId" filterable placeholder="选择试产单" style="width: 220px"
                         @change="loadCost">
                <el-option v-for="m in mos" :key="m.id" :label="m.moNo" :value="m.id" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button size="small" @click="loadCost">查询</el-button>
            </el-form-item>
          </el-form>

          <template v-if="cost">
            <el-descriptions :column="4" size="small" border style="margin-bottom: 10px">
              <el-descriptions-item label="归集合计">{{ cost.collectedTotal }}</el-descriptions-item>
              <el-descriptions-item label="冲回待确认">
                <span class="warn">{{ cost.reversalPending }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="冲回已确认">{{ cost.reversalConfirmed }}</el-descriptions-item>
              <el-descriptions-item label="净额">{{ cost.netTotal }}</el-descriptions-item>
            </el-descriptions>

            <el-table :data="cost.rows || []" border size="small" max-height="220">
              <el-table-column prop="entryNo" label="行号" width="160" />
              <el-table-column prop="costType" label="类型" width="110" align="center">
                <template #default="{ row }">
                  <el-tag size="small" :type="costTag(row.costType)">{{ row.costType }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="amount" label="金额" width="110" align="right" />
              <el-table-column prop="drCr" label="借贷方向" width="150" />
              <el-table-column prop="finStatus" label="财务" width="140" align="center">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.finStatus === 'CONFIRMED' ? 'success' : 'warning'">
                    {{ row.finStatus }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="remark" label="摘要" min-width="180" show-overflow-tooltip />
              <template #empty>尚无成本行（完工确认后自动归集）</template>
            </el-table>

            <el-divider content-position="left">关单预检</el-divider>
            <el-form :inline="true" size="small" @submit.prevent>
              <el-form-item>
                <el-button size="small" @click="loadRemainder">查剩余物料</el-button>
                <el-button size="small" type="warning" :disabled="!remainder.length"
                           @click="ackRemainder">确认剩余料留痕</el-button>
                <el-button size="small" type="danger" :disabled="!costMoId" :loading="saving"
                           @click="closeMo">关闭工单</el-button>
              </el-form-item>
            </el-form>
            <el-table :data="remainder" border size="small" max-height="160">
              <el-table-column prop="itemCode" label="物料" width="150" />
              <el-table-column prop="itemName" label="名称" min-width="140" />
              <el-table-column prop="batchNo" label="试产批次" width="140" />
              <el-table-column prop="availableQty" label="可用余量" width="100" align="right" />
              <template #empty>无剩余物料（或未查询）</template>
            </el-table>
            <span class="tip">关单五项卡控：采集完整 / 评审 APPROVED / 成本已归集 / 剩余料确认留痕；关单为终态</span>
          </template>
          <el-empty v-else description="选择试产单查看成本明细与关单预检" :image-size="80" />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createApplyApi, submitApplyApi, getApplyListApi, convertApi,
  getTrialMoListApi, getTrialPhaseApi,
  getDataFormApi, saveDataApi, getDataListApi,
  saveSummaryApi, submitSummaryApi, getSummaryListApi,
  promoteApi, getTrialCostApi, getRemainderApi, ackRemainderApi
} from '@/api/mrp/trial'
import { closePrecheckApi, closeMoApi } from '@/api/mrp/mo'

const route = useRoute()
const stage = computed(() => route.meta.stage || 'EVT')
const stageTitle = computed(() => route.meta.title || '试产管理')

const tab = ref('apply')
const saving = ref(false)

// ================= Tab1 申请与建单 =================
const applies = ref([])
const applyLoading = ref(false)
const applyForm = ref({ productCode: '', qty: 10, goal: '' })

async function loadApplies() {
  applyLoading.value = true
  try {
    const res = await getApplyListApi({ trialStage: stage.value })
    applies.value = res.data || []
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '申请列表加载失败')
  } finally {
    applyLoading.value = false
  }
}

async function createApply() {
  if (!applyForm.value.productCode) return ElMessage.warning('产品编码必填')
  if (!applyForm.value.goal?.trim()) return ElMessage.warning('验证目标必填（缺失将被退回补充）')
  saving.value = true
  try {
    const res = await createApplyApi({ ...applyForm.value, trialStage: stage.value })
    ElMessage.success('申请已建档：' + res.data.trialNo)
    applyForm.value = { productCode: '', qty: 10, goal: '' }
    await loadApplies()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '建档失败')
  } finally {
    saving.value = false
  }
}

async function submitApply(row) {
  try {
    await submitApplyApi(row.trialNo)
    ElMessage.success('已提交工艺主管审批')
    await loadApplies()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '提交失败')
  }
}

async function convert(row) {
  try {
    await ElMessageBox.confirm(
      `转开试产工单？申请 ${row.trialNo}（${row.productCode} × ${row.qty}）`, '转开确认',
      { type: 'warning' })
  } catch { return }
  saving.value = true
  try {
    const res = await convertApi(row.trialNo, null, null, true)
    if (res.data.pendingDiff) {
      ElMessage.warning('PVT 一致性差异待审批（TrialPvtDiff），审批通过后重试转开')
    } else if (res.data.mo) {
      ElMessage.success('已转开工单：' + res.data.mo.moNo)
    }
    await Promise.all([loadApplies(), loadMos()])
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '转开失败')
  } finally {
    saving.value = false
  }
}

function applyTag(s) {
  return { DRAFT: 'info', PENDING: 'warning', APPROVED: 'success',
    REJECTED: 'danger', CONVERTED: '' }[s] || 'info'
}

// ================= Tab2 试产工单 =================
const mos = ref([])
const moLoading = ref(false)
const moStatus = ref('')
const moStatuses = ['PLANNED', 'PENDING', 'CONFIRMED', 'RELEASED', 'COMPLETED', 'CLOSED', 'HOLD', 'CANCELLED']

async function loadMos() {
  moLoading.value = true
  try {
    const params = { trialStage: stage.value }
    if (moStatus.value) params.status = moStatus.value
    const res = await getTrialMoListApi(params)
    mos.value = res.data || []
    for (const m of mos.value) {
      if (['COMPLETED', 'CLOSED'].includes(m.status)) {
        try {
          const p = await getTrialPhaseApi(m.id)
          m.phase = p.data
        } catch { m.phase = null }
      } else {
        m.phase = null
      }
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '工单列表加载失败')
  } finally {
    moLoading.value = false
  }
}

function statusTag(s) {
  return { PLANNED: 'info', PENDING: 'warning', CONFIRMED: '', RELEASED: 'success',
    COMPLETED: 'success', CLOSED: 'info', HOLD: 'warning', CANCELLED: 'danger' }[s] || 'info'
}
function stageTag(s) {
  return { EVT: 'warning', DVT: '', PVT: 'success' }[s] || 'info'
}
function phaseTag(p) {
  return { EXECUTING: '', PENDING_INSP: 'warning', PENDING_STOCK_IN: 'danger',
    PENDING_REVIEW: 'warning', PENDING_CLOSE: '', CLOSED: 'info' }[p] || 'info'
}
function phaseText(p) {
  return { EXECUTING: '执行中', PENDING_INSP: '待检验', PENDING_STOCK_IN: '待入库',
    PENDING_REVIEW: '待评审', PENDING_CLOSE: '待关闭', CLOSED: '终态' }[p] || p
}

// ================= Tab3 数据采集 =================
const dataMo = ref(null)
const dataPrefilled = ref(false)
const dataRows = ref([])
const dataForm = ref({ opSeq: 10, actualHours: 0, params: '', defectModes: '', remark: '' })

function openData(row) {
  dataMo.value = row
  tab.value = 'data'
  dataForm.value = { opSeq: 10, actualHours: 0, params: '', defectModes: '', remark: '' }
  dataPrefilled.value = false
  loadDataForm()
  loadDataRow()
}

async function loadDataForm() {
  if (!dataMo.value) return
  try {
    const res = await getDataFormApi(dataMo.value.id, dataForm.value.opSeq)
    const d = res.data?.data || {}
    dataPrefilled.value = res.data?.prefilled === true
    dataForm.value.actualHours = d.actualHours != null ? Number(d.actualHours) : 0
    dataForm.value.params = d.params || ''
    dataForm.value.defectModes = d.defectModes || ''
    dataForm.value.remark = d.remark || ''
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '表单带出失败')
  }
}

async function loadDataRow() {
  if (!dataMo.value) return
  try {
    const res = await getDataListApi(dataMo.value.id)
    dataRows.value = res.data || []
  } catch { dataRows.value = [] }
}

async function saveData() {
  if (!dataMo.value) return
  saving.value = true
  try {
    await saveDataApi({ moId: dataMo.value.id, ...dataForm.value })
    ElMessage.success('采集已保存（upsert 按工单+工序）')
    await loadDataRow()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

// ================= Tab4 总结评审 =================
const summaries = ref([])
const sumLoading = ref(false)
const sumForm = ref({ moId: '', issues: '' })

async function loadSummaries() {
  sumLoading.value = true
  try {
    const res = await getSummaryListApi({ trialStage: stage.value })
    summaries.value = res.data || []
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '总结列表加载失败')
  } finally {
    sumLoading.value = false
  }
}

async function saveSummary() {
  if (!sumForm.value.moId) return ElMessage.warning('请选择试产单')
  saving.value = true
  try {
    const res = await saveSummaryApi(sumForm.value)
    ElMessage.success('总结已保存：' + res.data.summaryNo + '（指标服务端重算）')
    sumForm.value = { moId: '', issues: '' }
    await loadSummaries()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function submitSummary(row) {
  try {
    await submitSummaryApi(row.summaryNo)
    ElMessage.success('已发起跨部门会签（工艺/质量/计划三并行）')
    await loadSummaries()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '发起失败')
  }
}

async function promote(row) {
  try {
    await ElMessageBox.confirm(
      `转量产复制？${row.moNo} 的试产 BOM/路线将克隆为量产草稿（原试产版不动）`,
      '转量产确认', { type: 'warning' })
  } catch { return }
  saving.value = true
  try {
    const res = await promoteApi(row.moId)
    ElMessage.success(`已复制：BOM ${res.data.bomVersion} / 路线 ${res.data.routingVersion}，请到发布页提交审核`)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '转量产失败')
  } finally {
    saving.value = false
  }
}

function sumTag(s) {
  return { DRAFT: 'info', PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger' }[s] || 'info'
}
function fmtPct(v) {
  if (v == null) return '-'
  return Number(v).toFixed(2)
}

// ================= Tab5 成本与关单 =================
const costMoId = ref('')
const cost = ref(null)
const remainder = ref([])

async function openCost(row) {
  costMoId.value = row.id
  tab.value = 'cost'
  remainder.value = []
  await loadCost()
}

async function loadCost() {
  if (!costMoId.value) return
  try {
    const res = await getTrialCostApi(costMoId.value)
    cost.value = res.data
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '成本查询失败')
  }
}

async function loadRemainder() {
  if (!costMoId.value) return
  try {
    const res = await getRemainderApi(costMoId.value)
    remainder.value = res.data || []
    if (!remainder.value.length) ElMessage.success('无剩余物料')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '查询失败')
  }
}

async function ackRemainder() {
  try {
    await ElMessageBox.confirm(
      `确认剩余物料处理留痕（${remainder.value.length} 项余量：退库/转样品库存）？`,
      '剩余料确认', { type: 'warning' })
  } catch { return }
  try {
    await ackRemainderApi(costMoId.value, '前端确认：余量已退库或转样品库存')
    ElMessage.success('确认留痕完成，可关闭工单')
    await loadRemainder()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '确认失败')
  }
}

async function closeMo() {
  const pre = await requestPrecheck()
  if (pre && pre.passed === false) {
    const msgs = (pre.checks || []).filter(c => !c.passed).map(c => `${c.name}：${c.msg || '未通过'}`)
    return ElMessageBox.alert(msgs.join('\n'), '关单预检未通过', { type: 'error' })
  }
  try {
    await ElMessageBox.confirm('关闭试产工单？关单为终态，不可回退。', '关单确认',
      { type: 'error', confirmButtonText: '确认关闭' })
  } catch { return }
  saving.value = true
  try {
    await closeMoApi(costMoId.value)
    ElMessage.success('工单已关闭（终态）')
    await loadMos()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '关单失败')
  } finally {
    saving.value = false
  }
}

async function requestPrecheck() {
  try {
    const res = await closePrecheckApi(costMoId.value)
    return res.data
  } catch {
    // 无预检结果时交由服务端卡控直接报错
    return null
  }
}

function costTag(t) {
  return { MATERIAL: '', LABOR: 'success', OVERHEAD: 'warning', REVERSAL: 'danger' }[t] || 'info'
}

onMounted(() => {
  loadApplies()
  loadMos()
  loadSummaries()
})
</script>

<style scoped>
.trial-stage { padding: 4px; }
.tip { color: var(--el-text-color-secondary); font-size: 12px; }
.filter-bar { margin-bottom: 4px; }
.warn { color: var(--el-color-danger); font-weight: 600; }
</style>
