<template>
  <div class="page-wrap">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>12.7.3 月度报告（生成 → 审核 → 归档 → 发布；审核人=集成架构师且不得为生成人）</span>
          <span>
            <el-button size="small" type="primary" @click="openGenerate">生成报告</el-button>
            <el-button size="small" @click="$router.push('/m/12.7.1')">指标采集 12.7.1</el-button>
            <el-button size="small" @click="$router.push('/m/12.7.2')">告警升级 12.7.2</el-button>
          </span>
        </div>
      </template>

      <div class="toolbar">
        <el-select v-model="q.status" placeholder="状态" clearable size="small" style="width: 160px;" @change="load">
          <el-option label="已生成" value="GENERATED" />
          <el-option label="已审核" value="REVIEWED" />
          <el-option label="已归档" value="ARCHIVED" />
          <el-option label="已发布" value="PUBLISHED" />
        </el-select>
        <el-button size="small" type="primary" @click="load">查询</el-button>
        <span class="hint">状态机 GENERATED → REVIEWED → ARCHIVED → PUBLISHED，非法跃迁 422；未归档发布 L1 硬阻断（BR-4.9-29）</span>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="monthTag" label="月份" width="80" />
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="version" label="版本" width="70" align="center" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="mini" :type="stTag(row.status)">{{ stText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="生成" width="150">
          <template #default="{ row }">
            <span>{{ row.generatedBy || '—' }}</span>
            <div class="hint">{{ String(row.generatedAt || '').replace('T', ' ').slice(0, 16) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="审核" width="150">
          <template #default="{ row }">
            <span>{{ row.reviewedBy || '—' }}</span>
            <div class="hint">{{ String(row.reviewedAt || '').replace('T', ' ').slice(0, 16) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="归档/发布" width="160">
          <template #default="{ row }">
            <span>{{ row.archivedBy || '—' }}</span>
            <div class="hint">
              {{ String(row.archivedAt || '').replace('T', ' ').slice(0, 16) }}
              <template v-if="row.publishAt"> → 发布 {{ String(row.publishAt).replace('T', ' ').slice(0, 16) }}</template>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" @click="openPreview(row)">章节预览</el-button>
            <el-button v-if="row.status === 'GENERATED' && canReview" link type="warning" size="small"
              @click="openReview(row)">审核</el-button>
            <el-button v-if="row.status === 'REVIEWED' && isAdmin" link type="primary" size="small"
              @click="doArchive(row)">归档</el-button>
            <el-button v-if="row.status === 'ARCHIVED' && isAdmin" link type="success" size="small"
              @click="openPublish(row)">发布</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top: 10px; justify-content: flex-end;"
        layout="total, prev, pager, next" :total="total"
        v-model:current-page="q.current" :page-size="q.size" @current-change="load" />
    </el-card>

    <!-- 生成 -->
    <el-dialog v-model="genDlg" title="生成月度报告（记录当前操作人为生成人）" width="420px">
      <el-form label-width="90px">
        <el-form-item label="月份">
          <el-input v-model="genMonth" placeholder="yyyyMM，留空=上月" />
        </el-form-item>
      </el-form>
      <div class="hint">生成人将记为当前登录账号——本人不可再审核该报告（互斥，spec L5）。</div>
      <template #footer>
        <el-button @click="genDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doGenerate">生成</el-button>
      </template>
    </el-dialog>

    <!-- 审核 -->
    <el-dialog v-model="revDlg" title="审核报告（意见必填，审核人≠生成人）" width="460px">
      <el-form label-width="90px">
        <el-form-item label="报告"><span>{{ current.monthTag }} {{ current.title }}</span></el-form-item>
        <el-form-item label="审核意见" required>
          <el-input v-model="revOpinion" type="textarea" :rows="3" placeholder="必填，至少 2 字" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="revDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doReview">审核通过</el-button>
      </template>
    </el-dialog>

    <!-- 发布 -->
    <el-dialog v-model="pubDlg" title="对外发布（仅已归档；门户按章节范围可见）" width="460px">
      <el-form label-width="90px">
        <el-form-item label="报告"><span>{{ current.monthTag }} {{ current.title }}</span></el-form-item>
        <el-form-item label="发布范围">
          <el-input v-model="pubScope" placeholder="ALL 或伙伴编码（逗号分隔）" />
        </el-form-item>
      </el-form>
      <div class="hint">未归档发布将被 422 硬阻断并记录违规尝试（C-5.5-04）。</div>
      <template #footer>
        <el-button @click="pubDlg = false">取消</el-button>
        <el-button type="success" :loading="submitting" @click="doPublish">发布</el-button>
      </template>
    </el-dialog>

    <!-- 章节预览 -->
    <el-dialog v-model="prevDlg" :title="`章节预览 - ${current.monthTag || ''}`" width="760px">
      <template v-if="preview">
        <el-descriptions :column="4" border size="small" style="margin-bottom: 10px;">
          <el-descriptions-item v-for="(v, k) in preview.overview || {}" :key="k" :label="k">{{ v }}</el-descriptions-item>
        </el-descriptions>
        <div class="sub-title">伙伴章节（{{ (preview.chapters || []).length }}）</div>
        <el-table :data="preview.chapters || []" size="small" border max-height="320">
          <el-table-column prop="partnerCode" label="伙伴" width="140" />
          <el-table-column label="指标聚合" min-width="300">
            <template #default="{ row }">
              <el-tag v-for="(a, k) in row.metrics || {}" :key="k" size="mini" style="margin-right: 4px;">
                {{ k }}: avg {{ a.avg ?? '—' }}（{{ JSON.stringify(a.levels) }}）
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="未达标" width="90" align="center">
            <template #default="{ row }">{{ (row.unreach || []).length }}</template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty v-else description="该报告无章节数据（生成时无伙伴指标行）" :image-size="60" />
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getReportsApi, generateReportApi, reviewReportApi,
  archiveReportApi, publishReportApi
} from '@/api/proc/service-monitoring'

const userStore = useUserStore()
const roles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))
// 审核角色=集成架构师（ADMIN 直通），与服务层 requireReviewer 同口径
const canReview = computed(() => isAdmin.value || roles.value.includes('ROLE_INTF_ARCH'))

const loading = ref(false)
const submitting = ref(false)
const rows = ref([])
const total = ref(0)
const q = reactive({ status: '', current: 1, size: 20 })
const current = ref({})

const ST_TEXT = { GENERATED: '已生成', REVIEWED: '已审核', ARCHIVED: '已归档', PUBLISHED: '已发布' }
const ST_TAG = { GENERATED: 'info', REVIEWED: 'warning', ARCHIVED: 'primary', PUBLISHED: 'success' }
const stText = s => ST_TEXT[s] || s
const stTag = s => ST_TAG[s] || 'info'

async function load() {
  loading.value = true
  try {
    const { data } = await getReportsApi({
      current: q.current, size: q.size, status: q.status || undefined
    })
    rows.value = (data && data.records) || []
    total.value = Number((data && data.total) || 0)
  } finally { loading.value = false }
}

// ---------------- 生成 ----------------
const genDlg = ref(false)
const genMonth = ref('')
function openGenerate() { genMonth.value = ''; genDlg.value = true }
async function doGenerate() {
  submitting.value = true
  try {
    const r = await generateReportApi(genMonth.value ? { monthTag: genMonth.value } : {})
    ElMessage.success(`已生成 ${r.data?.monthTag || ''} 报告（生成人 ${r.data?.generatedBy || ''}）`)
    genDlg.value = false
    await load()
  } finally { submitting.value = false }
}

// ---------------- 审核 ----------------
const revDlg = ref(false)
const revOpinion = ref('')
function openReview(row) { current.value = row; revOpinion.value = ''; revDlg.value = true }
async function doReview() {
  if (!revOpinion.value || revOpinion.value.trim().length < 2) {
    return ElMessage.warning('审核意见必填（至少 2 字）')
  }
  submitting.value = true
  try {
    await reviewReportApi(current.value.id, revOpinion.value.trim())
    ElMessage.success('审核通过（REVIEWED）')
    revDlg.value = false
    await load()
  } finally { submitting.value = false }
}

// ---------------- 归档 ----------------
async function doArchive(row) {
  try {
    await ElMessageBox.confirm(`归档 ${row.monthTag} 报告（版本 ${row.version}）？归档后内容不可修改。`,
      '归档', { type: 'warning' })
  } catch (e) { return }
  try {
    const r = await archiveReportApi(row.id)
    ElMessage.success(`已归档（${r.data?.version} @ ${String(r.data?.archivedAt || '').replace('T', ' ').slice(0, 16)}）`)
    await load()
  } catch (e) { /* 拦截器已提示 */ }
}

// ---------------- 发布 ----------------
const pubDlg = ref(false)
const pubScope = ref('ALL')
function openPublish(row) { current.value = row; pubScope.value = 'ALL'; pubDlg.value = true }
async function doPublish() {
  submitting.value = true
  try {
    await publishReportApi(current.value.id, pubScope.value || 'ALL')
    ElMessage.success('已发布（门户按章节范围可见）')
    pubDlg.value = false
    await load()
  } finally { submitting.value = false }
}

// ---------------- 章节预览 ----------------
const prevDlg = ref(false)
const preview = ref(null)
function openPreview(row) {
  current.value = row
  preview.value = null
  if (row.metricsJson) {
    try {
      const m = typeof row.metricsJson === 'string' ? JSON.parse(row.metricsJson) : row.metricsJson
      preview.value = m
    } catch { preview.value = null }
  }
  prevDlg.value = true
}

onMounted(load)
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
.hint { color: #909399; font-size: 12px; }
.sub-title { font-size: 13px; font-weight: 600; color: #303133; margin-bottom: 6px; }
</style>
