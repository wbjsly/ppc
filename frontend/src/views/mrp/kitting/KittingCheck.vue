<template>
  <div>
    <el-page-header content="齐套校验（5.5.1）— 单工单校验/勾选批量校验（串行去重），逐行明细含替代候选提示（仅展示不参与判定）"
                    style="margin-bottom: 8px" />

    <div class="toolbar">
      <el-select v-model="status" style="width: 170px" size="small" @change="load">
        <el-option label="已释放（主场景）" value="RELEASED" />
        <el-option label="挂起" value="HOLD" />
        <el-option label="全部状态" value="" />
      </el-select>
      <el-select v-model="shortageFlag" style="width: 140px" size="small" @change="load">
        <el-option label="仅缺料待料" value="1" />
        <el-option label="仅齐套" value="0" />
        <el-option label="全部标记" value="" />
      </el-select>
      <el-button size="small" @click="load">刷新</el-button>
      <el-button size="small" type="primary" :loading="batching"
                 :disabled="selection.length === 0" @click="doBatch">
        批量校验（{{ selection.length }}）
      </el-button>
      <span class="tip">口径：需求 = 剩余量×用量×(1+损耗)；可用 = 库存 + 计划开工日前在途（KITTING_TRANSIT_DATE_FILTER）</span>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe size="small"
              @selection-change="onSelect">
      <el-table-column type="selection" width="40" />
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
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" :loading="checkingId === row.id"
                     @click="doCheck(row)">校验</el-button>
          <el-button link type="warning" size="small" @click="openDetail(row)">逐行明细</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="暂无工单" :image-size="60" />

    <!-- 逐行明细抽屉 -->
    <el-drawer v-model="detailVisible" :title="`逐行明细 - ${currentMo?.moNo || ''}`" size="720px">
      <el-alert v-if="detail" :title="`齐套率 ${detail.rate}%（BR-4.5-04 行数比率；替代候选仅提示不参与判定）`"
                :type="Number(detail.rate) === 100 ? 'success' : 'warning'"
                :closable="false" style="margin-bottom: 8px" />
      <el-table :data="detailLines" size="small" border max-height="520">
        <el-table-column prop="bomLine" label="行" width="50" />
        <el-table-column prop="itemCode" label="子项编码" width="130" />
        <el-table-column prop="itemName" label="名称" min-width="110" show-overflow-tooltip />
        <el-table-column label="需求" width="90">
          <template #default="{ row }">{{ row.reqQty }}</template>
        </el-table-column>
        <el-table-column label="可用" width="90">
          <template #default="{ row }">{{ row.availQty }}</template>
        </el-table-column>
        <el-table-column label="缺料" width="90">
          <template #default="{ row }">
            <span v-if="row.shortage" style="color: #f56c6c">{{ row.shortQty }}</span>
            <span v-else style="color: #67c23a">—</span>
          </template>
        </el-table-column>
        <el-table-column label="替代候选（提示）" min-width="240">
          <template #default="{ row }">
            <template v-if="row.substitutes && row.substitutes.length">
              <el-tag v-for="s in row.substitutes" :key="s.itemCode" size="small" type="info"
                      style="margin-right: 4px">
                {{ s.itemCode }} 可用{{ s.availQty }} ×比例{{ s.ratio }} → 可抵{{ s.coverableQty }}
              </el-tag>
            </template>
            <span v-else style="color: #909399">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted, onActivated } from 'vue'
import { ElMessage } from 'element-plus'
import { getKittingMosApi, checkKittingApi, getKittingDetailApi } from '@/api/mrp/kitting'

// 列表（5.4 getMosApi 同源：status/shortageFlag 筛选）
const loading = ref(false)
const rows = ref([])
const status = ref('RELEASED')
const shortageFlag = ref('')
const selection = ref([])
const checkingId = ref(null)
const batching = ref(false)

// 明细抽屉
const detailVisible = ref(false)
const detail = ref(null)
const currentMo = ref(null)
const detailLines = ref([])

const statusText = s => ({
  PLANNED: '计划', PENDING: '审批中', CONFIRMED: '已确认',
  RELEASED: '已释放', HOLD: '挂起', COMPLETED: '已完工',
  CLOSED: '已关闭', CANCELLED: '已取消'
}[s] || s)
const statusTag = s => ({
  PLANNED: 'info', PENDING: 'warning', CONFIRMED: '',
  RELEASED: 'success', HOLD: 'warning', COMPLETED: 'success',
  CLOSED: 'info', CANCELLED: 'danger'
}[s] || 'info')

async function load() {
  loading.value = true
  try {
    const res = await getKittingMosApi({
      status: status.value || undefined,
      shortageFlag: shortageFlag.value || undefined
    })
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

function onSelect(sel) {
  selection.value = sel
}

/** 单工单校验：返回实时齐套率，刷新标记 */
async function doCheck(row) {
  checkingId.value = row.id
  try {
    const res = await checkKittingApi([row.id])
    const r = (res.data || [])[0] || {}
    if (r.error) {
      ElMessage.error(`校验失败：${r.error}`)
      return
    }
    ElMessage.success(`${row.moNo} 齐套率 ${r.rate}%（${r.shortageFlag === '1' ? '缺料待料' : '齐套'}）`)
    await load()
  } finally {
    checkingId.value = null
  }
}

/** 勾选批量校验（后端串行 + 批次内去重） */
async function doBatch() {
  batching.value = true
  try {
    const ids = selection.value.map(r => r.id)
    const res = await checkKittingApi(ids)
    const list = res.data || []
    const ok = list.filter(r => !r.error).length
    const short = list.filter(r => r.shortageFlag === '1').length
    ElMessage.success(`批量校验完成：${ok}/${list.length} 张，其中缺料待料 ${short} 张`)
    await load()
  } finally {
    batching.value = false
  }
}

async function openDetail(row) {
  currentMo.value = row
  detail.value = null
  detailLines.value = []
  detailVisible.value = true
  const res = await getKittingDetailApi(row.id)
  detail.value = res.data
  detailLines.value = res.data?.lines || []
}

onMounted(load)
onActivated(load) // 列表页回刷约定（01 文档 3.5）
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
</style>
