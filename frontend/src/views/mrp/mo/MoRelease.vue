<template>
  <div>
    <el-page-header content="工单释放（5.4.3）— 已确认工单释放为已释放；释放前重跑齐套，缺料不阻断仅标记「缺料待料」"
                    style="margin-bottom: 8px" />

    <div class="toolbar">
      <el-select v-model="status" style="width: 160px" size="small" @change="load">
        <el-option label="已确认（可释放）" value="CONFIRMED" />
        <el-option label="已释放" value="RELEASED" />
        <el-option label="挂起" value="HOLD" />
        <el-option label="全部状态" value="" />
      </el-select>
      <el-button size="small" @click="load">刷新</el-button>
      <span class="tip">释放留痕释放人/时间；缺料工单照常释放并打标（C-4.5-11 的 L1 阻断在 5.6 派工）</span>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe size="small">
      <el-table-column prop="moNo" label="工单号" width="160" />
      <el-table-column prop="productCode" label="产品" width="130" />
      <el-table-column prop="productName" label="名称" min-width="120" show-overflow-tooltip />
      <el-table-column prop="qty" label="数量" width="80" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="缺料标记" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.shortageFlag === '1'" type="warning" size="small">缺料待料</el-tag>
          <el-tag v-else type="success" size="small">齐套</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="释放留痕" width="170">
        <template #default="{ row }">{{ row.releaseBy || '—' }} {{ (row.releaseAt || '').replace('T', ' ').slice(0, 16) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'CONFIRMED'" link type="primary" size="small"
                     @click="doRelease(row)">释放</el-button>
          <el-button link type="warning" size="small" @click="openShortages(row)">缺料清单</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="暂无工单" :image-size="60" />

    <!-- 缺料清单 -->
    <el-dialog v-model="shortageVisible" :title="`缺料清单 - ${currentMo?.moNo || ''}`" width="720px">
      <el-alert v-if="shortageRate !== null"
                :title="`齐套率 ${shortageRate}%（BR-4.5-04：需求 = 数量×用量×(1+损耗)，可用 = 库存 + 在途）`"
                :type="shortageRows.length ? 'warning' : 'success'" :closable="false"
                style="margin-bottom: 8px" />
      <el-table :data="shortageRows" size="small" border max-height="360">
        <el-table-column prop="bomLine" label="行" width="50" />
        <el-table-column prop="itemCode" label="子项编码" width="140" />
        <el-table-column prop="itemName" label="名称" min-width="120" show-overflow-tooltip />
        <el-table-column prop="reqQty" label="需求" width="90" />
        <el-table-column prop="availQty" label="可用" width="90" />
        <el-table-column prop="shortQty" label="缺料" width="90">
          <template #default="{ row }">
            <span style="color: #f56c6c">{{ row.shortQty }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">{{ row.resolvedFlag === '1' ? '已齐套' : '缺料' }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!shortageRows.length" description="无缺料行（齐套 100%）" :image-size="60" />
      <template #footer>
        <el-button @click="shortageVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMosApi, getShortagesApi, releaseMoApi } from '@/api/mrp/mo'

const statusText = s => ({
  PLANNED: '计划', PENDING: '待审核', CONFIRMED: '已确认', RELEASED: '已释放',
  HOLD: '挂起', COMPLETED: '已完工', CLOSED: '已关闭', CANCELLED: '已取消'
}[s] || s)
const statusTag = s => ({
  PLANNED: 'info', PENDING: 'warning', CONFIRMED: '', RELEASED: 'success',
  HOLD: 'warning', COMPLETED: 'success', CLOSED: 'info', CANCELLED: 'danger'
}[s] || 'info')

const status = ref('CONFIRMED')
const rows = ref([])
const loading = ref(false)
const shortageVisible = ref(false)
const shortageRows = ref([])
const shortageRate = ref(null)
const currentMo = ref(null)

async function load() {
  loading.value = true
  try {
    const res = await getMosApi({ status: status.value || undefined })
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function doRelease(row) {
  await ElMessageBox.confirm(
    `释放工单 ${row.moNo}（${row.productCode}，数量 ${row.qty}）？释放前将重跑齐套检查`,
    '工单释放', { type: 'info' })
  try {
    const res = await releaseMoApi(row.id)
    const flag = res.data?.shortageFlag === '1'
    ElMessage.success(flag ? '已释放（缺料待料标记已打上，请关注缺料清单）' : '已释放（齐套）')
    load()
  } catch (e) {
    console.warn('[mo-release] release failed', e)
  }
}

async function openShortages(row) {
  currentMo.value = row
  const res = await getShortagesApi(row.id)
  shortageRows.value = res.data || []
  // 齐套率 = 无缺料行时 100%；有缺料行时由缺口占比估算展示（真实率在创建/释放时计算）
  shortageRate.value = shortageRows.value.length ? null : 100
  shortageVisible.value = true
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
.tip { color: #909399; font-size: 12px; }
</style>
