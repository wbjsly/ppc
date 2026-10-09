<template>
  <div class="exp-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="效期优先（4.6.2）：FEFO 排序第二键 + 效期两级标记 + 黄橙红预警"
      description="推荐排序：同日入库按有效期升序（FEFO）。两级标记——效期警告（剩余 < MIN_REMAINING_SHELF_DAYS，放行但出库单加注）；效期锁定（剩余 < 有效期×EXPIRY_LOCK_RATIO，剔出推荐池并禁止出库过账，BR-4.4-20 / C-4.4-03）。锁定后的质量评估处置归 4.10 效期管理。" />

    <div class="toolbar">
      <el-input v-model="filters.itemCode" placeholder="物料" clearable style="width: 160px;"
        @clear="load" @keyup.enter="load" />
      <el-input v-model="filters.warehouseCode" placeholder="仓库" clearable style="width: 140px;"
        @clear="load" @keyup.enter="load" />
      <el-select v-model="filters.level" placeholder="预警等级" clearable style="width: 130px;"
        @change="load">
        <el-option label="黄色（≤90 天）" value="YELLOW" />
        <el-option label="橙色（≤60 天）" value="ORANGE" />
        <el-option label="红色（≤30 天）" value="RED" />
      </el-select>
      <el-select v-model="filters.locked" placeholder="锁定状态" clearable style="width: 130px;"
        @change="load">
        <el-option label="已锁定" :value="true" />
        <el-option label="未锁定" :value="false" />
      </el-select>
      <el-button @click="load">查询</el-button>
      <el-button type="primary" plain @click="load">刷新</el-button>
    </div>

    <div class="stats" v-if="thresholds">
      <el-tag type="warning" size="large">黄色 ≤ {{ thresholds.yellow }} 天</el-tag>
      <el-tag type="warning" size="large" effect="dark">橙色 ≤ {{ thresholds.orange }} 天</el-tag>
      <el-tag type="danger" size="large" effect="dark">红色 ≤ {{ thresholds.red }} 天</el-tag>
      <span class="ratio">锁定阈值：剩余 &lt; 有效期总天数 × {{ lockRatio }}</span>
    </div>

    <el-table :data="rows" size="small" border v-loading="loading" style="margin-top: 10px;">
      <el-table-column prop="itemCode" label="物料" width="150" />
      <el-table-column prop="itemName" label="名称" min-width="140" />
      <el-table-column prop="batchNo" label="批次" width="150" />
      <el-table-column prop="expiryDate" label="有效期至" width="110" />
      <el-table-column prop="remainingDays" label="剩余天数" width="90" sortable />
      <el-table-column label="预警等级" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="levelType(row.level)">{{ levelText(row.level) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.locked" size="small" type="danger">已锁定</el-tag>
          <el-tag v-else size="small" type="success">正常</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="qty" label="可用量" width="100" />
      <el-table-column label="处置" min-width="220">
        <template #default="{ row }">
          <span v-if="row.locked" style="color: #F56C6C;">
            已剔出推荐池并禁止出库过账；质量评估与处置归 4.10 效期管理
          </span>
          <span v-else style="color: #E6A23C;">
            效期警告：仍在推荐池，出库单将加注剩余效期提示（BR-4.4-21）
          </span>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top: 10px; justify-content: flex-end;"
      layout="total, prev, pager, next" :total="total" :page-size="20"
      :current-page="filters.current" @current-change="p => { filters.current = p; load() }" />
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { getExpiryWarningsApi } from '@/api/inv/fifo'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const thresholds = ref(null)
const lockRatio = ref('')
const filters = reactive({ current: 1, itemCode: '', warehouseCode: '', level: '', locked: null })

function levelText(l) {
  return l === 'RED' ? '红色' : l === 'ORANGE' ? '橙色' : '黄色'
}
function levelType(l) {
  return l === 'RED' ? 'danger' : l === 'ORANGE' ? 'warning' : 'warning'
}

async function load() {
  loading.value = true
  try {
    const res = await getExpiryWarningsApi({
      current: filters.current,
      size: 20,
      itemCode: filters.itemCode || undefined,
      warehouseCode: filters.warehouseCode || undefined,
      level: filters.level || undefined,
      locked: filters.locked === null ? undefined : filters.locked
    })
    rows.value = res.data.records || []
    total.value = Number(res.data.total || 0)
    thresholds.value = res.data.thresholds || null
    lockRatio.value = res.data.lockRatio
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.exp-page { padding: 12px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 10px; align-items: center; flex-wrap: wrap; }
.stats { display: flex; gap: 10px; align-items: center; }
.ratio { font-size: 13px; color: #909399; margin-left: 8px; }
</style>
