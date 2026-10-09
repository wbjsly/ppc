<template>
  <div class="page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="效期预警（4.10.1）：每日报告留痕 + 当日实时清单（与 4.6.2 同源双视角）"
      description="每日 00:30 扫描后自动生成报告并推送仓库（黄 ≤90 / 橙 ≤60 / 红 ≤30 天），含较前报新增/解除清单；出库作业视角的实时清单在 4.6.2 效期优先。" />

    <div class="toolbar">
      <el-select v-model="view" style="width: 140px" @change="switchView">
        <el-option label="当日实时清单" value="live" />
        <el-option label="历史报告" value="report" />
      </el-select>
      <template v-if="view === 'live'">
        <el-input v-model="filters.level" placeholder="等级筛选" style="width: 130px" disabled />
        <el-button @click="loadLive">刷新</el-button>
      </template>
      <template v-else>
        <el-button @click="loadHistory">刷新报告列表</el-button>
        <el-button type="primary" plain :loading="generating" @click="doGenerate">补生成当日报告</el-button>
      </template>
      <span v-if="thresholds" class="tip">
        阈值：黄 ≤{{ thresholds.yellow }} / 橙 ≤{{ thresholds.orange }} / 红 ≤{{ thresholds.red }} 天；
        锁定线 = 有效期总天数 × {{ lockRatio }}
      </span>
    </div>

    <!-- 当日实时清单 -->
    <template v-if="view === 'live'">
      <el-table :data="liveRows" v-loading="loading" size="small" border>
        <el-table-column prop="itemCode" label="物料" width="140" />
        <el-table-column prop="itemName" label="名称" min-width="120" show-overflow-tooltip />
        <el-table-column prop="batchNo" label="批次" width="140" />
        <el-table-column prop="expiryDate" label="有效期至" width="110" />
        <el-table-column prop="remainingDays" label="剩余天数" width="90" sortable />
        <el-table-column label="等级" width="90">
          <template #default="{ row }">
            <el-tag :type="levelTag(row.level)" size="small">{{ levelText(row.level) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="锁定" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.locked" type="danger" size="small">已锁定</el-tag>
            <span v-else class="dim">否</span>
          </template>
        </el-table-column>
        <el-table-column prop="qty" label="在库量" width="90" align="right" />
      </el-table>
    </template>

    <!-- 历史报告 -->
    <template v-else>
      <el-table :data="reports" v-loading="loading" size="small" border @row-click="openReport">
        <el-table-column prop="reportDate" label="报告日" width="120" />
        <el-table-column label="黄色" width="80" align="center">
          <template #default="{ row }"><el-tag type="warning" size="small">{{ row.yellowCnt }}</el-tag></template>
        </el-table-column>
        <el-table-column label="橙色" width="80" align="center">
          <template #default="{ row }"><el-tag type="warning" effect="dark" size="small">{{ row.orangeCnt }}</el-tag></template>
        </el-table-column>
        <el-table-column label="红色" width="80" align="center">
          <template #default="{ row }"><el-tag type="danger" effect="dark" size="small">{{ row.redCnt }}</el-tag></template>
        </el-table-column>
        <el-table-column label="锁定" width="80" align="center">
          <template #default="{ row }">{{ row.lockedCnt }}</template>
        </el-table-column>
        <el-table-column label="基线" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.noBaseline" type="info" size="small">首日无基线</el-tag>
            <span v-else class="dim">有</span>
          </template>
        </el-table-column>
        <el-table-column prop="genAt" label="生成时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.genAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="openReport(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 报告详情抽屉：增减清单 + 明细 -->
    <el-drawer v-model="detailVisible" :title="`预警报告 ${detail?.reportDate || ''}`" size="640px">
      <template v-if="detail">
        <div class="stats">
          <el-tag type="warning">黄 {{ detail.yellowCnt }}</el-tag>
          <el-tag type="warning" effect="dark">橙 {{ detail.orangeCnt }}</el-tag>
          <el-tag type="danger" effect="dark">红 {{ detail.redCnt }}</el-tag>
          <el-tag type="danger">锁定 {{ detail.lockedCnt }}</el-tag>
        </div>
        <el-alert v-if="detail.noBaseline" type="info" :closable="false" style="margin: 10px 0;"
          title="首日无前报基线，新增/解除清单不适用" />

        <template v-else>
          <h4>较前报新增（{{ list(detail.added).length }}）</h4>
          <el-table v-if="list(detail.added).length" :data="detail.added" size="small" border>
            <el-table-column prop="itemCode" label="物料" width="130" />
            <el-table-column prop="batchNo" label="批次" width="130" />
            <el-table-column prop="remainingDays" label="剩余" width="70" />
            <el-table-column label="等级" width="70">
              <template #default="{ row }">{{ levelText(row.level) }}</template>
            </el-table-column>
          </el-table>
          <p v-else class="dim">无新增</p>

          <h4>较前报解除（{{ list(detail.cleared).length }}）</h4>
          <el-table v-if="list(detail.cleared).length" :data="detail.cleared" size="small" border>
            <el-table-column prop="itemCode" label="物料" width="130" />
            <el-table-column prop="batchNo" label="批次" width="130" />
            <el-table-column prop="remainingDays" label="解除时剩余" width="90" />
          </el-table>
          <p v-else class="dim">无解除</p>
        </template>

        <h4>当日全量明细（{{ list(detail.detail).length }}）</h4>
        <el-table :data="list(detail.detail)" size="small" border max-height="320">
          <el-table-column prop="itemCode" label="物料" width="130" />
          <el-table-column prop="batchNo" label="批次" width="130" />
          <el-table-column prop="expiryDate" label="有效期至" width="105" />
          <el-table-column prop="remainingDays" label="剩余" width="70" />
          <el-table-column label="等级" width="70">
            <template #default="{ row }">{{ levelText(row.level) }}</template>
          </el-table-column>
          <el-table-column label="锁定" width="70">
            <template #default="{ row }">{{ row.locked ? '是' : '否' }}</template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getExpiryWarningsApi } from '@/api/inv/fifo'
import {
  getExpiryReportHistoryApi, getExpiryReportDetailApi,
  generateExpiryReportApi
} from '@/api/inv/expiry'

// 4.10.1 效期预警：每日报告留痕 + 当日实时清单（数据源与 4.6.2 同源，D1 双视角）
const view = ref('live')
const loading = ref(false)
const generating = ref(false)
const liveRows = ref([])
const reports = ref([])
const thresholds = ref(null)
const lockRatio = ref(0.5)
const filters = ref({ level: '' })

const detailVisible = ref(false)
const detail = ref(null)

const levelText = l => ({ YELLOW: '黄', ORANGE: '橙', RED: '红' }[l] || l)
const levelTag = l => ({ YELLOW: 'warning', ORANGE: 'warning', RED: 'danger' }[l] || 'info')
const list = v => (Array.isArray(v) ? v : [])
function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}

async function loadLive() {
  loading.value = true
  try {
    const res = await getExpiryWarningsApi({ current: 1, size: 500 })
    liveRows.value = res.data?.records || []
    thresholds.value = res.data?.thresholds || null
    lockRatio.value = res.data?.lockRatio ?? 0.5
  } catch (e) {
    console.warn('[expiry-warning] live failed', e)
  } finally {
    loading.value = false
  }
}

async function loadHistory() {
  loading.value = true
  try {
    const res = await getExpiryReportHistoryApi({ size: 60 })
    reports.value = res.data || []
  } catch (e) {
    console.warn('[expiry-warning] history failed', e)
  } finally {
    loading.value = false
  }
}

function switchView(v) {
  if (v === 'report') loadHistory()
  else loadLive()
}

async function doGenerate() {
  generating.value = true
  try {
    const res = await generateExpiryReportApi()
    ElMessage.success(`报告已生成：黄 ${res.data?.yellowCnt ?? '-'} / 橙 ${res.data?.orangeCnt ?? '-'} / 红 ${res.data?.redCnt ?? '-'}`)
    loadHistory()
  } catch (e) {
    console.warn('[expiry-warning] generate failed', e)
  } finally {
    generating.value = false
  }
}

async function openReport(row) {
  detail.value = null
  detailVisible.value = true
  try {
    const res = await getExpiryReportDetailApi(row.reportDate)
    detail.value = res.data || null
    if (!detail.value) ElMessage.warning('报告不存在')
  } catch (e) {
    console.warn('[expiry-warning] detail failed', e)
  }
}

onMounted(loadLive)
</script>

<style scoped>
.page { padding: 4px; }
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
.dim { color: #909399; font-size: 12px; }
.stats { display: flex; gap: 8px; }
h4 { margin: 12px 0 6px; }
</style>
