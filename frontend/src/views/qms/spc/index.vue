<template>
  <div class="spc-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="SPC 过程控制（6.10）：采样组录入 → 控制限（≥25 组 X̄±3σ / 不足按规格折算标「预控制」）→ 三规则告警"
      description="R1 超控制限 / R2 连续 7 点同侧 / R3 连续 7 点单调；同特性同规则只开一条 OPEN 告警；告警处置闭环。" />

    <el-row :gutter="12">
      <!-- 左：采样录入 + 趋势 -->
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>
            <div style="display:flex;gap:10px;align-items:center;">
              <b>特性趋势</b>
              <el-select v-model="selectedChar" placeholder="选择特性" style="width:240px;" filterable
                @change="loadSamples">
                <el-option v-for="t in trends" :key="t.charCode"
                  :label="`${t.charCode} ${t.charName || ''}${t.openAlerts ? '（' + t.openAlerts + ' 告警）' : ''}`"
                  :value="t.charCode" />
              </el-select>
              <span class="spacer" />
              <el-button size="small" type="success" @click="openRecord">录入采样组</el-button>
            </div>
          </template>

          <!-- 内联 SVG 趋势图 -->
          <div v-if="chart.samples.length" class="chart-wrap">
            <svg :viewBox="`0 0 ${chart.w} ${chart.h}`" class="chart" preserveAspectRatio="xMidYMid meet">
              <!-- 网格 -->
              <line v-for="i in 4" :key="'g' + i" :x1="40" :x2="chart.w - 10"
                :y1="20 + (i - 1) * ((chart.h - 50) / 3)" :y2="20 + (i - 1) * ((chart.h - 50) / 3)"
                stroke="#ebeef5" stroke-width="1" />
              <!-- 控制限 -->
              <line :x1="40" :x2="chart.w - 10" :y1="chart.y(chart.ucl)" :y2="chart.y(chart.ucl)"
                stroke="#F56C6C" stroke-width="1.5" stroke-dasharray="6 4" />
              <line :x1="40" :x2="chart.w - 10" :y1="chart.y(chart.center)" :y2="chart.y(chart.center)"
                stroke="#909399" stroke-width="1" stroke-dasharray="3 3" />
              <line :x1="40" :x2="chart.w - 10" :y1="chart.y(chart.lcl)" :y2="chart.y(chart.lcl)"
                stroke="#F56C6C" stroke-width="1.5" stroke-dasharray="6 4" />
              <!-- 折线 -->
              <polyline :points="chart.polyline" fill="none" stroke="#409EFF" stroke-width="2" />
              <circle v-for="(p, i) in chart.points" :key="'p' + i" :cx="p.x" :cy="p.y" r="3.5"
                :fill="p.out ? '#F56C6C' : '#409EFF'" />
              <!-- 标签 -->
              <text :x="chart.w - 8" :y="chart.y(chart.ucl) + 12" font-size="11" fill="#F56C6C" text-anchor="end">
                UCL {{ fmt(chart.ucl) }}
              </text>
              <text :x="chart.w - 8" :y="chart.y(chart.center) + 12" font-size="11" fill="#909399" text-anchor="end">
                CL {{ fmt(chart.center) }}
              </text>
              <text :x="chart.w - 8" :y="chart.y(chart.lcl) - 4" font-size="11" fill="#F56C6C" text-anchor="end">
                LCL {{ fmt(chart.lcl) }}
              </text>
              <text x="40" y="14" font-size="11" fill="#606266">
                {{ modeText }}
              </text>
            </svg>
          </div>
          <el-empty v-else description="选择特性查看趋势" :image-size="70" />

          <el-table :data="chart.samples" size="mini" border max-height="200" style="margin-top:10px;">
            <el-table-column prop="groupNo" label="组" width="60" />
            <el-table-column prop="meanValue" label="均值" width="90" align="right" />
            <el-table-column prop="centerValue" label="中心" width="90" align="right" />
            <el-table-column prop="ucl" label="UCL" width="90" align="right" />
            <el-table-column prop="lcl" label="LCL" width="90" align="right" />
            <el-table-column label="模式" width="100" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.limitMode === 'NORMAL' ? 'success' : 'warning'">
                  {{ row.limitMode === 'NORMAL' ? 'X̄±3σ' : '预控制' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sampleTime" label="时间" min-width="140" />
          </el-table>
        </el-card>
      </el-col>

      <!-- 右：告警 + 趋势汇总 -->
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>
            <div style="display:flex;gap:10px;align-items:center;">
              <b>告警（OPEN {{ openTotal }}）</b>
              <span class="spacer" />
              <el-select v-model="alertStatus" placeholder="状态" clearable style="width:110px;" @change="loadAlerts">
                <el-option label="待处置" value="OPEN" />
                <el-option label="已处置" value="HANDLED" />
              </el-select>
            </div>
          </template>
          <el-table :data="alerts" size="mini" border max-height="320">
            <el-table-column prop="alertNo" label="告警号" width="140" show-overflow-tooltip />
            <el-table-column prop="charCode" label="特性" width="110" />
            <el-table-column label="规则" width="70" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.ruleCode === 'R1' ? 'danger' : 'warning'">{{ row.ruleCode }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="hitValue" label="命中值" width="80" align="right" />
            <el-table-column label="状态" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'OPEN' ? 'danger' : 'success'">
                  {{ row.status === 'OPEN' ? '待处置' : '已处置' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button v-if="row.status === 'OPEN'" link type="primary"
                  @click="openHandle(row)">处置</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!alerts.length" description="无告警" :image-size="60" />
        </el-card>

        <el-card shadow="never" style="margin-top:12px;">
          <template #header><b>特性汇总</b></template>
          <el-table :data="trends" size="mini" border max-height="240">
            <el-table-column prop="charCode" label="特性" width="110" />
            <el-table-column prop="meanValue" label="最新均值" width="90" align="right" />
            <el-table-column label="模式" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.limitMode === 'NORMAL' ? 'success' : 'warning'">
                  {{ row.limitMode === 'NORMAL' ? 'σ' : '预控' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="OPEN 告警" width="90" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.openAlerts > 0" type="danger" size="small">{{ row.openAlerts }}</el-tag>
                <span v-else>0</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 录入采样组 -->
    <el-dialog v-model="recordVisible" title="录入采样组" width="500px">
      <el-form label-width="100px" size="small">
        <el-form-item label="特性编码" required>
          <el-input v-model="recordForm.charCode" placeholder="如 KEY-DIM-01" />
        </el-form-item>
        <el-form-item label="特性名称">
          <el-input v-model="recordForm.charName" />
        </el-form-item>
        <el-form-item label="物料编码">
          <el-input v-model="recordForm.materialCode" placeholder="填物料则自动带出规格（现行标准）" />
        </el-form-item>
        <el-form-item label="均值" required>
          <el-input-number v-model="recordForm.meanValue" :precision="4" />
        </el-form-item>
        <el-form-item label="规格下限">
          <el-input-number v-model="recordForm.specLower" :precision="4" />
        </el-form-item>
        <el-form-item label="规格上限">
          <el-input-number v-model="recordForm.specUpper" :precision="4" />
        </el-form-item>
        <el-form-item label="样本量">
          <el-input-number v-model="recordForm.sampleQty" :min="1" :max="100" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="recordVisible = false">取消</el-button>
        <el-button type="primary" @click="doRecord">录入</el-button>
      </template>
    </el-dialog>

    <!-- 告警处置 -->
    <el-dialog v-model="handleVisible" :title="`告警处置 · ${handleForm.alertNo}`" width="460px">
      <el-input v-model="handleForm.result" type="textarea" :rows="3"
        placeholder="处置结论（原因分析 + 纠正动作，至少 2 字）" />
      <template #footer>
        <el-button @click="handleVisible = false">取消</el-button>
        <el-button type="primary" @click="doHandle">完成处置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { recordSampleApi, samplesApi, alertsApi, handleAlertApi, trendsApi } from '@/api/qms/spc'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const trends = ref([])
const selectedChar = ref('')
const alerts = ref([])
const alertStatus = ref('OPEN')
const openTotal = ref(0)

const chart = reactive({ samples: [], w: 640, h: 260, ucl: 0, lcl: 0, center: 0, modeText: '', points: [], polyline: '' })

const canEdit = ['ROLE_ADMIN', 'ROLE_INSPECTOR', 'ROLE_QUALITY_ENG', 'ROLE_QUALITY_MGR']
  .some(r => (userStore.userInfo && userStore.userInfo.roles || []).includes(r))
const modeText = computed(() => chart.modeText)

function fmt(v) {
  const n = Number(v || 0)
  return Number.isInteger(n) ? String(n) : n.toFixed(3)
}

async function loadTrends() {
  const res = await trendsApi()
  trends.value = res.data || []
  if (!selectedChar.value && trends.value.length) {
    selectedChar.value = trends.value[0].charCode
    loadSamples()
  }
}
async function loadSamples() {
  if (!selectedChar.value) return
  const res = await samplesApi(selectedChar.value, 60)
  const list = res.data || []
  chart.samples = list
  if (!list.length) { chart.points = []; chart.polyline = ''; return }
  const last = list[list.length - 1]
  chart.ucl = Number(last.ucl)
  chart.lcl = Number(last.lcl)
  chart.center = Number(last.centerValue)
  chart.modeText = `${selectedChar.value} · ${last.limitMode === 'NORMAL' ? 'X̄±3σ 控制图（≥25 组）' : '预控制图（规格折算，<25 组）'}`

  const vals = list.map(x => Number(x.meanValue))
  const inSpec = [chart.lcl, chart.ucl, ...vals]
  const lo = Math.min(...inSpec)
  const hi = Math.max(...inSpec)
  const pad = (hi - lo) * 0.1 || 1
  const yMin = lo - pad
  const yMax = hi + pad
  const y = v => 20 + ((yMax - Number(v)) / (yMax - yMin || 1)) * (chart.h - 50)
  const x = i => 45 + (i / Math.max(list.length - 1, 1)) * (chart.w - 65)
  chart.points = vals.map((v, i) => ({ x: x(i), y: y(v), out: v > chart.ucl || v < chart.lcl }))
  chart.polyline = chart.points.map(p => `${p.x},${p.y}`).join(' ')
  chart.y = y
}

async function loadAlerts() {
  const res = await alertsApi({ current: 1, size: 50, status: alertStatus.value || undefined })
  alerts.value = (res.data && res.data.records) || []
  openTotal.value = alerts.value.filter(a => a.status === 'OPEN').length
}

// 录入
const recordVisible = ref(false)
const recordForm = reactive({ charCode: '', charName: '', materialCode: '', meanValue: undefined, specLower: undefined, specUpper: undefined, sampleQty: 5 })
function openRecord() {
  recordForm.charCode = ''
  recordForm.charName = ''
  recordForm.materialCode = ''
  recordForm.meanValue = undefined
  recordForm.specLower = undefined
  recordForm.specUpper = undefined
  recordForm.sampleQty = 5
  recordVisible.value = true
}
async function doRecord() {
  try {
    await recordSampleApi({ ...recordForm })
    ElMessage.success('采样组已录入（控制限与三规则已评估）')
    recordVisible.value = false
    if (!selectedChar.value || selectedChar.value === recordForm.charCode) {
      selectedChar.value = recordForm.charCode
    }
    loadSamples()
    loadTrends()
    loadAlerts()
  } catch { /* 拦截器已弹错 */ }
}

// 处置
const handleVisible = ref(false)
const handleForm = reactive({ alertNo: '', id: '' })
function openHandle(row) {
  handleForm.alertNo = row.alertNo
  handleForm.id = row.id
  handleForm.result = ''
  handleVisible.value = true
}
async function doHandle() {
  try {
    await handleAlertApi(handleForm.id, handleForm.result)
    ElMessage.success('告警已处置闭环')
    handleVisible.value = false
    loadAlerts()
    loadTrends()
  } catch { /* 拦截器已弹错 */ }
}

onMounted(async () => {
  if (!userStore.userInfo) {
    try { await userStore.getUserInfo() } catch { /* ignore */ }
  }
  loadTrends()
  loadAlerts()
})
</script>

<style scoped>
.spc-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; }
.spacer { flex: 1; }
.chart-wrap { width: 100%; }
.chart { width: 100%; height: auto; display: block; }
</style>
