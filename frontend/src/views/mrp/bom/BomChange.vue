<template>
  <div class="page">
    <el-page-header content="清单变更（5.1.2）— 从已发布版本发起变更，强制变更原因，生成新版本草稿"
                    style="margin-bottom: 8px" />

    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item label="状态">
        <el-select v-model="query.status" style="width: 140px;" @change="loadData">
          <el-option label="已发布" value="PUBLISHED" />
          <el-option label="全部（版本历史）" value="" />
          <el-option label="草稿" value="DRAFT" />
          <el-option label="待审核" value="PENDING" />
          <el-option label="已变更" value="REVISED" />
          <el-option label="已废止" value="OBSOLETE" />
        </el-select>
      </el-form-item>
      <el-form-item label="父项">
        <el-input v-model="query.parentItemCode" clearable placeholder="父项编码/名称" style="width: 200px;"
                  @keyup.enter="loadData" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadData">查询</el-button>
      </el-form-item>
      <span class="tip">变更 = 克隆已发布版本为新草稿（默认次版本+1）；已下达工单不受影响（BR-4.5-09 快照语义由工单模块落实）</span>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe>
      <el-table-column prop="parentItemCode" label="父项编码" width="130" />
      <el-table-column prop="parentItemName" label="父项名称" min-width="140" show-overflow-tooltip />
      <el-table-column label="版本" width="80">
        <template #default="{ row }">V{{ row.versionLabel }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="变更链" width="120">
        <template #default="{ row }">
          <el-tooltip v-if="row.changeFromId" content="来源版本 ID（change_from_id）" placement="top">
            <span class="mono">{{ row.changeFromId.slice(0, 8) }}…</span>
          </el-tooltip>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="changeReason" label="变更原因" min-width="150" show-overflow-tooltip />
      <el-table-column label="发布人/时间" width="170">
        <template #default="{ row }">{{ row.publishBy || '-' }} / {{ fmtTime(row.publishAt) }}</template>
      </el-table-column>
      <el-table-column prop="rejectReason" label="驳回意见" min-width="130" show-overflow-tooltip />
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canEng && row.status === 'PUBLISHED'" link type="warning" size="small"
                     @click="openChange(row)">发起变更</el-button>
          <el-button link type="info" size="small" @click="openDetail(row)">明细</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="暂无 BOM 版本" />

    <!-- 发起变更弹窗 -->
    <el-dialog v-model="changeVisible" title="发起 BOM 变更" width="520px" :close-on-click-modal="false">
      <el-alert v-if="current" :title="`来源：${current.parentItemCode} V${current.versionLabel}（已发布）`"
                type="info" :closable="false" style="margin-bottom: 12px;" />
      <el-form :model="changeForm" label-width="96px" size="small">
        <el-form-item label="变更原因" required>
          <el-input v-model="changeForm.changeReason" type="textarea" :rows="3" maxlength="500"
                    show-word-limit placeholder="必填：说明变更背景与内容（将记入版本历史）" />
        </el-form-item>
        <el-form-item label="升级主版本">
          <el-switch v-model="changeForm.upgradeMajor" />
          <span class="tip"> 默认次版本+1；勾选则主版本+1 次版本归零（如 V1.2 → V2.0）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="changeVisible = false">取消</el-button>
        <el-button type="warning" :loading="saving" @click="submitChange">生成变更草稿</el-button>
      </template>
    </el-dialog>

    <!-- 明细抽屉（含行级替代与留痕） -->
    <el-drawer v-model="detailVisible" title="BOM 版本明细" size="640px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="父项">{{ detail.parentItemCode }} {{ detail.parentItemName }}</el-descriptions-item>
          <el-descriptions-item label="版本">V{{ detail.versionLabel }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusText(detail.status) }}</el-descriptions-item>
          <el-descriptions-item label="来源版本">{{ detail.changeFromId ? detail.changeFromId.slice(0, 12) + '…' : (detail.copyFromId ? '复制自 ' + detail.copyFromId.slice(0, 12) + '…' : '-') }}</el-descriptions-item>
          <el-descriptions-item label="变更原因" :span="2">{{ detail.changeReason || '-' }}</el-descriptions-item>
          <el-descriptions-item label="发布人">{{ detail.publishBy || '-' }}</el-descriptions-item>
          <el-descriptions-item label="发布时间">{{ fmtTime(detail.publishAt) }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="detail.items || []" size="small" border style="margin-top: 10px;">
          <el-table-column prop="lineNo" label="#" width="44" />
          <el-table-column prop="itemCode" label="子项编码" width="130" />
          <el-table-column prop="itemName" label="子项名称" min-width="120" show-overflow-tooltip />
          <el-table-column prop="qty" label="用量" width="76" />
          <el-table-column prop="lossRate" label="损耗率%" width="80" />
          <el-table-column label="替代料" min-width="150">
            <template #default="{ row }">
              <template v-if="(row.substitutes || []).length">
                <el-tag v-for="s in row.substitutes" :key="s.substituteItemCode" size="small" type="warning"
                        style="margin-right: 4px;">
                  {{ s.substituteItemCode }}×{{ s.ratio }}(P{{ s.priority }})
                </el-tag>
              </template>
              <span v-else>-</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getBomsApi, getBomDetailApi, changeBomApi
} from '@/api/mrp/bom'

// 5.1.2 清单变更：仅已发布可发起（422），变更原因必填（前端 + 后端双校验），
// 默认展示已发布列表，切「全部」即版本历史（FR-7 降级留痕出口）
const userStore = useUserStore()
const canEng = computed(() => {
  const roles = userStore.userInfo?.roles || []
  return roles.includes('ROLE_ADMIN') || roles.includes('ROLE_PROCESS_ENG')
})

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const query = ref({ status: 'PUBLISHED', parentItemCode: '' })

const changeVisible = ref(false)
const current = ref(null)
const changeForm = ref({ changeReason: '', upgradeMajor: false })

const detailVisible = ref(false)
const detail = ref(null)

function statusText(s) {
  return { DRAFT: '草稿', PENDING: '待审核', PUBLISHED: '已发布', REVISED: '已变更', OBSOLETE: '已废止' }[s] || s
}
function statusTag(s) {
  return { DRAFT: 'info', PENDING: 'warning', PUBLISHED: 'success', REVISED: 'info', OBSOLETE: 'danger' }[s] || 'info'
}
function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}

async function loadData() {
  loading.value = true
  try {
    const params = {}
    Object.entries(query.value).forEach(([k, v]) => { if (v) params[k] = v })
    const res = await getBomsApi(params)
    rows.value = res.data || []
  } catch (e) {
    console.warn('[bom-change] load failed', e)
  } finally {
    loading.value = false
  }
}

function openChange(row) {
  current.value = row
  changeForm.value = { changeReason: '', upgradeMajor: false }
  changeVisible.value = true
}

async function submitChange() {
  const reason = (changeForm.value.changeReason || '').trim()
  if (reason.length < 1) {
    ElMessage.warning('变更原因必填')
    return
  }
  saving.value = true
  try {
    const res = await changeBomApi(current.value.id, {
      changeReason: reason,
      upgradeMajor: changeForm.value.upgradeMajor
    })
    ElMessage.success(`已生成变更草稿 V${res.data.versionLabel}（变更链已记录）`)
    changeVisible.value = false
    query.value.status = ''
    loadData()
  } catch (e) {
    console.warn('[bom-change] change failed', e)
  } finally {
    saving.value = false
  }
}

async function openDetail(row) {
  const res = await getBomDetailApi(row.id)
  detail.value = res.data
  detailVisible.value = true
}

onMounted(loadData)
</script>

<style scoped>
.filter-bar {
  margin-bottom: 4px;
}
.tip {
  color: #909399;
  font-size: 12px;
}
.mono {
  font-family: monospace;
}
</style>
