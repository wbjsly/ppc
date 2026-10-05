<template>
  <div class="framework-agreement">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">框架协议</span>
          <div class="header-actions">
            <el-select v-model="listQuery.status" placeholder="状态" clearable style="width: 140px;" @change="loadList(1)">
              <el-option v-for="(n, v) in statusNames" :key="v" :label="n" :value="v" />
            </el-select>
            <el-input v-model="listQuery.keyword" placeholder="协议号/招标号/名称" clearable style="width: 200px;" @keyup.enter="loadList(1)" />
            <el-button type="primary" @click="loadList(1)">查询</el-button>
            <el-button type="success" @click="openCreate">手工创建协议</el-button>
          </div>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
        title="采购框架协议（FR-4.2-10-3 / BR-4.2-05 / L1059）：招标公示期满自动生成，或手工创建（design D5）。"
        description="状态机：生效中 → 临期(到期≤30天提醒续签) → 已到期 / 已终止；到期后新 PO 不可引用（已有 PO 正常执行）。价格与份额锁定，直接修改 422，须走变更审批留痕；行含承诺量/已下单余量（S-4.2-03）。" />

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="agreementNo" label="协议编号" width="150">
          <template #default="{ row }"><b>{{ row.agreementNo }}</b></template>
        </el-table-column>
        <el-table-column prop="tenderNo" label="关联招标" width="150">
          <template #default="{ row }">{{ row.tenderNo || '—' }}</template>
        </el-table-column>
        <el-table-column label="来源" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.source === 'MANUAL' ? 'warning' : 'success'">
              {{ row.source === 'MANUAL' ? '手工' : '招标' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="effectiveDate" label="生效日" width="110" />
        <el-table-column prop="expireDate" label="到期日" width="110" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lineCount" label="明细行" width="80" align="center" />
        <el-table-column prop="totalShare" label="份额合计" width="90" align="center" />
        <el-table-column label="可下单" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.usable ? 'success' : 'danger'">{{ row.usable ? '可引用' : '阻断' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="warning" :disabled="!['2', '3'].includes(row.status)"
              @click="doRenew(row)">续签</el-button>
            <el-button link type="danger" :disabled="!['1', '2'].includes(row.status) || !isAdmin"
              @click="doStop(row)">终止</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination layout="total, prev, pager, next" style="margin-top: 12px; justify-content: flex-end;"
        :total="total" :page-size="10" :current-change="loadList" />
    </el-card>

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" :title="detail.agreement ? `${detail.agreement.agreementNo} ${detail.agreement.title || ''}` : '详情'" width="720px">
      <template v-if="detail.agreement">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px;">
          <el-descriptions-item label="协议编号">{{ detail.agreement.agreementNo }}</el-descriptions-item>
          <el-descriptions-item label="来源">
            {{ detail.agreement.source === 'MANUAL' ? '手工创建' : '招标生成' }}
            <span v-if="detail.agreement.renewOf" style="color:#909399;">（续签自原协议）</span>
          </el-descriptions-item>
          <el-descriptions-item label="关联招标">{{ detail.agreement.tenderNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="生效日">{{ detail.agreement.effectiveDate }}</el-descriptions-item>
          <el-descriptions-item label="到期日">{{ detail.agreement.expireDate }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(detail.agreement.status)" size="small">{{ statusName(detail.agreement.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="份额合计">{{ detail.agreement.totalShare }}%</el-descriptions-item>
          <el-descriptions-item label="可引用">{{ detail.agreement.status === '1' || detail.agreement.status === '2' ? '可下单' : '阻断（L1059）' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.agreement.stopReason" label="终止原因" :span="2">
            <span style="font-size: 12px; color: #F56C6C;">{{ detail.agreement.stopReason }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.agreement.changeReason" label="最近变更" :span="2">
            <span style="font-size: 12px;">{{ detail.agreement.changeReason }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-table :data="detail.lines || []" size="small" border>
          <el-table-column label="#" width="50" align="center">
            <template #default="{ row }">{{ row.lineNo }}</template>
          </el-table-column>
          <el-table-column prop="itemCode" label="物料" width="130" />
          <el-table-column prop="itemName" label="名称" min-width="100" show-overflow-tooltip />
          <el-table-column prop="awardSupplierName" label="中标方" width="110" show-overflow-tooltip />
          <el-table-column label="单价" width="95" align="right">
            <template #default="{ row }"><b>{{ row.unitPrice }}</b></template>
          </el-table-column>
          <el-table-column label="区间" width="110" align="center">
            <template #default="{ row }">
              <template v-if="row.priceMin != null">{{ row.priceMin }}~{{ row.priceMax }}</template>
              <span v-else style="color:#C0C4CC;">锁定价</span>
            </template>
          </el-table-column>
          <el-table-column label="份额" width="70" align="center">
            <template #default="{ row }">{{ row.sharePct }}%</template>
          </el-table-column>
          <el-table-column label="承诺量" width="85" align="right">
            <template #default="{ row }">{{ row.commitQty == null ? '不限量' : row.commitQty }}</template>
          </el-table-column>
          <el-table-column label="已下单" width="85" align="right">
            <template #default="{ row }">{{ row.orderedQty || 0 }}</template>
          </el-table-column>
          <el-table-column label="剩余" width="85" align="right">
            <template #default="{ row }">
              <span :style="{ color: remain(row) <= 0 ? '#F56C6C' : '#67C23A' }">
                {{ row.commitQty == null ? '∞' : remain(row) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="!isAdmin" @click="openChange(row)">变更</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-alert type="warning" :closable="false" style="margin-top: 10px;"
          title="价格与份额已锁定（FR-4.2-10-3）"
          description="直接修改明细单价/份额会被后端 422 拒绝；请通过「变更」提交，经审批后生效并留痕。" />
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 变更 -->
    <el-dialog v-model="changeVisible" title="协议变更（经审批）" width="460px" :close-on-click-modal="false">
      <el-alert v-if="changeMsg" type="error" show-icon :closable="false" :title="changeMsg" style="margin-bottom: 10px;" />
      <el-form label-width="90px">
        <el-form-item label="物料">
          <el-input :model-value="changeForm.itemCode" disabled />
        </el-form-item>
        <el-form-item label="新单价">
          <el-input-number v-model="changeForm.unitPrice" :min="0" :precision="4" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="新份额">
          <el-input-number v-model="changeForm.sharePct" :min="0" :max="100" :precision="2" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="审批意见" required>
          <el-input v-model="changeForm.note" type="textarea" :rows="2" placeholder="变更原因（不少于 2 字）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="changeVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitChange">提交变更</el-button>
      </template>
    </el-dialog>

    <!-- 手工创建协议（design D5） -->
    <el-dialog v-model="createVisible" title="手工创建协议（创建即生效，Σ份额=100%）" width="800px"
      :close-on-click-modal="false" destroy-on-close>
      <el-form label-width="90px" style="margin-bottom: 8px;">
        <div style="display: flex; gap: 12px;">
          <el-form-item label="协议名称" required style="flex: 1;">
            <el-input v-model="createForm.title" placeholder="如 2026 年度管件框架" />
          </el-form-item>
          <el-form-item label="生效日">
            <el-date-picker v-model="createForm.effectiveDate" type="date" value-format="YYYY-MM-DD"
              placeholder="默认当日" />
          </el-form-item>
          <el-form-item label="到期日">
            <el-date-picker v-model="createForm.expireDate" type="date" value-format="YYYY-MM-DD"
              placeholder="默认 +12 月" />
          </el-form-item>
        </div>
      </el-form>
      <el-table :data="createForm.lines" size="small" border>
        <el-table-column label="#" width="46" align="center">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column label="物料" width="170">
          <template #default="{ row }">
            <el-select v-model="row.itemCode" filterable allow-create default-first-option placeholder="选择或输入" style="width: 100%;">
              <el-option v-for="o in itemOptions" :key="o.code" :label="`[${o.code}] ${o.name}`" :value="o.code" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="供应商" width="150">
          <template #default="{ row }">
            <el-select v-model="row.supplierId" filterable placeholder="选择" style="width: 100%;">
              <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="单价" width="115">
          <template #default="{ row }">
            <el-input-number v-model="row.unitPrice" :min="0" :precision="4" size="small" style="width: 105px;" />
          </template>
        </el-table-column>
        <el-table-column label="区间下限" width="110">
          <template #default="{ row }">
            <el-input-number v-model="row.priceMin" :min="0" :precision="4" size="small" style="width: 100px;" />
          </template>
        </el-table-column>
        <el-table-column label="区间上限" width="110">
          <template #default="{ row }">
            <el-input-number v-model="row.priceMax" :min="0" :precision="4" size="small" style="width: 100px;" />
          </template>
        </el-table-column>
        <el-table-column label="份额%" width="100">
          <template #default="{ row }">
            <el-input-number v-model="row.sharePct" :min="0" :max="100" :precision="2" size="small" style="width: 90px;" />
          </template>
        </el-table-column>
        <el-table-column label="承诺量" width="100">
          <template #default="{ row }">
            <el-input-number v-model="row.commitQty" :min="0" :precision="2" size="small" style="width: 90px;" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" @click="createForm.lines.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 8px; display: flex; gap: 12px; align-items: center;">
        <el-button size="small" @click="createForm.lines.push({ itemCode: '', supplierId: '', unitPrice: 0, priceMin: null, priceMax: null, sharePct: 0, commitQty: null })">+ 加行</el-button>
        <span :style="{ color: createSum === 100 ? '#67C23A' : '#F56C6C', fontWeight: 600 }">
          份额合计：{{ createSum.toFixed(2) }}% {{ createSum === 100 ? '✓' : '（须 = 100）' }}
        </span>
        <span class="hint">区间选填（上下限须成对且含单价）；承诺量空 = 不限量</span>
      </div>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="createSum !== 100" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onActivated } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getAgreementsApi, getAgreementDetailApi, updateAgreementLineApi, changeAgreementLineApi,
  createAgreementApi, renewAgreementApi, stopAgreementApi
} from '@/api/proc/framework-agreement'
import { getSupplierPageApi } from '@/api/mdm/supplier-admission'
import { getItemOptionsApi } from '@/api/mdm/item'

const userStore = useUserStore()
const isAdmin = (userStore.userInfo?.roles || []).includes('ROLE_ADMIN')
const loading = ref(false)
const saving = ref(false)

// design D4 状态机：1 生效 / 2 临期 / 3 已到期 / 4 已终止
const statusNames = { '1': '生效中', '2': '临期', '3': '已到期', '4': '已终止' }
function statusName(s) { return statusNames[s] || s }
function statusTag(s) { return { '1': 'success', '2': 'warning', '3': 'info', '4': 'danger' }[s] }

/** 行剩余量 = 承诺量 − 已下单量（S-4.2-03） */
function remain(row) {
  if (row.commitQty == null) return Infinity
  return Number(row.commitQty) - Number(row.orderedQty || 0)
}

// ---- 续签 / 终止（spec「续签与终止」） ----
async function doRenew(row) {
  try {
    await ElMessageBox.confirm(
      `续签 ${row.agreementNo}？将生成新协议（继承行结构与份额），原协议置为已到期。`,
      '协议续签', { type: 'warning' })
    const { data } = await renewAgreementApi(row.id)
    ElMessage.success(`已续签：${data.agreement.agreementNo}（原 ${data.renewedFrom} → 已到期）`)
    loadList()
  } catch { /* 取消 */ }
}

async function doStop(row) {
  try {
    const { value } = await ElMessageBox.prompt(
      '终止原因（必填，终止后不可逆、不可再下单）', `终止 ${row.agreementNo}`,
      { inputPattern: /^.{2,}$/, inputErrorMessage: '原因不少于 2 字' })
    await stopAgreementApi(row.id, value)
    ElMessage.success('已终止，该协议不可再引用（L1059）')
    loadList()
  } catch { /* 取消 */ }
}

// ---- 手工创建（design D5） ----
const createVisible = ref(false)
const suppliers = ref([])
const itemOptions = ref([])
const createForm = reactive({
  title: '', effectiveDate: '', expireDate: '',
  lines: [{ itemCode: '', supplierId: '', unitPrice: 0, priceMin: null, priceMax: null, sharePct: 0, commitQty: null }]
})
const createSum = computed(() =>
  createForm.lines.reduce((s, r) => s + (Number(r.sharePct) || 0), 0))

async function openCreate() {
  createForm.title = ''
  createForm.effectiveDate = ''
  createForm.expireDate = ''
  createForm.lines = [{ itemCode: '', supplierId: '', unitPrice: 0, priceMin: null, priceMax: null, sharePct: 0, commitQty: null }]
  createVisible.value = true
  const [{ data: sups }, { data: items }] = await Promise.all([
    getSupplierPageApi({ current: 1, size: 100 }), getItemOptionsApi()
  ])
  suppliers.value = sups.records || sups || []
  itemOptions.value = items || []
}

async function submitCreate() {
  if (createSum.value !== 100) { ElMessage.warning('份额合计须等于 100%'); return }
  saving.value = true
  try {
    const { data } = await createAgreementApi({
      title: createForm.title,
      effectiveDate: createForm.effectiveDate || undefined,
      expireDate: createForm.expireDate || undefined,
      lines: createForm.lines.map(l => ({
        itemCode: l.itemCode, supplierId: l.supplierId, unitPrice: l.unitPrice,
        priceMin: l.priceMin ?? undefined, priceMax: l.priceMax ?? undefined,
        sharePct: l.sharePct, commitQty: l.commitQty ?? undefined
      }))
    })
    ElMessage.success(`已创建 ${data.agreement.agreementNo}（${data.lineCount} 行，生效中）`)
    createVisible.value = false
    loadList()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '创建失败')
  } finally {
    saving.value = false
  }
}

const listQuery = ref({ status: '', keyword: '' })
const rows = ref([])
const total = ref(0)

async function loadList(page) {
  loading.value = true
  try {
    const res = await getAgreementsApi({
      keyword: listQuery.value.keyword || undefined,
      status: listQuery.value.status || undefined
    })
    rows.value = res.data || []
    total.value = rows.value.length
  } finally {
    loading.value = false
  }
}

const detailVisible = ref(false)
const detail = reactive({ agreement: null, lines: [] })

async function openDetail(row) {
  const res = await getAgreementDetailApi(row.id)
  Object.assign(detail, res.data)
  detailVisible.value = true
}

// ---- 直接改（后端固定 422，用于验证锁定） ----
async function tryDirectEdit(row) {
  try {
    await updateAgreementLineApi(row.id, { unitPrice: 1 })
    ElMessage.error('锁定未生效，应返回 422')
  } catch (e) {
    ElMessage.info(e?.message || '价格与份额已锁定')
  }
}
defineExpose({ tryDirectEdit })

// ---- 变更 ----
const changeVisible = ref(false)
const changeMsg = ref('')
const changeForm = reactive({ lineId: '', itemCode: '', unitPrice: null, sharePct: null, note: '' })

function openChange(row) {
  changeMsg.value = ''
  Object.assign(changeForm, {
    lineId: row.id, itemCode: row.itemCode,
    unitPrice: row.unitPrice, sharePct: row.sharePct, note: ''
  })
  changeVisible.value = true
}

async function submitChange() {
  changeMsg.value = ''
  saving.value = true
  try {
    const res = await changeAgreementLineApi(detail.agreement.id, changeForm.lineId, {
      unitPrice: changeForm.unitPrice, sharePct: changeForm.sharePct, note: changeForm.note
    })
    ElMessage.success('变更已生效')
    detail.agreement.changeReason = res.data.changeReason
    changeVisible.value = false
    await openDetail({ id: detail.agreement.id })
    await loadList()
  } catch (e) {
    changeMsg.value = e?.message || '变更失败'
  } finally {
    saving.value = false
  }
}

onMounted(loadList)
onActivated(loadList)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.hint { color: #909399; font-size: 12px; }
</style>
