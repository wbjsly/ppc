<template>
  <div class="page">
    <el-alert type="info" :closable="false" style="margin-bottom: 8px"
      title="按原因分流门槛：呆滞=技术/质量/财务三方会签（C-4.4-14 未会签阻断过账）；质量=挂 NCR（处置须为报废）；损坏/其他=直接批准。过账生成凭证一，处置核销生成凭证二。" />

    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" style="width: 150px">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已批准" value="APPROVED" />
          <el-option label="已过账" value="POSTED" />
          <el-option label="已核销" value="DISPOSED" />
          <el-option label="已作废" value="CANCELLED" />
        </el-select>
      </el-form-item>
      <el-form-item label="关键字">
        <el-input v-model="query.keyword" placeholder="报废单/NCR/仓库" clearable style="width: 200px"
          @keyup.enter="reload" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="reload">查询</el-button>
        <el-button type="success" @click="openCreate" :disabled="!canOperate">新建报废单</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe>
      <el-table-column prop="scrapNo" label="报废单号" width="150" />
      <el-table-column label="原因" width="140">
        <template #default="{ row }">
          <el-tag :type="reasonTag(row.reason)" size="small">{{ reasonText(row.reason) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="ncrNo" label="NCR" width="130">
        <template #default="{ row }">{{ row.ncrNo || '-' }}</template>
      </el-table-column>
      <el-table-column prop="warehouseCode" label="仓库" width="110" />
      <el-table-column prop="totalQty" label="数量" width="90" align="right" />
      <el-table-column prop="totalAmount" label="金额" width="100" align="right" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="330" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          <el-button v-if="row.status === 'DRAFT' && row.reason === 'STALE' && !row.apprId && canOperate"
            link type="warning" size="small" :loading="acting" @click="doSubmitApproval(row)">提交会签</el-button>
          <el-button v-if="row.status === 'DRAFT' && row.reason !== 'STALE' && canOperate"
            link type="warning" size="small" :loading="acting" @click="doApprove(row)">批准</el-button>
          <el-button v-if="row.apprId && (row.status === 'DRAFT')" link type="primary" size="small"
            @click="openProgress(row)">会签进度</el-button>
          <el-button v-if="row.status === 'APPROVED' && canOperate" link type="primary" size="small"
            :loading="acting" @click="doPost(row)">过账</el-button>
          <el-button v-if="row.status === 'POSTED' && canOperate" link type="success" size="small"
            :loading="acting" @click="doDispose(row)">处置核销</el-button>
          <el-button v-if="row.status === 'DRAFT' && canOperate" link type="danger" size="small"
            @click="doCancel(row)">作废</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0" description="暂无报废单" />
    <el-pagination v-if="total > 0" class="pager" background layout="total, prev, pager, next"
      :total="total" :page-size="query.size" :current-page="query.current" @current-change="onPage" />

    <!-- ============ 新建报废单 ============ -->
    <el-dialog v-model="createVisible" title="新建报废单（按原因分流门槛）" width="900px" :close-on-click-modal="false">
      <el-form :model="form" label-width="90px">
        <el-row :gutter="16">
          <el-col :span="14">
            <el-form-item label="报废原因" required>
              <el-radio-group v-model="form.reason">
                <el-radio-button value="STALE">呆滞（须三方会签）</el-radio-button>
                <el-radio-button value="QUALITY">质量（挂 NCR）</el-radio-button>
                <el-radio-button value="DAMAGE">损坏</el-radio-button>
                <el-radio-button value="OTHER">其他</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="10">
            <el-form-item label="NCR 关联号" :required="form.reason === 'QUALITY'">
              <el-input v-model="form.ncrNo" :disabled="form.reason !== 'QUALITY'"
                placeholder="质量原因必填（处置须为报废）" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="14">
            <el-form-item label="报废仓库" required>
              <el-select v-model="form.warehouseCode" placeholder="选择仓库" style="width: 100%">
                <el-option v-for="w in warehouses" :key="w.whCode" :label="`${w.whName}（${w.whCode}）`"
                  :value="w.whCode" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="10">
            <el-form-item label="备注">
              <el-input v-model="form.remark" maxlength="100" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <el-table :data="form.lines" border size="small">
        <el-table-column label="物料" min-width="200">
          <template #default="{ row }">
            <el-select v-model="row.itemCode" filterable placeholder="选择物料" style="width: 100%">
              <el-option v-for="it in itemOptions" :key="it.code" :label="`${it.name}（${it.code}）`"
                :value="it.code" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="批次（必填）" width="140">
          <template #default="{ row }">
            <el-input v-model="row.batchNo" placeholder="批次号" />
          </template>
        </el-table-column>
        <el-table-column label="数量" width="125">
          <template #default="{ row }">
            <el-input-number v-model="row.qty" :min="0.0001" :precision="4" controls-position="right"
              style="width: 105px" />
          </template>
        </el-table-column>
        <el-table-column label="单位成本（凭证金额）" width="165">
          <template #default="{ row }">
            <el-input-number v-model="row.unitCost" :min="0" :precision="4" controls-position="right"
              style="width: 145px" placeholder="过账前必补" />
          </template>
        </el-table-column>
        <el-table-column label="序列号（序列物料必填）" width="200">
          <template #default="{ row }">
            <el-input v-model="row.serials" placeholder="逗号分隔，如 SN001,SN002" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" :disabled="form.lines.length <= 1"
              @click="form.lines.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 8px">
        <el-button size="small" @click="form.lines.push(blankLine())">+ 加行</el-button>
        <span class="as-of" style="margin-left: 12px">库龄快照由系统按批次最早入库日期固化；单位成本过账前须大于 0</span>
      </div>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- ============ 详情抽屉 ============ -->
    <el-drawer v-model="detailVisible" :title="`报废单：${current.scrapNo || ''}`" size="640px">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="状态">{{ statusText(current.status) }}</el-descriptions-item>
        <el-descriptions-item label="原因">
          <el-tag :type="reasonTag(current.reason)" size="small">{{ reasonText(current.reason) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="NCR">{{ current.ncrNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="仓库">{{ current.warehouseCode }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ current.totalQty }}</el-descriptions-item>
        <el-descriptions-item label="金额">{{ current.totalAmount }}</el-descriptions-item>
        <el-descriptions-item label="过账人">{{ current.postBy || '-' }}</el-descriptions-item>
        <el-descriptions-item label="核销人">{{ current.disposeBy || '-' }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">报废行（库龄快照创建时固化）</el-divider>
      <el-table :data="detailLines" border size="small" max-height="240">
        <el-table-column prop="lineNo" label="#" width="45" />
        <el-table-column prop="itemCode" label="物料" width="130" />
        <el-table-column prop="batchNo" label="批次" width="120" />
        <el-table-column prop="qty" label="数量" width="80" align="right" />
        <el-table-column prop="stockAgeDays" label="库龄(天)" width="90" align="right" />
        <el-table-column prop="unitCost" label="单位成本" width="90" align="right">
          <template #default="{ row }">{{ row.unitCost ?? '未填' }}</template>
        </el-table-column>
        <el-table-column prop="serials" label="序列" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.serials || '-' }}</template>
        </el-table-column>
      </el-table>

      <el-divider content-position="left">出入库流水与凭证</el-divider>
      <el-table :data="flows" border size="small" v-loading="flowLoading" max-height="200">
        <el-table-column prop="txnNo" label="流水号" width="150" />
        <el-table-column prop="typeCode" label="类型" width="110" />
        <el-table-column prop="batchNo" label="批次" width="110" />
        <el-table-column prop="qty" label="数量" width="70" align="right" />
        <el-table-column label="前后余额" width="110" align="right">
          <template #default="{ row }">{{ row.beforeQty }} → {{ row.afterQty }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!flowLoading && flows.length === 0" description="暂无流水" :image-size="60" />
      <div class="as-of" style="margin-top: 8px">
        凭证以来源单号 {{ current.scrapNo }} 反查（凭证一 借1901/贷1403；核销凭证二 借6711/贷1901）
      </div>
    </el-drawer>

    <!-- ============ 会签进度 ============ -->
    <el-dialog v-model="progressVisible" :title="`三方会签进度：${current.scrapNo || ''}`" width="700px">
      <el-table :data="progressNodes" border size="small" v-loading="progressLoading">
        <el-table-column prop="nodeName" label="节点" width="140" />
        <el-table-column prop="roleRequired" label="会签角色" width="190" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="nodeTag(row.status)" size="small">{{ nodeText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="signerName" label="签署人" width="110">
          <template #default="{ row }">{{ row.signerName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="opinion" label="意见" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.opinion || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="row.status === 'ACTIVE'" link type="primary" size="small"
              :loading="acting" @click="doPass(row)">通过</el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="as-of" style="margin-top: 8px">签署按角色校验（技术负责人/质量主管/财务主管），角色不符 403</div>
      <template #footer>
        <el-button @click="progressVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createScrapApi, cancelScrapApi, submitScrapApprovalApi, approveScrapApi,
  postScrapApi, disposeScrapApi, getScrapApi, getScrapsApi
} from '@/api/inv/scrap'
import { getTransactionsByDocApi } from '@/api/inv/transaction'
import { getQmsApprovalLogsApi, passQmsApprovalApi } from '@/api/qms/approval'
import { getEnabledWarehousesApi } from '@/api/inv/warehouse'
import { getItemOptionsApi } from '@/api/mdm/item'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const loading = ref(false)
const acting = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ status: '', keyword: '', current: 1, size: 20 })

const warehouses = ref([])
const itemOptions = ref([])

const createVisible = ref(false)
const form = ref({ reason: 'STALE', ncrNo: '', warehouseCode: '', remark: '', lines: [] })

const detailVisible = ref(false)
const current = ref({})
const detailLines = ref([])
const flowLoading = ref(false)
const flows = ref([])

const progressVisible = ref(false)
const progressLoading = ref(false)
const progressNodes = ref([])

const canOperate = computed(() => {
  const roles = userStore.userInfo?.roles || []
  return roles.includes('ROLE_ADMIN') || roles.includes('ROLE_WAREHOUSE')
})

function blankLine() {
  return { itemCode: '', batchNo: '', qty: 1, unitCost: null, serials: '' }
}
function reasonText(r) {
  return { STALE: '呆滞', QUALITY: '质量', DAMAGE: '损坏', OTHER: '其他' }[r] || r || '-'
}
function reasonTag(r) {
  return { STALE: 'warning', QUALITY: 'danger', DAMAGE: 'primary', OTHER: 'info' }[r] || 'info'
}
function statusText(s) {
  return { DRAFT: '草稿', APPROVED: '已批准', POSTED: '已过账', DISPOSED: '已核销', CANCELLED: '已作废' }[s] || s || '-'
}
function statusTag(s) {
  return { DRAFT: 'info', APPROVED: 'success', POSTED: 'warning', DISPOSED: 'success', CANCELLED: 'danger' }[s] || 'info'
}
function nodeText(s) {
  return { ACTIVE: '待签署', PASSED: '已通过', REJECTED: '已驳回', SKIPPED: '跳过' }[s] || s
}
function nodeTag(s) {
  return { ACTIVE: 'warning', PASSED: 'success', REJECTED: 'danger', SKIPPED: 'info' }[s] || 'info'
}
function errMsg(e, fallback) {
  return e?.response?.data?.message || e?.message || fallback
}

async function loadData() {
  loading.value = true
  try {
    const params = { current: query.value.current, size: query.value.size }
    if (query.value.status) params.status = query.value.status
    if (query.value.keyword) params.keyword = query.value.keyword
    const res = await getScrapsApi(params)
    rows.value = res.data.rows || []
    total.value = Number(res.data.total || 0)
  } finally {
    loading.value = false
  }
}
function reload() {
  query.value.current = 1
  loadData()
}
function onPage(p) {
  query.value.current = p
  loadData()
}

async function loadRefs() {
  const [wh, items] = await Promise.all([getEnabledWarehousesApi(), getItemOptionsApi()])
  warehouses.value = wh.data || []
  itemOptions.value = items.data || []
}

function openCreate() {
  form.value = { reason: 'STALE', ncrNo: '', warehouseCode: '', remark: '', lines: [blankLine()] }
  createVisible.value = true
}
async function doCreate() {
  const f = form.value
  if (!f.warehouseCode) return ElMessage.warning('请选择报废仓库')
  if (f.reason === 'QUALITY' && !f.ncrNo?.trim()) return ElMessage.warning('质量原因必填 NCR 关联号')
  for (const l of f.lines) {
    if (!l.itemCode) return ElMessage.warning('行物料必填')
    if (!l.batchNo?.trim()) return ElMessage.warning('报废须指定批次')
    if (!(l.qty > 0)) return ElMessage.warning('行数量须大于 0')
  }
  acting.value = true
  try {
    const res = await createScrapApi({ head: f, lines: f.lines })
    if (res.code === 200) {
      ElMessage.success(`报废单 ${res.data.order.scrapNo} 已创建（草稿）`)
      createVisible.value = false
      reload()
    } else {
      ElMessage.error(res.message || '创建失败')
    }
  } catch (e) {
    ElMessage.error(errMsg(e, '创建失败'))
  } finally {
    acting.value = false
  }
}

async function doSubmitApproval(row) {
  await ElMessageBox.confirm(
    `提交 ${row.scrapNo} 三方会签？（技术负责人 + 质量主管 + 财务主管，全部通过方可过账，C-4.4-14）`,
    '提交会签', { type: 'warning' })
  acting.value = true
  try {
    const res = await submitScrapApprovalApi(row.id)
    if (res.code === 200) {
      ElMessage.success('已提交三方会签')
      reload()
      openProgress({ id: row.id, scrapNo: row.scrapNo })
    } else {
      ElMessage.error(res.message || '提交失败')
    }
  } catch (e) {
    ElMessage.error(errMsg(e, '提交失败'))
  } finally {
    acting.value = false
  }
}

async function doApprove(row) {
  await ElMessageBox.confirm(`批准 ${row.scrapNo}？（${reasonText(row.reason)}原因免会签，批准后可过账）`,
    '批准报废单', { type: 'warning' })
  acting.value = true
  try {
    const res = await approveScrapApi(row.id)
    if (res.code === 200) {
      ElMessage.success('已批准')
      reload()
    } else {
      ElMessage.error(res.message || '批准失败')
    }
  } catch (e) {
    ElMessage.error(errMsg(e, '批准失败'))
  } finally {
    acting.value = false
  }
}

async function doPost(row) {
  await ElMessageBox.confirm(
    `确认对 ${row.scrapNo} 执行报废出库过账？（扣减库存、生成凭证一：借1901/贷1403）`,
    '报废过账', { type: 'warning' })
  acting.value = true
  try {
    const res = await postScrapApi(row.id)
    if (res.code === 200) {
      ElMessage.success('过账成功，已生成报废流水与凭证一')
      reload()
    } else {
      ElMessage.error(res.message || '过账失败')
    }
  } catch (e) {
    ElMessage.error(errMsg(e, '过账失败'))
  } finally {
    acting.value = false
  }
}

async function doDispose(row) {
  await ElMessageBox.confirm(
    `确认 ${row.scrapNo} 处置核销？（生成凭证二：借6711/贷1901，核销后只读）`,
    '处置核销', { type: 'warning' })
  acting.value = true
  try {
    const res = await disposeScrapApi(row.id)
    if (res.code === 200) {
      ElMessage.success('已核销，两步凭证成链')
      reload()
    } else {
      ElMessage.error(res.message || '核销失败')
    }
  } catch (e) {
    ElMessage.error(errMsg(e, '核销失败'))
  } finally {
    acting.value = false
  }
}

async function doCancel(row) {
  const { value } = await ElMessageBox.prompt('作废原因（必填）', '作废报废单',
    { inputPattern: /\S+/, inputErrorMessage: '原因必填' })
  try {
    await cancelScrapApi(row.id, value)
    ElMessage.success('已作废')
    reload()
  } catch (e) {
    ElMessage.error(errMsg(e, '作废失败'))
  }
}

async function openDetail(row) {
  current.value = row
  detailVisible.value = true
  detailLines.value = []
  flows.value = []
  flowLoading.value = true
  try {
    const res = await getScrapApi(row.id)
    detailLines.value = res.data.lines || []
    const fr = await getTransactionsByDocApi('SCRAP', row.scrapNo)
    flows.value = fr.data || []
  } finally {
    flowLoading.value = false
  }
}

async function openProgress(row) {
  current.value = row
  progressVisible.value = true
  progressLoading.value = true
  progressNodes.value = []
  try {
    const res = await getQmsApprovalLogsApi('Scrap', row.id)
    const logs = res.data || {}
    progressNodes.value = logs.tasks || logs.nodes || []
  } finally {
    progressLoading.value = false
  }
}
async function doPass(node) {
  await ElMessageBox.confirm(`通过「${node.nodeName}」会签节点？`, '会签签署', { type: 'warning' })
  acting.value = true
  try {
    const res = await passQmsApprovalApi(node.id, '同意报废')
    if (res.code === 200) {
      ElMessage.success('已签署')
      openProgress(current.value)
      reload()
    } else {
      ElMessage.error(res.message || '签署失败')
    }
  } catch (e) {
    ElMessage.error(errMsg(e, '签署失败（角色不符 403？）'))
  } finally {
    acting.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadRefs(), loadData()])
})
</script>

<style scoped>
.page { padding: 4px; }
.filter-bar { margin-bottom: 4px; }
.pager { margin-top: 12px; justify-content: flex-end; }
.as-of { color: #909399; font-size: 12px; }
</style>
