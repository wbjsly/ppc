<template>
  <div class="task-detail" v-loading="loading">
    <el-card>
      <template #header>
        <div class="card-header">
          <div class="hd-left">
            <el-button link type="primary" @click="$router.back()">← 返回看板</el-button>
            <span class="ttl">{{ lot?.lotNo || '检验批详情' }}</span>
            <el-tag v-if="lot" size="small" :type="lotStatusTag(lot.status)">{{ lotStatusName(lot.status) }}</el-tag>
            <el-tag v-if="lot?.priority === 'URGENT'" size="small" type="danger">紧急</el-tag>
            <el-tag v-if="lot?.agreeCode" size="small" type="warning" :title="`客户协议 ${lot.agreeCode} 加严`">
              协议 {{ lot.agreeCode }}
            </el-tag>
          </div>
          <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        </div>
      </template>

      <template v-if="lot">
        <!-- 快照头 -->
        <el-descriptions :column="4" border size="small" style="margin-bottom: 12px;">
          <el-descriptions-item label="标准">{{ lot.standardCode || '—' }}
            V{{ lot.standardVersion || '—' }}</el-descriptions-item>
          <el-descriptions-item label="样本量">{{ lot.sampleQty }}（Ac={{ lot.acValue }} / Re={{ lot.reValue }}）</el-descriptions-item>
          <el-descriptions-item label="严格度">{{ lot.strictness }}</el-descriptions-item>
          <el-descriptions-item label="分派质检员">{{ lot.inspectorName || '未分派' }}</el-descriptions-item>
          <el-descriptions-item label="时限">
            <span :style="isOverdue ? 'color:#f56c6c;font-weight:bold' : ''">
              {{ lot.dueTime }}{{ isOverdue ? '（超时）' : '' }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="物料">{{ lot.itemCode }} {{ lot.itemName }}</el-descriptions-item>
          <el-descriptions-item label="批量">{{ lot.lotQty }}</el-descriptions-item>
          <el-descriptions-item label="判定">{{ lot.result }}</el-descriptions-item>
          <el-descriptions-item label="抽样依据" :span="4">{{ lot.sampleBasis }}</el-descriptions-item>
        </el-descriptions>

        <!-- 抽样执行记录（6.3.1，可选留痕） -->
        <el-card shadow="never" style="margin-bottom: 12px;">
          <template #header>
            <div class="card-header">
              <span>抽样执行记录</span>
              <el-button v-if="editable" size="small" type="success" @click="samplingVisible = true">记录抽样</el-button>
            </div>
          </template>
          <el-table v-if="samplingRecords.length" :data="samplingRecords" size="small" border>
            <el-table-column prop="seq" label="#" width="50" />
            <el-table-column label="方法" width="120">
              <template #default="{ row }">{{ methodName(row.sampleMethod) }}</template>
            </el-table-column>
            <el-table-column prop="actualSampleQty" label="实际数量" width="90" align="right" />
            <el-table-column label="触发原因" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.triggerReason === 'INITIAL' ? 'info' : 'warning'">
                  {{ reasonName(row.triggerReason) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="samplerName" label="抽样人" width="100" />
            <el-table-column prop="sampledTime" label="抽样时间" width="160" />
            <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
          </el-table>
          <span v-else style="color: #909399; font-size: 12px;">未记录抽样（可选留痕，不阻断检验）</span>
        </el-card>

        <!-- 分派历史时间线 -->
        <el-collapse style="margin-bottom: 12px;">
          <el-collapse-item title="分派/改派历史" name="assign">
            <div v-if="assignHistory.length" class="assign-log">
              <div v-for="(a, i) in assignHistory" :key="i" class="assign-item">
                <el-tag size="small" :type="a.action === 'REASSIGN' ? 'warning' : 'info'">
                  {{ a.action === 'REASSIGN' ? '改派' : '分派' }}</el-tag>
                <span>{{ a.from || '—' }} → {{ a.to }}</span>
                <span class="ts">{{ a.ts }}</span>
                <span class="reason">{{ a.reason }}</span>
              </div>
            </div>
            <span v-else style="color: #909399; font-size: 12px;">无分派记录</span>
          </el-collapse-item>
        </el-collapse>

        <!-- 检验项录入 -->
        <el-table :data="items" size="small" border style="margin-bottom: 12px;">
          <el-table-column prop="sequenceNo" label="#" width="50" />
          <el-table-column prop="characteristicName" label="检验项" min-width="150" />
          <el-table-column label="CTQ" width="70" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.ctqFlag === '1'" size="small" type="danger">CTQ</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="规格" width="150">
            <template #default="{ row }">
              {{ row.lowerLimit ?? '—' }} ~ {{ row.upperLimit ?? '—' }} {{ row.unit || '' }}
            </template>
          </el-table-column>
          <el-table-column label="实测值" width="140">
            <template #default="{ row }">
              <el-input v-model="row.measuredValue" size="small" :disabled="!editable"
                        :placeholder="row.specType === 'LOOK' ? '外观选判定' : '数值'" />
            </template>
          </el-table-column>
          <el-table-column label="外观判定" width="120">
            <template #default="{ row }">
              <el-select v-model="row.judge" size="small" :disabled="row.specType !== 'LOOK' || !editable"
                         clearable placeholder="—">
                <el-option label="合格 PASS" value="PASS" />
                <el-option label="不合格 FAIL" value="FAIL" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="器具编码" width="140">
            <template #default="{ row }">
              <el-input v-model="row.instrumentCode" size="small" :disabled="!editable" placeholder="可空" />
            </template>
          </el-table-column>
          <el-table-column label="结果" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.judge === 'PASS' ? 'success'
                : (row.judge === 'FAIL' ? 'danger' : 'info')">{{ row.judge || '待录' }}</el-tag>
            </template>
          </el-table-column>
        </el-table>

        <!-- 操作区 -->
        <div class="actions">
          <el-button v-if="canInspect && editable" type="primary" :loading="busy" @click="saveInput">保存录入</el-button>
          <el-button v-if="canInspect" type="warning" :loading="busy" @click="doJudge">自动判定</el-button>
          <el-button v-if="canInspect && lot.result === 'PASS' && lot.status === 'INPUTTING'"
                     type="success" :loading="busy" @click="doRelease">确认合格放行</el-button>
          <el-button v-if="lot.status === 'REVIEWING' && canReview" type="warning"
                     @click="reviewVisible = true">边界复核</el-button>
          <span v-if="abnormal.length" class="abnormal">
            ⚠ 数据异常待二次确认：{{ abnormal.join('、') }}（再次保存即确认保留原值）
          </span>
        </div>

        <!-- 合格证（OQC 放行后） -->
        <el-card v-if="cert" shadow="never" class="cert-card" id="cert-area">
          <template #header>
            <div class="sec-row">
              <span class="sec">检验合格证 {{ cert.certNo }}</span>
              <el-button size="small" @click="printCert">打印</el-button>
            </div>
          </template>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="批号">{{ cert.lotNo }}</el-descriptions-item>
            <el-descriptions-item label="物料">{{ cert.itemCode }} {{ cert.itemName }}</el-descriptions-item>
            <el-descriptions-item label="数量">{{ cert.qty }}</el-descriptions-item>
            <el-descriptions-item label="结论">{{ cert.result }}</el-descriptions-item>
            <el-descriptions-item label="签发人">{{ cert.issuedBy }}</el-descriptions-item>
            <el-descriptions-item label="签发时间">{{ cert.issuedDate }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <!-- 批次时间线 -->
        <el-card shadow="never" class="cert-card">
          <template #header><span class="sec">批次时间线</span></template>
          <el-timeline>
            <el-timeline-item v-for="(t, i) in timeline" :key="i" :type="t.type" :timestamp="t.ts">
              {{ t.text }}
            </el-timeline-item>
          </el-timeline>
        </el-card>
      </template>
      <el-empty v-else description="批次不存在" :image-size="80" />
    </el-card>

    <!-- 边界复核 -->
    <el-dialog v-model="reviewVisible" title="边界复核（L2）" width="460px">
      <el-alert type="warning" :closable="false" style="margin-bottom: 10px;"
                title="不合格数恰好 = Ac（BR-4.12-17）：判定合格但放行前须质量工程师复核。" />
      <el-input v-model="reviewOpinion" type="textarea" :rows="3" placeholder="复核意见（退回时必填）" />
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="danger" :loading="busy" @click="submitReview(false)">退回重检</el-button>
        <el-button type="success" :loading="busy" @click="submitReview(true)">复核通过</el-button>
      </template>
    </el-dialog>

    <!-- 抽样记录弹窗（6.3.1，与看板同一组件） -->
    <SamplingDialog v-model="samplingVisible" :lot="lot" @saved="load" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getLotDetailApi, inputLotApi, judgeLotApi, reviewLotApi, releaseLotApi, getCertificateApi
} from '@/api/qms/inspection'
import SamplingDialog from './components/SamplingDialog.vue'

const route = useRoute()
const userStore = useUserStore()
const roles = computed(() => userStore.userInfo?.roles || [])
const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))
const canInspect = computed(() => isAdmin.value || roles.value.includes('ROLE_INSPECTOR')
  || roles.value.includes('ROLE_QUALITY_ENG'))
const canReview = computed(() => isAdmin.value || roles.value.includes('ROLE_QUALITY_ENG'))

const loading = ref(false)
const busy = ref(false)
const lot = ref(null)
const items = ref([])
const abnormal = ref([])
const cert = ref(null)
const reviewVisible = ref(false)
const reviewOpinion = ref('')
// 抽样执行记录（6.3.1，可选留痕）
const samplingRecords = ref([])
const samplingVisible = ref(false)

function methodName(m) {
  return { RANDOM: '随机抽样', NINE_POINT: '九点取样', FULL: '逐件全检', OTHER: '其他' }[m] || m
}
function reasonName(r) {
  return { INITIAL: '首次', ESCALATE: '加大', RESAMPLE: '补抽' }[r] || r
}

const editable = computed(() => {
  const s = lot.value?.status
  return canInspect.value && ['PENDING', 'INPUTTING'].includes(s)
})
const isOverdue = computed(() => lot.value?.overdueFlag === '1')

const assignHistory = computed(() => {
  try {
    const log = lot.value?.assignLog
    return log && log.trim().startsWith('[') ? JSON.parse(log) : []
  } catch (e) { return [] }
})

const timeline = computed(() => {
  const t = []
  if (lot.value?.createDate) t.push({ ts: lot.value.createDate, text: '批次创建（自动分派质检员）', type: 'primary' })
  for (const a of assignHistory.value) {
    t.push({ ts: a.ts, text: `${a.action === 'REASSIGN' ? '超时改派' : '分派'}：${a.from || '—'} → ${a.to}（${a.reason}）`,
             type: a.action === 'REASSIGN' ? 'warning' : 'info' })
  }
  if (lot.value?.releasedDate) t.push({ ts: lot.value.releasedDate, text: '合格放行', type: 'success' })
  if (cert.value?.issuedDate) t.push({ ts: cert.value.issuedDate, text: `合格证签发 ${cert.value.certNo}`, type: 'success' })
  return t
})

function lotStatusName(s) {
  return { BLOCKED: '无标准阻断', PENDING: '待检', INPUTTING: '录入中', REVIEWING: '边界复核',
           RELEASED: '已放行', FROZEN: '不合格冻结', SKIPPED: '免检', CONCESSION: '让步接收',
           CANCELLED: '已作废' }[s] || s
}
function lotStatusTag(s) {
  return { BLOCKED: 'danger', PENDING: 'primary', INPUTTING: 'warning', REVIEWING: 'warning',
           RELEASED: 'success', FROZEN: 'danger', SKIPPED: 'info' }[s] || 'info'
}

async function load() {
  const id = route.params.id
  if (!id) return
  loading.value = true
  try {
    const res = await getLotDetailApi(id)
    lot.value = res.data?.lot || null
    items.value = (res.data?.items || []).map(i => ({
      ...i,
      measuredValue: i.measuredValue ?? '',
      instrumentCode: i.instrumentCode || ''
    }))
    abnormal.value = []
    samplingRecords.value = res.data?.samplingRecords || []
    // 合格证（放行后可查）
    try {
      const c = await getCertificateApi(id)
      cert.value = c.data || null
    } catch (e) { cert.value = null }
  } catch (e) { /* 拦截器提示 */ } finally { loading.value = false }
}

async function saveInput() {
  busy.value = true
  try {
    const payload = items.value.map(i => ({
      itemId: i.id,
      measuredValue: i.measuredValue === '' || i.measuredValue == null ? null : Number(i.measuredValue),
      judge: i.judge || undefined,
      instrumentCode: i.instrumentCode || undefined,
      remark: i.remark || undefined
    }))
    const res = await inputLotApi(lot.value.id, payload, abnormal.value.length > 0)
    abnormal.value = res.data?.abnormal || []
    ElMessage.success(abnormal.value.length ? '已录入（数据异常待确认）' : '录入成功')
    await load()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}

async function doJudge() {
  busy.value = true
  try {
    const res = await judgeLotApi(lot.value.id)
    const v = res.data?.verdict
    if (v === 'FAIL') ElMessage.error('判定不合格：批次已冻结并生成处置待办')
    else if (v === 'BOUNDARY') ElMessage.warning('边界判定（=Ac）：已转质量工程师复核')
    else ElMessage.success('判定合格，待质检员确认放行')
    await load()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}

async function doRelease() {
  try {
    await ElMessageBox.confirm(`确认放行批次 ${lot.value.lotNo}？放行不可撤回。`, '合格放行', { type: 'warning' })
  } catch (e) { return }
  busy.value = true
  try {
    await releaseLotApi(lot.value.id)
    ElMessage.success('已放行（OQC 完工批已联动入库并签发合格证）')
    await load()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}

async function submitReview(pass) {
  if (!pass && (reviewOpinion.value || '').trim().length < 2) {
    ElMessage.warning('退回须填写意见（≥2 字）')
    return
  }
  busy.value = true
  try {
    await reviewLotApi(lot.value.id, pass, reviewOpinion.value)
    ElMessage.success(pass ? '复核通过，待确认放行' : '已退回重检')
    reviewVisible.value = false
    await load()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}

function printCert() {
  const area = document.getElementById('cert-area')
  if (!area) return
  const printWin = window.open('', '_blank')
  printWin.document.write(`<html><head><title>合格证</title></head><body>${area.outerHTML}</body></html>`)
  printWin.document.close()
  printWin.print()
}

onMounted(load)
</script>

<style scoped>
.task-detail { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.hd-left { display: flex; align-items: center; gap: 8px; }
.ttl { font-weight: bold; font-size: 16px; }
.actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 14px; }
.abnormal { color: #e6a23c; font-size: 13px; }
.sec { font-weight: 600; }
.sec-row { display: flex; justify-content: space-between; align-items: center; }
.cert-card { margin-top: 14px; }
.assign-log { display: flex; flex-direction: column; gap: 6px; }
.assign-item { display: flex; gap: 10px; align-items: center; font-size: 13px; }
.ts { color: #909399; }
.reason { color: #606266; }
@media print {
  body * { visibility: hidden; }
  #cert-area, #cert-area * { visibility: visible; }
  #cert-area { position: absolute; left: 0; top: 0; }
}
</style>
