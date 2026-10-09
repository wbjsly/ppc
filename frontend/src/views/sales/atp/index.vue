<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.4.1 可承诺量试算（ATP）"
              description="Available = OnHand + InProcess + Incoming − Reserved − SafetyStock（SKU+仓库维度）。在制恒 0 并固定标注；到货晚于期望交期的在途剔除；不足仅 L4 提示可承诺量与最早可承诺日期，不自动削量（BR-4.3-19~22 / C-4.3-02）。" />

    <!-- 试算输入 -->
    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>试算输入</span>
          <el-button size="small" type="primary" :loading="loading" @click="runTrial">执行试算</el-button>
        </div>
      </template>
      <el-form inline size="small" label-width="80px">
        <el-form-item label="SKU" required>
          <el-select v-model="form.itemCode" filterable style="width:260px" placeholder="选择物料">
            <el-option v-for="it in items" :key="it.itemCode"
                       :label="`${it.itemCode} ${it.itemName}`" :value="it.itemCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="仓库">
          <el-select v-model="form.warehouseCode" style="width:160px">
            <el-option v-for="w in warehouses" :key="w.whCode"
                       :label="`${w.whCode} ${w.whName}`" :value="w.whCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="需求量" required>
          <el-input-number v-model="form.qty" :min="1" :precision="3" style="width:150px" />
        </el-form-item>
        <el-form-item label="期望交期">
          <el-date-picker v-model="form.expectDate" type="date" value-format="YYYY-MM-DD"
                          style="width:150px" placeholder="延迟剔除基准" />
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 试算结果 -->
    <template v-if="result">
      <!-- L4 提示（不削量） -->
      <el-alert v-for="(w, i) in (result.warnings || [])" :key="i" type="warning"
                :closable="false" show-icon class="mb12" :title="w" />

      <el-row :gutter="12" class="mb12">
        <el-col :span="6">
          <el-card shadow="never" class="kpi">
            <div class="kpi-label">ATP 可承诺量</div>
            <div class="kpi-value" :class="result.enough ? 'ok' : 'bad'">
              {{ result.available }}
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="kpi">
            <div class="kpi-label">需求量</div>
            <div class="kpi-value">{{ result.qty }}</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="kpi">
            <div class="kpi-label">缺口</div>
            <div class="kpi-value" :class="Number(result.shortage) > 0 ? 'bad' : 'ok'">
              {{ result.shortage }}
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="kpi">
            <div class="kpi-label">最早可承诺日期</div>
            <div class="kpi-value date">{{ result.earliestPromiseDate || '无确定日期' }}</div>
          </el-card>
        </el-col>
      </el-row>

      <el-card shadow="never" class="mb12">
        <template #header>
          <div class="card-head">
            <span>四因子明细（BR-4.3-19）</span>
            <div>
              <el-button size="small" @click="save">保存试算记录</el-button>
            </div>
          </div>
        </template>
        <el-table :data="factorRows" size="small" border>
          <el-table-column prop="name" label="因子" width="220" />
          <el-table-column prop="value" label="数量" width="140" align="right" />
          <el-table-column prop="note" label="口径说明" min-width="300" />
        </el-table>
        <p class="promise-note">{{ result.promiseNote }}</p>
      </el-card>

      <!-- 在途明细（延迟剔除展示） -->
      <el-card v-if="(result.factors.incomingDetail || []).length" shadow="never">
        <template #header><span>已批 PO 在途明细（确定补货才计入）</span></template>
        <el-table :data="result.factors.incomingDetail" size="small" border>
          <el-table-column prop="arrive" label="预计到货" width="140" />
          <el-table-column prop="qty" label="未收量" width="140" align="right" />
          <el-table-column label="是否计入" width="140">
            <template #default="{ row }">
              <el-tag size="small"
                      :type="isDelayed(row) ? 'danger' : 'success'">
                {{ isDelayed(row) ? '计划延迟，不计入' : '计入 ATP' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>

    <!-- 历史记录 -->
    <el-card shadow="never" class="mt12">
      <template #header>
        <div class="card-head">
          <span>试算记录</span>
          <el-button size="small" @click="loadTrials">刷新</el-button>
        </div>
      </template>
      <el-table :data="trials" size="small" border>
        <el-table-column prop="itemCode" label="SKU" width="150" />
        <el-table-column prop="warehouseCode" label="仓库" width="100" />
        <el-table-column prop="qty" label="需求" width="90" align="right" />
        <el-table-column prop="available" label="ATP" width="110" align="right" />
        <el-table-column label="满足" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enough === '1' ? 'success' : 'danger'">
              {{ row.enough === '1' ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="earliestPromiseDate" label="最早承诺" width="120" />
        <el-table-column prop="createDate" label="试算时间" width="160">
          <template #default="{ row }">{{ (row.createDate || '').replace('T', ' ') }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { trialAtpApi, saveTrialApi, getTrialsApi } from '@/api/sd/atp'
import { getItemPageApi } from '@/api/mdm/item'
import { getEnabledWarehousesApi } from '@/api/inv/warehouse'

const form = ref({ itemCode: '', warehouseCode: 'WH-MAIN', qty: 100, expectDate: '' })
const items = ref([])
const warehouses = ref([])
const result = ref(null)
const trials = ref([])
const loading = ref(false)

const factorRows = computed(() => {
  if (!result.value) return []
  const f = result.value.factors
  return [
    { name: 'OnHand 在手', value: f.onHand, note: 'erp_inv_stock.AVAILABLE_QTY（已排除待检/让步锁定）' },
    { name: '└ 被排除：待检/锁定', value: f.excludedQc, note: 'QC_QTY 单列展示，不计入 ATP' },
    { name: '└ 被排除：寄售库存', value: f.excludedVmi, note: '寄售表余量，货权非本方，不计入 ATP' },
    { name: 'InProcess 在制', value: f.inProcess, note: f.inProcessNote },
    { name: 'Incoming 在途', value: f.incoming, note: '已批 APPROVED PO 未收量且到货 ≤ 期望交期' },
    { name: '└ 计划延迟剔除', value: f.incomingDelayed, note: '到货晚于期望交期（BR-4.3-20），不计入 ATP' },
    { name: 'Reserved 预留', value: '-' + f.reserved, note: '预留表 SUM(ACTIVE) 实时汇总（非存储字段）' },
    { name: 'SafetyStock 安全库存', value: '-' + f.safetyStock,
      note: `max(物料字段 ${f.safetyField}, 日均 ${f.avgDaily} × ${f.safetyDays} 天 = ${f.safetyFlow})，不参与承诺（BR-4.3-21）` }
  ]
})

function isDelayed(row) {
  if (!result.value.expectDate || !row.arrive) return false
  return row.arrive > result.value.expectDate
}

async function runTrial() {
  if (!form.value.itemCode) return ElMessage.error('请选择 SKU')
  if (!form.value.qty || form.value.qty <= 0) return ElMessage.error('需求量须大于 0')
  loading.value = true
  try {
    const res = await trialAtpApi(form.value)
    result.value = res.data
    if (result.value.enough) {
      ElMessage.success(`ATP ${result.value.available} 满足需求，最早 ${result.value.earliestPromiseDate} 可承诺`)
    }
  } catch (e) {
    ElMessage.error(e?.message || '试算失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  try {
    await saveTrialApi(result.value, '')
    ElMessage.success('试算记录已保存')
    await loadTrials()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  }
}

async function loadTrials() {
  try {
    const res = await getTrialsApi({ current: 1, size: 20, status: 'SAVED' })
    trials.value = res.data.records || []
  } catch (e) { /* 忽略 */ }
}

onMounted(async () => {
  try {
    const [i, w] = await Promise.all([
      getItemPageApi({ current: 1, size: 300 }),
      getEnabledWarehousesApi()
    ])
    items.value = i.data.records || i.data || []
    warehouses.value = w.data || []
    if (items.value.length && !form.value.itemCode) {
      form.value.itemCode = items.value[0].itemCode
    }
  } catch (e) { /* 忽略 */ }
  await loadTrials()
})
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
.kpi .kpi-label { font-size: 12px; color: #909399; }
.kpi .kpi-value { font-size: 22px; font-weight: 600; margin-top: 4px; }
.kpi .kpi-value.ok { color: #67c23a; }
.kpi .kpi-value.bad { color: #f56c6c; }
.kpi .kpi-value.date { font-size: 16px; }
.promise-note { font-size: 12px; color: #909399; margin-top: 8px; }
</style>
