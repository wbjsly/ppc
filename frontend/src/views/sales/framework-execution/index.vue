<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.11.2 框架分批发货（执行视图）"
              description="本页是框架协议的执行视图：按协议行展示已下达/已发/剩余可发与分批执行时间表，并从视图对已下达单发起分批发货（复用 3.7 发货单能力，过账后回写已发量）。本页不承载单次发货的拆行策略——那属于 3.7.3 分批发货（单次发货按策略拆行），两者职责不同：此处按「协议 → 下达单」维度执行，彼时按「单张发货单」维度拆行。下达超总量 / 发货超已下达以 L1 硬阻断（C-4.3-10）；协议终止/过期冻结后续发货。" />

    <el-row :gutter="12" class="mb12">
      <el-col :span="4" v-for="k in kpis" :key="k.label">
        <div class="kpi-box">
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value" :style="{ color: k.color || '#303133' }">{{ k.value }}</div>
        </div>
      </el-col>
      <el-col :span="4">
        <el-select v-model="fwId" filterable placeholder="选择框架协议" size="small"
                   style="width:100%" @change="loadExecution">
          <el-option v-for="f in frameworks" :key="f.id"
                     :label="`${f.fwNo} ${f.customerName || ''}`" :value="f.id" />
        </el-select>
      </el-col>
    </el-row>

    <el-alert v-if="current && current.status !== 'EFFECTIVE'" type="error" :closable="false"
              show-icon class="mb12"
              :title="`协议状态 ${current.status === 'TERMINATED' ? '已终止' : '已过期'} —— 后续下达与发货已冻结（S-4.3-11 / 状态机）`" />

    <el-row :gutter="12">
      <!-- 行余量 -->
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <b>协议行执行情况（三量余量）</b>
              <el-button size="small" @click="loadExecution">刷新</el-button>
            </div>
          </template>
          <el-table :data="lines" size="small" border>
            <el-table-column prop="line.lineNo" label="#" width="42" />
            <el-table-column prop="line.itemCode" label="物料" width="120" />
            <el-table-column prop="line.itemName" label="名称" min-width="120" show-overflow-tooltip />
            <el-table-column label="总量/已下达/已发" width="160" align="right">
              <template #default="{ row }">
                {{ Number(row.line.totalQty) }} / {{ Number(row.line.releasedQty) }} /
                <b>{{ Number(row.line.shippedQty) }}</b>
              </template>
            </el-table-column>
            <el-table-column label="剩余可发" width="95" align="right">
              <template #default="{ row }">
                <span :style="{ color: Number(row.remainShip) > 0 ? '#67c23a' : '#c0c4cc' }">
                  {{ Number(row.remainShip) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="执行率" width="130">
              <template #default="{ row }">
                <el-progress :percentage="Math.min(100,
                  Math.round(Number(row.executeRate) * 100))" :stroke-width="12" />
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!lines.length" description="请选择协议" :image-size="60" />
        </el-card>
      </el-col>

      <!-- 执行时间表 / 发起发货 -->
      <el-col :span="10">
        <el-card shadow="never" style="height:100%">
          <template #header>
            <b>分批执行时间表（下达单）</b>
          </template>
          <el-table :data="releases" size="small" border>
            <el-table-column label="下达单" width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ row.release.releaseNo }}</template>
            </el-table-column>
            <el-table-column prop="release.itemCode" label="物料" width="100" />
            <el-table-column label="下达/已发/剩余" width="130" align="right">
              <template #default="{ row }">
                {{ Number(row.release.qty) }} / {{ Number(row.release.shippedQty) }} /
                <b>{{ Number(row.remainShip) }}</b>
              </template>
            </el-table-column>
            <el-table-column label="交期" width="100">
              <template #default="{ row }">{{ row.release.deliverDate || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button v-if="row.release.status !== 'CANCELLED'
                          && row.release.status !== 'SHIPPED'
                          && active && Number(row.remainShip) > 0"
                           size="small" type="primary" @click="openShip(row)">
                  发起发货
                </el-button>
                <el-tag v-else-if="row.release.status === 'SHIPPED'" size="small"
                        type="success">已发完</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!releases.length" description="暂无下达单（到 3.11.1 下达）"
                    :image-size="60" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 发起发货 -->
    <el-dialog v-model="shipVisible" title="框架分批发货（复用 3.7 发货能力）" width="520px">
      <el-descriptions v-if="shipRow" :column="2" size="small" border class="mb12">
        <el-descriptions-item label="下达单">{{ shipRow.release.releaseNo }}</el-descriptions-item>
        <el-descriptions-item label="物料">{{ shipRow.release.itemCode }}</el-descriptions-item>
        <el-descriptions-item label="本单剩余可发"><b>{{ Number(shipRow.remainShip) }}</b></el-descriptions-item>
        <el-descriptions-item label="协议剩余可发">
          {{ lineRemainOf(shipRow) }}
        </el-descriptions-item>
      </el-descriptions>
      <el-form label-width="90px" size="small">
        <el-form-item label="发货数量" required>
          <el-input-number v-model="shipForm.qty" :min="0" :precision="3"
                           :max="Number(shipRow ? shipRow.remainShip : 0)"
                           style="width:100%" />
          <span class="hint">出库过账按 FIFO 选批，合格可锁批次不足将阻断（C-4.3-10 / ATP 口径）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="shipVisible = false">取消</el-button>
        <el-button type="primary" :loading="shipping" @click="doShip">出库过账</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getFrameworksApi, getFrameworkExecutionApi, shipReleaseApi
} from '@/api/sd/framework'

const frameworks = ref([])
const fwId = ref('')
const current = ref(null)
const lines = ref([])
const releases = ref([])
const shipVisible = ref(false)
const shipRow = ref(null)
const shipping = ref(false)
const shipForm = ref({ qty: 0 })

const active = computed(() => current.value && current.value.status === 'EFFECTIVE')

const kpis = computed(() => {
  const total = lines.value.reduce((s, r) => s + Number(r.line.totalQty || 0), 0)
  const released = lines.value.reduce((s, r) => s + Number(r.line.releasedQty || 0), 0)
  const shipped = lines.value.reduce((s, r) => s + Number(r.line.shippedQty || 0), 0)
  const remain = lines.value.reduce((s, r) => s + Number(r.remainShip || 0), 0)
  return [
    { label: '协议总量', value: total, color: '#409eff' },
    { label: '已下达', value: released, color: '#e6a23c' },
    { label: '已发量', value: shipped, color: '#67c23a' },
    { label: '剩余可发', value: remain, color: remain > 0 ? '#67c23a' : '#c0c4cc' }
  ]
})

function lineRemainOf(row) {
  const l = lines.value.find(x => x.line.id === row.release.fwLineId)
  return l ? Number(l.remainShip) : '—'
}

async function load() {
  const res = await getFrameworksApi({ current: 1, size: 100 })
  frameworks.value = res.data.records || res.data || []
  if (!fwId.value && frameworks.value.length) {
    fwId.value = frameworks.value[0].id
    loadExecution()
  }
}

async function loadExecution() {
  if (!fwId.value) return
  const res = await getFrameworkExecutionApi(fwId.value)
  const d = res.data || {}
  current.value = d.framework
  lines.value = d.lines || []
  releases.value = d.releases || []
}

function openShip(row) {
  shipRow.value = row
  shipForm.value = { qty: Number(row.remainShip) }
  shipVisible.value = true
}

async function doShip() {
  if (Number(shipForm.value.qty) <= 0) return ElMessage.warning('发货数量必须大于 0')
  shipping.value = true
  try {
    const res = await shipReleaseApi(shipRow.value.release.id, { qty: shipForm.value.qty })
    const ship = res.data.shipment || {}
    ElMessage.success('已出库过账：' + ship.shipNo + '（已回写已发量）')
    shipVisible.value = false
    loadExecution()
  } finally {
    shipping.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-wrap { padding: 12px; }
.mb12 { margin-bottom: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.kpi-box { background: #f5f7fa; border-radius: 4px; padding: 10px 12px; }
.kpi-label { font-size: 12px; color: #909399; margin-bottom: 4px; }
.kpi-value { font-size: 18px; font-weight: 600; }
.hint { font-size: 12px; color: #909399; display: block; margin-top: 4px; }
</style>
