<template>
  <div class="count-panel">
    <!-- 任务列表 -->
    <div class="toolbar">
      <el-select v-model="filters.status" placeholder="状态" clearable style="width: 130px"
        @change="load(1)">
        <el-option label="盘点中" value="COUNTING" />
        <el-option label="审批中" value="ADJUSTING" />
        <el-option label="已完成" value="DONE" />
      </el-select>
      <el-input v-model="filters.warehouseCode" placeholder="仓库" clearable style="width: 130px"
        @clear="load(1)" @keyup.enter="load(1)" />
      <el-button @click="load(1)">查询</el-button>
    </div>

    <el-table :data="tasks" v-loading="loading" size="small" border @row-click="openDetail">
      <el-table-column prop="taskNo" label="任务号" width="170" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.taskType === 'FULL' ? 'warning' : 'info'">
            {{ row.taskType === 'FULL' ? '全面' : '周期' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="warehouseCode" label="仓库" width="110" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'DONE' ? 'success'
            : row.status === 'ADJUSTING' ? 'warning' : 'primary'">
            {{ { COUNTING: '盘点中', ADJUSTING: '审批中', DONE: '已完成' }[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" width="140">
        <template #default="{ row }">
          已录 {{ row.countedLines }} / {{ row.totalLines }}，已调 {{ row.adjustedLines }}
        </template>
      </el-table-column>
      <el-table-column prop="createBy" label="创建人" width="110" />
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click.stop="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top: 10px; justify-content: flex-end;"
      layout="total, prev, pager, next" :total="total" :page-size="filters.size"
      :current-page="filters.current" @current-change="p => { filters.current = p; load() }" />

    <!-- 任务详情抽屉：行列表 + 录入 + 报告 -->
    <el-drawer v-model="detailVisible" :title="`盘点任务 ${current?.taskNo || ''}`" size="760px">
      <template v-if="current">
        <div class="head">
          <el-tag :type="current.status === 'DONE' ? 'success'
            : current.status === 'ADJUSTING' ? 'warning' : 'primary'">
            {{ { COUNTING: '盘点中', ADJUSTING: '审批中', DONE: '已完成' }[current.status] }}
          </el-tag>
          <span class="dim">{{ current.warehouseCode }} · 已录 {{ current.countedLines }}/{{ current.totalLines }}
            · 已调 {{ current.adjustedLines }}</span>
          <el-button v-if="current.status === 'COUNTING'" link type="danger" size="small"
            style="margin-left: auto" @click="doCancel">取消任务</el-button>
        </div>

        <!-- DONE 报告 -->
        <template v-if="current.status === 'DONE' && report">
          <el-alert type="success" :closable="false" style="margin: 10px 0"
            :title="`盘点报告：${report.range?.binCount} 仓位 / ${report.range?.itemCount} 物料 / ${report.range?.lineCount} 行`"
            :description="`差异行 ${report.diff?.diffLines}，差异金额 ${report.diff?.totalDiffAmount}
              （自动调整 ${report.diff?.autoAdjusted} / 审批调整 ${report.diff?.apprAdjusted}）
              ${report.watchBins?.length ? '；重点监控仓位：' + report.watchBins.join('、') : ''}`" />
        </template>

        <el-table :data="lines" v-loading="linesLoading" size="small" border max-height="480">
          <el-table-column prop="binCode" label="仓位" width="110" />
          <el-table-column prop="itemCode" label="物料" width="130" />
          <el-table-column prop="batchNo" label="批次" width="130" />
          <el-table-column label="账面" width="90">
            <template #default="{ row }">
              <!-- 录入遮蔽：未录行不显账面（FR-4.4-6-3） -->
              <span v-if="row.bookQty !== undefined">{{ row.bookQty }}</span>
              <span v-else class="dim">待录</span>
            </template>
          </el-table-column>
          <el-table-column label="实盘" width="110">
            <template #default="{ row }">
              <template v-if="row.actualQty !== undefined && row.actualQty !== null">{{ row.actualQty }}</template>
              <el-button v-else-if="current.status !== 'DONE'" link type="primary" size="small"
                @click="openCount(row)">录入</el-button>
              <span v-else class="dim">未录</span>
            </template>
          </el-table-column>
          <el-table-column label="差异" width="150">
            <template #default="{ row }">
              <template v-if="row.diffQty !== undefined && row.diffQty !== null">
                <span :class="{ 'danger-text': row.diffRate > 0.005 }">
                  {{ row.diffQty }}（{{ (row.diffRate * 100).toFixed(2) }}%）
                </span>
              </template>
              <span v-else class="dim">-</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.countStatus === 'ADJUSTED' ? 'success'
                : row.countStatus === 'RECOUNT' ? 'danger' : 'info'">
                {{ { PENDING: '待录', COUNTED: '已录', ADJUSTED: '已调', RECOUNT: '待复盘' }[row.countStatus] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="70" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.countStatus === 'PENDING' && current.status !== 'DONE'"
                link type="primary" size="small" @click="openCount(row)">录入</el-button>
              <el-button v-else-if="row.countStatus === 'RECOUNT'"
                link type="danger" size="small" @click="openCount(row)">重录</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-drawer>

    <!-- 录入对话框（遮蔽：无账面列） -->
    <el-dialog v-model="countVisible" title="实盘录入（账面与差异提交后可见）" width="460px">
      <el-descriptions v-if="countLine" :column="1" border size="small" style="margin-bottom: 12px">
        <el-descriptions-item label="仓位">{{ countLine.binCode }}</el-descriptions-item>
        <el-descriptions-item label="物料/批次">{{ countLine.itemCode }} / {{ countLine.batchNo }}</el-descriptions-item>
      </el-descriptions>
      <el-form label-width="90px">
        <el-form-item label="实盘数量" required>
          <el-input-number v-model="countForm.qty" :min="0" :precision="2" style="width: 200px" />
        </el-form-item>
        <el-form-item label="实物异常">
          <el-select v-model="countForm.abnormalFlag" clearable placeholder="无">
            <el-option label="破损" value="DAMAGED" />
            <el-option label="过期" value="EXPIRED" />
            <el-option label="无标识" value="NO_LABEL" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="countForm.remark" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="countVisible = false">取消</el-button>
        <el-button type="primary" :loading="counting" @click="doCount">提交</el-button>
      </template>
    </el-dialog>

    <!-- 提交回显 -->
    <el-dialog v-model="resultVisible" title="差异回显" width="460px">
      <template v-if="result">
        <el-result :icon="resultIcon" :title="resultTitle" :sub-title="resultSub">
          <template #extra>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="账面">{{ result.bookQty }}</el-descriptions-item>
              <el-descriptions-item label="实盘">{{ result.actualQty }}</el-descriptions-item>
              <el-descriptions-item label="差异数">{{ result.diffQty }}</el-descriptions-item>
              <el-descriptions-item label="差异率">
                {{ (result.diffRate * 100).toFixed(2) }}%
              </el-descriptions-item>
              <el-descriptions-item label="差异金额" :span="2">{{ result.diffAmount }}</el-descriptions-item>
            </el-descriptions>
          </template>
        </el-result>
      </template>
      <template #footer>
        <el-button type="primary" @click="resultVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getCountTasksApi, getCountTaskDetailApi, getCountLinesApi, cancelCountTaskApi,
  submitCountApi
} from '@/api/inv/count'

// 共享盘点任务面板（4.11.1/4.11.2 复用）：列表 + 详情（遮蔽录入 + 差异回显 + 报告）
const props = defineProps({ taskType: { type: String, default: '' } })

const loading = ref(false)
const linesLoading = ref(false)
const counting = ref(false)
const tasks = ref([])
const total = ref(0)
const filters = reactive({ status: '', warehouseCode: '', current: 1, size: 20 })

const detailVisible = ref(false)
const current = ref(null)
const lines = ref([])
const report = ref(null)

const countVisible = ref(false)
const countLine = ref(null)
const countForm = reactive({ qty: 0, abnormalFlag: '', remark: '' })
const resultVisible = ref(false)
const result = ref(null)

const resultIcon = computed(() => {
  if (!result.value) return 'info'
  if (result.value.outcome === 'AUTO_ADJUSTED') return 'success'
  if (result.value.outcome === 'PENDING_APPROVAL') return 'warning'
  return 'info'
})
const resultTitle = computed(() => {
  if (!result.value) return ''
  if (result.value.outcome === 'AUTO_ADJUSTED') return '容差内自动调整完成'
  if (result.value.outcome === 'PENDING_APPROVAL') return '超容差，已提交仓库主管审批'
  return '已录入'
})
const resultSub = computed(() => {
  if (!result.value) return ''
  return result.value.outcome === 'PENDING_APPROVAL'
    ? `差异单 ${result.value.diffNo || ''}，4.11.3 审批通过后自动调整`
    : '差异已按容差规则处理'
})

async function load(page) {
  if (page) filters.current = page
  loading.value = true
  try {
    const res = await getCountTasksApi({
      status: filters.status || undefined,
      taskType: props.taskType || undefined,
      warehouseCode: filters.warehouseCode || undefined,
      current: filters.current, size: filters.size
    })
    tasks.value = res.data?.records || []
    total.value = Number(res.data?.total || 0)
  } catch (e) {
    console.warn('[count] task page failed', e)
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  current.value = null
  report.value = null
  detailVisible.value = true
  try {
    const res = await getCountTaskDetailApi(row.id)
    current.value = res.data
    if (res.data?.reportJson) {
      try { report.value = JSON.parse(res.data.reportJson) } catch (e) { report.value = null }
    }
    await loadLines()
  } catch (e) {
    console.warn('[count] detail failed', e)
  }
}

async function loadLines() {
  if (!current.value) return
  linesLoading.value = true
  try {
    const res = await getCountLinesApi(current.value.id)
    lines.value = res.data || []
  } catch (e) {
    console.warn('[count] lines failed', e)
  } finally {
    linesLoading.value = false
  }
}

function openCount(row) {
  countLine.value = row
  countForm.qty = 0
  countForm.abnormalFlag = ''
  countForm.remark = ''
  countVisible.value = true
}

async function doCount() {
  if (countForm.qty === null || countForm.qty === undefined) {
    ElMessage.warning('实盘数量必填')
    return
  }
  counting.value = true
  try {
    const res = await submitCountApi(countLine.value.id, {
      actualQty: countForm.qty,
      abnormalFlag: countForm.abnormalFlag || undefined,
      remark: countForm.remark || undefined
    })
    countVisible.value = false
    result.value = res.data
    resultVisible.value = true
    await loadLines()
    if (current.value) {
      const d = await getCountTaskDetailApi(current.value.id)
      current.value = d.data
      if (d.data?.reportJson) {
        try { report.value = JSON.parse(d.data.reportJson) } catch (e) { report.value = null }
      }
    }
  } catch (e) {
    // >10% 阻断：后端 422 由 request 拦截器提示；刷新行状态见待复盘标记
    console.warn('[count] submit failed', e)
    await loadLines()
  } finally {
    counting.value = false
  }
}

async function doCancel() {
  try {
    const { value } = await ElMessageBox.prompt('取消原因（必填）', '取消盘点任务', {
      inputPattern: /\S+/, inputErrorMessage: '原因必填'
    })
    await cancelCountTaskApi(current.value.id, value)
    ElMessage.success('任务已取消（仓位解锁）')
    detailVisible.value = false
    load(1)
  } catch (e) {
    if (e !== 'cancel' && e?.message) console.warn('[count] cancel failed', e)
  }
}

defineExpose({ reload: load })
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.head { display: flex; gap: 10px; align-items: center; }
.dim { color: #909399; font-size: 12px; }
.danger-text { color: #f56c6c; font-weight: 600; }
</style>
