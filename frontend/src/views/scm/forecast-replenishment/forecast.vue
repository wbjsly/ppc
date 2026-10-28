<template>
  <div class="cpfr-forecast">
    <el-tabs v-model="tab">
      <!-- Tab1 协议管理 -->
      <el-tab-pane label="协议管理" name="agreement">
        <el-card shadow="never">
          <div class="toolbar">
            <el-input v-model="agrKeyword" placeholder="供应商名称" clearable style="width:220px"
                      @keyup.enter="loadAgreements" />
            <el-button type="primary" @click="loadAgreements">查询</el-button>
            <el-button v-if="canConfig" type="success" @click="openAgrForm()">新增协议</el-button>
            <el-button @click="evaluateDeadlines()">时限判定</el-button>
          </div>
          <el-table :data="agreements" border stripe>
            <el-table-column prop="supplierName" label="供应商" min-width="160" />
            <el-table-column prop="cycleType" label="周期" width="70">
              <template #default="{ row }">{{ row.cycleType === 'WEEK' ? '周' : '月' }}</template>
            </el-table-column>
            <el-table-column prop="horizonWeeks" label="展望期(周)" width="90" />
            <el-table-column prop="freezeWeeks" label="冻结周数" width="85" />
            <el-table-column label="弹性区间" width="130">
              <template #default="{ row }">
                W{{ row.elasticStartWeek }}-W{{ row.elasticEndWeek }} ±{{ row.elasticPct }}%
              </template>
            </el-table-column>
            <el-table-column prop="deviationThresholdPct" label="分歧阈值" width="90">
              <template #default="{ row }">±{{ row.deviationThresholdPct }}%</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="pauseReason" label="暂停原因" width="95" />
            <el-table-column prop="expireDate" label="到期日" width="105" />
            <el-table-column label="操作" width="180" fixed="right">
              <template #default="{ row }">
                <el-button v-if="canConfig && row.status !== '已启用'" link type="primary"
                           @click="openAgrForm(row)">编辑</el-button>
                <el-button v-if="canConfig && row.status !== '已启用'" link type="success"
                           @click="doEnable(row)">启用</el-button>
                <el-button v-if="canConfig && row.status === '已启用'" link type="danger"
                           @click="doDisable(row)">停用</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- 协议表单 -->
        <el-dialog v-model="agrVisible" :title="agrForm.id ? '编辑协议' : '新增协议'" width="560px">
          <el-form :model="agrForm" label-width="110px">
            <el-form-item label="供应商ID" required>
              <el-input v-model="agrForm.supplierId" :disabled="!!agrForm.id" placeholder="erp_mdm_supplier.ID" />
            </el-form-item>
            <el-form-item label="供应商名称">
              <el-input v-model="agrForm.supplierName" />
            </el-form-item>
            <el-form-item label="协同周期" required>
              <el-radio-group v-model="agrForm.cycleType">
                <el-radio value="WEEK">周</el-radio>
                <el-radio value="MONTH">月</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="预测展望期" required>
              <el-input-number v-model="agrForm.horizonWeeks" :min="1" :max="52" /> 周
            </el-form-item>
            <el-form-item label="冻结期" required>
              <el-input-number v-model="agrForm.freezeWeeks" :min="1" :max="12" /> 周（不可调）
            </el-form-item>
            <el-form-item label="弹性区间" required>
              W<el-input-number v-model="agrForm.elasticStartWeek" :min="1" :max="52" />
              - W<el-input-number v-model="agrForm.elasticEndWeek" :min="1" :max="52" />
              ±<el-input-number v-model="agrForm.elasticPct" :min="1" :max="100" />%
            </el-form-item>
            <el-form-item label="分歧阈值" required>
              ±<el-input-number v-model="agrForm.deviationThresholdPct" :min="1" :max="100" />%
              <span class="hint">（默认 CPFR_DEVIATION_THRESHOLD ±10%）</span>
            </el-form-item>
            <el-form-item label="到期日">
              <el-date-picker v-model="agrForm.expireDate" type="date" value-format="YYYY-MM-DD" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="agrVisible = false">取消</el-button>
            <el-button type="primary" @click="saveAgr">保存</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- Tab2 预测版本 -->
      <el-tab-pane label="预测版本" name="forecast">
        <el-card shadow="never">
          <div class="toolbar">
            <el-button type="success" @click="headVisible = true">新建预测版本</el-button>
          </div>
          <el-table :data="heads" border stripe>
            <el-table-column prop="periodTag" label="周期" width="110" />
            <el-table-column prop="status" label="状态" width="90" />
            <el-table-column prop="pushIdempotentKey" label="幂等键" width="140" />
            <el-table-column prop="pushedAt" label="推送时间" width="160" />
            <el-table-column label="操作" width="300" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openLines(row)">预测行</el-button>
                <el-button link type="warning" :disabled="row.status !== 'DRAFT'"
                           @click="doGenerate(row)">生成数据包</el-button>
                <el-button link type="success"
                           :disabled="row.status === 'CLOSED'"
                           @click="doPush(row)">推送</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-dialog v-model="headVisible" title="新建预测版本" width="420px">
          <el-form label-width="80px">
            <el-form-item label="周期标识" required>
              <el-input v-model="headForm.periodTag" placeholder="2026W44 或 202610" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="headForm.remark" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="headVisible = false">取消</el-button>
            <el-button type="primary" @click="doCreateHead">创建</el-button>
          </template>
        </el-dialog>

        <!-- 预测行维护 -->
        <el-dialog v-model="linesVisible" :title="`预测行 - ${currentHead?.periodTag || ''}`" width="960px">
          <el-form inline>
            <el-form-item label="协议">
              <el-select v-model="lineForm.agreementId" style="width:240px">
                <el-option v-for="a in agreements" :key="a.id" :value="a.id" :label="a.supplierName" />
              </el-select>
            </el-form-item>
            <el-form-item label="物料">
              <el-input v-model="lineForm.itemCode" style="width:140px" />
            </el-form-item>
            <el-form-item label="周偏移">
              <el-input-number v-model="lineForm.weekOffset" :min="1" :max="52" style="width:110px" />
            </el-form-item>
            <el-form-item label="预测量">
              <el-input-number v-model="lineForm.forecastQty" :min="0" :precision="3" style="width:130px" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="doSaveLine">保存行</el-button>
            </el-form-item>
          </el-form>
          <el-table :data="lines" border stripe max-height="360">
            <el-table-column prop="itemCode" label="物料" width="110" />
            <el-table-column prop="itemName" label="名称" min-width="120" />
            <el-table-column prop="weekOffset" label="周" width="60" />
            <el-table-column prop="forecastQty" label="预测量" width="100" />
            <el-table-column prop="snapshotStock" label="库存快照" width="100" />
            <el-table-column prop="snapshotOnhandPo" label="在途PO快照" width="105" />
            <el-table-column prop="safetyStock" label="安全库存" width="95" />
          </el-table>
        </el-dialog>
      </el-tab-pane>

      <!-- Tab3 反馈监控 -->
      <el-tab-pane label="反馈监控" name="feedback">
        <el-card shadow="never">
          <div class="toolbar">
            <el-select v-model="fbAgreementId" placeholder="协议（可选）" clearable style="width:220px">
              <el-option v-for="a in agreements" :key="a.id" :value="a.id" :label="a.supplierName" />
            </el-select>
            <el-input v-model="fbPeriodTag" placeholder="周期（可选）" clearable style="width:130px" />
            <el-button type="primary" @click="loadFeedbacks">查询</el-button>
          </div>
          <el-table :data="feedbacks" border stripe>
            <el-table-column prop="periodTag" label="周期" width="100" />
            <el-table-column prop="itemCode" label="物料" width="110" />
            <el-table-column prop="weekOffset" label="周" width="60" />
            <el-table-column prop="demandQty" label="需求量" width="95" />
            <el-table-column prop="supplyQty" label="可供量" width="95" />
            <el-table-column prop="status" label="状态" width="95">
              <template #default="{ row }">
                <el-tag :type="fbStatusType(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="feedbackSource" label="来源" width="95" />
            <el-table-column prop="capacityNote" label="产能约束" min-width="120" show-overflow-tooltip />
            <el-table-column prop="riskNote" label="风险提示" min-width="120" show-overflow-tooltip />
            <el-table-column prop="escalateToRole" label="升级至" width="90" />
            <el-table-column prop="submitAt" label="反馈时间" width="160" />
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- Tab4 例外协商 -->
      <el-tab-pane label="例外协商" name="exception">
        <el-card shadow="never">
          <div class="toolbar">
            <el-select v-model="exAgreementId" placeholder="协议（可选）" clearable style="width:220px">
              <el-option v-for="a in agreements" :key="a.id" :value="a.id" :label="a.supplierName" />
            </el-select>
            <el-button type="primary" @click="loadExceptions">查询</el-button>
          </div>
          <el-table :data="exceptions" border stripe>
            <el-table-column prop="periodTag" label="周期" width="100" />
            <el-table-column prop="itemCode" label="物料" width="110" />
            <el-table-column prop="weekOffset" label="周" width="60" />
            <el-table-column prop="demandQty" label="需求" width="90" />
            <el-table-column prop="supplyQty" label="供给" width="90" />
            <el-table-column prop="deviationPct" label="分歧率" width="85">
              <template #default="{ row }">{{ row.deviationPct }}%</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="exStatusType(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="协商历史" min-width="260">
              <template #default="{ row }">
                <div v-for="(h, i) in row.history || []" :key="i" class="history-line">
                  {{ h.operatedAt }} [{{ h.action }}] {{ h.operator }}：
                  {{ h.oldQty ?? '-' }} → {{ h.newQty ?? '-' }} {{ h.remark || '' }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === '待协商'">
                  <el-button link type="success" @click="doConfirm(row, true)">接受</el-button>
                  <el-button link type="danger" @click="doConfirm(row, false)">驳回</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import * as cpfr from '@/api/scm/cpfr'

const user = useUserStore()
const canConfig = computed(() => {
  const perms = user.permissions || []
  return perms.includes('ROLE_SCMDIR') || perms.includes('ROLE_ADMIN')
})

const tab = ref('agreement')

// ---- 协议 ----
const agrKeyword = ref('')
const agreements = ref([])
const agrVisible = ref(false)
const agrForm = ref({})

async function loadAgreements() {
  const res = await cpfr.agreementPage({ keyword: agrKeyword.value, size: 100 })
  agreements.value = res.data.records || []
}
function openAgrForm(row) {
  agrForm.value = row ? { ...row } : {
    cycleType: 'WEEK', horizonWeeks: 13, freezeWeeks: 2,
    elasticStartWeek: 3, elasticEndWeek: 6, elasticPct: 20, deviationThresholdPct: 10
  }
  agrVisible.value = true
}
async function saveAgr() {
  await cpfr.saveAgreement(agrForm.value)
  ElMessage.success('已保存')
  agrVisible.value = false
  loadAgreements()
}
async function doEnable(row) {
  try {
    await cpfr.enableAgreement(row.id)
    ElMessage.success('协同已启用')
    loadAgreements()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message)
  }
}
async function doDisable(row) {
  await cpfr.disableAgreement(row.id)
  ElMessage.success('已停用')
  loadAgreements()
}

// ---- 预测版本 ----
const heads = ref([])
const headVisible = ref(false)
const headForm = ref({})
const linesVisible = ref(false)
const currentHead = ref(null)
const lines = ref([])
const lineForm = ref({})

async function loadHeads() {
  const res = await cpfr.forecastPage({ size: 50 })
  heads.value = res.data.records || []
}
async function doCreateHead() {
  await cpfr.createForecastHead(headForm.value)
  ElMessage.success('已创建')
  headVisible.value = false
  headForm.value = {}
  loadHeads()
}
async function openLines(row) {
  currentHead.value = row
  lineForm.value = { agreementId: '', itemCode: '', weekOffset: 1, forecastQty: 0 }
  linesVisible.value = true
  await loadLines()
}
async function loadLines() {
  const res = await cpfr.forecastLines(currentHead.value.id)
  lines.value = res.data || []
}
async function doSaveLine() {
  await cpfr.saveForecastLine({ headId: currentHead.value.id, ...lineForm.value })
  ElMessage.success('行已保存')
  loadLines()
}
async function doGenerate(row) {
  const res = await cpfr.generatePackage(row.id)
  ElMessage.success(`数据包已生成（${res.data.lineCount} 行，已快照库存/在途/安全库存）`)
}
async function doPush(row) {
  try {
    const res = await cpfr.pushCycle(row.id)
    ElMessage.success(`推送成功，幂等键 ${res.data.idempotentKey}，生成反馈 ${res.data.feedbackRowsCreated} 行`)
    loadHeads()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message)
  }
}

// ---- 反馈监控 ----
const fbAgreementId = ref('')
const fbPeriodTag = ref('')
const feedbacks = ref([])
async function loadFeedbacks() {
  const res = await cpfr.feedbackMonitor({ agreementId: fbAgreementId.value, periodTag: fbPeriodTag.value })
  feedbacks.value = res.data || []
}

// ---- 例外 ----
const exAgreementId = ref('')
const exceptions = ref([])
async function loadExceptions() {
  const res = await cpfr.exceptionList({ agreementId: exAgreementId.value })
  exceptions.value = res.data || []
}
async function doConfirm(row, accept) {
  const { value } = await ElMessageBox.prompt(
    accept ? '请输入接受意见（协商留痕）' : '请输入驳回意见', '终确认', { inputPattern: /.{2,}/, inputValue: accept ? '接受供应商调整量' : '不同意，继续协商' })
  await cpfr.finalConfirmException(row.id, { accept, remark: value })
  ElMessage.success('已处理')
  loadExceptions()
}

function statusType(s) {
  return { '未启用': 'info', '已启用': 'success', '已暂停': 'warning', '待复盘': 'danger' }[s] || 'info'
}
function fbStatusType(s) {
  return { '待反馈': 'info', '已反馈': 'success', '已催办': 'warning', '已升级': 'danger', '默认采纳': 'warning' }[s] || 'info'
}
function exStatusType(s) {
  return { '待协商': 'warning', '已达成': 'success', '已驳回': 'danger' }[s] || 'info'
}

onMounted(() => {
  loadAgreements()
  loadHeads()
  loadFeedbacks()
  loadExceptions()
})
</script>

<style scoped>
.cpfr-forecast { padding: 4px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.hint { color: #909399; font-size: 12px; margin-left: 8px; }
.history-line { font-size: 12px; color: #606266; line-height: 1.6; }
</style>
