<template>
  <div class="i18n-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="语言管理（17.1）：键值维护、缺口报告、版本发布"
      description="后端表为源（决策2）；占位符不一致拒绝导入（BR-5.7-03）；预检 P0/P1=100%+总体≥99% 阻断发布（BR-5.7-01/02）。" />
    <el-tabs v-model="tab">
      <!-- 17.1.1 键值维护 -->
      <el-tab-pane label="键值维护" name="keys">
        <el-form inline size="small">
          <el-form-item label="目标语">
            <el-select v-model="q.locale" style="width:120px" @change="loadKeys(1)">
              <el-option label="en-US" value="en-US" /><el-option label="ja-JP" value="ja-JP" />
            </el-select>
          </el-form-item>
          <el-form-item label="模块">
            <el-select v-model="q.module" clearable style="width:130px" @change="loadKeys(1)">
              <el-option v-for="m in MODULES" :key="m" :label="m" :value="m" />
            </el-select>
          </el-form-item>
          <el-form-item label="优先级">
            <el-select v-model="q.priority" clearable style="width:100px" @change="loadKeys(1)">
              <el-option v-for="p in ['P0','P1','P2','P3']" :key="p" :label="p" :value="p" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-input v-model="q.keyword" placeholder="键名/源语文本" clearable style="width:180px" @keyup.enter="loadKeys(1)" />
          </el-form-item>
          <el-button type="primary" size="small" @click="loadKeys(1)">查询</el-button>
          <el-button size="small" @click="importVisible=true">导入译文</el-button>
          <el-button size="small" @click="exportCsv">导出</el-button>
        </el-form>
        <el-table :data="keys" v-loading="loading" border size="small">
          <el-table-column prop="keyName" label="键名" min-width="200" show-overflow-tooltip />
          <el-table-column prop="module" label="模块" width="90" />
          <el-table-column prop="priority" label="优先级" width="70" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.priority==='P0'?'danger':row.priority==='P1'?'warning':'info'">{{ row.priority }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="sourceText" label="源语 zh-CN" min-width="180" show-overflow-tooltip />
          <el-table-column label="译文" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.targetText">{{ row.targetText }}</span>
              <el-tag v-else size="small" type="danger">缺口</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="edit(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 17.1.2 缺口报告 -->
      <el-tab-pane label="缺口报告" name="coverage">
        <el-form inline size="small">
          <el-form-item label="语种">
            <el-select v-model="covLocale" style="width:120px" @change="loadCoverage">
              <el-option label="en-US" value="en-US" /><el-option label="ja-JP" value="ja-JP" />
            </el-select>
          </el-form-item>
        </el-form>
        <el-row :gutter="12" style="margin-bottom:12px" v-if="coverage">
          <el-col :span="6"><el-card shadow="never"><div class="stat-label">总体覆盖率</div><div class="stat-num">{{ coverage.coverage }}%</div></el-card></el-col>
          <el-col :span="6"><el-card shadow="never"><div class="stat-label">已翻译 / 总键</div><div class="stat-num">{{ coverage.translated }}/{{ coverage.totalKeys }}</div></el-card></el-col>
          <el-col :span="6"><el-card shadow="never"><div class="stat-label">缺口数</div><div class="stat-num warn">{{ (coverage.gaps || []).length }}</div></el-card></el-col>
          <el-col :span="6"><el-card shadow="never"><div class="stat-label">门槛</div><div class="stat-num">{{ coverageMin }}%</div></el-card></el-col>
        </el-row>
        <el-table v-if="coverage" :data="coverage.items || []" border size="small" style="margin-bottom:12px">
          <el-table-column prop="priority" label="优先级" width="90" />
          <el-table-column prop="total" label="总键数" width="100" />
          <el-table-column prop="translated" label="已翻译" width="100" />
          <el-table-column prop="coverage" label="覆盖率%" width="110" />
        </el-table>
        <h4>缺口键清单（按优先级）</h4>
        <el-table :data="(coverage && coverage.gaps) || []" border size="small" max-height="300">
          <el-table-column prop="keyName" label="键名" min-width="220" show-overflow-tooltip />
          <el-table-column prop="module" label="模块" width="100" />
          <el-table-column prop="priority" label="优先级" width="80" />
        </el-table>
        <h4 style="margin-top:14px">运行时缺口台账（i18n.missing 批量回传累计）</h4>
        <el-table :data="gaps" border size="small" max-height="260">
          <el-table-column prop="keyName" label="键名" min-width="220" show-overflow-tooltip />
          <el-table-column prop="module" label="模块" width="90" />
          <el-table-column prop="priority" label="优先级" width="80" />
          <el-table-column prop="hitCount" label="命中次数" width="100" />
          <el-table-column prop="firstHitAt" label="首次命中" width="160" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button v-if="row.status==='OPEN'" link type="primary" size="small" @click="closeGap(row)">闭环</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 17.1.3 版本发布（SOP-5.7-B 六步） -->
      <el-tab-pane label="版本发布" name="publish">
        <el-alert type="warning" :closable="false" style="margin-bottom:10px"
          title="发布六步：构建 → 预检 → 回退策略 → 审批 → 灰度（10%，异常率>0.1% 自动回退 BR-5.7-07）→ 归档锁定（C-0-05）" />
        <el-form inline size="small">
          <el-form-item label="语种">
            <el-select v-model="pub.locale" style="width:120px">
              <el-option label="en-US" value="en-US" /><el-option label="ja-JP" value="ja-JP" />
            </el-select>
          </el-form-item>
          <el-form-item label="版本号">
            <el-input v-model="pub.packNo" placeholder="如 1.0.0" style="width:110px" />
          </el-form-item>
          <el-form-item label="回退策略" required>
            <el-input v-model="pub.fallback" placeholder='{"locale":"zh-CN","visualMark":true,"track":true}' style="width:300px" />
          </el-form-item>
          <el-button type="primary" size="small" @click="build">构建版本</el-button>
          <el-button size="small" @click="runPrecheck">预检</el-button>
        </el-form>
        <el-alert v-if="precheck" :type="precheck.pass ? 'success' : 'error'" :closable="false" style="margin-bottom:10px"
          :title="precheck.pass
            ? `预检通过：总体 ${precheck.coverage}% ≥ ${precheck.coverageMin}%，P0 ${precheck.p0Coverage}% / P1 ${precheck.p1Coverage}% = 100%`
            : `预检阻断：${precheck.blockReason}（缺口 ${(precheck.gaps || []).length} 条）`" />
        <el-table :data="packs" border size="small">
          <el-table-column prop="packNo" label="版本" width="100" />
          <el-table-column prop="locale" label="语种" width="90" />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="stTag(row.status)">{{ stText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="canaryPct" label="灰度%" width="80" />
          <el-table-column label="操作" width="300">
            <template #default="{ row }">
              <el-button v-if="row.status==='DRAFT'" size="small" type="primary" @click="approve(row)">审批</el-button>
              <el-button v-if="row.status==='APPROVED'" size="small" @click="canary(row)">灰度</el-button>
              <el-button v-if="row.status==='CANARY'" size="small" @click="canaryCheck(row)">灰度检查</el-button>
              <el-button v-if="row.status!=='ARCHIVED'" size="small" type="warning" @click="archive(row)">归档</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 键值编辑抽屉 -->
    <el-drawer v-model="editVisible" :title="editing && editing.id ? '编辑键值' : '新增键值'" size="440px">
      <el-form label-width="90px" size="small">
        <el-form-item label="键名" required><el-input v-model="ef.keyName" :disabled="!!(editing && editing.id)" /></el-form-item>
        <el-form-item label="目标语">
          <el-select v-model="ef.locale" style="width:100%">
            <el-option label="zh-CN（源语）" value="zh-CN" />
            <el-option label="en-US" value="en-US" /><el-option label="ja-JP" value="ja-JP" />
          </el-select>
        </el-form-item>
        <el-form-item label="模块"><el-input v-model="ef.module" /></el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="ef.priority" style="width:100%">
            <el-option v-for="p in ['P0','P1','P2','P3']" :key="p" :label="p" :value="p" />
          </el-select>
        </el-form-item>
        <el-form-item label="源语文本" v-if="ef.locale==='zh-CN'" required>
          <el-input type="textarea" :rows="2" v-model="ef.sourceText" />
        </el-form-item>
        <el-form-item label="译文" v-else>
          <el-input type="textarea" :rows="2" v-model="ef.targetText" placeholder="占位符须与源语一致（BR-5.7-03）" />
        </el-form-item>
        <el-form-item label="上下文"><el-input v-model="ef.contextNote" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible=false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-drawer>

    <!-- 导入抽屉 -->
    <el-drawer v-model="importVisible" title="导入译文 CSV（key_name,target_text 两列）" size="480px">
      <el-input type="textarea" :rows="10" v-model="importCsv"
        placeholder="key_name,target_text&#10;btn.query,Search&#10;btn.save,Save" />
      <template #footer>
        <el-button @click="importVisible=false">取消</el-button>
        <el-button type="primary" @click="doImport">导入（占位符校验 BR-5.7-03）</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getKeysApi, saveKeyApi, importKeysApi, exportKeysApi,
  buildPackApi, precheckApi, approvePackApi, startCanaryApi, checkCanaryApi,
  archivePackApi, getPacksApi, getGapsApi, closeGapApi, coverageApi } from '@/api/i18n/i18n'

const route = useRoute()
const tab = ref(route.meta.tab || 'keys')
const MODULES = ['common', 'field', 'error', 'menu', 'opp', 'lead', 'report', 'sla', 'i18n', 'help', 'term']

const q = ref({ locale: 'en-US', module: '', priority: '', keyword: '' })
const keys = ref([])
const loading = ref(false)
const editVisible = ref(false)
const editing = ref(null)
const ef = ref({})
const importVisible = ref(false)
const importCsv = ref('')

const covLocale = ref('en-US')
const coverage = ref(null)
const coverageMin = 99
const gaps = ref([])

const pub = ref({ locale: 'ja-JP', packNo: '1.0.0', fallback: '{"locale":"zh-CN","visualMark":true,"track":true}' })
const precheck = ref(null)
const packs = ref([])

function stTag(s) {
  return { DRAFT: 'info', PRECHECK: 'warning', REVIEWING: 'warning', APPROVED: 'primary', CANARY: '', ARCHIVED: 'success' }[s] || 'info'
}
function stText(s) {
  return { DRAFT: '草稿', PRECHECK: '预检', REVIEWING: '审核中', APPROVED: '已审批', CANARY: '灰度中', ARCHIVED: '已归档' }[s] || s
}

onMounted(() => { loadKeys(1); loadCoverage(); loadGaps(); loadPacks() })

async function loadKeys(page) {
  loading.value = true
  try {
    const r = await getKeysApi({ ...q.value, page: page || 1, size: 50 })
    keys.value = r.data || []
  } finally { loading.value = false }
}
function edit(row) {
  editing.value = row || null
  ef.value = row
    ? { keyName: row.keyName, locale: q.value.locale, module: row.module, priority: row.priority,
        sourceText: row.sourceText, targetText: row.targetText || '', contextNote: row.contextNote || '' }
    : { keyName: '', locale: 'zh-CN', module: 'common', priority: 'P1', sourceText: '', targetText: '', contextNote: '' }
  editVisible.value = true
}
async function save() {
  try {
    await saveKeyApi(ef.value)
    ElMessage.success('已保存')
    editVisible.value = false
    loadKeys(1)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '保存失败（占位符须与源语一致 BR-5.7-03）')
  }
}
async function doImport() {
  try {
    const r = await importKeysApi(q.value.locale, importCsv.value)
    ElMessage.success(`导入成功 ${r.data.imported} 条`)
    importVisible.value = false
    loadKeys(1); loadCoverage()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '导入被拒绝（占位符不一致 BR-5.7-03）')
  }
}
async function exportCsv() {
  const r = await exportKeysApi({ locale: q.value.locale, module: q.value.module || undefined })
  const blob = new Blob([r.data.csv], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `i18n-keys-${q.value.locale}.csv`
  a.click()
}

async function loadCoverage() {
  try {
    const r = await coverageApi(covLocale.value)
    coverage.value = r.data
  } catch { coverage.value = null }
}
async function loadGaps() {
  try {
    const r = await getGapsApi({ limit: 100 })
    gaps.value = r.data || []
  } catch { gaps.value = [] }
}
async function closeGap(row) {
  await closeGapApi(row.id)
  ElMessage.success('缺口已闭环')
  loadGaps()
}

async function loadPacks() {
  try {
    const r = await getPacksApi()
    packs.value = r.data || []
  } catch { packs.value = [] }
}
async function build() {
  try {
    await buildPackApi({ locale: pub.value.locale, packNo: pub.value.packNo,
      fallback: pub.value.fallback, remark: '' })
    // 回退策略随版本（写 remark 旁路：直接随构建传）
    ElMessage.success(`版本 ${pub.value.packNo} 已构建（不可变）`)
    loadPacks()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '构建失败')
  }
}
async function runPrecheck() {
  const r = await precheckApi(pub.value.locale)
  precheck.value = r.data
}
async function approve(row) {
  try {
    await approvePackApi(row.id)
    ElMessage.success('审批通过（预检强制通过）')
    loadPacks()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '审批被阻断（预检不通过 BR-5.7-01/02）')
    runPrecheck()
  }
}
async function canary(row) {
  try {
    await startCanaryApi(row.id, 10)
    ElMessage.success('灰度已启动（10%，回退策略前置校验）')
    loadPacks()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '灰度启动失败（回退策略未配置）')
  }
}
async function canaryCheck(row) {
  try {
    const r = await checkCanaryApi(row.id)
    if (r.data.rolledBack) {
      ElMessageBox.alert(r.data.reason, '灰度自动回退（BR-5.7-07）', { type: 'warning' })
    } else {
      ElMessage.success('灰度正常（异常率 ≤0.1%）')
    }
    loadPacks()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '检查失败')
  }
}
async function archive(row) {
  await ElMessageBox.confirm(`归档版本 ${row.packNo}？归档后不可修改（C-0-05）`, '提示', { type: 'warning' })
  await archivePackApi(row.id)
  ElMessage.success('已归档')
  loadPacks()
}
</script>

<style scoped>
.i18n-page { padding: 12px; }
.stat-label { font-size: 12px; color: #909399; }
.stat-num { font-size: 22px; font-weight: bold; color: #303133; margin-top: 4px; }
.stat-num.warn { color: #e6a23c; }
</style>
