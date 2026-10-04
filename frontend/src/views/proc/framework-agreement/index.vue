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
          </div>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
        title="采购框架协议（FR-4.2-10-3 / BR-4.2-05）：招标公示期满无异议后自动生成，含中标单价、份额分配、有效期。"
        description="协议编号与关联招标编号唯一，重复触发只生成一次；中标单价与份额锁定，直接修改将被 422 拒绝，须走协议变更审批（变更前后值、操作人与时间全程留痕）。" />

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="agreementNo" label="协议编号" width="150">
          <template #default="{ row }"><b>{{ row.agreementNo }}</b></template>
        </el-table-column>
        <el-table-column prop="tenderNo" label="关联招标" width="160" />
        <el-table-column prop="title" label="名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="effectiveDate" label="生效日" width="110" />
        <el-table-column prop="expireDate" label="到期日" width="110" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lineCount" label="明细行" width="80" align="center" />
        <el-table-column prop="totalShare" label="份额合计" width="90" align="center" />
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
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
          <el-descriptions-item label="关联招标">{{ detail.agreement.tenderNo }}</el-descriptions-item>
          <el-descriptions-item label="生效日">{{ detail.agreement.effectiveDate }}</el-descriptions-item>
          <el-descriptions-item label="到期日">{{ detail.agreement.expireDate }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(detail.agreement.status)" size="small">{{ statusName(detail.agreement.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="份额合计">{{ detail.agreement.totalShare }}%</el-descriptions-item>
          <el-descriptions-item v-if="detail.agreement.changeReason" label="最近变更" :span="2">
            <span style="font-size: 12px;">{{ detail.agreement.changeReason }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-table :data="detail.lines || []" size="small" border>
          <el-table-column label="#" width="50" align="center">
            <template #default="{ row }">{{ row.lineNo }}</template>
          </el-table-column>
          <el-table-column prop="itemCode" label="物料" width="140" />
          <el-table-column prop="itemName" label="名称" min-width="110" show-overflow-tooltip />
          <el-table-column prop="awardSupplierName" label="中标方" width="130" />
          <el-table-column label="中标单价" width="110" align="right">
            <template #default="{ row }"><b>{{ row.unitPrice }}</b></template>
          </el-table-column>
          <el-table-column label="份额" width="80" align="center">
            <template #default="{ row }">{{ row.sharePct }}%</template>
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
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onActivated } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getAgreementsApi, getAgreementDetailApi, updateAgreementLineApi, changeAgreementLineApi
} from '@/api/proc/framework-agreement'

const userStore = useUserStore()
const isAdmin = (userStore.userInfo?.roles || []).includes('ROLE_ADMIN')
const loading = ref(false)
const saving = ref(false)

const statusNames = { '0': '未生效', '1': '生效中', '2': '已过期', '3': '已停用' }
function statusName(s) { return statusNames[s] || s }
function statusTag(s) { return { '0': 'info', '1': 'success', '2': 'warning', '3': 'info' }[s] }

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
</style>
