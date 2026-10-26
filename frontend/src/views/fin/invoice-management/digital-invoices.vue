<template>
  <div class="page">
    <div class="head">
      <span class="title">9.2.3 数电发票（额度 → 请求队列 → 补开 → 勾稽）</span>
      <div>
        <el-button size="small" @click="openQuota">登记额度</el-button>
        <el-button type="primary" size="small" @click="openSubmit">提交开票请求</el-button>
        <el-button type="success" size="small" @click="doReconcile">执行勾稽</el-button>
      </div>
    </div>

    <el-card shadow="never" class="mb12">
      <div class="quota">
        本期额度：<b>{{ quota.totalLimit || 0 }}</b>
        ｜已用：<b>{{ quota.usedAmt || 0 }}</b>
        ｜可用：<b :class="{ danger: Number(quota.available) <= 0 }">{{ quota.available || 0 }}</b>
        <el-tag v-if="!quota.exists" type="warning" size="small" class="ml8">本期未登记额度——提交请求将被阻断（BR-4.14-36）</el-tag>
      </div>
    </el-card>

    <el-table :data="requests" size="small" border>
      <el-table-column prop="reqNo" label="请求号" width="160" />
      <el-table-column prop="clientName" label="客户" min-width="120" show-overflow-tooltip />
      <el-table-column prop="amount" label="金额" width="100" />
      <el-table-column prop="soNo" label="SO" width="120" />
      <el-table-column prop="idempotentKey" label="幂等键" width="200" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ISSUED' ? 'success' : 'warning'" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="invoiceNos" label="发票号" min-width="160" show-overflow-tooltip />
      <el-table-column prop="splitCount" label="张数" width="70" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button v-if="row.status === 'PENDING'" link type="success" size="small"
            @click="doIssue(row)">补开</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-card v-if="recon" shadow="never" class="mt12">
      <template #header>勾稽报告（{{ recon.period }}）</template>
      <el-alert :type="recon.allGreen ? 'success' : 'error'" :closable="false"
        :title="recon.allGreen ? '逐票勾稽全绿（漏开 0 / 重开 0 / 差异 0）' : `发现 ${recon.issueCount} 项差异`" />
      <el-table v-if="recon.issues && recon.issues.length" :data="recon.issues" size="small" class="mt8">
        <el-table-column prop="type" label="类型" width="110" />
        <el-table-column prop="detail" label="明细" min-width="280" />
      </el-table>
    </el-card>

    <!-- 额度登记 -->
    <el-dialog v-model="quotaVisible" title="授信额度登记（按税局核定向）" width="420px">
      <el-form label-width="90px" size="small">
        <el-form-item label="期间" required>
          <el-input v-model="quotaForm.period" placeholder="YYYYMM" />
        </el-form-item>
        <el-form-item label="总额度" required>
          <el-input v-model="quotaForm.totalLimit" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="quotaVisible = false">取消</el-button>
        <el-button type="primary" size="small" @click="doSaveQuota">保存</el-button>
      </template>
    </el-dialog>

    <!-- 提交请求 -->
    <el-dialog v-model="submitVisible" title="提交开票请求（额度校验+幂等键）" width="460px">
      <el-form label-width="90px" size="small">
        <el-form-item label="客户" required>
          <el-input v-model="submitForm.clientName" />
        </el-form-item>
        <el-form-item label="金额" required>
          <el-input v-model="submitForm.amount" />
        </el-form-item>
        <el-form-item label="SO 号">
          <el-input v-model="submitForm.soNo" placeholder="幂等键组成（建议填）" />
        </el-form-item>
        <el-form-item label="出库号">
          <el-input v-model="submitForm.shipNo" placeholder="幂等键组成（建议填）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="submitVisible = false">取消</el-button>
        <el-button type="primary" size="small" @click="doSubmit">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  saveQuota, getQuota, submitRequest, listRequests, issueRequest, reconcile
} from '@/api/fin/invoice-management'

const quota = ref({})
const requests = ref([])
const recon = ref(null)
const quotaVisible = ref(false)
const submitVisible = ref(false)
const quotaForm = ref({})
const submitForm = ref({})

async function load() {
  try {
    const [{ data: q }, { data: r }] = await Promise.all([getQuota(), listRequests()])
    quota.value = q
    requests.value = r
  } catch (e) { /* ignore */ }
}

function openQuota() {
  quotaForm.value = { period: new Date().toISOString().slice(0, 7).replace('-', ''), totalLimit: '' }
  quotaVisible.value = true
}

async function doSaveQuota() {
  try {
    await saveQuota(quotaForm.value)
    ElMessage.success('额度已登记')
    quotaVisible.value = false
    load()
  } catch (e) { /* 422 已提示 */ }
}

function openSubmit() {
  submitForm.value = { clientName: '', amount: '', soNo: '', shipNo: '' }
  submitVisible.value = true
}

async function doSubmit() {
  try {
    const { data } = await submitRequest(submitForm.value)
    ElMessage.success(`请求 ${data.reqNo} 已入队（PENDING）`)
    submitVisible.value = false
    load()
  } catch (e) { /* 422：额度不足/幂等已开 */ }
}

async function doIssue(row) {
  try {
    await ElMessageBox.confirm('补开将扣减授信额度并回填发票号（超限自动拆分）。确认补开？', '补开', { type: 'warning' })
    const { data } = await issueRequest(row.id)
    ElMessage.success(`已开具 ${data.splitCount} 张：${data.invoiceNos}（交付记录已自动创建）`)
    load()
  } catch (e) { /* 取消或 422 */ }
}

async function doReconcile() {
  try {
    const { data } = await reconcile()
    recon.value = data
  } catch (e) { /* ignore */ }
}

onMounted(() => { load() })
</script>

<style scoped>
.page { padding: 12px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-size: 16px; font-weight: 600; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
.mt8 { margin-top: 8px; }
.ml8 { margin-left: 8px; }
.quota { font-size: 13px; }
.danger { color: #f56c6c; }
</style>
