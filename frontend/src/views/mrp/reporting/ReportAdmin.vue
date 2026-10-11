<template>
  <div>
    <el-page-header content="报工管理（5.7.2）— 记录查询 / 冲销发起（原因必填，PLAN_MGR 签）/ 完工确认（倒冲补足）/ 良率冻结与 NCR 入口"
                    style="margin-bottom: 8px" />

    <!-- 查询条件 -->
    <div class="toolbar">
      <el-input v-model="qMoNo" placeholder="工单号" clearable style="width: 160px" size="small"
                @keyup.enter="load" @clear="load" />
      <el-input v-model="qOpSeq" placeholder="工序序号" clearable style="width: 100px" size="small"
                @keyup.enter="load" @clear="load" />
      <el-select v-model="qStatus" placeholder="状态" clearable style="width: 130px" size="small" @change="load">
        <el-option label="进行中" value="OPEN" />
        <el-option label="已确认" value="CONFIRMED" />
        <el-option label="已冲销" value="REVERSED" />
      </el-select>
      <el-button size="small" type="primary" @click="load">查询</el-button>
      <span class="tip">确认后只读（BR-4.5-25）；冲销走 ReportReversal 审批（计划主管签）</span>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe size="small" max-height="300"
              highlight-current-row @current-change="onPick">
      <el-table-column prop="rptNo" label="报工单号" width="150" />
      <el-table-column prop="moNo" label="工单" width="140" />
      <el-table-column prop="opSeq" label="序" width="50" align="center" />
      <el-table-column prop="opName" label="工序" width="110" show-overflow-tooltip />
      <el-table-column prop="wcCode" label="WC" width="110" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'CONFIRMED' ? 'success'
            : row.status === 'REVERSED' ? 'info' : 'warning'">
            {{ row.status === 'CONFIRMED' ? '已确认' : row.status === 'REVERSED' ? '已冲销' : '进行中' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="标记" width="150">
        <template #default="{ row }">
          <el-tag v-if="row.overprodFlag === '1'" size="small" type="danger">超产挂起</el-tag>
          <el-tag v-if="row.hourAlertFlag === '1' && !row.hourConfirmBy" size="small" type="warning">
            工时待确认
          </el-tag>
          <el-tag v-if="row.proxyFlag === '1'" size="small" type="info">代报</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createDate" label="时间" width="150" />
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="无报工记录" :image-size="50" />

    <!-- 详情 + 管理动作 -->
    <el-card v-if="picked" shadow="never" style="margin-top: 12px">
      <template #header>
        <div class="card-head">
          <strong>{{ picked.rptNo }}</strong>
          <span class="tip">{{ picked.moNo }} / 工序{{ picked.opSeq }} {{ picked.opName }}</span>
          <el-button size="small" type="danger" plain :disabled="picked.status !== 'CONFIRMED'"
                     @click="reversalVisible = true">
            发起冲销
          </el-button>
          <el-button size="small" type="success" plain @click="doComplete">
            完工确认（{{ picked.moNo }}）
          </el-button>
        </div>
      </template>

      <!-- 投料 / 产出 -->
      <el-row :gutter="12">
        <el-col :span="12">
          <strong>投料行</strong>
          <el-table :data="feeds" border stripe size="small" max-height="160">
            <el-table-column prop="itemCode" label="物料" width="130" />
            <el-table-column prop="batchCode" label="批次" width="110">
              <template #default="{ row }">{{ row.batchCode || '-' }}</template>
            </el-table-column>
            <el-table-column prop="qty" label="数量" width="80" align="right" />
            <el-table-column label="类型" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.flowType === 'BFLUSH' ? 'info' : 'success'">
                  {{ row.flowType === 'BFLUSH' ? '倒冲' : '手工' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-col>
        <el-col :span="12">
          <strong>产出</strong>
          <el-descriptions :column="2" size="small" border>
            <el-descriptions-item label="合格">{{ output?.goodQty ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="不合格">{{ output?.ngQty ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="处置">{{ output?.ngDispose || '-' }}</el-descriptions-item>
            <el-descriptions-item label="NCR">
              <el-tag v-if="output?.ncrId" size="small" type="danger">已建 NCR</el-tag>
              <span v-else-if="output?.directDisposeFlag === '1'">小额域内处置</span>
              <span v-else>-</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-col>
      </el-row>
    </el-card>

    <!-- 良率与冻结 -->
    <div class="toolbar" style="margin-top: 16px">
      <strong>工序良率</strong>
      <el-input v-model="yieldMoId" placeholder="工单 ID" clearable style="width: 220px" size="small" />
      <el-input v-model="yieldOpSeq" placeholder="工序序号" clearable style="width: 100px" size="small" />
      <el-button size="small" type="primary" @click="loadYield">计算</el-button>
    </div>
    <el-descriptions v-if="yieldInfo" :column="4" size="small" border>
      <el-descriptions-item label="累计投入">{{ yieldInfo.inputQty }}</el-descriptions-item>
      <el-descriptions-item label="累计合格">{{ yieldInfo.goodQty }}</el-descriptions-item>
      <el-descriptions-item label="实际良率">{{ yieldInfo.actualYield ?? '-' }}%</el-descriptions-item>
      <el-descriptions-item label="标准良率">{{ yieldInfo.standardYield ?? '-' }}%</el-descriptions-item>
      <el-descriptions-item label="偏差">{{ yieldInfo.deviation ?? '-' }}%</el-descriptions-item>
      <el-descriptions-item label="确认单数">{{ yieldInfo.rptCount }}</el-descriptions-item>
      <el-descriptions-item label="超阈值" :span="2">
        <el-tag :type="yieldInfo.exceeded ? 'danger' : 'success'" size="small">
          {{ yieldInfo.exceeded ? '已超 YIELD_TOLERANCE（冻结/NCR 已触发）' : '正常' }}
        </el-tag>
      </el-descriptions-item>
    </el-descriptions>

    <!-- 冲销对话框 -->
    <el-dialog v-model="reversalVisible" title="发起冲销（原因必填，计划主管签）" width="480px">
      <el-input v-model="reversalReason" type="textarea" :rows="3"
                placeholder="冲销原因（必填，将随审批流转并留痕）" />
      <template #footer>
        <el-button @click="reversalVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!reversalReason || reversalReason.trim().length === 0"
                   @click="doReversal">
          提交申请
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getReportingListApi, getReportingDetailApi, getFeedsApi, getOutputApi,
  applyReversalApi, completeMoApi, getYieldApi
} from '@/api/mrp/reporting'

const qMoNo = ref('')
const qOpSeq = ref('')
const qStatus = ref('')
const rows = ref([])
const loading = ref(false)
const picked = ref(null)
const feeds = ref([])
const output = ref(null)

const reversalVisible = ref(false)
const reversalReason = ref('')

const yieldMoId = ref('')
const yieldOpSeq = ref('')
const yieldInfo = ref(null)

async function load() {
  loading.value = true
  try {
    const res = await getReportingListApi(qMoNo.value || null,
      qOpSeq.value ? Number(qOpSeq.value) : null, qStatus.value || null)
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function onPick(row) {
  if (!row) return
  picked.value = row
  const [f, o] = await Promise.all([getFeedsApi(row.id), getOutputApi(row.id)])
  feeds.value = f.data || []
  output.value = o.data
}

async function doReversal() {
  try {
    await applyReversalApi(picked.value.id, reversalReason.value.trim())
    ElMessage.success('冲销申请已提交（计划主管审批后自动执行五步反向）')
    reversalVisible.value = false
    reversalReason.value = ''
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '冲销申请失败')
  }
}

async function doComplete() {
  try {
    const out = await completeMoApi(picked.value.moId)
    const d = out.data
    ElMessage.success(`完工成功：合格 ${d.qualifiedQty}，倒冲补投 ${d.backflushRows} 行`)
    await load()
  } catch (e) {
    // 三校验未决项 422 原样列出
    ElMessage.error(e?.response?.data?.message || '完工校验未通过')
  }
}

async function loadYield() {
  if (!yieldMoId.value) {
    ElMessage.warning('请填工单 ID')
    return
  }
  try {
    const res = await getYieldApi(yieldMoId.value,
      yieldOpSeq.value ? Number(yieldOpSeq.value) : null)
    yieldInfo.value = res.data
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '良率查询失败')
  }
}

load()
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 8px; }
.tip { color: #909399; font-size: 12px; }
.card-head { display: flex; gap: 10px; align-items: center; }
</style>
