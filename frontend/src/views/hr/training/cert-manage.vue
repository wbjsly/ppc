<template>
  <div class="page">
    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-header">
          <span>证书类别字典（关键标记决定排班门禁范围，BR-4.7-06）</span>
          <el-button type="primary" size="small" @click="openCate()">新建类别</el-button>
        </div>
      </template>
      <el-table :data="categories" size="small" border>
        <el-table-column prop="cateCode" label="编码" width="100" />
        <el-table-column prop="cateName" label="名称" min-width="160" />
        <el-table-column label="关键" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.isKey === '1' ? 'danger' : 'info'">
              {{ row.isKey === '1' ? '关键(拦排班)' : '非关键(仅提醒)' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="映射技能" width="160">
          <template #default="{ row }">
            <span v-if="row.skillCode">{{ row.skillCode }} {{ row.skillLevel || '' }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
              {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openCate(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>证书台账</span>
          <div>
            <el-select v-model="ledgerStatus" size="small" style="width: 130px; margin-right: 8px" @change="loadLedger">
              <el-option label="全部状态" value="" />
              <el-option v-for="(n, s) in statusNames" :key="s" :label="n" :value="s" />
            </el-select>
            <el-button :type="pending.length ? 'warning' : 'default'" size="small" @click="loadLedger">
              刷新{{ pending.length ? `（待审 ${pending.length}）` : '' }}
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="ledgerRows" size="small" border>
        <el-table-column prop="empNo" label="工号" width="90" />
        <el-table-column prop="empName" label="姓名" width="90" />
        <el-table-column prop="cateName" label="证书类别" min-width="150" />
        <el-table-column prop="certNo" label="证书编号" width="120" />
        <el-table-column prop="issueOrg" label="发证机关" width="140" />
        <el-table-column label="有效期至" width="110">
          <template #default="{ row }">{{ row.validTo || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusNames[row.status] || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reviewOpinion" label="驳回意见" width="140" />
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button link type="success" size="small" @click="audit(row, true)">通过</el-button>
              <el-button link type="danger" size="small" @click="audit(row, false)">驳回</el-button>
            </template>
            <el-button v-if="row.status !== 'PENDING' && row.status !== 'ARCHIVED'"
              link type="warning" size="small" @click="archive(row)">换证归档</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="muted mtips">
        审核通过自动写入技能矩阵并开始到期跟踪；换证归档旧证后员工方可提交新证（同类别唯一有效）；
        失效恢复须员工重新提交并经审核（无手工改状态通道）。
      </div>
    </el-card>

    <!-- 类别编辑 -->
    <el-dialog v-model="cateVisible" :title="cateForm.id ? '编辑类别' : '新建类别（编码自动生成，创建后不可改）'" width="440px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="cateForm.cateName" /></el-form-item>
        <el-form-item label="关键类别">
          <el-switch v-model="cateForm.isKey" active-value="1" inactive-value="0" />
          <span class="muted" style="margin-left: 8px">开启后该类证书失效将拦截排班上岗</span>
        </el-form-item>
        <el-form-item label="映射技能">
          <el-input v-model="cateForm.skillCode" placeholder="技能编码（可空）" style="width: 200px" />
          <el-input v-model="cateForm.skillLevel" placeholder="默认等级" style="width: 100px; margin-left: 8px" />
        </el-form-item>
        <el-form-item v-if="cateForm.id" label="状态">
          <el-radio-group v-model="cateForm.status">
            <el-radio value="ACTIVE">启用</el-radio><el-radio value="DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cateVisible = false">取消</el-button>
        <el-button type="primary" @click="saveCate">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  certCategories, certCategoryCreate, certCategoryUpdate, certLedger, certAudit, certArchive
} from '@/api/hr/training'

const statusNames = { PENDING: '待审核', VALID: '有效', REVIEW_DUE: '临期复审', EXPIRED: '已失效',
  REJECTED: '已驳回', ARCHIVED: '已归档' }
const statusTag = (s) => ({ PENDING: 'warning', VALID: 'success', REVIEW_DUE: 'warning',
  EXPIRED: 'danger', REJECTED: 'info', ARCHIVED: 'info' }[s] || 'info')

const categories = ref([])
const ledgerRows = ref([])
const pending = ref([])
const ledgerStatus = ref('')
const cateVisible = ref(false)
const cateForm = ref({})

async function load() {
  const [{ data: cs }, { data: ls }, { data: ps }] = await Promise.all([
    certCategories(), certLedger({ status: ledgerStatus.value || undefined }), certPending()
  ])
  categories.value = cs || []
  ledgerRows.value = ls || []
  pending.value = ps || []
}
const loadLedger = load

function openCate(row) {
  cateForm.value = row ? { ...row } : { cateName: '', isKey: '0', skillCode: '', skillLevel: '' }
  cateVisible.value = true
}

async function saveCate() {
  if (!cateForm.value.cateName) { ElMessage.warning('名称必填'); return }
  try {
    if (cateForm.value.id) {
      await certCategoryUpdate(cateForm.value.id, cateForm.value)
    } else {
      await certCategoryCreate(cateForm.value)
    }
    ElMessage.success('已保存')
    cateVisible.value = false
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

async function audit(row, pass) {
  let opinion
  if (!pass) {
    try {
      const { value } = await ElMessageBox.prompt('驳回意见', '驳回证书', { inputPlaceholder: '如：照片不清晰' })
      opinion = value
    } catch { return }
  }
  try {
    await certAudit(row.id, pass, opinion)
    ElMessage.success(pass ? '已通过，进入到期跟踪并写入技能矩阵' : '已驳回')
    load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

async function archive(row) {
  try {
    await ElMessageBox.confirm(`将「${row.cateName}」旧证归档（办理换证）？归档后员工方可提交新证。`,
      '换证归档', { type: 'warning' })
    await certArchive(row.id)
    ElMessage.success('已归档')
    load()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e?.response?.data?.msg || e?.message) }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.mb12 { margin-bottom: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.muted { color: #909399; font-size: 12px; }
.mtips { margin-top: 8px; }
</style>
