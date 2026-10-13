<template>
  <div class="rework-order">
    <el-card shadow="never">
      <template #header>
        <span>5.8.1 返工工单（计划侧）</span>
        <span class="tip">判定来源选入 → C-4.5-13 卡控 → 返工路线 → 冻结转在制 → 建单</span>
      </template>

      <!-- 判定来源选择 -->
      <el-form :inline="true" class="filter-bar" @submit.prevent>
        <el-form-item label="来源类型">
          <el-radio-group v-model="srcType" @change="loadSources">
            <el-radio-button value="NCR">NCR（处置=返工）</el-radio-button>
            <el-radio-button value="RETURN">退货（判定=可返修）</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-button @click="loadSources">刷新候选</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="sources" border stripe highlight-current-row size="small"
                v-loading="srcLoading" @current-change="onPickSource" max-height="220">
        <el-table-column label="判定单号" width="180">
          <template #default="{ row }">
            <el-tag size="small" :type="srcType === 'NCR' ? 'danger' : 'warning'">{{ srcType }}</el-tag>
            {{ row.srcNo }}
          </template>
        </el-table-column>
        <el-table-column prop="productCode" label="产品编码" width="150" />
        <el-table-column prop="productName" label="产品名称" min-width="140" />
        <el-table-column prop="batchNo" label="投入批次" width="120" />
        <el-table-column label="批准数量" width="100" align="right">
          <template #default="{ row }">{{ row.qty }}</template>
        </el-table-column>
        <template #empty>无待开返工的判定单（处置/判定已关联或无返工类结论）</template>
      </el-table>

      <!-- 建单表单 -->
      <el-divider content-position="left">建单信息</el-divider>
      <el-form :model="form" label-width="110px" size="small">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="判定单">
              <el-input :model-value="form.srcNo || '（从上表选入）'" readonly />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="投入数量" required>
              <el-input-number v-model="form.qty" :min="0.01" :precision="4"
                               :max="maxQty || undefined" style="width: 100%"
                               placeholder="≤ 判定批准数量（C-4.5-13）" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="计划完工">
              <el-date-picker v-model="form.planEndDate" type="date" style="width: 100%"
                              placeholder="默认 +7 天" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="追加料（返工BOM）">
          <el-table :data="form.bom" size="small" border max-height="180">
            <el-table-column label="物料编码" width="200">
              <template #default="{ row }">
                <el-input v-model="row.itemCode" placeholder="编码级扫码/手输" />
              </template>
            </el-table-column>
            <el-table-column label="名称" min-width="140">
              <template #default="{ row }">
                <el-input v-model="row.itemName" />
              </template>
            </el-table-column>
            <el-table-column label="单位用量" width="140">
              <template #default="{ row }">
                <el-input-number v-model="row.unitQty" :min="0.0001" :precision="6"
                                 :controls="false" style="width: 100%" />
              </template>
            </el-table-column>
            <el-table-column label="损耗率" width="120">
              <template #default="{ row }">
                <el-input-number v-model="row.lossRate" :min="0" :max="1" :precision="4"
                                 :controls="false" style="width: 100%" />
              </template>
            </el-table-column>
            <el-table-column label="" width="70">
              <template #default="{ $index }">
                <el-button size="small" type="danger" text @click="form.bom.splice($index, 1)">删</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button size="small" style="margin-top: 6px" @click="addBomRow">+ 加追加料行（可空）</el-button>
          <span class="tip" style="margin-left: 10px">BR-4.5-32：追加料逐笔确认、禁止倒冲；投入品由冻结转在制</span>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" :disabled="!form.srcNo"
                     @click="createOrder">创建返工工单</el-button>
          <el-button @click="loadSources">清空选择</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 返工列表 -->
    <el-card shadow="never" style="margin-top: 12px">
      <template #header>
        <span>返工工单列表（RW 分池）</span>
        <el-button size="small" style="float: right" @click="loadList">刷新</el-button>
      </template>
      <el-table :data="rows" border stripe size="small" v-loading="listLoading">
        <el-table-column prop="moNo" label="返工单号" width="170">
          <template #default="{ row }">
            <el-tag type="warning" size="small">RW</el-tag> {{ row.moNo }}
          </template>
        </el-table-column>
        <el-table-column prop="productCode" label="产品" width="140" />
        <el-table-column prop="qty" label="投入数量" width="90" align="right" />
        <el-table-column prop="srcType" label="来源" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.srcType === 'NCR' ? 'danger' : 'warning'">{{ row.srcType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="srcNo" label="判定单" width="180" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="阶段" width="140" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.phase" size="small" :type="phaseTag(row.phase)">{{ phaseText(row.phase) }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="$router.push({ path: '/m/5.6.1', query: { moNo: row.moNo } })">
              派工
            </el-button>
          </template>
        </el-table-column>
        <template #empty>暂无返工工单</template>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getSourcesApi, createReworkApi, getReworkListApi, getReworkPhaseApi
} from '@/api/mrp/rework'

const srcType = ref('NCR')
const sources = ref([])
const srcLoading = ref(false)
const rows = ref([])
const listLoading = ref(false)
const saving = ref(false)

const form = ref({ srcNo: null, qty: null, planEndDate: null, bom: [] })
const picked = ref(null)
const maxQty = computed(() => (picked.value ? Number(picked.value.qty) : null))

function addBomRow() {
  form.value.bom.push({ itemCode: '', itemName: '', unitQty: 1, lossRate: 0 })
}

async function loadSources() {
  srcLoading.value = true
  try {
    const res = await getSourcesApi(srcType.value)
    sources.value = res.data || []
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '候选加载失败')
  } finally {
    srcLoading.value = false
  }
}

function onPickSource(row) {
  picked.value = row
  if (!row) return
  form.value.srcNo = row.srcNo
  form.value.qty = Number(row.qty)
}

async function createOrder() {
  if (!form.value.srcNo) return ElMessage.warning('请先从候选表选入判定单')
  if (!form.value.qty || form.value.qty > (maxQty.value ?? Infinity)) {
    return ElMessage.warning('投入数量须 ≤ 判定批准数量（C-4.5-13）')
  }
  saving.value = true
  try {
    const head = {
      srcType: srcType.value,
      srcNo: form.value.srcNo,
      productCode: picked.value?.productCode,
      qty: form.value.qty,
      planEndDate: form.value.planEndDate
        ? new Date(form.value.planEndDate).toISOString().slice(0, 10) : null
    }
    const res = await createReworkApi(head, form.value.bom.filter(b => b.itemCode))
    ElMessage.success('返工工单已创建：' + res.data.moNo)
    form.value = { srcNo: null, qty: null, planEndDate: null, bom: [] }
    picked.value = null
    await Promise.all([loadSources(), loadList()])
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '创建失败')
  } finally {
    saving.value = false
  }
}

async function loadList() {
  listLoading.value = true
  try {
    const res = await getReworkListApi({})
    rows.value = res.data || []
    // COMPLETED 段拉阶段（推导）
    for (const r of rows.value) {
      if (r.status === 'COMPLETED') {
        try {
          const p = await getReworkPhaseApi(r.id)
          r.phase = p.data
        } catch { r.phase = null }
      } else {
        r.phase = null
      }
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '列表加载失败')
  } finally {
    listLoading.value = false
  }
}

function statusTag(s) {
  return { PLANNED: 'info', PENDING: 'warning', CONFIRMED: '', RELEASED: 'success',
    HOLD: 'warning', COMPLETED: 'success', CLOSED: 'info', CANCELLED: 'danger' }[s] || 'info'
}
function phaseTag(p) {
  return { PENDING_INSP: 'warning', PENDING_STOCK_IN: 'danger', CLOSED: 'info' }[p] || 'info'
}
function phaseText(p) {
  return { PENDING_INSP: '待检验', PENDING_STOCK_IN: '待入库', CLOSED: '已闭环' }[p] || p
}

onMounted(() => {
  loadSources()
  loadList()
})
</script>

<style scoped>
.rework-order { padding: 4px; }
.tip { color: var(--el-text-color-secondary); font-size: 12px; }
.filter-bar { margin-bottom: 4px; }
</style>
