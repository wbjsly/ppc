<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.4.2 分批交付"
              description="ATP 不足的行可编制分批方案：拆分后各行独立绑定交期与库存预留，Σ 必须等于原行数量（不等回滚，BR-4.3-23）；已确认订单拆行将释放原行预留并按新行重锁，锁不足整体回滚；客户不接受分批时订单可搁置或取消。" />

    <!-- 选择订单 -->
    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>选择订单（草稿/已确认）</span>
          <div>
            <el-select v-model="soId" filterable placeholder="选择 SO" size="small"
                       style="width:320px" @change="loadLines">
              <el-option v-for="s in sos" :key="s.id"
                         :label="`${s.soNo} ${s.customerName} ¥${s.totalAmount}（${s.status}）`"
                         :value="s.id" />
            </el-select>
            <el-button size="small" style="margin-left:8px" @click="loadSo">刷新</el-button>
          </div>
        </div>
      </template>

      <!-- 行 + 行级 ATP -->
      <el-table :data="lineRows" size="small" border v-loading="loading">
        <el-table-column prop="lineNo" label="#" width="45" />
        <el-table-column prop="itemCode" label="SKU" width="150" />
        <el-table-column prop="itemName" label="名称" min-width="110" show-overflow-tooltip />
        <el-table-column prop="qty" label="行数量" width="100" align="right" />
        <el-table-column label="行 ATP" width="110" align="right">
          <template #default="{ row }">
            <span :class="row.atpCheck && !row.atpCheck.enough ? 'atp-bad' : 'atp-ok'">
              {{ row.atpCheck ? row.atpCheck.atp : '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="最早承诺" width="120">
          <template #default="{ row }">
            {{ row.atpCheck ? (row.atpCheck.earliestPromiseDate || '无确定') : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.lineStatus === 'CANCELLED' ? 'info' : ''">
              {{ row.lineStatus || 'OPEN' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button size="small" type="primary" link
                       :disabled="row.lineStatus === 'CANCELLED'"
                       @click="openSplit(row)">
              编制分批方案
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 行级 L4 提示 -->
      <div class="warn-list">
        <el-alert v-for="(w, i) in lineWarnings" :key="i" type="warning" :closable="false"
                  show-icon class="mb6" :title="w" />
      </div>
    </el-card>

    <!-- 分批方案编制 -->
    <el-dialog v-model="splitVisible" :title="`分批方案：行 ${current?.lineNo} ${current?.itemCode}`"
               width="780px" top="5vh">
      <template v-if="current">
        <el-descriptions :column="3" size="small" border class="mb12">
          <el-descriptions-item label="原行数量">{{ current.qty }}</el-descriptions-item>
          <el-descriptions-item label="行 ATP">
            <span :class="atpOf(current) < Number(current.qty) ? 'atp-bad' : 'atp-ok'">
              {{ atpOf(current) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="最早承诺">
            {{ current.atpCheck?.earliestPromiseDate || '无确定日期' }}
          </el-descriptions-item>
        </el-descriptions>

        <el-table :data="batches" size="small" border>
          <el-table-column label="批次" width="70">
            <template #default="{ $index }">第 {{ $index + 1 }} 批</template>
          </el-table-column>
          <el-table-column label="数量" width="180">
            <template #default="{ row }">
              <el-input-number v-model="row.qty" :min="0" :precision="3" size="small"
                               controls-position="right" style="width:160px" />
            </template>
          </el-table-column>
          <el-table-column label="交期（逐行绑定）" width="190">
            <template #default="{ row }">
              <el-date-picker v-model="row.planShipDate" type="date" value-format="YYYY-MM-DD"
                              size="small" style="width:170px" />
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="180">
            <template #default="{ row, $index }">
              <el-input v-model="row.remark" size="small" :placeholder="$index === 0 ? '首批（原行承接）' : '如：补货到货后发'" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="70">
            <template #default="{ $index }">
              <el-button size="small" type="danger" link :disabled="batches.length <= 2"
                         @click="batches.splice($index, 1)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="split-bar">
          <el-button size="small" @click="addBatch">+ 加一批</el-button>
          <span :class="conserved ? 'ok' : 'bad'">
            方案合计 {{ sumBatches.toFixed(3) }} / 原行 {{ current.qty }}
            —— {{ conserved ? '守恒校验通过' : '不守恒，提交将被回滚（BR-4.3-23）' }}
          </span>
        </div>

        <el-alert type="info" :closable="false" show-icon class="mt8"
                  :title="soStatus === 'CONFIRMED'
                    ? '该单已确认：拆分将释放原行预留并按新行重新锁批，锁不足整体回滚'
                    : '该单为草稿：拆分仅改行结构，确认时再锁批次'" />
      </template>
      <template #footer>
        <el-button @click="splitVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!conserved" :loading="submitting"
                   @click="doSplit">确认拆分</el-button>
      </template>
    </el-dialog>

    <!-- 拆分结果 -->
    <el-dialog v-model="resultVisible" title="拆分结果" width="640px">
      <template v-if="splitResult">
        <el-alert type="success" :closable="false" class="mb12">
          <template #title>
            行 {{ splitResult.lineNo }} 拆为 {{ splitResult.batchCount }} 批：
            {{ splitResult.totalBefore }} → {{ splitResult.totalAfter }}（守恒 ✔）
            <span v-if="splitResult.reservedLines">
              ，已按新行重新锁批 {{ splitResult.reservedLines }} 行
            </span>
          </template>
        </el-alert>
        <el-table :data="splitResult.lines" size="small" border>
          <el-table-column prop="lineNo" label="#" width="50" />
          <el-table-column prop="itemCode" label="SKU" width="140" />
          <el-table-column prop="qty" label="数量" width="100" align="right" />
          <el-table-column prop="planShipDate" label="交期" width="120" />
          <el-table-column prop="reservedQty" label="预留" width="100" align="right" />
          <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
        </el-table>
      </template>
      <template #footer>
        <el-button type="primary" @click="resultVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  trialAtpApi, splitLineApi, checkSoLinesApi
} from '@/api/sd/atp'
import { getSoPageApi, getSoDetailApi } from '@/api/sd/so'

const sos = ref([])
const soId = ref('')
const soStatus = ref('')
const detail = ref(null)
const lineRows = ref([])
const loading = ref(false)
const splitVisible = ref(false)
const resultVisible = ref(false)
const submitting = ref(false)
const current = ref(null)
const batches = ref([])
const splitResult = ref(null)

const lineWarnings = computed(() => {
  const ws = []
  for (const r of lineRows.value) {
    if (r.atpCheck && r.atpCheck.level === 'L4') {
      for (const w of (r.atpCheck.warnings || [])) {
        ws.push(`行 ${r.lineNo}：${w}`)
      }
    }
  }
  return ws
})
const sumBatches = computed(() =>
  batches.value.reduce((s, b) => s + (Number(b.qty) || 0), 0))
const conserved = computed(() => current.value
  && Math.abs(sumBatches.value - Number(current.value.qty)) < 0.001)

function atpOf(row) {
  return row.atpCheck ? Number(row.atpCheck.atp) : 0
}

async function loadSo() {
  try {
    const [a, b] = await Promise.all([
      getSoPageApi({ current: 1, size: 50, status: 'CONFIRMED' }),
      getSoPageApi({ current: 1, size: 50, status: 'DRAFT' })
    ])
    sos.value = [...(a.data.records || []), ...(b.data.records || [])]
  } catch (e) {
    ElMessage.error(e?.message || '加载订单失败')
  }
}

async function loadLines() {
  if (!soId.value) return
  loading.value = true
  try {
    const d = await getSoDetailApi(soId.value)
    detail.value = d.data
    soStatus.value = d.data.so.status
    const lines = d.data.lines || []
    // 行级 ATP 检查（C-4.3-02 L4）
    let checks = []
    try {
      const c = await checkSoLinesApi(soId.value)
      checks = c.data || []
    } catch (e) {
      console.error('atp check failed', e)
    }
    lineRows.value = lines.map(l => ({
      ...l,
      atpCheck: checks.find(c => c.lineNo === l.lineNo) || null
    }))
  } catch (e) {
    ElMessage.error(e?.message || '加载明细失败')
  } finally {
    loading.value = false
  }
}

function openSplit(row) {
  current.value = row
  const first = Math.min(Number(row.qty), atpOf(row) > 0 ? atpOf(row) : Number(row.qty))
  batches.value = [
    { qty: first, planShipDate: '', remark: '' },
    { qty: Math.max(0, Number(row.qty) - first), planShipDate: '', remark: '' }
  ]
  splitVisible.value = true
}

function addBatch() {
  batches.value.push({ qty: 0, planShipDate: '', remark: '' })
}

async function doSplit() {
  if (!conserved.value) return ElMessage.error('拆分前后数量必须一致（BR-4.3-23）')
  submitting.value = true
  try {
    const res = await splitLineApi(current.value.id, batches.value)
    splitResult.value = res.data
    splitVisible.value = false
    resultVisible.value = true
    await loadLines()
  } catch (e) {
    ElMessage.error(e?.message || '拆分失败（已回滚）')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await loadSo()
  if (sos.value.length) {
    soId.value = sos.value[0].id
    await loadLines()
  }
})
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.mb12 { margin-bottom: 12px; }
.mb6 { margin-bottom: 6px; }
.mt8 { margin-top: 8px; }
.atp-ok { color: #67c23a; font-weight: 600; }
.atp-bad { color: #f56c6c; font-weight: 600; }
.warn-list { margin-top: 10px; }
.split-bar { display: flex; justify-content: space-between; align-items: center; margin-top: 10px; }
.split-bar .ok { color: #67c23a; font-size: 13px; }
.split-bar .bad { color: #f56c6c; font-size: 13px; font-weight: 600; }
</style>
