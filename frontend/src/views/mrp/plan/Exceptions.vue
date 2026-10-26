<template>
  <div class="page">
    <el-page-header content="异常标记（5.3.3）— 过量供给与逾期需求聚合；标记已处理留痕"
                    style="margin-bottom: 8px" />

    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item label="关键字">
        <el-input v-model="keyword" clearable placeholder="物料编码/名称" style="width: 200px;"
                  @keyup.enter="loadData" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadData">查询</el-button>
        <el-button @click="loadData(true)">仅看未处理</el-button>
      </el-form-item>
      <span class="tip">
        过量供给 → 提示评估取消/推迟在途；逾期需求 → 提示加急/协商交期（异常与边界 L2755/2756）
      </span>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe>
      <el-table-column prop="itemCode" label="物料编码" width="150" />
      <el-table-column prop="itemName" label="物料名称" min-width="140" show-overflow-tooltip />
      <el-table-column label="异常类型" width="100">
        <template #default="{ row }">
          <el-tag :type="row.type === 'EXCESS' ? 'warning' : 'danger'" size="small">
            {{ row.type === 'EXCESS' ? '过量供给' : '逾期需求' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="demandQty" label="需求" width="90" />
      <el-table-column prop="netReq" label="NetReq" width="100">
        <template #default="{ row }">
          <span :class="{ neg: Number(row.netReq) < 0 }">{{ row.netReq }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="orderDate" label="建议下单日" width="105" />
      <el-table-column prop="remark" label="提示" min-width="200" show-overflow-tooltip />
      <el-table-column label="处置" width="96">
        <template #default="{ row }">
          <el-tag v-if="row.handledFlag === '1'" type="success" size="small">已处理</el-tag>
          <el-tag v-else type="info" size="small">未处理</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="handledNote" label="处理备注" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.handledNote || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canRun && row.handledFlag !== '1'" link type="success" size="small"
                     @click="openHandle(row)">标记已处理</el-button>
          <el-button link type="primary" size="small" @click="jumpToSuggestion(row)">查看建议</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="无异常（过量供给与逾期需求均未发现）" />

    <!-- 标记已处理 -->
    <el-dialog v-model="handleVisible" title="异常标记已处理" width="480px">
      <el-descriptions v-if="current" :column="1" border size="small" style="margin-bottom: 8px;">
        <el-descriptions-item label="物料">{{ current.itemCode }} {{ current.itemName }}</el-descriptions-item>
        <el-descriptions-item label="异常">{{ current.type === 'EXCESS' ? '过量供给' : '逾期需求' }}</el-descriptions-item>
      </el-descriptions>
      <el-input v-model="handleNote" type="textarea" :rows="3" maxlength="500" show-word-limit
                placeholder="处理备注必填（如：已联系采购推迟在途 / 已与客户协商交期）" />
      <template #footer>
        <el-button @click="handleVisible = false">关闭</el-button>
        <el-button type="primary" :loading="saving" @click="doHandle">标记已处理</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { getSuggestionsApi, handleSuggestionApi } from '@/api/mrp/plan'

// 5.3.3 异常标记：EXCESS + OVERDUE 聚合（一表视图，proposal D7 无站内通知）
const router = useRouter()
const userStore = useUserStore()
const canRun = computed(() => {
  const roles = userStore.userInfo?.roles || []
  return roles.includes('ROLE_ADMIN') || roles.includes('ROLE_PLANNER')
})

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const keyword = ref('')
const unhandledOnly = ref(false)

const handleVisible = ref(false)
const current = ref(null)
const handleNote = ref('')

async function loadData(unhandled) {
  unhandledOnly.value = unhandled === true
  loading.value = true
  try {
    const params = { exceptionsOnly: 'true' }
    if (keyword.value) params.keyword = keyword.value
    const res = await getSuggestionsApi(params)
    let list = res.data || []
    if (unhandledOnly.value) {
      list = list.filter(r => r.handledFlag !== '1')
    }
    rows.value = list
  } catch (e) {
    console.warn('[exceptions] load failed', e)
  } finally {
    loading.value = false
  }
}

function openHandle(row) {
  current.value = row
  handleNote.value = ''
  handleVisible.value = true
}

async function doHandle() {
  if (!handleNote.value || !handleNote.value.trim()) {
    ElMessage.warning('处理备注必填')
    return
  }
  saving.value = true
  try {
    await handleSuggestionApi(current.value.id, handleNote.value.trim())
    ElMessage.success('已标记处理（处理人/时间留痕）')
    handleVisible.value = false
    loadData()
  } catch (e) {
    console.warn('[exceptions] handle failed', e)
  } finally {
    saving.value = false
  }
}

function jumpToSuggestion(row) {
  // OVERDUE 行跳转对应建议审核页（EXCESS 行无审核入口，留本页）
  const target = row.type === 'PRODUCTION' || row.purchaseType === 'MAKE' ? '/m/5.3.2' : '/m/5.3.1'
  router.push({ path: target, query: { item: row.itemCode } })
}

onMounted(() => loadData())
</script>

<style scoped>
.filter-bar {
  margin-bottom: 4px;
}
.tip {
  color: #909399;
  font-size: 12px;
}
.neg {
  color: #f56c6c;
  font-weight: 600;
}
</style>
