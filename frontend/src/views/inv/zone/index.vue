<template>
  <div class="zone-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="仓位规划（4.1.2）"
      description="三层模型 仓库 → 区域 → 仓位；区域挂默认属性（B2），新建仓位继承可覆盖；编号 = 区域码-排2位-列2位-层2位，系统生成不可改，字典序即拣货路径序。"
    />
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">仓位规划</span>
          <div class="header-actions">
            <el-select v-model="query.whCode" placeholder="仓库" clearable style="width: 150px;" @change="loadZones">
              <el-option v-for="w in warehouses" :key="w.whCode" :label="`${w.whName}（${w.whCode}）`" :value="w.whCode" />
            </el-select>
            <el-input v-model="query.keyword" placeholder="区域编码/名称" clearable :prefix-icon="Search"
                      style="width: 170px;" @keyup.enter="loadZones" />
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 100px;" @change="loadZones">
              <el-option label="启用" value="1" />
              <el-option label="停用" value="0" />
            </el-select>
            <el-button type="primary" @click="loadZones">查询</el-button>
            <el-button type="primary" :icon="Plus" @click="openZoneForm()">新建区域</el-button>
            <el-button type="success" :icon="Grid" :disabled="!currentZone" @click="openBatch">批量规划</el-button>
          </div>
        </div>
      </template>

      <div class="split">
        <!-- 左：区域列表 -->
        <div class="zone-list">
          <el-table :data="zones" v-loading="zoneLoading" stripe size="small"
                    highlight-current-row @current-change="onZoneSelect">
            <el-table-column prop="zone.zoneCode" label="编码" width="70" />
            <el-table-column prop="zone.zoneName" label="区域名称" min-width="120" show-overflow-tooltip />
            <el-table-column prop="binCount" label="仓位" width="60" align="center" />
            <el-table-column label="默认温湿度" width="90">
              <template #default="{ row }">{{ dictName('TEMP_LEVEL', row.zone.defTempLevel) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="70">
              <template #default="{ row }">
                <el-tag :type="row.zone.status === '1' ? 'success' : 'info'" size="small">
                  {{ row.zone.status === '1' ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openZoneForm(row.zone)">变更</el-button>
                <el-button v-if="row.zone.status === '1'" link type="warning" @click="handleZoneDisable(row.zone)">停用</el-button>
                <el-button v-else link type="success" @click="handleZoneEnable(row.zone)">启用</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 右：仓位明细 -->
        <div class="bin-panel">
          <div class="bin-toolbar">
            <span v-if="currentZone" class="zone-title">
              {{ currentZone.zoneName }}（{{ currentZone.zoneCode }}）
            </span>
            <span v-else class="zone-title placeholder">← 选择左侧区域查看仓位</span>
            <el-input v-model="binQuery.keyword" placeholder="仓位编号" clearable style="width: 160px;"
                      :disabled="!currentZone" @keyup.enter="loadBins" />
            <el-select v-model="binQuery.status" placeholder="状态" clearable style="width: 100px;"
                       :disabled="!currentZone" @change="loadBins">
              <el-option label="启用" value="1" />
              <el-option label="停用" value="0" />
            </el-select>
            <el-button type="primary" :disabled="!currentZone" @click="loadBins">查询</el-button>
            <el-button type="primary" :icon="Plus" :disabled="!currentZone" @click="openBinForm">新建仓位</el-button>
          </div>
          <el-table :data="bins" v-loading="binLoading" stripe size="small" max-height="520">
            <el-table-column prop="bin.binCode" label="仓位编号" width="130" />
            <el-table-column label="排/列/层" width="100">
              <template #default="{ row }">{{ row.bin.binSeq }}/{{ row.bin.colNo }}/{{ row.bin.layerNo }}</template>
            </el-table-column>
            <el-table-column label="类型" width="90">
              <template #default="{ row }">{{ dictName('BIN_TYPE', row.bin.binType) || '-' }}</template>
            </el-table-column>
            <el-table-column label="温湿度" width="80">
              <template #default="{ row }">{{ dictName('TEMP_LEVEL', row.bin.tempLevel) || '-' }}</template>
            </el-table-column>
            <el-table-column label="危化" width="70">
              <template #default="{ row }">{{ dictName('HAZARD_LEVEL', row.bin.hazardLevel) || '-' }}</template>
            </el-table-column>
            <el-table-column label="洁净" width="70">
              <template #default="{ row }">{{ dictName('CLEAN_LEVEL', row.bin.cleanLevel) || '-' }}</template>
            </el-table-column>
            <el-table-column label="托位" width="70" align="right">
              <template #default="{ row }">{{ row.bin.capacityPallet ?? '不限' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="70">
              <template #default="{ row }">
                <el-tag :type="row.bin.status === '1' ? 'success' : 'info'" size="small">
                  {{ row.bin.status === '1' ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openBinEdit(row.bin)">变更</el-button>
                <el-button v-if="row.bin.status === '1'" link type="warning" @click="handleBinDisable(row.bin)">停用</el-button>
                <el-button v-else link type="success" @click="handleBinEnable(row.bin)">启用</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-card>

    <!-- 新建/变更区域 -->
    <el-dialog v-model="zoneFormVisible" :title="zoneForm.id ? '变更区域' : '新建区域'" width="600px">
      <el-form ref="zoneFormRef" :model="zoneForm" :rules="zoneRules" label-width="110px">
        <el-form-item label="所属仓库" prop="whCode">
          <el-select v-model="zoneForm.whCode" :disabled="!!zoneForm.id" style="width: 100%;">
            <el-option v-for="w in warehouses" :key="w.whCode"
                       :label="`${w.whName}（${w.whCode}）`" :value="w.whCode"
                       :disabled="w.status !== '1'" />
          </el-select>
        </el-form-item>
        <el-form-item label="区域编码" prop="zoneCode">
          <el-input v-model="zoneForm.zoneCode" :disabled="!!zoneForm.id" maxlength="32"
                    placeholder="如 A（创建后不可改，将作为仓位编号前缀）" />
        </el-form-item>
        <el-form-item label="区域名称" prop="zoneName">
          <el-input v-model="zoneForm.zoneName" maxlength="128" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="zoneForm.sortOrder" :min="0" />
        </el-form-item>
        <el-divider content-position="left">默认属性（新建仓位继承，可单个覆盖）</el-divider>
        <el-form-item label="仓位类型">
          <el-select v-model="zoneForm.defBinType" clearable style="width: 100%;">
            <el-option v-for="d in dictOptions.BIN_TYPE" :key="d.dictCode" :label="d.dictName" :value="d.dictCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="温湿度等级">
          <el-select v-model="zoneForm.defTempLevel" clearable style="width: 100%;">
            <el-option v-for="d in dictOptions.TEMP_LEVEL" :key="d.dictCode" :label="d.dictName" :value="d.dictCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="危化品等级">
          <el-select v-model="zoneForm.defHazardLevel" clearable style="width: 100%;">
            <el-option v-for="d in dictOptions.HAZARD_LEVEL" :key="d.dictCode" :label="d.dictName" :value="d.dictCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="洁净等级">
          <el-select v-model="zoneForm.defCleanLevel" clearable style="width: 100%;">
            <el-option v-for="d in dictOptions.CLEAN_LEVEL" :key="d.dictCode" :label="d.dictName" :value="d.dictCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="托位容量">
          <el-input-number v-model="zoneForm.defCapacityPallet" :min="0" placeholder="留空 = 不限" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="zoneForm.remark" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="zoneFormVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitZoneForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 批量规划 -->
    <el-dialog v-model="batchVisible" title="批量规划仓位" width="620px">
      <el-form label-width="110px">
        <el-form-item label="区域">
          <el-input :model-value="currentZone ? `${currentZone.zoneName}（${currentZone.zoneCode}）` : ''" disabled />
        </el-form-item>
        <el-form-item label="排（起~止）">
          <div class="range-row">
            <el-input-number v-model="batch.seqStart" :min="1" :max="99" />
            <span class="sep">~</span>
            <el-input-number v-model="batch.seqEnd" :min="1" :max="99" />
          </div>
        </el-form-item>
        <el-form-item label="列（起~止）">
          <div class="range-row">
            <el-input-number v-model="batch.colStart" :min="1" :max="99" />
            <span class="sep">~</span>
            <el-input-number v-model="batch.colEnd" :min="1" :max="99" />
          </div>
        </el-form-item>
        <el-form-item label="层（起~止）">
          <div class="range-row">
            <el-input-number v-model="batch.layerStart" :min="1" :max="99" />
            <span class="sep">~</span>
            <el-input-number v-model="batch.layerEnd" :min="1" :max="99" />
          </div>
        </el-form-item>
        <el-divider content-position="left">属性（留空 = 继承区域默认）</el-divider>
        <el-form-item label="仓位类型">
          <el-select v-model="batch.overrideBinType" clearable style="width: 100%;">
            <el-option v-for="d in dictOptions.BIN_TYPE" :key="d.dictCode" :label="d.dictName" :value="d.dictCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="温湿度等级">
          <el-select v-model="batch.overrideTempLevel" clearable style="width: 100%;">
            <el-option v-for="d in dictOptions.TEMP_LEVEL" :key="d.dictCode" :label="d.dictName" :value="d.dictCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="托位容量">
          <el-input-number v-model="batch.overrideCapacityPallet" :min="0" placeholder="留空 = 继承" />
        </el-form-item>
        <el-alert type="success" :closable="false" show-icon
                  :title="`预览：将生成 ${batchPreview.count} 个仓位`"
                  :description="`编号范围：${batchPreview.from} ~ ${batchPreview.to}`" />
      </el-form>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitBatch">生成</el-button>
      </template>
    </el-dialog>

    <!-- 单个新建/变更仓位 -->
    <el-dialog v-model="binFormVisible" :title="binForm.id ? '变更仓位属性' : '新建仓位'" width="560px">
      <el-form ref="binFormRef" :model="binForm" :rules="binRules" label-width="110px">
        <template v-if="!binForm.id">
          <el-form-item label="区域">
            <el-input :model-value="currentZone ? `${currentZone.zoneName}（${currentZone.zoneCode}）` : ''" disabled />
          </el-form-item>
          <el-form-item label="排/列/层" required>
            <div class="range-row">
              <el-input-number v-model="binForm.binSeq" :min="1" :max="99" />
              <el-input-number v-model="binForm.colNo" :min="1" :max="99" />
              <el-input-number v-model="binForm.layerNo" :min="1" :max="99" />
            </div>
          </el-form-item>
          <el-form-item label="编号预览">
            <el-input :model-value="binCodePreview" disabled />
          </el-form-item>
        </template>
        <el-form-item v-else label="仓位编号">
          <el-input :model-value="binForm.binCode" disabled />
        </el-form-item>
        <el-form-item label="仓位类型">
          <el-select v-model="binForm.binType" clearable style="width: 100%;"
                     :placeholder="binForm.id ? '' : '留空 = 继承区域默认'">
            <el-option v-for="d in dictOptions.BIN_TYPE" :key="d.dictCode" :label="d.dictName" :value="d.dictCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="温湿度等级">
          <el-select v-model="binForm.tempLevel" clearable style="width: 100%;"
                     :placeholder="binForm.id ? '' : '留空 = 继承区域默认'">
            <el-option v-for="d in dictOptions.TEMP_LEVEL" :key="d.dictCode" :label="d.dictName" :value="d.dictCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="托位容量">
          <el-input-number v-model="binForm.capacityPallet" :min="0"
                           :placeholder="binForm.id ? '' : '留空 = 继承区域默认'" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="binForm.remark" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="binFormVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitBinForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus, Grid } from '@element-plus/icons-vue'
import { getWarehousesApi } from '@/api/inv/warehouse'
import {
  getZonesWithCountApi, createZoneApi, updateZoneApi,
  enableZoneApi, disableZoneApi
} from '@/api/inv/zone'
import { getBinsApi, createBinApi, updateBinApi, batchCreateBinsApi, enableBinApi, disableBinApi } from '@/api/inv/bin'
import { INV_DICT_TYPES, getDictActiveApi } from '@/api/inv/dict'

const warehouses = ref([])
const zones = ref([])
const bins = ref([])
const zoneLoading = ref(false)
const binLoading = ref(false)
const saving = ref(false)
const currentZone = ref(null)

const query = reactive({ whCode: '', keyword: '', status: '' })
const binQuery = reactive({ keyword: '', status: '' })

// ---- 字典 ----
const dictOptions = reactive({})
const dictCache = reactive({}) // DICT_TYPE -> Map<code, name>
async function loadDicts() {
  for (const t of INV_DICT_TYPES) {
    try {
      const res = await getDictActiveApi(t.type)
      dictOptions[t.type] = res.data || []
      dictCache[t.type] = Object.fromEntries((res.data || []).map(d => [d.dictCode, d.dictName]))
    } catch (e) {
      dictOptions[t.type] = []
      dictCache[t.type] = {}
    }
  }
}
function dictName(type, code) {
  if (!code) return ''
  return (dictCache[type] && dictCache[type][code]) || code
}

// ---- 区域 ----
async function loadWarehouses() {
  const res = await getWarehousesApi({})
  warehouses.value = res.data || []
}
async function loadZones() {
  zoneLoading.value = true
  try {
    const res = await getZonesWithCountApi({
      whCode: query.whCode || undefined,
      keyword: query.keyword || undefined,
      status: query.status || undefined
    })
    zones.value = res.data || []
    // 选中区域失联时清空右侧
    if (currentZone.value && !zones.value.some(z => z.zone.id === currentZone.value.id)) {
      currentZone.value = null
      bins.value = []
    }
  } finally {
    zoneLoading.value = false
  }
}
function onZoneSelect(row) {
  currentZone.value = row ? row.zone : null
  binQuery.keyword = ''
  binQuery.status = ''
  loadBins()
}

// ---- 仓位 ----
async function loadBins() {
  if (!currentZone.value) return
  binLoading.value = true
  try {
    const res = await getBinsApi({
      whCode: currentZone.value.whCode,
      zoneCode: currentZone.value.zoneCode,
      keyword: binQuery.keyword || undefined,
      status: binQuery.status || undefined
    })
    bins.value = res.data || []
  } finally {
    binLoading.value = false
  }
}

// ---- 区域表单 ----
const zoneFormVisible = ref(false)
const zoneFormRef = ref(null)
const zoneForm = reactive({})
const zoneRules = {
  whCode: [{ required: true, message: '请选择所属仓库', trigger: 'change' }],
  zoneCode: [{ required: true, message: '请输入区域编码', trigger: 'blur' }],
  zoneName: [{ required: true, message: '请输入区域名称', trigger: 'blur' }]
}
function openZoneForm(row) {
  Object.assign(zoneForm, row ? { ...row } : {
    whCode: query.whCode || '', zoneCode: '', zoneName: '', sortOrder: 0,
    defBinType: '', defTempLevel: '', defHazardLevel: '', defCleanLevel: '',
    defCapacityPallet: undefined, remark: ''
  })
  zoneFormVisible.value = true
}
async function submitZoneForm() {
  await zoneFormRef.value.validate()
  saving.value = true
  try {
    if (zoneForm.id) {
      await updateZoneApi(zoneForm)
      ElMessage.success('变更已保存（编码不可改）')
    } else {
      await createZoneApi(zoneForm)
      ElMessage.success('新建成功')
    }
    zoneFormVisible.value = false
    loadZones()
  } finally {
    saving.value = false
  }
}
async function handleZoneDisable(row) {
  await ElMessageBox.confirm(
    `确认停用「${row.zoneName}」？停用后该区域不可新建仓位，既有仓位状态不受影响。`,
    '停用确认', { type: 'warning' }
  )
  await disableZoneApi(row.id)
  ElMessage.success('已停用')
  loadZones()
}
async function handleZoneEnable(row) {
  await enableZoneApi(row.id)
  ElMessage.success('已启用')
  loadZones()
}

// ---- 批量规划 ----
const batchVisible = ref(false)
const batch = reactive({
  seqStart: 1, seqEnd: 1, colStart: 1, colEnd: 1, layerStart: 1, layerEnd: 1,
  overrideBinType: '', overrideTempLevel: '', overrideCapacityPallet: undefined
})
const batchPreview = computed(() => {
  const { seqStart, seqEnd, colStart, colEnd, layerStart, layerEnd } = batch
  if (seqEnd < seqStart || colEnd < colStart || layerEnd < layerStart) {
    return { count: 0, from: '-', to: '-' }
  }
  const count = (seqEnd - seqStart + 1) * (colEnd - colStart + 1) * (layerEnd - layerStart + 1)
  const z = currentZone.value ? currentZone.value.zoneCode : '?'
  const pad = (n) => String(n).padStart(2, '0')
  return {
    count,
    from: `${z}-${pad(seqStart)}-${pad(colStart)}-${pad(layerStart)}`,
    to: `${z}-${pad(seqEnd)}-${pad(colEnd)}-${pad(layerEnd)}`
  }
})
function openBatch() {
  if (!currentZone.value) return
  Object.assign(batch, {
    seqStart: 1, seqEnd: 1, colStart: 1, colEnd: 1, layerStart: 1, layerEnd: 1,
    overrideBinType: '', overrideTempLevel: '', overrideCapacityPallet: undefined
  })
  batchVisible.value = true
}
async function submitBatch() {
  if (batchPreview.value.count === 0) {
    ElMessage.warning('区间非法：起始须 ≤ 截止且 ≥ 1')
    return
  }
  await ElMessageBox.confirm(
    `确认生成 ${batchPreview.value.count} 个仓位（${batchPreview.value.from} ~ ${batchPreview.value.to}）？`,
    '批量规划确认', { type: 'warning' }
  )
  saving.value = true
  try {
    const res = await batchCreateBinsApi({
      whCode: currentZone.value.whCode,
      zoneCode: currentZone.value.zoneCode,
      ...batch
    })
    ElMessage.success(`已生成 ${res.data.created} 个仓位（${res.data.from} ~ ${res.data.to}）`)
    batchVisible.value = false
    loadZones()
    loadBins()
  } catch (e) {
    // 冲突 409：request.js 已弹错（含冲突编号清单），此处静默恢复
  } finally {
    saving.value = false
  }
}

// ---- 仓位表单 ----
const binFormVisible = ref(false)
const binFormRef = ref(null)
const binForm = reactive({})
const binRules = {}
const binCodePreview = computed(() => {
  if (!currentZone.value) return ''
  const pad = (n) => String(n || 0).padStart(2, '0')
  return `${currentZone.value.zoneCode}-${pad(binForm.binSeq)}-${pad(binForm.colNo)}-${pad(binForm.layerNo)}`
})
function openBinForm() {
  if (!currentZone.value) return
  Object.assign(binForm, {
    binSeq: 1, colNo: 1, layerNo: 1,
    binType: '', tempLevel: '', capacityPallet: undefined, remark: ''
  })
  binFormVisible.value = true
}
function openBinEdit(row) {
  Object.assign(binForm, { ...row })
  binFormVisible.value = true
}
async function submitBinForm() {
  saving.value = true
  try {
    if (binForm.id) {
      await updateBinApi(binForm)
      ElMessage.success('变更已保存（编号不可改）')
    } else {
      await createBinApi({ ...binForm, whCode: currentZone.value.whCode, zoneCode: currentZone.value.zoneCode })
      ElMessage.success('新建成功')
    }
    binFormVisible.value = false
    loadZones()
    loadBins()
  } finally {
    saving.value = false
  }
}
async function handleBinDisable(row) {
  await ElMessageBox.confirm(`确认停用仓位「${row.binCode}」？`, '停用确认', { type: 'warning' })
  await disableBinApi(row.id)
  ElMessage.success('已停用')
  loadBins()
}
async function handleBinEnable(row) {
  await enableBinApi(row.id)
  ElMessage.success('已启用')
  loadBins()
}

onMounted(async () => {
  await Promise.all([loadWarehouses(), loadDicts()])
  loadZones()
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-actions {
  display: flex;
  gap: 8px;
}
.split {
  display: flex;
  gap: 12px;
}
.zone-list {
  width: 420px;
  flex-shrink: 0;
}
.bin-panel {
  flex: 1;
  min-width: 0;
}
.bin-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
}
.zone-title {
  font-weight: bold;
  margin-right: 8px;
}
.zone-title.placeholder {
  color: #909399;
  font-weight: normal;
}
.range-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.range-row .sep {
  color: #909399;
}
</style>
