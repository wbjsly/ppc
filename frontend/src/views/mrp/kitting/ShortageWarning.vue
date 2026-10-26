<template>
  <div>
    <el-page-header content="缺料预警（5.5.2）— 跨工单按物料聚合缺料，勾选转采购建议（5.3.1 审核转正单一漏斗防重复下单）"
                    style="margin-bottom: 8px" />

    <div class="toolbar">
      <el-input v-model="itemCode" placeholder="物料编码（模糊）" clearable style="width: 180px"
                size="small" @keyup.enter="load" @clear="load" />
      <el-input v-model="moNo" placeholder="工单号（模糊）" clearable style="width: 170px"
                size="small" @keyup.enter="load" @clear="load" />
      <el-button size="small" type="primary" @click="load">查询</el-button>
      <el-button size="small" :disabled="selectedMos.length === 0" @click="doConvert">
        转采购建议（{{ selectedMos.length }}）
      </el-button>
      <span class="tip">转出为待审核建议，须在 5.3.1 审核→转正；同工单同物料未处置前不可重复转（422）</span>
    </div>

    <el-table :data="groups" v-loading="loading" border stripe size="small"
              row-key="itemCode" @selection-change="onSelect">
      <el-table-column type="selection" width="40" :selectable="() => true" />
      <el-table-column prop="itemCode" label="物料编码" width="150" />
      <el-table-column prop="itemName" label="物料名称" min-width="140" show-overflow-tooltip />
      <el-table-column label="缺料工单数" width="100" align="center">
        <template #default="{ row }">
          <el-tag type="danger" size="small">{{ row.moCount }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="总缺料量" width="110" align="right">
        <template #default="{ row }">
          <span style="color: #f56c6c; font-weight: 600">{{ row.totalShortQty }}</span>
        </template>
      </el-table-column>
      <el-table-column label="缺料明细（下钻）" min-width="360">
        <template #default="{ row }">
          <div v-for="m in row.mos" :key="m.moId" class="mo-line">
            <el-link type="primary" :underline="false" @click="goMo(m)">{{ m.moNo }}</el-link>
            <span class="mo-meta">缺 {{ m.shortQty }}（需 {{ m.reqQty }} / 可用 {{ m.availQty }}）</span>
            <el-tag size="small" :type="m.status === 'RELEASED' ? 'success' : 'info'">
              {{ m.status }}
            </el-tag>
          </div>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && groups.length === 0" description="无缺料（全部齐套）" :image-size="60" />
  </div>
</template>

<script setup>
import { ref, onMounted, onActivated } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getShortageAggregateApi, convertFromShortageApi } from '@/api/mrp/kitting'

const router = useRouter()
const loading = ref(false)
const groups = ref([])
const itemCode = ref('')
const moNo = ref('')
const selectedMos = ref([]) // 勾选的聚合行（含 mos 明细）

async function load() {
  loading.value = true
  try {
    const res = await getShortageAggregateApi({
      itemCode: itemCode.value || undefined,
      moNo: moNo.value || undefined
    })
    groups.value = res.data || []
  } finally {
    loading.value = false
  }
}

function onSelect(sel) {
  selectedMos.value = sel
}

function goMo(m) {
  router.push({ path: '/m/5.5.1', query: { moId: m.moId } })
}

/** 转采购建议：收集勾选组下全部缺料行所属工单的缺料行 ID（下钻明细携带 shortageId） */
async function doConvert() {
  const ids = []
  for (const g of selectedMos.value) {
    for (const m of g.mos) {
      if (m.shortageId) ids.push(m.shortageId)
    }
  }
  if (ids.length === 0) {
    ElMessage.warning('勾选行缺少缺料行标识，请刷新后重试')
    return
  }
  await ElMessageBox.confirm(
    `将把 ${ids.length} 条缺料行转为待审核采购建议（可在 5.3.1 审核后转正），继续？`,
    '转采购建议', { type: 'warning' })
  const res = await convertFromShortageApi(ids)
  ElMessage.success(`已生成 ${res.data?.created ?? ids.length} 条待审核建议，5.3.1 可见`)
  await load()
}

onMounted(load)
onActivated(load) // 列表页回刷约定（01 文档 3.5）
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
.mo-line { display: flex; gap: 8px; align-items: center; line-height: 24px; }
.mo-meta { color: #606266; font-size: 12px; }
</style>
