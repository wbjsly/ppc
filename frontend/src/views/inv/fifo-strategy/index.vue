<template>
  <div class="fifo-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="先进先出（4.6.1）：FIFO+FEFO 批次推荐试算与改批偏离监控"
      description="排序口径：入库日期升序（FIFO）→ 同日按有效期升序（FEFO）→ 排除冻结、效期锁定与零可用批次（BR-4.4-19）。试算为只读模拟，不生成预留、不改库存。" />

    <el-tabs v-model="tab">
      <!-- ================= Tab B：推荐试算 ================= -->
      <el-tab-pane label="推荐试算" name="simulate">
        <div class="toolbar">
          <el-input v-model="form.warehouseCode" placeholder="仓库（默认 WH-MAIN）" clearable
            style="width: 170px;" />
          <el-input v-model="form.itemCode" placeholder="物料编码（必填）" clearable
            style="width: 200px;" />
          <el-input-number v-model="form.qty" :min="0.0001" :precision="3" :controls="false"
            style="width: 130px;" placeholder="出库数量" />
          <el-checkbox v-model="form.binLevel">按仓位拆分</el-checkbox>
          <el-button type="primary" :loading="loading" @click="runSimulate">试算</el-button>
        </div>

        <el-alert v-if="simError" type="error" :closable="false" :title="simError"
          style="margin-bottom: 10px;" />

        <template v-if="sim">
          <el-alert :type="sim.satisfied ? 'success' : 'warning'" :closable="false"
            style="margin-bottom: 10px;"
            :title="sim.satisfied ? `满足：累计取量 ${form.qty}` : `缺口 ${sim.gap}`"
            :description="sim.splitSuggestion || sim.sortRule" />

          <el-table :data="sim.lines || []" size="small" border>
            <el-table-column type="index" label="#" width="50" />
            <el-table-column prop="batchNo" label="批次" width="150" />
            <el-table-column prop="inboundDate" label="入库日期" width="110" />
            <el-table-column prop="expiryDate" label="有效期至" width="110">
              <template #default="{ row }">{{ row.expiryDate || '—' }}</template>
            </el-table-column>
            <el-table-column prop="available" label="可用量" width="100" />
            <el-table-column prop="take" label="本单取量" width="100" />
            <el-table-column prop="cumulative" label="累计" width="100" />
            <el-table-column label="剩余效期" width="120">
              <template #default="{ row }">
                <template v-if="row.remainingDays != null">
                  <el-tag size="small" :type="row.expiryWarning ? 'warning' : 'info'">
                    {{ row.remainingDays }} 天{{ row.expiryWarning ? '（效期警告）' : '' }}
                  </el-tag>
                </template>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column label="仓位拆分">
              <template #default="{ row }">
                <template v-if="row.bins && row.bins.length">
                  <el-tag v-for="b in row.bins" :key="b.binCode" size="small" style="margin-right: 4px;">
                    {{ b.binCode || '未分配' }}：{{ b.take }}
                  </el-tag>
                </template>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>

          <div v-if="sim.excluded && sim.excluded.length" style="margin-top: 10px;">
            <div class="excluded-title">已剔除批次（不参与推荐）</div>
            <el-table :data="sim.excluded" size="mini" border max-height="200">
              <el-table-column prop="batchNo" label="批次" width="150" />
              <el-table-column prop="reason" label="剔除原因" min-width="260" />
            </el-table>
          </div>
        </template>
      </el-tab-pane>

      <!-- ================= Tab C：偏离监控 ================= -->
      <el-tab-pane label="偏离监控" name="deviation">
        <div class="toolbar">
          <el-input v-model="devFilters.docNo" placeholder="来源单据号" clearable style="width: 170px;"
            @clear="loadDeviations" @keyup.enter="loadDeviations" />
          <el-input v-model="devFilters.itemCode" placeholder="物料" clearable style="width: 150px;"
            @clear="loadDeviations" @keyup.enter="loadDeviations" />
          <el-input v-model="devFilters.createBy" placeholder="操作人" clearable style="width: 130px;"
            @clear="loadDeviations" @keyup.enter="loadDeviations" />
          <el-date-picker v-model="devRange" type="daterange" value-format="YYYY-MM-DD"
            start-placeholder="开始日期" end-placeholder="结束日期" style="width: 240px;"
            @change="loadDeviations" />
          <el-button @click="loadDeviations">查询</el-button>
        </div>

        <el-table :data="devRows" size="small" border v-loading="devLoading">
          <el-table-column label="来源类型" width="120">
            <template #default="{ row }">
              <el-tag size="small">{{ row.srcDocType === 'MATERIAL_ISSUE' ? '领料改批'
                : (row.srcDocType === 'PICK_RECOMMEND' ? '拣货推荐改写' : row.srcDocType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="srcDocNo" label="来源单据号" width="160" />
          <el-table-column prop="lineNo" label="行号" width="60" />
          <el-table-column prop="itemCode" label="物料" width="150" />
          <el-table-column label="系统推荐批次" width="150">
            <template #default="{ row }">
              <span :style="{ color: '#909399' }">{{ row.recommendedBatch || '（无推荐）' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="实际批次" width="150">
            <template #default="{ row }">
              <span style="color: #E6A23C; font-weight: 600;">{{ row.actualBatch }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="改批原因" min-width="200" />
          <el-table-column prop="createBy" label="操作人" width="100" />
          <el-table-column prop="createDate" label="时间" width="160" />
        </el-table>

        <el-pagination style="margin-top: 10px; justify-content: flex-end;"
          layout="total, prev, pager, next" :total="devTotal" :page-size="20"
          :current-page="devFilters.current" @current-change="p => { devFilters.current = p; loadDeviations() }" />

        <el-alert type="info" :closable="false" style="margin-top: 10px;"
          title="首期仅留痕：非波次的行级改批记录偏离台账（原因必填），强制审批随波次管理（4.8）上线再挂接（C-4.4-08 / 偏差 D1）。" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { simulateFifoApi, getDeviationsApi } from '@/api/inv/fifo'

const tab = ref('simulate')

// ---------- Tab B 试算 ----------
const loading = ref(false)
const form = reactive({ warehouseCode: 'WH-MAIN', itemCode: '', qty: 100, binLevel: false })
const sim = ref(null)
const simError = ref('')

async function runSimulate() {
  if (!form.itemCode) {
    ElMessage.error('物料编码必填')
    return
  }
  loading.value = true
  simError.value = ''
  sim.value = null
  try {
    const res = await simulateFifoApi({
      warehouseCode: form.warehouseCode || 'WH-MAIN',
      itemCode: form.itemCode,
      qty: form.qty,
      binLevel: form.binLevel
    })
    sim.value = res.data
  } catch (e) {
    simError.value = (e && e.message) || '试算失败'
  } finally {
    loading.value = false
  }
}

// ---------- Tab C 偏离台账 ----------
const devLoading = ref(false)
const devRows = ref([])
const devTotal = ref(0)
const devRange = ref(null)
const devFilters = reactive({ current: 1, docNo: '', itemCode: '', createBy: '' })

async function loadDeviations() {
  devLoading.value = true
  try {
    const res = await getDeviationsApi({
      current: devFilters.current,
      size: 20,
      docNo: devFilters.docNo || undefined,
      itemCode: devFilters.itemCode || undefined,
      createBy: devFilters.createBy || undefined,
      dateFrom: devRange.value ? devRange.value[0] : undefined,
      dateTo: devRange.value ? devRange.value[1] : undefined
    })
    devRows.value = res.data.records || []
    devTotal.value = Number(res.data.total || 0)
  } finally {
    devLoading.value = false
  }
}

onMounted(loadDeviations)
</script>

<style scoped>
.fifo-page { padding: 12px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; flex-wrap: wrap; }
.excluded-title { font-size: 13px; color: #909399; margin-bottom: 6px; }
</style>
