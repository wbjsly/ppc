<template>
  <div class="item-master">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">物料管理</span>
          <div class="header-actions">
            <el-select v-model="query.categoryCode" placeholder="分类" clearable style="width: 140px;"
                       :disabled="isRelationView" @change="loadData(1)">
              <el-option v-for="c in categories" :key="c.code" :label="`${c.code} ${c.name}`" :value="c.code" />
            </el-select>
            <el-input v-model="query.keyword" placeholder="编码/名称" clearable
                      :prefix-icon="Search" style="width: 170px;" @keyup.enter="loadData(1)" />
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 100px;" @change="loadData(1)">
              <el-option label="启用" value="1" />
              <el-option label="停用" value="0" />
              <el-option label="已归档" value="2" />
            </el-select>
            <el-select v-model="query.hasSubstitute" placeholder="有无替代" clearable style="width: 110px;" @change="loadData(1)">
              <el-option label="有替代" value="1" />
              <el-option label="无替代" value="0" />
            </el-select>
            <el-select v-model="query.dimension" placeholder="替代维度" clearable style="width: 110px;" @change="loadData(1)">
              <el-option label="正向（谁被替代）" value="source" />
              <el-option label="反查（谁指向我）" value="target" />
            </el-select>
            <el-button type="primary" @click="loadData(1)">查询</el-button>
            <el-button type="primary" :icon="Plus" @click="openCreate()">新建</el-button>
            <el-button type="danger" :disabled="!selectedIds.length" @click="openBatch">
              批量停用{{ selectedIds.length ? `（${selectedIds.length}）` : '' }}
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe @selection-change="onSelectionChange">
        <el-table-column type="selection" width="42" :selectable="row => row.status === '1'" />
        <el-table-column prop="itemCode" label="物料编码" width="140" />
        <el-table-column prop="itemName" label="物料名称" min-width="180" show-overflow-tooltip />
        <el-table-column label="分类" width="110">
          <template #default="{ row }">{{ categoryName(row.categoryCode) }}</template>
        </el-table-column>
        <el-table-column prop="baseUnit" label="单位" width="65" />
        <el-table-column label="采购类型" width="85">
          <template #default="{ row }">{{ purchaseMap[row.purchaseType] || row.purchaseType || '-' }}</template>
        </el-table-column>
        <el-table-column label="存储属性" width="150">
          <template #default="{ row }">{{ storageAttrText(row) }}</template>
        </el-table-column>
        <el-table-column label="ABC" width="72" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.abcClass" size="small"
              :type="row.abcClass === 'A' ? 'danger' : row.abcClass === 'B' ? 'warning' : 'info'">
              {{ row.abcClass }}
            </el-tag>
            <span v-else style="color: #909399; font-size: 12px;">未分类</span>
          </template>
        </el-table-column>
        <el-table-column label="ABC" width="72" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.abcClass" size="small"
              :type="row.abcClass === 'A' ? 'danger' : row.abcClass === 'B' ? 'warning' : 'info'">
              {{ row.abcClass }}
            </el-tag>
            <span v-else style="color: #909399; font-size: 12px;">未分类</span>
          </template>
        </el-table-column>
        <el-table-column label="ABC" width="72" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.abcClass" size="small"
              :type="row.abcClass === 'A' ? 'danger' : row.abcClass === 'B' ? 'warning' : 'info'">
              {{ row.abcClass }}
            </el-tag>
            <span v-else style="color: #909399; font-size: 12px;">未分类</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="85">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="替代指向" width="170">
          <template #default="{ row }">
            <template v-if="row.altItemCode">
              <span>{{ row.altItemCode }}</span>
              <el-tag v-if="row.targetCode === null" type="danger" size="small" style="margin-left: 4px;">目标已失效</el-tag>
              <el-tag v-else-if="row.targetStatus && row.targetStatus !== '1'" type="info" size="small" style="margin-left: 4px;">
                目标{{ statusName(row.targetStatus) }}
              </el-tag>
            </template>
            <span v-else style="color: #c0c4cc;">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="330" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status !== '2'" link type="primary" @click="openChange(row)">变更</el-button>
            <el-button v-if="row.status === '1'" link type="danger" @click="openLifecycle('disable', row)">停用</el-button>
            <template v-if="row.status === '0'">
              <el-button link type="success" @click="openLifecycle('enable', row)">启用</el-button>
              <el-button link type="warning" @click="openLifecycle('archive', row)">归档</el-button>
            </template>
            <el-button v-if="row.status !== '2'" link type="primary" @click="openSubstitute(row)">替代</el-button>
            <el-button link type="primary" @click="impactRow = row; impactVisible = true">影响分析</el-button>
            <span v-if="row.status === '2'" style="color: #909399; font-size: 12px; margin-left: 4px;">终态</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        style="margin-top: 14px; justify-content: flex-end;"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.size"
        :current-change="loadData"
      />
    </el-card>

    <!-- 新建 / 变更表单（含查重、477 校验、差异预览、编码阻断跳转） -->
    <ItemFormDialog
      v-model:visible="formVisible"
      :row="formRow"
      :prefill="formPrefill"
      :categories="categories"
      :dicts="dicts"
      @saved="onFormSaved"
      @jump-create="onJumpCreate"
    />

    <!-- 替代设置 / 清除 -->
    <SubstituteDialog v-model:visible="substVisible" :row="substRow" @saved="loadData()" />

    <!-- 影响分析抽屉（替代清单 + 下游桩口径明示） -->
    <ImpactDrawer v-model:visible="impactVisible" :row="impactRow" />

    <!-- 停用 / 启用 / 归档统一确认（原因必填 + 影响摘要 + 终态警示） -->
    <LifecycleDialog v-model:visible="lifeVisible" :row="lifeRow" :kind="lifeKind" @done="loadData()" />

    <!-- 批量停用（逐条校验，分组结果回显） -->
    <el-dialog v-model="batchVisible" :title="`批量停用（${selectedIds.length} 条）`" width="560px">
      <el-alert type="warning" :closable="false" style="margin-bottom: 10px;"
        title="逐条校验：存在替代引用的物料会阻断，其余正常停用，结果按成功/失败分组返回。" />
      <el-form label-width="90px">
        <el-form-item label="停用原因" required>
          <el-input v-model="batchReason" type="textarea" :rows="3"
            placeholder="如：产品升级换代，统一停用旧料" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="danger" :disabled="!batchReason.trim()" :loading="saving" @click="submitBatch">确认停用</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉（含版本历史与对比） -->
    <el-drawer v-model="detailVisible" title="物料详情" size="640px">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="物料编码">{{ detail.itemCode }}</el-descriptions-item>
        <el-descriptions-item label="物料名称">{{ detail.itemName }}</el-descriptions-item>
        <el-descriptions-item label="分类">{{ categoryName(detail.categoryCode) }}</el-descriptions-item>
        <el-descriptions-item label="单位/物料组">{{ detail.baseUnit }} / {{ dictName('MATERIAL_GROUP', detail.materialGroup) }}</el-descriptions-item>
        <el-descriptions-item label="采购类型">{{ purchaseMap[detail.purchaseType] }}</el-descriptions-item>
        <el-descriptions-item label="存储条件">{{ dictName('STORAGE', detail.storageCondition) }}</el-descriptions-item>
        <el-descriptions-item label="存储属性">
          温湿度 {{ detail.tempLevel ? dictName('TEMP_LEVEL', detail.tempLevel) : '无要求' }} /
          危化 {{ detail.hazardLevel ? dictName('HAZARD_LEVEL', detail.hazardLevel) : '无要求' }} /
          洁净 {{ detail.cleanLevel ? dictName('CLEAN_LEVEL', detail.cleanLevel) : '无要求' }}
          <span class="form-tip">（仓位分配合规校验依据 4.4.5；无要求=不拦截）</span>
        </el-descriptions-item>
        <el-descriptions-item label="批次管理">{{ detail.batchFlag === '1' ? `是（保质期 ${detail.shelfLifeDays ?? '-'} 天）` : '否' }}</el-descriptions-item>
        <el-descriptions-item label="序列管理">{{ detail.serialFlag === '1' ? '是（出入库逐件校验）' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="安全库存/提前期">{{ detail.safetyStock ?? '-' }} / {{ detail.leadTimeDays ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="替代物料">{{ detail.altItemCode || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.packingSpec" label="包装规格">{{ detail.packingSpec }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.barcode" label="条码">{{ detail.barcode }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTag(detail.status)" size="small">{{ statusName(detail.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="当前版本">V{{ detail.verNo }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">版本历史</el-divider>
      <div class="diff-bar">
        <el-select v-model="diffFrom" placeholder="起始版本" style="width: 110px;">
          <el-option v-for="v in versions" :key="v.versionNo" :label="`V${v.versionNo}`" :value="v.versionNo" />
        </el-select>
        <span>→</span>
        <el-select v-model="diffTo" placeholder="目标版本" style="width: 110px;">
          <el-option v-for="v in versions" :key="v.versionNo" :label="`V${v.versionNo}`" :value="v.versionNo" />
        </el-select>
        <el-button type="primary" plain :disabled="!diffFrom || !diffTo || diffFrom === diffTo" @click="loadDiff">对比</el-button>
      </div>

      <el-table :data="versions" size="small" stripe>
        <el-table-column prop="versionNo" label="版本" width="60">
          <template #default="{ row }">V{{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column prop="opType" label="操作" width="90">
          <template #default="{ row }">{{ opMap[row.opType] || row.opType }}</template>
        </el-table-column>
        <el-table-column prop="changeType" label="分类" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.changeType === 'CRITICAL'" type="danger" size="small">关键</el-tag>
            <el-tag v-else-if="row.changeType === 'GENERAL'" type="info" size="small">一般</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="changeReason" label="变更原因" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.changeReason || '-' }}</template>
        </el-table-column>
        <el-table-column prop="diffSummary" label="变更摘要" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">{{ row.diffSummary || '初始版本' }}</template>
        </el-table-column>
        <el-table-column prop="createBy" label="操作人" width="100" />
        <el-table-column prop="createDate" label="时间" width="155">
          <template #default="{ row }">{{ (row.createDate || '').replace('T', ' ') }}</template>
        </el-table-column>
      </el-table>

      <template v-if="diffFields.length">
        <el-divider content-position="left">差异对比</el-divider>
        <el-table :data="diffFields" size="small" border>
          <el-table-column prop="field" label="字段" width="140" />
          <el-table-column prop="from" label="起始值" show-overflow-tooltip />
          <el-table-column prop="to" label="目标值" show-overflow-tooltip />
        </el-table>
      </template>
      <el-empty v-else-if="diffRequested" description="两版本无差异" :image-size="60" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import {
  getItemPageApi, getItemApi, getItemVersionsApi, getItemDiffApi,
  getCategoriesApi, getDictApi
} from '@/api/mdm/item'
import { getSubstitutesApi } from '@/api/mdm/substitute'
import { disableBatchApi } from '@/api/mdm/item-disable'
import ItemFormDialog from './ItemFormDialog.vue'
import SubstituteDialog from './SubstituteDialog.vue'
import ImpactDrawer from './ImpactDrawer.vue'
import LifecycleDialog from './LifecycleDialog.vue'

const purchaseMap = { BUY: '外购', MAKE: '自制', OUTSOURCE: '委外' }
const opMap = { CREATE: '新建', UPDATE: '变更', DISABLE: '停用', ENABLE: '启用', ARCHIVE: '归档' }

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({
  current: 1, size: 10, keyword: '', categoryCode: '', status: '',
  hasSubstitute: '', dimension: ''
})
const selectedIds = ref([])
const categories = ref([])
const dicts = ref({ UNIT: [], MATERIAL_GROUP: [], STORAGE: [], TEMP_LEVEL: [], HAZARD_LEVEL: [], CLEAN_LEVEL: [] })

/** 替代维度启用或「有替代」筛选时走 substitutes 端点（正反查语义 + 目标状态列红标）；否则走 /items 分页 */
const isRelationView = computed(() => !!query.value.dimension || query.value.hasSubstitute === '1')

/** substitutes 端点返回大写列名，统一为实体小写键（D2 行标准化） */
function normalize(r) {
  if (r.itemCode !== undefined) return r
  return {
    id: r.ID, itemCode: r.ITEM_CODE, itemName: r.ITEM_NAME,
    categoryCode: r.CATEGORY_CODE, baseUnit: r.BASE_UNIT,
    purchaseType: r.PURCHASE_TYPE, batchFlag: r.BATCH_FLAG, serialFlag: r.SERIAL_FLAG,
    status: r.STATUS, altItemCode: r.ALT_ITEM_CODE,
    targetCode: r.TARGET_CODE ?? null, targetName: r.TARGET_NAME ?? null,
    targetStatus: r.TARGET_STATUS ?? null
  }
}

async function loadData(page) {
  if (page) query.value.current = page
  loading.value = true
  try {
    if (isRelationView.value) {
      const res = await getSubstitutesApi({
        keyword: query.value.keyword,
        direction: query.value.dimension || 'source',
        status: query.value.status,
        current: query.value.current,
        size: query.value.size
      })
      rows.value = res.data.records.map(normalize)
      total.value = res.data.total
    } else {
      const res = await getItemPageApi(query.value)
      rows.value = res.data.records.map(normalize)
      total.value = res.data.total
    }
  } finally {
    loading.value = false
  }
}

async function loadRefs() {
  const [cats, u, g, s, t, h, c] = await Promise.all([
    getCategoriesApi(), getDictApi('UNIT'), getDictApi('MATERIAL_GROUP'), getDictApi('STORAGE'),
    getDictApi('TEMP_LEVEL'), getDictApi('HAZARD_LEVEL'), getDictApi('CLEAN_LEVEL')
  ])
  categories.value = cats.data
  dicts.value = {
    UNIT: u.data, MATERIAL_GROUP: g.data, STORAGE: s.data,
    TEMP_LEVEL: t.data, HAZARD_LEVEL: h.data, CLEAN_LEVEL: c.data
  }
}

function statusName(s) {
  return s === '1' ? '启用' : s === '0' ? '停用' : s === '2' ? '已归档' : '未知'
}
function statusTag(s) {
  return s === '1' ? 'success' : s === '0' ? 'info' : 'warning'
}
function categoryName(code) {
  const c = categories.value.find(x => x.code === code)
  return c ? c.name : code || '-'
}
function dictName(type, code) {
  const d = (dicts.value[type] || []).find(x => x.code === code)
  return d ? d.name : code || '-'
}
/** 列表存储属性列文案：三属性拼接，全空=无要求（偏差 D2） */
function storageAttrText(row) {
  const parts = []
  if (row.tempLevel) parts.push(`温${dictName('TEMP_LEVEL', row.tempLevel)}`)
  if (row.hazardLevel) parts.push(`危${dictName('HAZARD_LEVEL', row.hazardLevel)}`)
  if (row.cleanLevel) parts.push(`净${dictName('CLEAN_LEVEL', row.cleanLevel)}`)
  return parts.length ? parts.join(' · ') : '无要求'
}

function onSelectionChange(sel) {
  selectedIds.value = sel.map(r => r.id)
}

// ---------- 新建 / 变更 ----------
const formVisible = ref(false)
const formRow = ref(null)
const formPrefill = ref(null)

function openCreate(prefill = null) {
  formRow.value = null
  formPrefill.value = prefill
  formVisible.value = true
}
function openChange(row) {
  formRow.value = row
  formPrefill.value = null
  formVisible.value = true
}
function onFormSaved(prefill) {
  loadData()
  if (prefill) openCreate(prefill) // BR-4.1-13 编码阻断跳转向导：预填继承属性
}
function onJumpCreate(prefill) {
  openCreate(prefill)
}

// ---------- 替代维护 ----------
const substVisible = ref(false)
const substRow = ref(null)
function openSubstitute(row) {
  substRow.value = row
  substVisible.value = true
}

// ---------- 影响分析 ----------
const impactVisible = ref(false)
const impactRow = ref(null)

// ---------- 停用 / 启用 / 归档 ----------
const lifeVisible = ref(false)
const lifeKind = ref('disable')
const lifeRow = ref(null)
function openLifecycle(kind, row) {
  lifeKind.value = kind
  lifeRow.value = row
  lifeVisible.value = true
}

// ---------- 批量停用 ----------
const batchVisible = ref(false)
const batchReason = ref('')
function openBatch() {
  batchReason.value = ''
  batchVisible.value = true
}
async function submitBatch() {
  saving.value = true
  try {
    const res = await disableBatchApi(selectedIds.value, batchReason.value.trim())
    const { succeeded, failed } = res.data
    batchVisible.value = false
    if (failed.length) {
      const detail = failed.map(f => `${f.itemCode || f.id}：${f.message}`).join('；')
      await ElMessageBox.alert(
        `成功 ${succeeded.length} 条；失败 ${failed.length} 条。${detail}`,
        '批量停用结果', { type: 'warning' }
      )
    } else {
      ElMessage.success(`批量停用成功 ${succeeded.length} 条`)
    }
    loadData()
  } catch (e) {
    // 请求级错误已单点提示
  } finally {
    saving.value = false
  }
}

// ---------- 详情与版本 ----------
const detailVisible = ref(false)
const detail = ref({})
const versions = ref([])
const diffFrom = ref(null)
const diffTo = ref(null)
const diffFields = ref([])
const diffRequested = ref(false)

async function openDetail(row) {
  const res = await getItemApi(row.id)
  detail.value = res.data
  versions.value = await getItemVersionsApi(row.id).then(r => r.data)
  diffFrom.value = null
  diffTo.value = null
  diffFields.value = []
  diffRequested.value = false
  detailVisible.value = true
}

async function loadDiff() {
  const res = await getItemDiffApi(detail.value.id, diffFrom.value, diffTo.value)
  diffFields.value = res.data.fields
  diffRequested.value = true
}

onMounted(() => {
  loadData()
  loadRefs()
})
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.diff-bar { margin-bottom: 10px; display: flex; gap: 8px; align-items: center; }
</style>
