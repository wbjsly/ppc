<template>
  <div>
    <el-page-header content="负荷监控（5.6.2）— WC×日期负荷 = RELEASED 派工占用 ÷ 日历班次可用；超 110%（CAPACITY_LOAD_TRIGGER）标红并可跳外协建议"
                    style="margin-bottom: 8px" />

    <div class="toolbar">
      <el-date-picker v-model="range" type="daterange" size="small" value-format="YYYY-MM-DD"
                      start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false"
                      style="width: 260px" />
      <el-input v-model="wcCode" placeholder="工作中心编码" clearable style="width: 150px"
                size="small" @keyup.enter="load" @clear="load" />
      <el-button size="small" type="primary" @click="load">查询</el-button>
      <el-switch v-model="onlyOver" active-text="只看超限" size="small" @change="applyFilter" />
      <span class="tip">阈值来自参数 CAPACITY_LOAD_TRIGGER（默认 110%）；无可用时段有占用 = 999%</span>
    </div>

    <el-table :data="shown" v-loading="loading" border stripe size="small" max-height="440"
              :row-class-name="rowClass">
      <el-table-column prop="wcCode" label="工作中心" width="150" />
      <el-table-column prop="date" label="日期" width="120" align="center" />
      <el-table-column prop="assignedHours" label="已分配(h)" width="110" align="right" />
      <el-table-column prop="availableHours" label="可用(h)" width="110" align="right" />
      <el-table-column label="负荷率" width="110" align="right">
        <template #default="{ row }">
          <span :style="{ color: row.overLimit ? '#f56c6c' : '#67c23a', fontWeight: 600 }">
            {{ row.loadRate }}%
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.overLimit" type="danger" size="small">超限</el-tag>
          <el-tag v-else type="success" size="small">正常</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" align="center">
        <template #default="{ row }">
          <el-button v-if="row.overLimit" size="small" type="danger" link @click="goOutsource(row)">
            外协建议 →
          </el-button>
          <span v-else>-</span>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && shown.length === 0" description="区间内无派工占用（或筛选无命中）"
              :image-size="60" />
  </div>
</template>

<script setup>
import { ref, onMounted, onActivated } from 'vue'
import { useRouter } from 'vue-router'
import { getLoadsApi } from '@/api/mrp/dispatch'

const router = useRouter()
const loading = ref(false)
const rows = ref([])
const shown = ref([])
const range = ref([])
const wcCode = ref('')
const onlyOver = ref(false)

function defaultRange() {
  const fmt = d => d.toISOString().slice(0, 10)
  const from = new Date()
  const to = new Date()
  to.setDate(to.getDate() + 6)
  return [fmt(from), fmt(to)]
}

async function load() {
  loading.value = true
  try {
    const [from, to] = range.value.length === 2 ? range.value : defaultRange()
    const res = await getLoadsApi(from, to, wcCode.value || undefined)
    rows.value = res.data || []
    applyFilter()
  } finally {
    loading.value = false
  }
}

function applyFilter() {
  shown.value = onlyOver.value ? rows.value.filter(r => r.overLimit) : rows.value
}

function rowClass({ row }) {
  return row.overLimit ? 'over-row' : ''
}

function goOutsource(row) {
  router.push({ path: '/m/5.6.3', query: { wcCode: row.wcCode, date: row.date } })
}

onMounted(() => { range.value = defaultRange(); load() })
onActivated(() => { if (range.value.length !== 2) range.value = defaultRange(); load() })
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
:deep(.over-row) { background: #fef0f0; }
</style>
