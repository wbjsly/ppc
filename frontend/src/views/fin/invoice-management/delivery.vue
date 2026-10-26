<template>
  <div class="page">
    <div class="head">
      <span class="title">9.2.4 发票交付（双路登记 → 发送/重发 → 超期监控）</span>
      <div>
        <el-select v-model="query.status" clearable placeholder="状态" style="width: 110px; margin-right: 8px" size="small">
          <el-option label="待交付" value="PENDING" />
          <el-option label="已发送" value="SENT" />
          <el-option label="失败" value="FAILED" />
        </el-select>
        <el-button size="small" @click="load">查询</el-button>
        <el-button type="primary" size="small" @click="openRegister">登记交付（销项）</el-button>
      </div>
    </div>

    <el-alert v-if="overdueCount" type="warning" :closable="false" class="mb8"
      :title="`交付超期预警（L4）：${overdueCount} 条记录超期未交付`" />

    <el-table :data="rows" size="small" border>
      <el-table-column prop="invoiceNo" label="发票号" width="150" />
      <el-table-column prop="source" label="来源" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.source === 'DIGITAL' ? 'primary' : 'info'">
            {{ row.source === 'DIGITAL' ? '数电' : '销项' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="method" label="方式" width="90">
        <template #default="{ row }">{{ row.method || '待选' }}</template>
      </el-table-column>
      <el-table-column prop="contact" label="收件人/地址" min-width="140" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="stTag(row.status)" size="small">{{ stText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sentAt" label="交付时间" width="150" />
      <el-table-column prop="retryCount" label="重发" width="70" />
      <el-table-column label="超期" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.overdue" type="warning" size="small">超期</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button v-if="row.status !== 'SENT'" link type="success" size="small"
            @click="doSent(row)">标记已发送</el-button>
          <el-button v-if="row.status === 'FAILED'" link type="warning" size="small"
            @click="doResend(row)">重发</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="regVisible" title="销项交付登记" width="440px">
      <el-form label-width="90px" size="small">
        <el-form-item label="发票号" required>
          <el-input v-model="regForm.invoiceNo" placeholder="销项台账中的发票号" />
        </el-form-item>
        <el-form-item label="交付方式" required>
          <el-select v-model="regForm.method" style="width: 100%">
            <el-option label="邮件 EMAIL" value="EMAIL" />
            <el-option label="短信 SMS" value="SMS" />
            <el-option label="下载链接 LINK" value="LINK" />
            <el-option label="邮寄 MAIL" value="MAIL" />
            <el-option label="自取 PICKUP" value="PICKUP" />
          </el-select>
        </el-form-item>
        <el-form-item label="收件人">
          <el-input v-model="regForm.contact" placeholder="邮箱/手机/地址" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="regVisible = false">取消</el-button>
        <el-button type="primary" size="small" @click="doRegister">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { registerDelivery, deliveryList, markSent, resendDelivery } from '@/api/fin/invoice-management'

const query = ref({ status: '' })
const rows = ref([])
const regVisible = ref(false)
const regForm = ref({})
const overdueCount = computed(() => rows.value.filter(r => r.overdue).length)

async function load() {
  try {
    const params = {}
    if (query.value.status) params.status = query.value.status
    const { data } = await deliveryList(params)
    rows.value = data
  } catch (e) { /* ignore */ }
}

function openRegister() {
  regForm.value = { invoiceNo: '', method: 'EMAIL', contact: '' }
  regVisible.value = true
}

async function doRegister() {
  try {
    await registerDelivery(regForm.value)
    ElMessage.success('交付记录已登记')
    regVisible.value = false
    load()
  } catch (e) { /* 422 已提示 */ }
}

async function doSent(row) {
  try {
    await markSent(row.id)
    ElMessage.success('已标记发送')
    load()
  } catch (e) { /* ignore */ }
}

async function doResend(row) {
  try {
    const { data } = await resendDelivery(row.id)
    ElMessage.success(`已重发（第 ${data.retryCount} 次）`)
    load()
  } catch (e) { /* 422 超上限 */ }
}

function stText(s) {
  return { PENDING: '待交付', SENT: '已发送', FAILED: '失败' }[s] || s
}

function stTag(s) {
  return { PENDING: 'warning', SENT: 'success', FAILED: 'danger' }[s] || ''
}

onMounted(() => { load() })
</script>

<style scoped>
.page { padding: 12px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-size: 16px; font-weight: 600; }
.mb8 { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
</style>
