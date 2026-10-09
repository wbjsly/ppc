<template>
  <div class="pd-page">
    <el-alert type="warning" :closable="false" style="margin-bottom: 12px;"
      title="差异处理（4.7.4）：《盘点差异单》拣货差异闭环"
      description="PENDING 差异存在时该单据全部出库过账入口 422（BR-4.4-29 门闩）；闭环（处理说明必填）后自动解锁并回迁任务。本组仅主管确认、不挂审批（偏差 D3，超容差审批留 4.11）。" />

    <div class="toolbar">
      <el-select v-model="filters.status" placeholder="状态" clearable style="width: 130px;"
        @change="load">
        <el-option label="待处理" value="PENDING" />
        <el-option label="已闭环" value="RESOLVED" />
      </el-select>
      <el-select v-model="filters.kind" placeholder="差异类型" clearable style="width: 130px;"
        @change="load">
        <el-option label="数量差异" value="QTY" />
        <el-option label="批次差异" value="BATCH" />
        <el-option label="序列差异" value="SERIAL" />
        <el-option label="质量差异" value="QUALITY" />
      </el-select>
      <el-input v-model="filters.docNo" placeholder="来源单据号" clearable style="width: 170px;"
        @clear="load" @keyup.enter="load" />
      <el-input v-model="filters.itemCode" placeholder="物料" clearable style="width: 140px;"
        @clear="load" @keyup.enter="load" />
      <el-button @click="load">查询</el-button>
      <el-tag v-if="pendingCnt" type="danger" size="large">
        {{ pendingCnt }} 条未闭环（阻断过账）
      </el-tag>
    </div>

    <el-table :data="rows" size="small" border v-loading="loading">
      <el-table-column label="来源单据" width="180">
        <template #default="{ row }">{{ typeLabel(row.srcDocType) }} {{ row.srcDocNo }}</template>
      </el-table-column>
      <el-table-column prop="lineNo" label="行" width="50" />
      <el-table-column prop="itemCode" label="物料" width="140" />
      <el-table-column prop="batchNo" label="批次" width="120" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="kindTag(row.diffKind)">{{ kindText(row.diffKind) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="expectQty" label="应拣" width="80" />
      <el-table-column prop="actualQty" label="实拣" width="80" />
      <el-table-column prop="deltaQty" label="差额" width="80" />
      <el-table-column prop="diffNote" label="差异说明" min-width="160" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'PENDING' ? 'danger' : 'success'">
            {{ row.status === 'PENDING' ? '待处理' : '已闭环' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="resolveNote" label="处理说明" min-width="160" show-overflow-tooltip />
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canWh && row.status === 'PENDING'" link type="primary"
            size="small" @click="openClose(row)">闭环</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top: 10px; justify-content: flex-end;"
      layout="total, prev, pager, next" :total="total" :page-size="20"
      :current-page="filters.current" @current-change="p => { filters.current = p; load() }" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPickDiffsApi, closePickDiffApi } from '@/api/inv/pick'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const roles = (userStore.userInfo && userStore.userInfo.roles) || []
const canWh = roles.includes('ROLE_ADMIN') || roles.includes('ROLE_WAREHOUSE')

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const filters = reactive({ current: 1, status: 'PENDING', kind: '', docNo: '', itemCode: '' })
const pendingCnt = computed(() =>
  rows.value.filter(r => r.status === 'PENDING').length)

const typeLabel = t => ({ SALES_OUT: '销售出库', MATERIAL_OUT: '领料出库',
  TRANSFER_OUT: '调拨出库', SCRAP_OUT: '报废出库' }[t] || t || '—')
const kindText = k => ({ QTY: '数量', BATCH: '批次', SERIAL: '序列', QUALITY: '质量' }[k] || k)
const kindTag = k => ({ QTY: 'warning', BATCH: 'warning', SERIAL: 'warning',
  QUALITY: 'danger' }[k] || 'info')

async function load() {
  loading.value = true
  try {
    const res = await getPickDiffsApi({
      current: filters.current, size: 20,
      status: filters.status || undefined,
      kind: filters.kind || undefined,
      docNo: filters.docNo || undefined,
      itemCode: filters.itemCode || undefined
    })
    rows.value = res.data.records || []
    total.value = Number(res.data.total || 0)
  } finally {
    loading.value = false
  }
}

async function openClose(row) {
  const { value } = await ElMessageBox.prompt(
    `闭环处理说明（必填）：${row.srcDocNo} 行 ${row.lineNo} ${kindText(row.diffKind)}差异`,
    '差异闭环', { type: 'warning', inputValue: '' })
  if (!String(value).trim()) {
    ElMessage.error('处理说明必填')
    return
  }
  await closePickDiffApi(row.id, String(value).trim())
  ElMessage.success('已闭环（过账门闩解除，任务状态已回迁）')
  load()
}

onMounted(load)
</script>

<style scoped>
.pd-page { padding: 12px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; flex-wrap: wrap; }
</style>
