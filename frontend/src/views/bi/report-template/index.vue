<template>
  <div>
    <el-page-header content="报表模板管理（14.1）— 设计/测试/发布/版本四叶；口径 diff 硬阻断（C-4.10-01）、三方发布审批、快照回退"
                    style="margin-bottom: 8px" />

    <el-tabs v-model="tab" @tab-change="onTab">
      <!-- ========== 14.1.1 模板设计 ========== -->
      <el-tab-pane label="模板设计" name="design">
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
      </el-tab-pane>

      <!-- ========== 14.1.2 模板测试 ========== -->
      <el-tab-pane label="模板测试" name="test">
        <div class="toolbar">
          <el-select v-model="testTplId" placeholder="选择模板" size="small" style="width: 280px" filterable>
            <el-option v-for="t in allRows" :key="t.id" :label="`${t.code} ${t.name}`" :value="t.id" />
          </el-select>
          <el-button size="small" type="primary" :disabled="!testTplId" :loading="testing"
                     @click="doTest">执行测试</el-button>
          <span class="tip">三项报告：样本人工核对 / 性能阈值（标准10s·复杂30s）/ 权限行数对比；模板变更后旧报告作废须重测</span>
        </div>
        <el-table :data="tests" v-loading="testsLoading" border stripe size="small">
          <el-table-column label="执行时间" width="150">
            <template #default="{ row }">{{ (row.execAt || '').replace('T', ' ').slice(0, 16) }}</template>
          </el-table-column>
          <el-table-column prop="rowCount" label="行数" width="80" align="center" />
          <el-table-column prop="perfMs" label="耗时ms" width="90" align="center">
            <template #default="{ row }">
              <span :style="{ color: row.perfOk === '1' ? '#67C23A' : '#F56C6C' }">{{ row.perfMs }}</span>
            </template>
          </el-table-column>
          <el-table-column label="性能" width="70" align="center">
            <template #default="{ row }">
              <el-tag :type="row.perfOk === '1' ? 'success' : 'danger'" size="small">
                {{ row.perfOk === '1' ? '达标' : '超标' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="权限" width="70" align="center">
            <template #default="{ row }">
              <el-tag :type="row.permOk === '1' ? 'success' : 'danger'" size="small">
                {{ row.permOk === '1' ? '符合' : '异常' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="样本核对" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.accuracyOk === '1' ? 'success' : 'warning'" size="small">
                {{ row.accuracyOk === '1' ? '已核对' : '待核对' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="总体" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'PASS' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="有效" width="70" align="center">
            <template #default="{ row }">{{ row.valid === '1' ? '有效' : '已作废' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="160" align="center">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click="showSample(row)">查看样本</el-button>
              <el-button v-if="row.valid === '1' && row.accuracyOk === '0' && row.status !== 'PASS'"
                         size="small" type="success" link @click="doConfirm(row)">核对确认</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!testsLoading && tests.length === 0" description="暂无测试报告" />

        <el-dialog v-model="sampleVisible" title="样本人工核对（前10行）" width="720px">
          <el-table :data="sampleRows" border size="small" max-height="400">
            <el-table-column v-for="k in sampleCols" :key="k" :prop="k" :label="k" min-width="110"
                             show-overflow-tooltip />
          </el-table>
        </el-dialog>
      </el-tab-pane>

      <!-- ========== 14.1.3 模板发布 ========== -->
      <el-tab-pane label="模板发布" name="publish">
        <div class="toolbar">
          <el-button size="small" type="primary" @click="load">刷新</el-button>
          <span class="tip">三方串行审批：数据治理（口径）→ 业务负责人（业务准确性）→ IT（性能）；3/5 工作日超时催办升级；发布前二次口径校验（BR-4.10-11）</span>
        </div>
        <el-table :data="publishRows" v-loading="loading" border stripe size="small" max-height="400">
          <el-table-column prop="code" label="编码" width="130" />
          <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="240" align="center">
            <template #default="{ row }">
              <el-button v-if="['DRAFT','TESTING','REJECTED'].includes(row.status)"
                         size="small" type="primary" @click="doSubmit(row)">提交审批</el-button>
              <el-button size="small" type="info" link @click="openApproval(row)">审批进度</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-dialog v-model="approvalVisible" :title="`审批进度：${approvalName}`" width="760px">
          <el-timeline v-if="approvalTasks.length">
            <el-timeline-item v-for="t in approvalTasks" :key="t.id"
                              :type="t.status === 'PASSED' ? 'success' : (t.status === 'REJECTED' ? 'danger' : 'primary')"
                              :hollow="t.status === 'PENDING'"
                              :timestamp="(t.opTime || '').replace('T', ' ').slice(0, 16)">
              SEQ{{ t.seq }} · {{ t.nodeName || t.roleRequired }}
              <el-tag size="small" :type="t.status === 'PASSED' ? 'success' : 'info'" style="margin-left: 6px">
                {{ t.status }}
              </el-tag>
              <div v-if="t.opinion" class="tip">意见：{{ t.opinion }}</div>
              <div v-if="t.status === 'PENDING' && canSign(t)" style="margin-top: 6px">
                <el-input v-model="signOpinions[t.id]" size="small" placeholder="签署意见（驳回须 ≥2 字）"
                          style="width: 260px; margin-right: 6px" />
                <el-button size="small" type="success" @click="doPass(t)">通过</el-button>
                <el-button size="small" type="danger" @click="doReject(t)">驳回</el-button>
              </div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无审批实例" :image-size="60" />
        </el-dialog>
      </el-tab-pane>

      <!-- ========== 14.1.4 版本控制 ========== -->
      <el-tab-pane label="版本控制" name="version">
        <div class="toolbar">
          <el-select v-model="verTplId" placeholder="选择模板" size="small" style="width: 280px" filterable
                     @change="loadVersions">
            <el-option v-for="t in allRows" :key="t.id" :label="`${t.code} ${t.name}`" :value="t.id" />
          </el-select>
          <span class="tip">发布即不可变快照（报表版本 × 口径版本双维度绑定 BR-4.10-07）；回退生成新版本号保留链</span>
        </div>
        <el-table :data="versions" v-loading="versLoading" border stripe size="small">
          <el-table-column prop="versionNo" label="版本号" width="90" align="center">
            <template #default="{ row }">V{{ row.versionNo }}</template>
          </el-table-column>
          <el-table-column label="口径版本绑定" min-width="220">
            <template #default="{ row }">
              <template v-if="parseJson(row.metricVersionsJson).length">
                <el-tag v-for="m in parseJson(row.metricVersionsJson)" :key="m.metricKey" size="small"
                        style="margin-right: 4px">
                  {{ m.metricKey }}@v{{ m.metricVersion ?? '-' }}
                </el-tag>
              </template>
              <span v-else class="tip">无指标引用</span>
            </template>
          </el-table-column>
          <el-table-column label="回退溯源" width="100" align="center">
            <template #default="{ row }">{{ row.rollbackFrom ? '自 V' + row.rollbackFrom : '—' }}</template>
          </el-table-column>
          <el-table-column prop="publishBy" label="发布人" width="120" />
          <el-table-column label="发布时间" width="150">
            <template #default="{ row }">{{ (row.publishAt || '').replace('T', ' ').slice(0, 16) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" align="center">
            <template #default="{ row }">
              <el-button size="small" type="warning" link @click="doRollback(row)">回退到此版</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!versLoading && versions.length === 0" description="暂无版本快照" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onActivated } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getTemplatesApi, getTemplateApi, createTemplateApi, updateTemplateApi, deleteTemplateApi,
  runTestApi, confirmAccuracyApi, getTestsApi, submitTemplateApi, getVersionsApi,
  rollbackApi, renderApi, approvalPass, approvalReject
} from '@/api/bi/report-template'
import request from '@/utils/request'

const route = useRoute()
const loading = ref(false)
const rows = ref([])
const allRows = ref([])
const keyword = ref('')
const statusFilter = ref('')
const tab = ref('design')

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

// 测试
const testTplId = ref('')
const tests = ref([])
const testsLoading = ref(false)
const testing = ref(false)
const sampleVisible = ref(false)
const sampleRows = ref([])

// 发布
const publishRows = ref([])
const approvalVisible = ref(false)
const approvalName = ref('')
const approvalTasks = ref([])
const signOpinions = ref({})

// 版本
const verTplId = ref('')
const versions = ref([])
const versLoading = ref(false)

const TAB_WHITELIST = ['design', 'test', 'publish', 'version']
const pickTab = () => {
  const m = route.meta?.tab
  const q = route.query?.tab
  if (TAB_WHELIST_HAS(m)) return m
  if (TAB_WHITELIST_HAS(q)) return q
  return 'design'
}
function TAB_WHITELIST_HAS(v) { return TAB_WHITELIST.includes(v) }

function blankForm() {
  return { code: '', name: '', category: 'STANDARD', sqlText: '', columnsJson: '', metricsJson: '', remark: '' }
}

function statusLabel(s) {
  return { DRAFT: '草稿', TESTING: '测试中', PENDING_APPROVAL: '审批中',
    PUBLISHED: '已发布', REJECTED: '已驳回' }[s] || s
}
function statusType(s) {
  return { DRAFT: 'info', TESTING: 'warning', PENDING_APPROVAL: 'warning',
    PUBLISHED: 'success', REJECTED: 'danger' }[s] || 'info'
}

async function load() {
  loading.value = true
  try {
    const params = {}
    if (keyword.value) params.keyword = keyword.value
    if (statusFilter.value) params.status = statusFilter.value
    const res = await getTemplatesApi(params)
    rows.value = res.data || []
    publishRows.value = rows.value
  } finally {
    loading.value = false
  }
}

async function loadAll() {
  const res = await getTemplatesApi({})
  allRows.value = res.data || []
}

function onTab(t) {
  if (t === 'test' || t === 'version') loadAll()
  if (t === 'publish') load()
  if (t === 'test' && testTplId.value) loadTests()
  if (t === 'version' && verTplId.value) loadVersions()
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

// ---------- 测试 ----------
async function loadTests() {
  if (!testTplId.value) return
  testsLoading.value = true
  try {
    const res = await getTestsApi(testTplId.value)
    tests.value = res.data || []
  } finally {
    testsLoading.value = false
  }
}

async function doTest() {
  testing.value = true
  try {
    const res = await runTestApi(testTplId.value)
    ElMessage.success(`测试完成：${res.data?.rowCount ?? 0} 行，耗时 ${res.data?.perfMs ?? '-'}ms`)
    await loadTests()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '测试失败')
  } finally {
    testing.value = false
  }
}

async function doConfirm(row) {
  try {
    await confirmAccuracyApi(row.id)
    ElMessage.success('样本核对确认，总体 PASS')
    await loadTests()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '确认失败（性能/权限项未达标不可核对通过）')
  }
}

function showSample(row) {
  try {
    const arr = JSON.parse(row.sampleJson || '[]')
    sampleRows.value = arr
  } catch { sampleRows.value = [] }
  sampleVisible.value = true
}

const sampleCols = computed(() => {
  const keys = new Set()
  sampleRows.value.forEach(r => Object.keys(r || {}).forEach(k => keys.add(k)))
  return [...keys]
})

// ---------- 发布 ----------
async function doSubmit(row) {
  try {
    await submitTemplateApi(row.id)
    ElMessage.success('已提交三方串行审批（数据治理 → 业务负责人 → IT）')
    await load()
    openApproval(row)
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '提交失败（须有有效 PASS 测试报告且指标全部注册）')
  }
}

function canSign(t) {
  // 简化：任何登录签署人可见按钮，后端节点级授权（角色不符 403）兜底
  return t.status === 'PENDING'
}

async function doPass(t) {
  try {
    await approvalPass(t.id, signOpinions.value[t.id] || '同意')
    ElMessage.success('已签署')
    await openApprovalByName()
    await load()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '签署失败（角色不符或禁自签 403）')
  }
}

async function doReject(t) {
  const opinion = signOpinions.value[t.id] || ''
  if (opinion.length < 2) {
    ElMessage.warning('驳回意见须 ≥2 字')
    return
  }
  try {
    await approvalReject(t.id, opinion)
    ElMessage.success('已驳回')
    await openApprovalByName()
    await load()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '驳回失败')
  }
}

let currentApprovalRow = null
function openApproval(row) {
  currentApprovalRow = row
  openApprovalImpl(row)
}
async function openApprovalByName() {
  if (currentApprovalRow) await openApprovalImpl(currentApprovalRow)
}
async function openApprovalImpl(row) {
  approvalName.value = row.name
  approvalVisible.value = true
  try {
    const res = await request.get('/api/qms/approvals/logs', {
      params: { bizType: 'ReportTemplate', bizId: row.id }
    })
    approvalTasks.value = res.data?.tasks || []
  } catch {
    approvalTasks.value = []
  }
}

// ---------- 版本 ----------
async function loadVersions() {
  if (!verTplId.value) return
  versLoading.value = true
  try {
    const res = await getVersionsApi(verTplId.value)
    versions.value = res.data || []
  } finally {
    versLoading.value = false
  }
}

async function doRollback(row) {
  try {
    await ElMessageBox.confirm(
      `回退到 V${row.versionNo}？将生成新版本号（保留历史链），生效版切换。`, '回退确认', { type: 'warning' })
  } catch { return }
  try {
    const res = await rollbackApi(row.id)
    ElMessage.success(`已回退：生成 V${res.data?.versionNo}（源自 V${res.data?.rollbackFrom}）`)
    await loadVersions()
    await load()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '回退失败')
  }
}

function parseJson(s) {
  try { return JSON.parse(s || '[]') } catch { return [] }
}

onMounted(() => { tab.value = pickTab(); load(); loadAll() })
onActivated(() => { tab.value = pickTab(); load(); loadAll() })
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
</style>
