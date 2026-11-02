<template>
  <div>
    <el-page-header content="报表模板管理（14.1）— 设计/测试/发布/版本四叶；口径 diff 硬阻断（C-4.10-01）、三方发布审批、快照回退"
                    style="margin-bottom: 8px" />

    <!-- ========== 14.1.1 模板设计 ========== -->
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="名称/编码" size="small" clearable style="width: 200px"
                @keyup.enter="load" />
      <el-select v-model="statusFilter" placeholder="状态" size="small" clearable style="width: 150px"
                 @change="load">
        <el-option label="草稿" value="DRAFT" />
        <el-option label="测试中" value="TESTING" />
        <el-option label="审批中" value="PENDING_APPROVAL" />
        <el-option label="已发布" value="PUBLISHED" />
        <el-option label="已驳回" value="REJECTED" />
      </el-select>
      <el-button size="small" type="primary" @click="load">查询</el-button>
      <el-button size="small" type="success" @click="openEdit(null)">新建模板</el-button>
    </div>
    <el-table :data="rows" v-loading="loading" border stripe size="small" max-height="380">
      <el-table-column prop="code" label="编码" width="130" />
      <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
      <el-table-column label="分类" width="90" align="center">
        <template #default="{ row }">{{ row.category === 'COMPLEX' ? '复杂' : '标准' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="activeVersionNo" label="生效版" width="80" align="center" />
      <el-table-column prop="updateBy" label="更新人" width="120" />
      <el-table-column label="操作" width="180" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="primary" link @click="openPreview(row)">预览</el-button>
          <el-button size="small" type="danger" link @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="editVisible" :title="editId ? '编辑模板' : '新建模板'" width="720px"
               :close-on-click-modal="false">
      <el-form label-width="90px" size="small">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="模板编码" required>
              <el-input v-model="form.code" placeholder="如 RPT-SALES-01" :disabled="!!editId" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="名称" required>
              <el-input v-model="form.name" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="性能档">
              <el-radio-group v-model="form.category">
                <el-radio value="STANDARD">标准（≤10s）</el-radio>
                <el-radio value="COMPLEX">复杂（≤30s）</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="备注">
              <el-input v-model="form.remark" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="查询 SQL" required>
          <el-input v-model="form.sqlText" type="textarea" :rows="5"
                    placeholder="仅只读 SELECT（单语句，禁 DDL/DML）" />
        </el-form-item>
        <el-form-item label="列定义 JSON">
          <el-input v-model="form.columnsJson" type="textarea" :rows="4"
                    placeholder='存量：[{"field":"AMOUNT","title":"金额","entityCol":true}]；语义增强（14.6 自助查询）：{"field":"ORDER_DATE","label":"订单日期","role":"DIMENSION","timeFlag":true} / {"field":"AMOUNT","role":"MEASURE","agg":["SUM"],"roleFlag":false}——role=DIMENSION|MEASURE、agg 聚合白名单、timeFlag/roleFlag 供即席查询预检判定，均可选（缺省=维度不可聚合）' />
        </el-form-item>
        <el-form-item label="指标引用" required>
          <el-input v-model="form.metricsJson" type="textarea" :rows="3"
                    placeholder='[{"metricKey":"RECEIPT_AMT","formula":"{...}"}]——保存时与口径字典逐指标 diff，不一致 422 阻断' />
        </el-form-item>
      </el-form>
      <el-alert v-if="diffError" :title="diffError" type="error" :closable="false" style="margin-bottom: 8px" />
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存（口径校验）</el-button>
      </template>
    </el-dialog>

    <!-- 渲染预览抽屉 -->
    <el-drawer v-model="previewVisible" :title="`渲染预览：${previewName}`" size="70%">
      <div class="toolbar">
        <el-tag size="small" :type="previewStatus === 'PUBLISHED' ? 'success' : 'info'">
          {{ statusLabel(previewStatus) }}
        </el-tag>
        <el-button size="small" type="primary" @click="loadRender">刷新数据</el-button>
        <span class="tip">行级权限已注入、敏感列按列级规则掩码</span>
      </div>
      <el-table :data="renderRows" v-loading="renderLoading" border stripe size="small" max-height="520">
        <el-table-column v-for="c in renderCols" :key="c.field" :prop="c.field"
                         :label="c.title || c.field" min-width="120" show-overflow-tooltip />
      </el-table>
      <el-empty v-if="!renderLoading && renderRows.length === 0" description="无数据（或行级权限过滤后为空集）" />
      <div class="toolbar" style="margin-top: 8px">
        <el-pagination layout="prev, pager, next, total" :total="renderTotal"
                       :page-size="renderSize" v-model:current-page="renderPage"
                       @current-change="loadRender" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * 14.1.1 模板设计（拆分自原报表模板操作台）：模板检索/新建编辑（口径 diff 硬阻断）/ 渲染预览。
 * 模板列表与检索条件见 useReportTemplate.js（模板发布页、版本控制页共用），状态映射见 rt-helpers.js。
 */
import { ref, onMounted, onActivated } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getTemplateApi, createTemplateApi, updateTemplateApi, deleteTemplateApi,
  renderApi
} from '@/api/bi/report-template'
import { useReportTemplate } from './useReportTemplate'
import { statusLabel, statusType } from './rt-helpers'

const { rows, loading, keyword, statusFilter, load } = useReportTemplate()

// 设计
const editVisible = ref(false)
const editId = ref('')
const saving = ref(false)
const diffError = ref('')
const form = ref(blankForm())

// 预览
const previewVisible = ref(false)
const previewName = ref('')
const previewStatus = ref('')
const previewId = ref('')
const renderRows = ref([])
const renderCols = ref([])
const renderTotal = ref(0)
const renderPage = ref(1)
const renderSize = 100
const renderLoading = ref(false)

function blankForm() {
  return { code: '', name: '', category: 'STANDARD', sqlText: '', columnsJson: '', metricsJson: '', remark: '' }
}

// ---------- 设计 ----------
function openEdit(row) {
  diffError.value = ''
  if (row) {
    editId.value = row.id
    form.value = { code: row.code, name: row.name, category: row.category,
      sqlText: '', columnsJson: '', metricsJson: '', remark: row.remark || '' }
    getTemplateApi(row.id).then(res => {
      const t = res.data?.template || {}
      form.value.sqlText = t.sqlText || ''
      form.value.columnsJson = t.columnsJson || ''
      form.value.metricsJson = t.metricsJson || ''
    })
  } else {
    editId.value = ''
    form.value = blankForm()
  }
  editVisible.value = true
}

async function save() {
  diffError.value = ''
  if (!form.value.code || !form.value.name || !form.value.sqlText || !form.value.metricsJson) {
    ElMessage.warning('编码/名称/SQL/指标引用必填')
    return
  }
  saving.value = true
  try {
    if (editId.value) {
      await updateTemplateApi(editId.value, form.value)
    } else {
      await createTemplateApi(form.value)
    }
    ElMessage.success('已保存（口径校验通过）')
    editVisible.value = false
    await load()
  } catch (e) {
    diffError.value = (e && (e.msg || e.message)) || '保存失败（可能口径不一致 C-4.10-01 或 SQL 非只读）'
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确认删除模板「${row.name}」？`, '删除', { type: 'warning' })
  } catch { return }
  try {
    await deleteTemplateApi(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '删除失败')
  }
}

function openPreview(row) {
  previewId.value = row.id
  previewName.value = row.name
  previewStatus.value = row.status
  renderPage.value = 1
  previewVisible.value = true
  loadRender()
}

async function loadRender() {
  renderLoading.value = true
  try {
    const res = await renderApi(previewId.value, renderPage.value, renderSize)
    renderRows.value = res.data?.rows || []
    renderCols.value = res.data?.columns || []
    renderTotal.value = res.data?.total || 0
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '渲染失败')
    renderRows.value = []
  } finally {
    renderLoading.value = false
  }
}

onMounted(() => { load() })
onActivated(() => { load() })
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
</style>
