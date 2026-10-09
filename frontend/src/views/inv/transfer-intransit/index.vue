<template>
  <div class="page">
    <el-alert type="info" :closable="false" style="margin-bottom: 8px"
      title="在途 = 已完成出库过账、未入库的调拨单；超 TRANSIT_ALERT_DAYS（默认 30 天）未入库由每日扫描自动挂起并通知双方仓库主管（C-4.4-09）。" />

    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item label="关键字">
        <el-input v-model="keyword" placeholder="调拨单/仓库" clearable style="width: 200px"
          @keyup.enter="reload" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="reload">查询</el-button>
        <el-button @click="loadData" :loading="loading">刷新</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe>
      <el-table-column prop="transferNo" label="调拨单号" width="150" />
      <el-table-column label="调出 → 调入" width="180">
        <template #default="{ row }">{{ row.outWhCode }} → {{ row.inWhCode }}</template>
      </el-table-column>
      <el-table-column label="法人对" width="190">
        <template #default="{ row }">
          {{ row.outLeCode }} → {{ row.inLeCode }}
          <el-tag :type="row.crossLe === '1' ? 'warning' : 'info'" size="small" style="margin-left: 4px">
            {{ row.crossLe === '1' ? '跨法人' : '同法人' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="totalQty" label="数量" width="90" align="right" />
      <el-table-column prop="totalAmount" label="金额" width="100" align="right" />
      <el-table-column prop="outPostAt" label="出库时间" width="155">
        <template #default="{ row }">{{ fmtTime(row.outPostAt) }}</template>
      </el-table-column>
      <el-table-column label="在途天数" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="Number(row.inTransitDays) >= 30 ? 'danger' : (Number(row.inTransitDays) >= 15 ? 'warning' : 'success')"
            size="small">{{ row.inTransitDays }} 天</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.suspendedFlag === '1'" type="danger" size="small">超期挂起</el-tag>
          <el-tag v-else type="success" size="small">在途正常</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canOperate" link type="primary" size="small" :loading="actingId === row.id"
            @click="doPostIn(row)">入库过账</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && rows.length === 0" description="无在途调拨单（全部已入库）" />
    <el-pagination v-if="total > 0" class="pager" background layout="total, prev, pager, next"
      :total="total" :page-size="size" :current-page="current" @current-change="onPage" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getIntransitApi, postTransferInApi } from '@/api/inv/transfer'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const loading = ref(false)
const actingId = ref('')
const rows = ref([])
const total = ref(0)
const keyword = ref('')
const current = ref(1)
const size = ref(20)

const canOperate = computed(() => {
  const roles = userStore.userInfo?.roles || []
  return roles.includes('ROLE_ADMIN') || roles.includes('ROLE_WAREHOUSE')
})

function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}

async function loadData() {
  loading.value = true
  try {
    const params = { current: current.value, size: size.value }
    if (keyword.value) params.keyword = keyword.value
    const res = await getIntransitApi(params)
    rows.value = res.data.rows || []
    total.value = Number(res.data.total || 0)
  } finally {
    loading.value = false
  }
}
function reload() {
  current.value = 1
  loadData()
}
function onPage(p) {
  current.value = p
  loadData()
}

async function doPostIn(row) {
  await ElMessageBox.confirm(
    `确认对 ${row.transferNo} 执行入库过账？${row.crossLe === '1' ? '（跨法人：生成内部采购票并配对核销）' : ''}（完成后退出在途）`,
    '入库过账', { type: 'warning' })
  actingId.value = row.id
  try {
    const res = await postTransferInApi(row.id)
    if (res.code === 200) {
      ElMessage.success('入库过账完成，已退出在途')
      loadData()
    } else {
      ElMessage.error(res.message || '过账失败')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '过账失败')
  } finally {
    actingId.value = ''
  }
}

onMounted(loadData)
</script>

<style scoped>
.page { padding: 4px; }
.filter-bar { margin-bottom: 4px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
