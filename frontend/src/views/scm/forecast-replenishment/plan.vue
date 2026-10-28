<template>
  <div class="cpfr-plan">
    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="agreementId" placeholder="协议（可选）" clearable style="width:240px">
          <el-option v-for="a in agreements" :key="a.id" :value="a.id" :label="a.supplierName" />
        </el-select>
        <el-input v-model="periodTag" placeholder="周期（可选）" clearable style="width:130px" />
        <el-button type="primary" @click="load">查询</el-button>
        <el-button v-if="agreementId && periodTag" type="success" @click="doCreate">
          生成联合补货计划
        </el-button>
        <el-button @click="doAccuracy">月度准确率评估</el-button>
      </div>

      <el-empty v-if="!plans.length" description="暂无联合补货计划" />
      <el-card v-for="p in plans" :key="p.id" class="plan-card" shadow="hover">
        <div class="plan-head">
          <span class="plan-title">{{ p.periodTag }} · {{ supplierName(p.agreementId) }}</span>
          <el-tag :type="planStatusType(p.status)">{{ p.status }}</el-tag>
          <span class="confirm-info">
            供应商确认：{{ p.supplierConfirmAt || '未确认' }} |
            企业方确认：{{ p.internalConfirmAt || '未确认' }}
          </span>
          <span v-if="p.accuracyPct != null" class="accuracy">
            月度准确率（{{ p.accuracyMonth }}）：{{ p.accuracyPct }}%
          </span>
          <span class="spacer" />
          <el-button v-if="p.supplierConfirmAt == null" size="small"
                     @click="confirmPlan(p, 'SUPPLIER')">供应商确认</el-button>
          <el-button v-if="p.internalConfirmAt == null" size="small" type="primary"
                     @click="confirmPlan(p, 'INTERNAL')">企业方确认</el-button>
        </div>
        <el-table :data="p.lines" border stripe size="small">
          <el-table-column prop="itemCode" label="物料" width="110" />
          <el-table-column prop="itemName" label="名称" min-width="120" />
          <el-table-column prop="weekOffset" label="周" width="60" />
          <el-table-column prop="qty" label="锁定数量" width="100" />
          <el-table-column label="ZONE" width="90">
            <template #default="{ row }">
              <el-tag :type="zoneType(row.zone)">{{ zoneLabel(row.zone) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="poConversionQty" label="实际到货" width="95" />
          <el-table-column prop="adjustStatus" label="调整状态" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.adjustStatus === 'PENDING_APPROVAL'" type="warning">审批中</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="240" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small"
                         :disabled="row.adjustStatus === 'PENDING_APPROVAL'"
                         @click="openAdjust(row)">调整</el-button>
              <el-button link type="success" size="small" @click="openConvert(row)">转化登记</el-button>
              <el-button link type="warning" size="small" @click="openPoJump(row)">生成 PO</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </el-card>

    <!-- 调整对话框 -->
    <el-dialog v-model="adjustVisible" title="数量调整" width="460px">
      <el-alert type="info" :closable="false" style="margin-bottom:12px"
                :title="adjustZoneHint" />
      <el-form label-width="90px">
        <el-form-item label="新数量" required>
          <el-input-number v-model="adjustForm.qty" :min="0" :precision="3" style="width:180px" />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="adjustForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" @click="doAdjust">提交</el-button>
      </template>
    </el-dialog>

    <!-- 转化登记 -->
    <el-dialog v-model="convertVisible" title="转化登记（实际到货量）" width="420px">
      <el-form label-width="100px">
        <el-form-item label="实际到货量" required>
          <el-input-number v-model="convertForm.actualQty" :min="0" :precision="3" style="width:180px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="convertForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="convertVisible = false">取消</el-button>
        <el-button type="primary" @click="doConvert">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as cpfr from '@/api/scm/cpfr'

const agreements = ref([])
const agreementId = ref('')
const periodTag = ref('')
const plans = ref([])

async function loadAgreements() {
  const res = await cpfr.agreementPage({ size: 100 })
  agreements.value = res.data.records || []
}
function supplierName(agrId) {
  return agreements.value.find(a => a.id === agrId)?.supplierName || agrId
}
async function load() {
  const res = await cpfr.planList({ agreementId: agreementId.value, periodTag: periodTag.value })
  plans.value = res.data || []
}
async function doCreate() {
  try {
    await cpfr.createPlan({ agreementId: agreementId.value, periodTag: periodTag.value })
    ElMessage.success('联合补货计划已生成（ZONE 已按协议标注）')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message)
  }
}
async function confirmPlan(p, side) {
  try {
    await cpfr.confirmPlan(p.id, { side })
    ElMessage.success('已确认（留痕）')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message)
  }
}

// ---- 调整 ----
const adjustVisible = ref(false)
const adjustForm = ref({})
const currentLine = ref(null)
const adjustZoneHint = computed(() => {
  const z = currentLine.value?.zone
  if (z === 'FREEZE') return 'FREEZE 冻结区：调整将提交采购经理（PMGR）L2 审批，审批通过前原数量保持锁定'
  if (z === 'ELASTIC') return 'ELASTIC 弹性区：超约定比例将升级 L2 审批；区间内免审批但留版本记录'
  return 'FREE 自由区：调整免审批直接生效（留痕）'
})
function openAdjust(row) {
  currentLine.value = row
  adjustForm.value = { qty: row.qty, remark: '' }
  adjustVisible.value = true
}
async function doAdjust() {
  try {
    const res = await cpfr.adjustPlanLine(currentLine.value.id, adjustForm.value)
    ElMessage.success(res.data.approvalId
      ? `已提交 L2 审批（实例 ${res.data.approvalId}）`
      : '调整已生效（免审批留痕）')
    adjustVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message)
  }
}

// ---- 转化登记 ----
const convertVisible = ref(false)
const convertForm = ref({})
function openConvert(row) {
  currentLine.value = row
  convertForm.value = { actualQty: null, remark: '' }
  convertVisible.value = true
}
async function doConvert() {
  await cpfr.convertPlanLine(currentLine.value.id, convertForm.value)
  ElMessage.success('转化已登记')
  convertVisible.value = false
  load()
}

// ---- 生成 PO 跳转 ----
async function openPoJump(row) {
  const res = await cpfr.poJumpParams(row.id)
  const d = res.data
  const qs = new URLSearchParams({
    supplierId: d.supplierId || '',
    itemCode: d.itemCode || '',
    qty: d.qty ?? '',
    sourceRef: d.sourceRef || ''
  }).toString()
  ElMessageBox.alert(
    `将跳转订单下达页并预填：\n供应商 ${d.supplierName} / 物料 ${d.itemCode} / 数量 ${d.qty}\n来源 ${d.sourceRef}`,
    '生成 PO（跳转预填）'
  ).then(() => { window.location.hash = `#${d.jumpPath}?${qs}` })
    .catch(() => {})
}

// ---- 准确率 ----
async function doAccuracy() {
  const { value } = await ElMessageBox.prompt('评估月份', '月度准确率评估', {
    inputValue: new Date().toISOString().slice(0, 7).replace('-', '')
  })
  const res = await cpfr.evaluateAccuracy(value)
  ElMessage.success(`评估完成：计划 ${res.data.plans} 条；待复盘协议 ${res.data.reviewTriggered?.length || 0} 个`)
  load()
}

function planStatusType(s) {
  return { 'DRAFT': 'info', '单方确认': 'warning', '双方确认': 'primary', '已生成订单': 'success' }[s] || 'info'
}
function zoneType(z) {
  return { FREEZE: 'danger', ELASTIC: 'warning', FREE: 'success' }[z] || 'info'
}
function zoneLabel(z) {
  return { FREEZE: '冻结区', ELASTIC: '弹性区', FREE: '自由区' }[z] || z
}

onMounted(async () => {
  await loadAgreements()
  load()
})
</script>

<style scoped>
.cpfr-plan { padding: 4px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.plan-card { margin-bottom: 14px; }
.plan-head { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; flex-wrap: wrap; }
.plan-title { font-weight: 600; }
.confirm-info, .accuracy { color: #606266; font-size: 12px; }
.spacer { flex: 1; }
</style>
