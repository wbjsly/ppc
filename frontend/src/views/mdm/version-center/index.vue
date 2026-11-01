<template>
  <div class="page">
    <el-tabs v-model="tab">
      <!-- 1.7.1 版本对比 -->
      <el-tab-pane label="版本对比" name="compare">
        <el-form inline>
          <el-form-item label="主数据类型">
            <el-select v-model="bizType" style="width: 160px" @change="onTypeChange">
              <el-option v-for="t in TYPES" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="实体">
            <el-select v-model="entityId" filterable remote reserve-keyword clearable
                       :remote-method="searchEntities" :loading="entityLoading"
                       style="width: 280px" placeholder="输入编码/名称搜索" @change="loadVersions">
              <el-option v-for="e in entities" :key="e.id" :label="`${e.code} ${e.name || ''}`" :value="e.id" />
            </el-select>
          </el-form-item>
        </el-form>
        <template v-if="entityId">
          <el-form inline>
            <el-form-item label="基准版本">
              <el-select v-model="fromVer" style="width: 120px">
                <el-option v-for="v in versions" :key="v.versionNo" :label="`V${v.versionNo}`" :value="v.versionNo" />
              </el-select>
            </el-form-item>
            <el-form-item label="对比版本">
              <el-select v-model="toVer" style="width: 120px">
                <el-option v-for="v in versions" :key="v.versionNo" :label="`V${v.versionNo}`" :value="v.versionNo" />
              </el-select>
            </el-form-item>
            <el-button type="primary" :disabled="!fromVer || !toVer || fromVer === toVer" @click="loadDiff">对比</el-button>
          </el-form>
          <el-table :data="diffFields" border size="small">
            <el-table-column prop="field" label="字段" width="220" />
            <el-table-column label="旧值">
              <template #default="{ row }">
                <span :class="{ 'diff-old': row.state !== 'ADDED' }">{{ row.oldVal || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="新值">
              <template #default="{ row }">
                <span :class="{ 'diff-new': row.state !== 'REMOVED' }">{{ row.newVal || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="stateType(row.state)" size="small">{{ stateLabel(row.state) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="diffDone && diffFields.length === 0" description="两版本无字段差异" />
        </template>
      </el-tab-pane>

      <!-- 1.7.2 历史回溯 -->
      <el-tab-pane label="历史回溯" name="history">
        <el-form inline>
          <el-form-item label="主数据类型">
            <el-select v-model="bizType" style="width: 160px" @change="onTypeChange">
              <el-option v-for="t in TYPES" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="实体">
            <el-select v-model="entityId" filterable remote reserve-keyword clearable
                       :remote-method="searchEntities" :loading="entityLoading"
                       style="width: 280px" placeholder="输入编码/名称搜索" @change="loadVersions">
              <el-option v-for="e in entities" :key="e.id" :label="`${e.code} ${e.name || ''}`" :value="e.id" />
            </el-select>
          </el-form-item>
        </el-form>
        <el-timeline v-if="versions.length">
          <el-timeline-item v-for="v in versions" :key="v.id" :timestamp="`V${v.versionNo} · ${formatTime(v.createDate)}`"
                            :type="v.opType === 'ROLLBACK' ? 'warning' : 'primary'">
            <el-card shadow="never">
              <div>
                <el-tag size="small" :type="opTypeTag(v.opType)">{{ opTypeLabel(v.opType) }}</el-tag>
                <el-tag v-if="v.changeType" size="small" style="margin-left: 6px"
                        :type="v.changeType === 'CRITICAL' ? 'danger' : 'info'">
                  {{ v.changeType === 'CRITICAL' ? '关键属性' : '一般属性' }}
                </el-tag>
                <span style="margin-left: 10px; color: #909399">{{ v.createBy }} · 快照 {{ v.snapshotSize }}B</span>
              </div>
              <div v-if="v.changeReason" style="margin-top: 6px">变更原因：{{ v.changeReason }}</div>
              <el-button size="small" style="margin-top: 8px" @click="viewSnapshot(v.versionNo)">查看快照</el-button>
            </el-card>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else-if="entityId" description="无版本记录" />
      </el-tab-pane>

      <!-- 1.7.3 版本回滚（ADMIN） -->
      <el-tab-pane label="版本回滚" name="rollback">
        <el-alert type="warning" :closable="false" style="margin-bottom: 12px"
                  title="回滚将把所选历史版本快照生成为新版本 V(N+1) 并直接生效（审计留痕+事件同步下游），历史版本不受影响" />
        <el-form inline>
          <el-form-item label="主数据类型">
            <el-select v-model="bizType" style="width: 160px" @change="onTypeChange">
              <el-option v-for="t in TYPES" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="实体">
            <el-select v-model="entityId" filterable remote reserve-keyword clearable
                       :remote-method="searchEntities" :loading="entityLoading"
                       style="width: 280px" placeholder="输入编码/名称搜索" @change="loadVersions">
              <el-option v-for="e in entities" :key="e.id" :label="`${e.code} ${e.name || ''}`" :value="e.id" />
            </el-select>
          </el-form-item>
        </el-form>
        <el-table v-if="versions.length" :data="versions" border size="small" @row-click="selectRollback">
          <el-table-column prop="versionNo" label="版本" width="80">
            <template #default="{ row }">V{{ row.versionNo }}</template>
          </el-table-column>
          <el-table-column prop="opType" label="操作" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="opTypeTag(row.opType)">{{ opTypeLabel(row.opType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="changeReason" label="变更原因" />
          <el-table-column prop="createBy" label="操作人" width="120" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button type="warning" size="small" :disabled="!isAdmin" @click="selectRollback(row)">回滚到此版本</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-dialog v-model="rollbackVisible" title="确认回滚" width="480px">
          <el-form label-width="90px">
            <el-form-item label="目标版本">
              <el-tag type="warning">V{{ rollbackTarget }}</el-tag>
              <span style="margin-left: 8px; color: #909399">将生成 V(N+1) 并直接生效</span>
            </el-form-item>
            <el-form-item label="回滚原因" required>
              <el-input v-model="rollbackReason" type="textarea" :rows="3" placeholder="至少 2 字，将写入审计与版本记录" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="rollbackVisible = false">取消</el-button>
            <el-button type="warning" :loading="rollbackLoading" @click="doRollback">确认回滚</el-button>
          </template>
        </el-dialog>
        <el-empty v-if="!versions.length && entityId" description="无版本记录" />
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="snapshotVisible" title="版本快照" size="55%">
      <pre class="snapshot-pre">{{ snapshotJson }}</pre>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getEntitiesApi, getVersionsApi, getSnapshotApi, getDiffApi, rollbackApi } from '@/api/mdm/version-sync'

const route = useRoute()
const tab = ref(route.meta.tab || 'compare')

const TYPES = [
  { value: 'ITEM', label: '物料' }, { value: 'CUSTOMER_GROUP', label: '客户组' },
  { value: 'CUSTOMER_VIEW', label: '客户视图' }, { value: 'SUPPLIER', label: '供应商' },
  { value: 'EXCHANGE_RATE', label: '汇率' }, { value: 'TAX_CODE', label: '税码' },
  { value: 'ORG_UNIT', label: '组织' }, { value: 'COST_CENTER', label: '成本中心' },
  { value: 'PROFIT_CENTER', label: '利润中心' }, { value: 'LEGAL_ENTITY', label: '法人' },
  { value: 'ITEM_CATEGORY', label: '物料分类' }, { value: 'PRICE_AGREEMENT', label: '价格协议' },
  { value: 'TAX_POLICY', label: '税策' }
]

const isAdmin = ref(false)
const bizType = ref('ITEM')
const entityId = ref('')
const entities = ref([])
const entityLoading = ref(false)
const versions = ref([])
const fromVer = ref(null)
const toVer = ref(null)
const diffFields = ref([])
const diffDone = ref(false)
const snapshotVisible = ref(false)
const snapshotJson = ref('')
const rollbackVisible = ref(false)
const rollbackTarget = ref(null)
const rollbackReason = ref('')
const rollbackLoading = ref(false)

onMounted(() => {
  try {
    const roles = JSON.parse(localStorage.getItem('roles') || '[]')
    isAdmin.value = roles.includes('ROLE_ADMIN') || roles.includes('ADMIN')
  } catch (e) { isAdmin.value = false }
  if (tab.value === 'compare' || tab.value === 'history' || tab.value === 'rollback') {
    searchEntities('')
  }
})

async function searchEntities(q) {
  entityLoading.value = true
  try {
    const r = await getEntitiesApi(bizType.value, { keyword: q || '', page: 1, size: 30 })
    entities.value = r.data || []
  } finally {
    entityLoading.value = false
  }
}

function onTypeChange() {
  entityId.value = ''
  entities.value = []
  versions.value = []
  diffFields.value = []
  diffDone.value = false
  searchEntities('')
}

async function loadVersions() {
  if (!entityId.value) return
  const r = await getVersionsApi(bizType.value, entityId.value)
  versions.value = r.data || []
  if (versions.value.length >= 2) {
    fromVer.value = versions.value[1].versionNo
    toVer.value = versions.value[0].versionNo
  } else if (versions.value.length === 1) {
    fromVer.value = versions.value[0].versionNo
    toVer.value = versions.value[0].versionNo
  }
}

async function loadDiff() {
  const r = await getDiffApi(bizType.value, entityId.value, fromVer.value, toVer.value)
  diffFields.value = r.data.fields || []
  diffDone.value = true
}

async function viewSnapshot(v) {
  const r = await getSnapshotApi(bizType.value, entityId.value, v)
  try {
    snapshotJson.value = JSON.stringify(JSON.parse(r.data.snapshotJson), null, 2)
  } catch (e) {
    snapshotJson.value = r.data.snapshotJson || ''
  }
  snapshotVisible.value = true
}

function selectRollback(row) {
  if (row.versionNo === versions.value[0]?.versionNo) {
    ElMessage.warning('已是最新版本，无需回滚')
    return
  }
  rollbackTarget.value = row.versionNo
  rollbackReason.value = ''
  rollbackVisible.value = true
}

async function doRollback() {
  if (!rollbackReason.value || rollbackReason.value.trim().length < 2) {
    ElMessage.warning('回滚原因必填（至少 2 字）')
    return
  }
  await ElMessageBox.confirm(
    `确认回滚至 V${rollbackTarget}？将生成新版本 V(N+1) 并直接生效`, '提示', { type: 'warning' })
  rollbackLoading.value = true
  try {
    const r = await rollbackApi(bizType.value, entityId.value, rollbackTarget.value, rollbackReason.value.trim())
    ElMessage.success(`回滚成功，已生成 V${r.data.newVersionNo}`)
    rollbackVisible.value = false
    loadVersions()
  } finally {
    rollbackLoading.value = false
  }
}

function stateType(s) { return s === 'ADDED' ? 'success' : s === 'REMOVED' ? 'danger' : 'warning' }
function stateLabel(s) { return s === 'ADDED' ? '新增值' : s === 'REMOVED' ? '删除值' : '值变化' }
function opTypeLabel(t) { return { CREATE: '创建', UPDATE: '变更', DISABLE: '停用', ROLLBACK: '回滚' }[t] || t }
function opTypeTag(t) { return { CREATE: 'success', UPDATE: 'primary', DISABLE: 'info', ROLLBACK: 'warning' }[t] || 'info' }
function formatTime(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '' }
</script>

<style scoped>
.diff-old { color: #f56c6c; text-decoration: line-through; }
.diff-new { color: #67c23a; }
.snapshot-pre { background: #f5f7fa; padding: 12px; border-radius: 4px; max-height: 70vh; overflow: auto; font-size: 12px; }
</style>
