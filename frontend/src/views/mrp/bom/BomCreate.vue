<template>
  <div class="page">
    <el-page-header content="清单创建（5.1.1）— 新建 / 复制 / 草稿编辑，保存内嵌循环校验" style="margin-bottom: 8px" />

    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" style="width: 140px;" @change="loadData">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="待审核" value="PENDING" />
          <el-option label="已发布" value="PUBLISHED" />
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
        <el-button v-if="canEng" type="success" @click="openCreate">新建 BOM</el-button>
      </el-form-item>
      <span class="tip">创建即草稿（首版 V1.0）；同父项唯一在途——已有草稿/待审核时将被阻断</span>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe>
      <el-table-column prop="parentItemCode" label="父项编码" width="140" />
      <el-table-column prop="parentItemName" label="父项名称" min-width="160" show-overflow-tooltip />
      <el-table-column label="版本" width="80">
        <template #default="{ row }">V{{ row.versionLabel }}</template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="生效/失效" width="180">
        <template #default="{ row }">{{ row.effectiveDate || '-' }} ~ {{ row.expiryDate || '-' }}</template>
      </el-table-column>
      <el-table-column prop="changeReason" label="变更原因" min-width="140" show-overflow-tooltip />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canEng && row.status === 'DRAFT'" link type="primary" size="small"
                     @click="openEdit(row)">编辑</el-button>
          <el-button v-if="canEng" link type="warning" size="small" @click="doCopy(row)">复制</el-button>
          <el-button link type="info" size="small" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="暂无 BOM 版本" />

    <!-- 新建 / 编辑弹窗 -->
    <el-dialog v-model="editorVisible" :title="editingId ? `编辑草稿 V${editingVersion}` : '新建 BOM 草稿'"
               width="1080px" :close-on-click-modal="false">
      <el-form :model="head" label-width="90px" size="small">
        <el-row :gutter="12">
          <el-col :span="10">
            <el-form-item label="父项物料" required>
              <el-select v-model="head.parentItemCode" filterable remote :remote-method="searchParents"
                         :loading="parentLoading" :disabled="!!editingId" placeholder="搜索物料（仅启用）"
                         style="width: 100%;" @change="onParentChange">
                <el-option v-for="it in parentOptions" :key="it.itemCode"
                           :label="`${it.itemCode} ${it.itemName}`" :value="it.itemCode" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="7">
            <el-form-item label="生效日期">
              <el-date-picker v-model="head.effectiveDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="7">
            <el-form-item label="失效日期">
              <el-date-picker v-model="head.expiryDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <BomLineEditor v-model="lines" />

      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="BOM 版本详情" size="640px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="父项">{{ detail.parentItemCode }} {{ detail.parentItemName }}</el-descriptions-item>
          <el-descriptions-item label="版本">V{{ detail.versionLabel }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(detail.status)" size="small">{{ statusText(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="生效/失效">{{ detail.effectiveDate || '-' }} ~ {{ detail.expiryDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="发布人">{{ detail.publishBy || '-' }}</el-descriptions-item>
          <el-descriptions-item label="发布时间">{{ fmtTime(detail.publishAt) }}</el-descriptions-item>
          <el-descriptions-item label="变更原因">{{ detail.changeReason || '-' }}</el-descriptions-item>
          <el-descriptions-item label="驳回意见">{{ detail.rejectReason || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="detail.items || []" size="small" border style="margin-top: 10px;">
          <el-table-column prop="lineNo" label="#" width="44" />
          <el-table-column prop="itemCode" label="子项编码" width="130" />
          <el-table-column prop="itemName" label="子项名称" min-width="130" show-overflow-tooltip />
          <el-table-column prop="qty" label="用量" width="80" />
          <el-table-column prop="lossRate" label="损耗率%" width="80" />
          <el-table-column prop="uom" label="单位" width="66" />
          <el-table-column label="替代料" min-width="150">
            <template #default="{ row }">
              <template v-if="(row.substitutes || []).length">
                <el-tag v-for="s in row.substitutes" :key="s.id || s.substituteItemCode" size="small"
                        type="warning" style="margin-right: 4px;">
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
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import { useUserStore } from '@/store/user'
import {
  getBomsApi, getBomDetailApi, createBomApi, saveBomDraftApi, copyBomApi
} from '@/api/mrp/bom'
import BomLineEditor from './BomLineEditor.vue'

// 5.1.1 清单创建：列表 + 新建/复制/草稿编辑；错误提示由 request 拦截器单点弹出
// （循环阻断的环路径信息随 422 message 展示）
const userStore = useUserStore()
const canEng = computed(() => {
  const roles = userStore.userInfo?.roles || []
  return roles.includes('ROLE_ADMIN') || roles.includes('ROLE_PROCESS_ENG')
})

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const query = ref({ status: '', parentItemCode: '' })

const editorVisible = ref(false)
const editingId = ref('')
const editingVersion = ref('')
const head = ref({ parentItemCode: '', parentItemName: '', effectiveDate: null, expiryDate: null })
const lines = ref([])
const parentOptions = ref([])
const parentLoading = ref(false)

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
    console.warn('[bom-create] load failed', e)
  } finally {
    loading.value = false
  }
}

async function searchParents(kw) {
  parentLoading.value = true
  try {
    const res = await request.get('/mdm/items', {
      params: { current: 1, size: 20, keyword: kw || '', status: '1' }
    })
    parentOptions.value = res.data.records || []
  } finally {
    parentLoading.value = false
  }
}

function onParentChange(code) {
  const it = parentOptions.value.find(x => x.itemCode === code)
  head.value.parentItemName = it ? it.itemName : ''
}

function openCreate() {
  editingId.value = ''
  editingVersion.value = ''
  head.value = { parentItemCode: '', parentItemName: '', effectiveDate: null, expiryDate: null }
  lines.value = []
  editorVisible.value = true
}

async function openEdit(row) {
  const res = await getBomDetailApi(row.id)
  detail.value = res.data
  editingId.value = row.id
  editingVersion.value = row.versionLabel
  head.value = {
    parentItemCode: res.data.parentItemCode,
    parentItemName: res.data.parentItemName,
    effectiveDate: res.data.effectiveDate || null,
    expiryDate: res.data.expiryDate || null
  }
  lines.value = (res.data.items || []).map(it => ({
    itemCode: it.itemCode, itemName: it.itemName, uom: it.uom,
    qty: Number(it.qty), lossRate: Number(it.lossRate),
    effectiveDate: it.effectiveDate || null, expiryDate: it.expiryDate || null,
    remark: it.remark || '',
    substitutes: (it.substitutes || []).map(s => ({
      substituteItemCode: s.substituteItemCode, substituteItemName: s.substituteItemName,
      ratio: Number(s.ratio), priority: s.priority, options: [], loading: false
    })),
    options: [], loading: false
  }))
  editorVisible.value = true
}

function payload() {
  return {
    head: { ...head.value },
    items: lines.value.map(l => ({
      itemCode: l.itemCode, qty: l.qty, lossRate: l.lossRate,
      effectiveDate: l.effectiveDate || null, expiryDate: l.expiryDate || null,
      remark: l.remark || '',
      substitutes: (l.substitutes || []).map(s => ({
        substituteItemCode: s.substituteItemCode, ratio: s.ratio, priority: s.priority
      }))
    }))
  }
}

async function save() {
  if (!head.value.parentItemCode) {
    ElMessage.warning('请选择父项物料')
    return
  }
  if (!lines.value.length) {
    ElMessage.warning('至少一行子项物料')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await saveBomDraftApi(editingId.value, payload())
      ElMessage.success('草稿已保存')
    } else {
      const res = await createBomApi(payload())
      ElMessage.success(`已创建草稿 V${res.data.versionLabel}`)
    }
    editorVisible.value = false
    loadData()
  } catch (e) {
    // 422（环路径/用量/单位等）已由拦截器弹出具体 message
    console.warn('[bom-create] save failed', e)
  } finally {
    saving.value = false
  }
}

async function doCopy(row) {
  await ElMessageBox.confirm(`从 V${row.versionLabel}（${row.parentItemCode}）复制为新草稿？`, '复制 BOM', {
    type: 'info'
  })
  saving.value = true
  try {
    const res = await copyBomApi(row.id)
    ElMessage.success(`已复制为草稿 V${res.data.versionLabel}`)
    loadData()
  } catch (e) {
    console.warn('[bom-create] copy failed', e)
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
</style>
