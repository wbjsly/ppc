<template>
  <div>
    <el-page-header content="数据导出（14.5）— 模板/数据集双源、三格式水印（Excel 页眉/PDF 斜置/CSV BOM）、AES-256 加密下载 7 天有效、超量分级审批（经理/总监）"
                    style="margin-bottom: 8px" />

    <el-tabs v-model="tab">
      <!-- ==================== 14.5.1 导出申请 ==================== -->
      <el-tab-pane label="导出申请" name="apply">
        <el-alert type="info" show-icon :closable="false" style="margin-bottom: 8px"
                  title="任一已发布模板可导出；>1 万行自动拦截并按行数分级审批（≤5万部门经理 / >5万部门总监），用途说明 ≥10 字；文件 AES-256 加密，下载链接 7 天有效" />

        <div class="toolbar">
          <el-select v-model="form.source" size="small" style="width: 130px">
            <el-option label="报表模板" value="template" />
            <el-option label="成本快照" value="cost" />
            <el-option label="价格异动" value="alerts" />
            <el-option label="库存报表" value="inv-report" />
          </el-select>
          <el-select v-if="form.source === 'template'" v-model="form.templateId"
                     placeholder="选择已发布模板" size="small" style="width: 220px">
            <el-option v-for="t in templates" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
          <el-select v-model="form.format" size="small" style="width: 100px">
            <el-option label="CSV" value="csv" />
            <el-option label="Excel" value="xlsx" />
            <el-option label="PDF" value="pdf" />
          </el-select>
          <el-input-number v-model="form.estRows" :min="0" :max="1000000" size="small"
                           placeholder="预估行数" style="width: 140px" />
          <el-button size="small" type="success" @click="onSubmitExport">提交导出</el-button>
        </div>
        <el-input v-if="overLimit" v-model="form.purpose" size="small" style="margin-bottom: 8px"
                  placeholder="超量导出须填写用途说明（≥10 字）" maxlength="500" />
        <div v-else style="color: #909399; font-size: 12px; margin-bottom: 8px">
          预估 ≤{{ 10000 }} 行无需用途说明；超过后此处会出现必填输入框
        </div>

        <h4>我的任务</h4>
        <el-table :data="tasks" border stripe size="small" max-height="320">
          <el-table-column prop="taskNo" label="任务号" width="130" />
          <el-table-column prop="estRows" label="预估行数" width="90" align="center" />
          <el-table-column prop="rowCount" label="实际行数" width="90" align="center" />
          <el-table-column prop="priority" label="优先级" width="80" align="center" />
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'DONE' ? 'success'
                : row.status === 'FAILED' ? 'danger'
                : row.status === 'RUNNING' ? 'warning' : 'info'" size="small">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="审批" width="100" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.approvalStatus" :type="row.approvalStatus === 'APPROVED' ? 'success'
                : row.approvalStatus === 'REJECTED' ? 'danger' : 'warning'" size="small">
                {{ row.approvalStatus }}
              </el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column prop="expireAt" label="链接到期" width="150" />
          <el-table-column label="操作" width="120" align="center">
            <template #default="{ row }">
              <el-button v-if="row.status === 'DONE' && !row.cleanAt" size="small" type="primary"
                         link @click="onDownload(row)">下载</el-button>
              <span v-else-if="row.cleanAt" style="color: #909399; font-size: 12px">已清理</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ==================== 14.5.2 水印标记 ==================== -->
      <el-tab-pane label="水印标记" name="watermark">
        <el-alert type="info" show-icon :closable="false" style="margin-bottom: 8px"
                  title="导出文件自动嵌入水印：内容 = {用户名}|{时间戳}|{唯一流水号}；Excel 页眉页脚+背景半透明、PDF 每页右下角+斜置、CSV BOM+首行注释（FR-4.10-4-4）" />
        <el-table :data="tasks.filter(t => t.watermark)" border stripe size="small" max-height="320">
          <el-table-column prop="taskNo" label="任务号" width="140" />
          <el-table-column prop="userName" label="用户" width="110" />
          <el-table-column prop="watermark" label="水印内容" min-width="280" show-overflow-tooltip />
          <el-table-column prop="reqId" label="流水号" width="160" />
        </el-table>
        <el-empty v-if="!tasks.some(t => t.watermark)" description="暂无已完成导出（水印随任务完成生成）" />
      </el-tab-pane>

      <!-- ==================== 14.5.3 超量审批 ==================== -->
      <el-tab-pane label="超量审批" name="approval">
        <el-alert type="info" show-icon :closable="false" style="margin-bottom: 8px"
                  title="超量导出走 ApprovalEngine 分级单签：≤50000 行部门经理、>50000 行部门总监；签署在审批台完成；月度同一用户超量 ≥5 次自动告警信息安全专员（C-4.10-03）" />
        <el-table :data="approvals" border stripe size="small" max-height="320">
          <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
          <el-table-column prop="applyBy" label="申请人" width="110" />
          <el-table-column prop="status" label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'APPROVED' ? 'success'
                : row.status === 'REJECTED' ? 'danger' : 'warning'" size="small">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createDate" label="发起时间" width="160" />
          <el-table-column label="操作" width="120" align="center">
            <template #default>
              <el-button size="small" type="primary" link
                         @click="$router.push('/m/16.5')">去审批台</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  submitExportApi, myExportTasksApi, publishedTemplatesApi,
  downloadExportApi, overlimitApprovalsApi
} from '@/api/bi/export-console'

const route = useRoute()
const tab = ref(route.meta?.tab || 'apply')

const form = ref({ source: 'template', templateId: '', format: 'csv', estRows: 100, purpose: '' })
const templates = ref([])
const tasks = ref([])
const approvals = ref([])

const overLimit = computed(() => (form.value.estRows || 0) > 10000)

async function loadAll() {
  try {
    const t = await publishedTemplatesApi()
    templates.value = t.data || []
  } catch (e) { templates.value = [] }
  try {
    const m = await myExportTasksApi()
    tasks.value = m.data || []
  } catch (e) { tasks.value = [] }
  try {
    const a = await overlimitApprovalsApi()
    approvals.value = a.data || []
  } catch (e) { approvals.value = [] }
}

async function onSubmitExport() {
  const f = form.value
  if (f.source === 'template' && !f.templateId) {
    ElMessage.warning('请选择已发布模板')
    return
  }
  const params = { format: f.format }
  if (f.source === 'template') {
    params.templateId = f.templateId
    const t = templates.value.find(x => x.id === f.templateId)
    if (t) params.reportName = t.name
  }
  if (f.purpose) params.purpose = f.purpose
  try {
    const res = await submitExportApi({ dataset: f.source, params, estRows: f.estRows })
    ElMessage.success(res.data?.hint || '已提交')
    loadAll()
  } catch (e) {
    ElMessage.error(e?.message || '提交失败')
  }
}

async function onDownload(row) {
  try {
    const res = await downloadExportApi(row.id)
    const url = URL.createObjectURL(new Blob([res.data || res]))
    const a = document.createElement('a')
    a.href = url
    a.download = row.taskNo + '.bin'
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error(e?.message || '下载失败（可能已过期）')
  }
}

onMounted(loadAll)
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
h4 {
  margin: 10px 0 6px;
}
</style>
