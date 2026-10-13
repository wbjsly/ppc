<template>
  <div class="task-board">
    <el-card>
      <template #header>
        <div class="card-header">
          <span class="ttl">{{ title }}</span>
          <div>
            <el-button size="small" @click="$router.push('/m/6.2.config')">配置</el-button>
            <el-button size="small" type="primary" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
                title="检验前置于入库过账（C-4.12-06）；超时批次由调度器自动标记并改派（5min）；紧急批次优先展示。"
                description="无标准批次标红 BLOCKED（发布标准后一键激活）；免检批次 SKIPPED 可过账；OQC 完工批放行即联动入库并发合格证。" />

      <div class="filters">
        <el-select v-model="statusFilter" placeholder="状态" clearable style="width: 150px;" @change="load">
          <el-option label="无标准阻断" value="BLOCKED" />
          <el-option label="待检" value="PENDING" />
          <el-option label="录入中" value="INPUTTING" />
          <el-option label="边界复核" value="REVIEWING" />
          <el-option label="不合格冻结" value="FROZEN" />
        </el-select>
        <el-select v-model="lotTypeFilter" placeholder="类型" clearable style="width: 120px;" :disabled="lotTypeLocked" @change="load">
          <el-option label="IQC 来料" value="IQC" />
          <el-option label="IPQC 过程" value="IPQC" />
          <el-option label="OQC 出货" value="OQC" />
        </el-select>
        <el-button v-if="canEdit" type="success" @click="manualVisible = true">手工建批</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe @row-click="goDetail" style="cursor: pointer;">
        <el-table-column prop="lotNo" label="检验批号" width="170">
          <template #default="{ row }">
            <b>{{ row.lotNo }}</b>
            <el-tag v-if="row.priority === 'URGENT'" size="small" type="danger" style="margin-left: 4px;">紧急</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lotType" label="类型" width="70" />
        <el-table-column prop="itemCode" label="物料" min-width="150">
          <template #default="{ row }">{{ row.itemCode }} {{ row.itemName }}</template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" width="120" show-overflow-tooltip />
        <el-table-column prop="lotQty" label="批量" width="80" align="right" />
        <el-table-column prop="riskGrade" label="风险" width="60" align="center" />
        <el-table-column prop="inspectorName" label="分派质检员" width="110">
          <template #default="{ row }">
            <span v-if="row.inspectorName">{{ row.inspectorName }}</span>
            <el-tag v-else size="small" type="info">未分派</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="lotStatusTag(row.status)">{{ lotStatusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="协议" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.agreeCode" size="small" type="warning" :title="`客户协议 ${row.agreeCode} 加严`">加严</el-tag>
            <span v-else style="color: #c0c4cc;">—</span>
          </template>
        </el-table-column>
        <el-table-column label="时限" width="160">
          <template #default="{ row }">
            <span :style="isOverdue(row) ? 'color:#f56c6c;font-weight:bold' : ''">
              {{ row.dueTime || '—' }}{{ isOverdue(row) ? ' 超时' : '' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'BLOCKED' && canEdit" link type="warning"
                       @click.stop="doActivate(row)">激活</el-button>
            <el-button v-if="canInspect && ['PENDING','INPUTTING'].includes(row.status)"
                       link type="primary" @click.stop="goDetail(row)">录入/判定</el-button>
            <el-button v-if="row.status === 'REVIEWING' && canReview" link type="warning"
                       @click.stop="goDetail(row)">复核</el-button>
            <el-button v-if="canInspect && row.result === 'PASS' && row.status === 'INPUTTING'"
                       link type="success" @click.stop="goDetail(row)">放行</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="暂无在途检验批" :image-size="80" />
      <el-pagination style="margin-top: 10px;" layout="total, prev, pager, next" :total="total"
                     :page-size="size" :current-page="page" @current-change="p => { page = p; load() }" />
    </el-card>

    <!-- 手工建批 -->
    <el-dialog v-model="manualVisible" title="手工创建 IPQC / OQC 检验批" width="520px">
      <el-form label-width="100px">
        <el-form-item label="类型" required>
          <el-radio-group v-model="manualForm.lotType">
            <el-radio label="IPQC">IPQC 过程</el-radio>
            <el-radio label="OQC">OQC 出货</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="物料编码" required><el-input v-model="manualForm.itemCode" /></el-form-item>
        <el-form-item label="物料名称"><el-input v-model="manualForm.itemName" /></el-form-item>
        <el-form-item label="数量" required><el-input-number v-model="manualForm.qty" :min="1" /></el-form-item>
        <el-form-item label="批次号"><el-input v-model="manualForm.batchNo" /></el-form-item>
        <el-form-item label="工序"><el-input v-model="manualForm.processId" /></el-form-item>
        <el-form-item label="客户 ID"><el-input v-model="manualForm.customerId" placeholder="OQC 带客户可命中协议加严" /></el-form-item>
        <el-form-item label="优先级">
          <el-radio-group v-model="manualForm.priority">
            <el-radio label="NORMAL">普通</el-radio>
            <el-radio label="URGENT">紧急（时限从严）</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveManual">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { getLotBoardApi, activateLotApi, createManualLotApi } from '@/api/qms/inspection'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const roles = computed(() => userStore.userInfo?.roles || [])
const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))
const canInspect = computed(() => isAdmin.value || roles.value.includes('ROLE_INSPECTOR')
  || roles.value.includes('ROLE_QUALITY_ENG'))
const canReview = computed(() => isAdmin.value || roles.value.includes('ROLE_QUALITY_ENG'))
const canEdit = computed(() => isAdmin.value || roles.value.includes('ROLE_QUALITY_ENG'))

const title = computed(() => route.meta.title || '来料检验')
// 三叶（6.2.1/6.2.2/6.2.3）按 lotType 锁定过滤；2.5.1 等入口不限
const lotTypeLocked = computed(() => !!route.meta.lotType)
const lotTypeFilter = ref(route.meta.lotType || '')

const loading = ref(false)
const busy = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(50)
const statusFilter = ref('')
const manualVisible = ref(false)
const manualForm = reactive({ lotType: 'IPQC', itemCode: '', itemName: '', qty: 1, batchNo: '', processId: '', customerId: '', priority: 'NORMAL' })

function lotStatusName(s) {
  return { BLOCKED: '无标准阻断', PENDING: '待检', INPUTTING: '录入中', REVIEWING: '边界复核',
           RELEASED: '已放行', FROZEN: '不合格冻结', SKIPPED: '免检', CONCESSION: '让步接收',
           CANCELLED: '已作废' }[s] || s
}
function lotStatusTag(s) {
  return { BLOCKED: 'danger', PENDING: 'primary', INPUTTING: 'warning', REVIEWING: 'warning',
           RELEASED: 'success', FROZEN: 'danger', SKIPPED: 'info' }[s] || 'info'
}
function isOverdue(row) {
  return row.overdueFlag === '1' || row.overdue === true
}

async function load() {
  loading.value = true
  try {
    const params = { current: page.value, size: size.value }
    if (statusFilter.value) params.status = statusFilter.value
    const res = await getLotBoardApi(params)
    let recs = res.data?.records || []
    if (lotTypeFilter.value) recs = recs.filter(r => r.lotType === lotTypeFilter.value)
    rows.value = recs
    total.value = res.data?.total || 0
  } catch (e) { /* 拦截器提示 */ } finally { loading.value = false }
}

function goDetail(row) {
  router.push(`/m/6.2.lot/${row.id}`)
}

async function doActivate(row) {
  busy.value = true
  try {
    await activateLotApi(row.id)
    ElMessage.success('已加载有效标准并转待检')
    await load()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}

async function saveManual() {
  if (!manualForm.itemCode || !manualForm.qty) { ElMessage.warning('物料与数量必填'); return }
  busy.value = true
  try {
    const res = await createManualLotApi({ ...manualForm })
    ElMessage.success(`已创建 ${res.data?.lotNo}`)
    manualVisible.value = false
    await load()
  } catch (e) { /* 拦截器提示 */ } finally { busy.value = false }
}

watch(() => route.meta.lotType, v => { lotTypeFilter.value = v || ''; load() })
onMounted(load)
</script>

<style scoped>
.task-board { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.ttl { font-weight: bold; font-size: 16px; }
.filters { display: flex; gap: 8px; margin-bottom: 10px; }
</style>
