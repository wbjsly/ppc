<template>
  <div class="i18n-page">
    <PageInfo title="语言管理：键值维护、缺口报告、版本发布" description="后端表为源（决策2）；占位符不一致拒绝导入（BR-5.7-03）；预检 P0/P1=100%+总体≥99% 阻断发布（BR-5.7-01/02）。" style="margin-bottom:12px" />

    <!-- 17.1.1 键值维护 -->
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
/**
 * 17.1.1 键值维护（拆分自原语言管理页）：键值查询 / 编辑抽屉 / 译文导入 / 导出。
 * 覆盖率共享状态见 useLanguage.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getKeysApi, saveKeyApi, importKeysApi, exportKeysApi } from '@/api/i18n/i18n'
import { useLanguage } from './useLanguage'

const { loadCoverage } = useLanguage()

const MODULES = ['common', 'field', 'error', 'menu', 'opp', 'lead', 'report', 'sla', 'i18n', 'help', 'term']

const q = ref({ locale: 'en-US', module: '', priority: '', keyword: '' })
const keys = ref([])
const loading = ref(false)
const editVisible = ref(false)
const editing = ref(null)
const ef = ref({})
const importVisible = ref(false)
const importCsv = ref('')

onMounted(() => { loadKeys(1) })

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
</script>

<style scoped>
.i18n-page { padding: 12px; }
</style>
