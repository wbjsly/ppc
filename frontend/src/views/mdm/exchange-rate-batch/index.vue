<template>
  <div class="rate-batch">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">汇率批量更新</span>
          <el-button link type="primary" @click="goList">查看汇率列表 →</el-button>
        </div>
      </template>

      <!-- ① 模板区 -->
      <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
        title="载体说明：支持 CSV 文件上传或直接粘贴表格文本（Excel 单元格复制）；单批 ≤ 500 行；逐行校验按行发布（BR-4.1-19）。" />
      <div class="toolbar">
        <el-button @click="downloadTemplate">下载模板 CSV</el-button>
        <el-upload :auto-upload="false" :show-file-list="false" accept=".csv,text/csv"
                   :on-change="onFileChange">
          <el-button type="primary" plain>上传 .csv 文件</el-button>
        </el-upload>
        <span class="tip">列序：基础币种,报价币种,汇率类型,生效日期,失效日期,汇率值,来源文件编号</span>
      </div>

      <!-- ② 录入区 -->
      <el-input v-model="pasteText" type="textarea" :rows="5"
        placeholder="或直接粘贴表格文本（从 Excel 复制的 Tab 分隔内容）…" @blur="markDirty" />
      <div class="toolbar" style="margin-top: 10px;">
        <el-input v-model="defaultSource" placeholder="整批公共来源文件编号（行内留空时回填）"
                  style="width: 300px;" @input="markDirty" />
        <el-button type="primary" :loading="loading" @click="runPreview">解析并预检</el-button>
        <el-button v-if="previewRows.length" type="danger" :loading="saving"
                   :disabled="!previewRows.length" @click="submitBatch">
          {{ invalidCount ? `提交（${invalidCount} 行将失败）` : '确认提交' }}
        </el-button>
        <span v-if="parsedInfo" class="tip">{{ parsedInfo }}</span>
      </div>

      <!-- ③ 预检表格 -->
      <el-table v-if="previewRows.length" :data="previewRows" size="small" border
                :row-class-name="rowClass" style="margin-top: 12px;">
        <el-table-column prop="rowNo" label="行号" width="70" />
        <el-table-column label="币对" width="110">
          <template #default="{ row }">{{ row.baseCcy }}/{{ row.quoteCcy }}</template>
        </el-table-column>
        <el-table-column prop="rateType" label="类型" width="90" />
        <el-table-column label="区间" width="200">
          <template #default="{ row }">{{ row.effectiveDate }} ~ {{ row.expireDate }}</template>
        </el-table-column>
        <el-table-column prop="rate" label="汇率" width="110" align="right" />
        <el-table-column label="校验" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.valid ? 'success' : 'danger'" size="small">
              {{ row.valid ? '通过' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="原因" min-width="260">
          <template #default="{ row }">{{ row.reason || '—' }}</template>
        </el-table-column>
      </el-table>

      <!-- ④ 结果报告 -->
      <template v-if="report">
        <el-divider content-position="left">导入结果报告（BR-4.1-19）</el-divider>
        <el-row :gutter="12" style="margin-bottom: 10px;">
          <el-col :span="6"><el-statistic title="总行数" :value="report.total" /></el-col>
          <el-col :span="6"><el-statistic title="成功" :value="report.succeeded" value-style="color: #67c23a" /></el-col>
          <el-col :span="6"><el-statistic title="失败" :value="report.failed" value-style="color: #f56c6c" /></el-col>
          <el-col :span="6" style="display: flex; align-items: center; gap: 8px;">
            <el-button size="small" @click="downloadReport(report.details)">下载报告 CSV</el-button>
            <el-button v-if="report.succeeded > 0" size="small" type="primary" @click="goList">查看汇率列表</el-button>
          </el-col>
        </el-row>
        <el-table :data="report.details" size="small" border>
          <el-table-column prop="rowNo" label="行号" width="70" />
          <el-table-column prop="baseQuote" label="币对/类型" width="170" />
          <el-table-column label="结果" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" size="small">
                {{ row.result === 'SUCCESS' ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="原因" min-width="280">
            <template #default="{ row }">
              <span :style="row.result === 'FAILED' ? 'color:#f56c6c' : ''">{{ row.reason || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { previewBatchApi, submitBatchApi } from '@/api/mdm/exchange-rate-batch'
import { parseRows, downloadTemplate, downloadReport } from '@/utils/rate-import'

const router = useRouter()
const MAX_ROWS = 500

const loading = ref(false)
const saving = ref(false)
const pasteText = ref('')
const defaultSource = ref('')
const previewRows = ref([])
const parsedInfo = ref('')
const report = ref(null)
const dirty = ref(false)

const invalidCount = computed(() => previewRows.value.filter(r => !r.valid).length)

function rowClass({ row }) {
  return row.valid ? '' : 'row-invalid'
}
function markDirty() {
  dirty.value = true
}

function onFileChange(file) {
  const reader = new FileReader()
  reader.onload = () => {
    pasteText.value = String(reader.result || '')
    parsedInfo.value = `已加载文件：${file.name}`
    dirty.value = true
  }
  reader.readAsText(file.raw, 'utf-8')
}

async function runPreview() {
  const { rows, skipped } = parseRows(pasteText.value)
  if (!rows.length) {
    ElMessage.warning('未解析到任何数据行')
    return
  }
  if (rows.length > MAX_ROWS) {
    ElMessage.error(`单批上限 ${MAX_ROWS} 行，当前 ${rows.length} 行，请拆批`)
    return
  }
  loading.value = true
  try {
    const res = await previewBatchApi(rows, defaultSource.value.trim())
    previewRows.value = res.data.results.map(r => ({
      ...rows.find(x => x.rowNo === r.rowNo) || {},
      ...r
    }))
    parsedInfo.value = `解析 ${rows.length} 行（跳过表头/注释/空行）${skipped ? `，其中 ${skipped} 行列数不足` : ''}；预检失败 ${res.data.invalid} 行`
    report.value = null
    dirty.value = false
  } finally {
    loading.value = false
  }
}

async function submitBatch() {
  if (dirty.value) {
    ElMessage.warning('输入已修改，请先重新预检')
    return
  }
  saving.value = true
  try {
    const rows = previewRows.value.map(r => ({
      rowNo: r.rowNo, baseCcy: r.baseCcy, quoteCcy: r.quoteCcy, rateType: r.rateType,
      effectiveDate: r.effectiveDate, expireDate: r.expireDate, rate: r.rate,
      sourceFileNo: r.sourceFileNo
    }))
    const res = await submitBatchApi(rows, defaultSource.value.trim())
    report.value = res.data
    if (res.data.failed > 0) {
      ElMessage.warning(`提交完成：成功 ${res.data.succeeded} 行，失败 ${res.data.failed} 行`)
    } else {
      ElMessage.success(`批量导入成功 ${res.data.succeeded} 行`)
    }
  } catch (e) {
    // 422 上限/空行已单点提示
  } finally {
    saving.value = false
  }
}

function goList() {
  router.push('/m/1.5.1')
}
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; flex-wrap: wrap; }
.tip { font-size: 12px; color: #909399; }
:deep(.row-invalid) { background: #fef0f0; }
</style>
