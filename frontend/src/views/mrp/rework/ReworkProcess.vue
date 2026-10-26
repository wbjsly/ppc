<template>
  <div class="rework-process">
    <el-card shadow="never">
      <template #header>
        <span>5.8.2 返修处理（质量侧，计划员只读）</span>
        <span class="tip">复检跟踪 → 二次返工审批 → 强制报废 → 待入库重试 → 成本明细</span>
      </template>

      <el-table :data="rows" border stripe size="small" v-loading="loading"
                highlight-current-row @current-change="onSelect">
        <el-table-column prop="moNo" label="返工单号" width="170">
          <template #default="{ row }">
            <el-tag type="warning" size="small">RW</el-tag> {{ row.moNo }}
          </template>
        </el-table-column>
        <el-table-column prop="productCode" label="产品" width="130" />
        <el-table-column prop="qty" label="投入" width="70" align="right" />
        <el-table-column prop="srcNo" label="判定单" width="170" />
        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="阶段" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.phase" size="small" :type="phaseTag(row.phase)">{{ phaseText(row.phase) }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="330">
          <template #default="{ row }">
            <el-button size="small" type="warning" plain
                       :disabled="!canQuality || row.status !== 'PLANNED'"
                       @click="doSecond(row)">二次返工审批</el-button>
            <el-button size="small" type="danger" plain
                       :disabled="!canQuality || row.phase !== 'PENDING_INSP'"
                       @click="doScrap(row)">强制报废</el-button>
            <el-button size="small" type="success" plain
                       :disabled="!canQuality || row.phase !== 'PENDING_STOCK_IN'"
                       @click="doStockIn(row)">入库重试</el-button>
          </template>
        </el-table-column>
        <template #empty>暂无返工工单</template>
      </el-table>
      <div class="tip" style="margin-top: 6px">
        二次返工审批：判定单第二张建单前置（ROLE_QUALITY_MGR 签）；强制报废：第二次返工检验不合格终局（BR-4.5-02）；
        入库重试：复检合格放行回调失败的幂等补推
      </div>
    </el-card>

    <!-- 成本与经济性 -->
    <el-card shadow="never" style="margin-top: 12px">
      <template #header>
        <span>返工成本归集明细</span>
        <el-button size="small" style="float: right" :disabled="!selected" @click="loadCost">查询选中单</el-button>
      </template>
      <el-empty v-if="!selected" description="从上表选中一张返工工单" :image-size="60" />
      <template v-else>
        <el-descriptions v-if="cost.amount" :column="3" border size="small">
          <el-descriptions-item label="归集金额">¥ {{ cost.amount }}</el-descriptions-item>
          <el-descriptions-item label="归集状态">{{ cost.status || '-' }}</el-descriptions-item>
          <el-descriptions-item label="关联单号">{{ cost.sourceNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="明细" :span="3">{{ cost.remark }}</el-descriptions-item>
        </el-descriptions>
        <el-alert v-else :closable="false" type="info"
                  :title="`该工单暂无归集记录（${selected.moNo}）`"
                  description="完工确认时按投料×标准成本 + 工时×费率实时归集挂原判定单 COPQ（FR-4.5-7-7）；超重置成本会推送经济性评估通知" />
        <el-alert style="margin-top: 8px" :closable="false" type="warning"
                  v-if="cost.remark && /超产品重置成本|经济性/.test(cost.remark || '')"
                  title="经济性提示：归集额已超重置成本，请质量主管评估" />
      </template>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getReworkListApi, getReworkPhaseApi, getReworkCostApi,
  applySecondApi, enforceScrapApi, stockInApi } from '@/api/mrp/rework'

const rows = ref([])
const loading = ref(false)
const selected = ref(null)
const cost = ref({})

// 质量角色判定（按钮启用面；服务层仍有 403 硬校验）
import { useUserStore } from '@/store/user'
const userStore = useUserStore()
const canQuality = computed(() => {
  const roles = userStore.userInfo?.roles || []
  return roles.includes('ROLE_QUALITY_ENG') || roles.includes('ROLE_QUALITY_MGR')
    || roles.includes('ROLE_ADMIN')
})

async function load() {
  loading.value = true
  try {
    const res = await getReworkListApi({})
    rows.value = res.data || []
    for (const r of rows.value) {
      if (r.status === 'COMPLETED' || r.status === 'CLOSED') {
        try { r.phase = (await getReworkPhaseApi(r.id)).data } catch { r.phase = null }
      } else {
        r.phase = null
      }
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '列表加载失败')
  } finally {
    loading.value = false
  }
}

function onSelect(row) {
  selected.value = row
  cost.value = {}
}

async function loadCost() {
  if (!selected.value) return
  try {
    const res = await getReworkCostApi(selected.value.id)
    cost.value = res.data || {}
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '成本查询失败')
  }
}

async function doSecond(row) {
  try {
    await ElMessageBox.confirm(
      `对判定单 ${row.srcNo} 发起二次返工审批（质量主管签核后方可建第二张返工工单）？`,
      '二次返工审批', { type: 'warning' })
    const res = await applySecondApi(row.srcNo)
    ElMessage.success(res.data?.already ? '审批已通过，可建第二张'
      : (res.data?.pending ? '已发起（幂等返回既有实例）：' + res.data.apprNo : '已发起'))
  } catch (e) {
    if (e !== 'cancel' && e?.message !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || e.message || '发起失败')
    }
  }
}

async function doScrap(row) {
  try {
    await ElMessageBox.confirm(
      `强制报废 ${row.moNo}？仅第二次返工检验不合格可用——在制出账、冻结释放、工单关闭终局（BR-4.5-02）。`,
      '强制报废确认', { type: 'error', confirmButtonText: '确认报废' })
    await enforceScrapApi(row.id)
    ElMessage.success('强制报废已执行')
    await load()
  } catch (e) {
    if (e !== 'cancel' && e?.message !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || e.message || '报废失败')
    }
  }
}

async function doStockIn(row) {
  try {
    await ElMessageBox.confirm(`对 ${row.moId || row.moNo} 执行入库解冻关单重试（幂等）？`,
      '待入库重试', { type: 'warning' })
    await stockInApi(row.id)
    ElMessage.success('入库解冻关单完成')
    await load()
  } catch (e) {
    if (e !== 'cancel' && e?.message !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || e.message || '重试失败')
    }
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

onMounted(load)
</script>

<style scoped>
.rework-process { padding: 4px; }
.tip { color: var(--el-text-color-secondary); font-size: 12px; }
</style>
