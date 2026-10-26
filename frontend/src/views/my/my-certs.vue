<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>我的证书（自助端，提交后由 HR 审核生效）</span>
          <el-button type="primary" size="small" @click="openSubmit">提交新证书</el-button>
        </div>
      </template>
      <el-table :data="rows" size="small" border>
        <el-table-column label="证书类别" width="180">
          <template #default="{ row }">{{ row.cert.cateName }}</template>
        </el-table-column>
        <el-table-column label="证书编号" width="130">
          <template #default="{ row }">{{ row.cert.certNo || '—' }}</template>
        </el-table-column>
        <el-table-column label="有效期至" width="110">
          <template #default="{ row }">{{ row.cert.validTo || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="tag(row.cert.status)">{{ name(row.cert.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="到期倒计时" width="120">
          <template #default="{ row }">
            <template v-if="['VALID', 'REVIEW_DUE'].includes(row.cert.status)">
              <el-tag size="small" :type="row.daysLeft <= 60 ? 'warning' : 'success'">{{ row.daysLeft }} 天</el-tag>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="扫描件" min-width="180">
          <template #default="{ row }">
            <template v-if="row.attachments?.length">
              <a v-for="a in row.attachments" :key="a.id" :href="downloadUrl(a.id)" target="_blank"
                class="file-link">{{ a.fileName }}</a>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="驳回意见" min-width="140">
          <template #default="{ row }">
            <span class="muted">{{ row.cert.reviewOpinion || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button v-if="row.cert.status === 'PENDING' || row.cert.status === 'REJECTED'"
              link type="primary" size="small" @click="uploadFor(row.cert)">上传扫描件</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="muted mtips">
        关键岗位证书（特种作业证等）到期前 60 天系统提醒复审；失效期间将被限制排班上岗，重新提交并经 HR 审核通过后自动恢复。
      </div>
    </el-card>

    <!-- 提交新证书 -->
    <el-dialog v-model="submitVisible" title="提交证书（HR 审核通过后生效）" width="460px">
      <el-form label-width="90px">
        <el-form-item label="证书类别">
          <el-select v-model="submitForm.cateId" placeholder="选择类别" style="width: 260px">
            <el-option v-for="c in categories" :key="c.id" :label="c.cateName" :value="c.id"
              :disabled="c.status !== 'ACTIVE'" />
          </el-select>
        </el-form-item>
        <el-form-item label="证书编号"><el-input v-model="submitForm.certNo" /></el-form-item>
        <el-form-item label="发证机关"><el-input v-model="submitForm.issueOrg" /></el-form-item>
        <el-form-item label="有效期">
          <el-date-picker v-model="submitForm.validFrom" type="date" value-format="YYYY-MM-DD"
            placeholder="起" style="width: 130px" />
          <span style="margin: 0 6px">~</span>
          <el-date-picker v-model="submitForm.validTo" type="date" value-format="YYYY-MM-DD"
            placeholder="止（到期日）" style="width: 150px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitVisible = false">取消</el-button>
        <el-button type="primary" @click="doSubmit">提交</el-button>
      </template>
    </el-dialog>

    <!-- 扫描件上传 -->
    <el-dialog v-model="uploadVisible" title="上传证书扫描件" width="400px">
      <input type="file" accept=".pdf,.jpg,.jpeg,.png" @change="onFile" />
      <div class="muted mtips">支持 PDF/JPG/PNG；提交后在列表中可见并供 HR 下载查验。</div>
      <template #footer><el-button @click="uploadVisible = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { myCerts, myCertSubmit, certCategories, uploadCertFile, attachmentDownloadUrl } from '@/api/hr/training'

const rows = ref([])
const categories = ref([])
const submitVisible = ref(false)
const submitForm = ref({})
const uploadVisible = ref(false)
const uploadCertId = ref(null)

const name = (s) => ({ PENDING: '待审核', VALID: '有效', REVIEW_DUE: '临期复审',
  EXPIRED: '已失效', REJECTED: '已驳回（可修改重提）', ARCHIVED: '已归档' }[s] || s)
const tag = (s) => ({ PENDING: 'warning', VALID: 'success', REVIEW_DUE: 'warning',
  EXPIRED: 'danger', REJECTED: 'info', ARCHIVED: 'info' }[s] || 'info')
const downloadUrl = (id) => attachmentDownloadUrl(id)

async function load() {
  try { rows.value = (await myCerts()).data || [] } catch (e) { /* ignore */ }
}

async function openSubmit() {
  if (!categories.value.length) {
    categories.value = (await certCategories()).data || []
  }
  submitForm.value = { cateId: '', certNo: '', issueOrg: '', validFrom: '', validTo: '' }
  submitVisible.value = true
}

async function doSubmit() {
  if (!submitForm.value.cateId || !submitForm.value.validTo) { ElMessage.warning('类别与有效期至必填'); return }
  try {
    const { data } = await myCertSubmit(submitForm.value)
    ElMessage.success('已提交，待 HR 审核；可在列表上传扫描件')
    submitVisible.value = false
    uploadCertId.value = data.id
    uploadVisible.value = true
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

function uploadFor(cert) {
  uploadCertId.value = cert.id
  uploadVisible.value = true
}

async function onFile(ev) {
  const file = ev.target.files?.[0]
  if (!file || !uploadCertId.value) return
  try {
    await uploadCertFile(file, uploadCertId.value)
    ElMessage.success('扫描件已上传')
    uploadVisible.value = false
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.muted { color: #909399; font-size: 12px; }
.mtips { margin-top: 8px; }
.file-link { color: #409eff; margin-right: 8px; font-size: 12px; }
</style>
