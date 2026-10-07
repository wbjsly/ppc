<template>
  <div class="sc-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="绩效评估（2.9.2）— 供应商绩效记分卡（4.9 流程七）"
      description="质量/交付/成本/响应四维每月 1 日 02:00 自动采集（不可手工修改）；ABCD 分级；D 级冻结新单（BR-4.9-05）；审核→门户公示→7 个工作日申诉→修正版重公示。审核/公示/解冻/申诉复核为高危动作（仅管理员）。" />
    <el-tabs v-model="tab">
      <!-- ===== 记分卡列表 ===== -->
      <el-tab-pane label="记分卡" name="results">
        <el-form inline size="mini">
          <el-form-item label="月份"><el-input v-model="rq.monthTag" placeholder="yyyyMM" style="width:110px" /></el-form-item>
          <el-form-item label="等级">
            <el-select v-model="rq.grade" clearable placeholder="全部" style="width:90px">
              <el-option v-for="g in ['A','B','C','D']" :key="g" :label="g" :value="g" />
            </el-select>
          </el-form-item>
          <el-form-item><el-button type="primary" size="mini" @click="loadResults">查询</el-button></el-form-item>
          <el-form-item><el-button type="warning" size="mini" @click="doCollect">手动触发月度采集</el-button></el-form-item>
        </el-form>
        <el-table :data="results" size="mini" border v-loading="loading">
          <el-table-column prop="monthTag" label="月份" width="70" />
          <el-table-column prop="supplierId" label="供应商" width="130" />
          <el-table-column prop="versionTag" label="版本" width="80" />
          <el-table-column label="质量" width="70" align="center"><template #default="{row}">{{ num(row.qScore) }}</template></el-table-column>
          <el-table-column label="交付" width="70" align="center"><template #default="{row}">{{ num(row.dScore) }}</template></el-table-column>
          <el-table-column label="成本" width="70" align="center"><template #default="{row}">{{ num(row.cScore) }}</template></el-table-column>
          <el-table-column label="响应" width="70" align="center"><template #default="{row}">{{ num(row.rScore) }}</template></el-table-column>
          <el-table-column prop="totalScore" label="总分" width="70" align="center" />
          <el-table-column label="等级" width="70" align="center">
            <template #default="{row}">
              <el-tag size="mini" :type="gradeType(row.grade)">{{ row.grade || (row.dataStatus === 'INCOMPLETE' ? '暂缓' : '异常') }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="dataStatus" label="数据" width="90">
            <template #default="{row}">
              <span :style="{color: row.dataStatus === 'OK' ? '#67C23A' : '#E6A23C'}">{{ dataStatusLabel(row.dataStatus) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="90">
            <template #default="{row}">{{ statusLabel(row.status) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="230">
            <template #default="{row}">
              <el-button type="text" size="mini" @click="openDetail(row)">穿透</el-button>
              <el-button v-if="row.status === 'GENERATED' && isAdmin" type="text" size="mini" @click="doReview(row)">审核</el-button>
              <el-button v-if="row.status === 'REVIEWED' && isAdmin" type="text" size="mini" @click="doPublish(row)">公示</el-button>
              <el-button v-if="row.status === 'PUBLISHED'" type="text" size="mini" @click="openAppealList(row)">申诉记录</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top:8px" layout="total, prev, pager, next" :total="rtotal"
          :page-size="rq.size" :current-page.sync="rq.current" @current-change="loadResults" />

        <el-dialog title="记分卡穿透（计算明细）" :visible.sync="detailVisible" width="760px">
          <el-descriptions :column="2" size="mini" border v-if="detail.result">
            <el-descriptions-item label="供应商">{{ detail.result.supplierId }}</el-descriptions-item>
            <el-descriptions-item label="月份">{{ detail.result.monthTag }}</el-descriptions-item>
            <el-descriptions-item label="总分">{{ detail.result.totalScore }}</el-descriptions-item>
            <el-descriptions-item label="等级">{{ detail.result.grade }}</el-descriptions-item>
            <el-descriptions-item label="上月总分">{{ detail.prevTotal ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="上月等级">{{ detail.prevGrade ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="模型版本">v{{ detail.result.modelVersion }}</el-descriptions-item>
            <el-descriptions-item label="数据状态">{{ dataStatusLabel(detail.result.dataStatus) }}</el-descriptions-item>
          </el-descriptions>
          <pre style="max-height:260px;overflow:auto;background:#f5f7fa;padding:8px;font-size:12px">{{ JSON.stringify(detail.source, null, 2) }}</pre>
        </el-dialog>

        <el-dialog title="申诉记录" :visible.sync="appealVisible" width="700px">
          <el-table :data="appeals" size="mini" border>
            <el-table-column prop="appealNo" label="申诉单号" width="150" />
            <el-table-column prop="status" label="状态" width="90" />
            <el-table-column prop="reason" label="理由" />
            <el-table-column label="复核" width="260">
              <template #default="{row}">
                <template v-if="row.status === 'SUBMITTED' && isAdmin">
                  <el-button type="text" size="mini" @click="reviewAppeal(row, true)">成立(出修正版)</el-button>
                  <el-button type="text" size="mini" @click="reviewAppeal(row, false)">不成立</el-button>
                </template>
                <span v-else>{{ row.conclusion || '-' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-dialog>
      </el-tab-pane>

      <!-- ===== 模型配置 ===== -->
      <el-tab-pane label="模型配置" name="models">
        <el-alert type="warning" :closable="false" style="margin-bottom:8px"
          title="权重合计必须 =100%（L1 硬阻断）；维度指标须先注册口径字典；模型变更走版本+审批留痕" />
        <el-form label-width="110px" size="mini" style="max-width:560px">
          <el-form-item label="模型名称"><el-input v-model="model.name" placeholder="如：通用记分卡模型" /></el-form-item>
          <el-form-item label="质量权重%"><el-input-number v-model="model.wQuality" :min="0" :max="100" /></el-form-item>
          <el-form-item label="交付权重%"><el-input-number v-model="model.wDelivery" :min="0" :max="100" /></el-form-item>
          <el-form-item label="成本权重%"><el-input-number v-model="model.wCost" :min="0" :max="100" /></el-form-item>
          <el-form-item label="响应权重%"><el-input-number v-model="model.wResponse" :min="0" :max="100" /></el-form-item>
          <el-form-item label="权重合计">
            <el-tag :type="weightSum === 100 ? 'success' : 'danger'" size="small">{{ weightSum }}%</el-tag>
          </el-form-item>
          <el-form-item label="A/B/C 阈值">
            <el-input-number v-model="model.threshA" :min="0" :max="100" size="mini" /> /
            <el-input-number v-model="model.threshB" :min="0" :max="100" size="mini" /> /
            <el-input-number v-model="model.threshC" :min="0" :max="100" size="mini" />
          </el-form-item>
          <el-form-item label="成本指数权重"><el-input-number v-model="model.costIndexWeight" :min="0" :max="100" /></el-form-item>
          <el-form-item><el-button type="primary" @click="saveModel">保存模型</el-button></el-form-item>
        </el-form>
        <el-table :data="models" size="mini" border>
          <el-table-column prop="modelCode" label="编码" width="140" />
          <el-table-column prop="name" label="名称" />
          <el-table-column label="权重(质/交/成/响)" width="160">
            <template #default="{row}">{{ row.wQuality }}/{{ row.wDelivery }}/{{ row.wCost }}/{{ row.wResponse }}</template>
          </el-table-column>
          <el-table-column prop="version" label="版本" width="60" />
          <el-table-column prop="status" label="状态" width="80" />
        </el-table>
      </el-tab-pane>

      <!-- ===== 整改跟踪 ===== -->
      <el-tab-pane label="整改跟踪" name="rectifies">
        <el-alert type="error" :closable="false" style="margin-bottom:8px"
          title="D 级自动冻结：冻结期间该供应商创建新采购订单被 L1 阻断（在途不受影响）；整改关闭后须他人解冻审批（双人复核）" />
        <el-table :data="rectifies" size="mini" border>
          <el-table-column prop="rectifyNo" label="整改单号" width="160" />
          <el-table-column prop="supplierId" label="供应商" width="130" />
          <el-table-column prop="triggerType" label="触发" width="130" />
          <el-table-column label="冻结" width="70" align="center">
            <template #default="{row}">
              <el-tag size="mini" :type="row.frozen ? 'danger' : 'info'">{{ row.frozen ? '已冻结' : '否' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="80" />
          <el-table-column prop="dueAt" label="整改期限" width="160" />
          <el-table-column label="操作" width="200">
            <template #default="{row}">
              <el-button v-if="row.status === 'OPEN'" type="text" size="mini" @click="doClose(row)">关闭整改</el-button>
              <el-button v-if="row.frozen && row.status === 'CLOSED'" type="text" size="mini"
                @click="doUnfreeze(row)">解冻审批</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import { getResultsApi, getResultDetailApi, reviewResultApi, publishResultApi,
  getModelsApi, saveModelApi, collectScorecardApi, getRectifiesApi, closeRectifyApi,
  unfreezeApi, getPortalScorecardApi } from '@/api/proc/analysis'
import request from '@/utils/request'

const userStore = useUserStore()
const isAdmin = computed(() => (userStore.userInfo?.roles || []).includes('ROLE_ADMIN'))
const tab = ref('results')
const loading = ref(false)

// ---- 记分卡 ----
const results = ref([])
const rtotal = ref(0)
const rq = reactive({ monthTag: '', grade: '', current: 1, size: 10 })
async function loadResults() {
  loading.value = true
  try {
    const { data } = await getResultsApi({ ...rq })
    results.value = data.records || []
    rtotal.value = Number(data.total) || 0
  } finally {
    loading.value = false
  }
}
async function doCollect() {
  const { data } = await collectScorecardApi(rq.monthTag || undefined)
  ElMessage.success(`采集完成：新建 ${data.created}、跳过 ${data.skipped}（同月幂等不可变）`)
  loadResults()
}
async function doReview(row) {
  const { value } = await ElMessageBox.prompt('审核意见（必填）', '审核记分卡', { inputPattern: /.{2,}/, inputErrorMessage: '不少于 2 字' })
  await reviewResultApi(row.id, value)
  ElMessage.success('审核通过'); loadResults()
}
async function doPublish(row) {
  await ElMessageBox.confirm(`公示 ${row.supplierId} ${row.monthTag} 记分卡至门户？`, '公示确认', { type: 'warning' })
  await publishResultApi(row.id)
  ElMessage.success('已公示（申诉期 7 个工作日）'); loadResults()
}
const detailVisible = ref(false)
const detail = ref({})
async function openDetail(row) {
  const { data } = await getResultDetailApi(row.id)
  detail.value = data
  detailVisible.value = true
}

// ---- 申诉 ----
const appealVisible = ref(false)
const appeals = ref([])
async function openAppealList(row) {
  appealVisible.value = true
  // 申诉列表无独立端点：走详情 + 复核操作由 admin 处理（列表从结果详情接口外的 admin 视图取）
  const { data } = await request.get('/scm/scorecard/results/' + row.id)
  appeals.value = data.appeals || []
  if (!appeals.value.length) {
    // 兜底：显示该结果的申诉状态（后端 detail 不含 appeals 时为空表）
    appeals.value = []
  }
}
async function reviewAppeal(row, confirmed) {
  const { value } = await ElMessageBox.prompt('复核结论（必填）', '申诉复核', { inputPattern: /.{2,}/, inputErrorMessage: '不少于 2 字' })
  const { data } = await request.post(`/scm/scorecard/appeals/${row.id}/review`, { confirmed, conclusion: value })
  ElMessage.success(confirmed ? `申诉成立，修正版 ${data.newResultId ? '已生成并重新公示' : ''}` : '申诉驳回')
  appealVisible.value = false; loadResults()
}

// ---- 模型 ----
const models = ref([])
const model = reactive({ name: '', wQuality: 30, wDelivery: 30, wCost: 20, wResponse: 20,
  threshA: 90, threshB: 75, threshC: 60, costIndexWeight: 60 })
const weightSum = computed(() => model.wQuality + model.wDelivery + model.wCost + model.wResponse)
async function loadModels() {
  const { data } = await getModelsApi({ current: 1, size: 20 })
  models.value = data.records || []
}
async function saveModel() {
  if (weightSum.value !== 100) { ElMessage.error(`权重合计须为 100%，当前 ${weightSum.value}%`); return }
  const { data } = await saveModelApi({ ...model })
  ElMessage.success(`模型已保存（v${data.version}，权重合计 ${data.weightSum}%）`)
  loadModels()
}

// ---- 整改 ----
const rectifies = ref([])
async function loadRectifies() {
  const { data } = await getRectifiesApi({ current: 1, size: 50 })
  rectifies.value = data.records || []
}
async function doClose(row) {
  const { value } = await ElMessageBox.prompt('整改关闭说明', '关闭整改', { inputPattern: /.{2,}/, inputErrorMessage: '不少于 2 字' })
  await closeRectifyApi(row.id, value)
  ElMessage.success('整改已关闭'); loadRectifies()
}
async function doUnfreeze(row) {
  await ElMessageBox.confirm(`解冻 ${row.supplierId}？审批人须与整改关闭人不同（双人复核）。`, '解冻审批', { type: 'warning' })
  await unfreezeApi(row.id)
  ElMessage.success('已解冻，可正常创建新采购订单'); loadRectifies()
}

function num(v) { return v == null ? '-' : v }
function gradeType(g) { return { A: 'success', B: '', C: 'warning', D: 'danger' }[g] || 'info' }
function dataStatusLabel(s) { return { OK: '完整', MISSING_DIM: '指标缺失', INCOMPLETE: '维度缺失·暂缓', ABNORMAL: '异常·转人工' }[s] || s }
function statusLabel(s) { return { GENERATED: '待审核', REVIEWED: '已审核', PUBLISHED: '已公示', SUSPENDED: '暂缓' }[s] || s }

onMounted(() => { loadResults(); loadModels(); loadRectifies() })
</script>
