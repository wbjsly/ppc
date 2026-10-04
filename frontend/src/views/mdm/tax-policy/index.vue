<template>
  <div class="tax-policy">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">政策更新（政策台账）</span>
          <div class="header-actions">
            <el-input v-model="query.keyword" placeholder="文号/名称/发文机关" clearable
                      :prefix-icon="Search" style="width: 220px;" @keyup.enter="loadData(1)" />
            <el-button type="primary" @click="loadData(1)">查询</el-button>
            <el-button v-if="isAdmin" type="primary" :icon="Plus" @click="openCreate">登记政策</el-button>
          </div>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
        title="政策台账（S-4.1-07）：登记税收政策文件，政策文号唯一且登记后不可改；被税码记录引用的政策不可删除。"
        description="支持版本对比与政策驱动的税码批量切换（工作台生成衔接新段，历史区间零改动）。" />

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="policyNo" label="政策文号" min-width="200" show-overflow-tooltip>
          <template #default="{ row }"><b>{{ row.policyNo }}</b></template>
        </el-table-column>
        <el-table-column prop="policyName" label="政策名称" min-width="220" show-overflow-tooltip />
        <el-table-column prop="issuer" label="发文机关" width="140" show-overflow-tooltip />
        <el-table-column prop="issueDate" label="发布日期" width="110" />
        <el-table-column prop="effectiveDate" label="生效日期" width="110" />
        <el-table-column prop="summary" label="摘要" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.summary || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="270" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openLink(row)">关联变更</el-button>
            <el-button link type="primary" @click="openCompare(row)">对比</el-button>
            <el-button v-if="isAdmin" link type="warning" @click="openWorkbench(row)">发起变更</el-button>
            <template v-if="isAdmin">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination layout="total, prev, pager, next" style="margin-top: 12px; justify-content: flex-end;"
        :total="total" :page-size="query.size" :current-change="loadData" />
    </el-card>

    <!-- 登记/编辑弹窗 -->
    <el-dialog v-model="formVisible" :title="form.id ? '编辑政策' : '登记政策'" width="640px">
      <el-alert v-if="formMsg" type="error" show-icon :closable="false" :title="formMsg" style="margin-bottom: 10px;" />
      <el-form label-width="100px">
        <el-form-item label="政策文号" required>
          <el-input v-model="form.policyNo" :disabled="!!form.id" maxlength="64"
                    placeholder="如：税总公告2026年第15号（唯一，登记后不可改）" />
        </el-form-item>
        <el-form-item label="政策名称" required>
          <el-input v-model="form.policyName" maxlength="128" placeholder="政策全称" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="发文机关" required>
              <el-input v-model="form.issuer" maxlength="64" placeholder="如：国家税务总局" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="发布日期" required>
              <el-date-picker v-model="form.issueDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="生效日期" required>
              <el-date-picker v-model="form.effectiveDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="摘要">
          <el-input v-model="form.summary" type="textarea" :rows="2" maxlength="500" placeholder="政策要点（≤500 字）" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 关联税码变更抽屉 -->
    <el-drawer v-model="linkVisible" :title="`关联税码变更：${linkPolicy.policyNo || ''}`" size="680px">
      <el-alert v-if="linkData.hint" type="info" :closable="false" :title="linkData.hint" style="margin-bottom: 10px;" />
      <template v-if="linkData.taxCodes && linkData.taxCodes.length">
        <el-divider content-position="left">关联税码（{{ linkData.taxCodes.length }} 条）</el-divider>
        <el-table :data="linkData.taxCodes" size="small" stripe>
          <el-table-column prop="taxCode" label="税码" width="110" />
          <el-table-column prop="taxRate" label="税率%" width="80" align="right" />
          <el-table-column label="区间" width="200">
            <template #default="{ row }">{{ row.effectiveDate }} ~ {{ row.expireDate }}</template>
          </el-table-column>
          <el-table-column prop="scope" label="范围" width="80" />
        </el-table>
      </template>
      <template v-if="linkData.flow && linkData.flow.length">
        <el-divider content-position="left">版本流水（倒序）</el-divider>
        <el-table :data="linkData.flow" size="small" stripe>
          <el-table-column prop="taxCode" label="税码" width="100" />
          <el-table-column label="版本" width="60" align="center">
            <template #default="{ row }">V{{ row.versionNo }}</template>
          </el-table-column>
          <el-table-column label="操作" width="70">
            <template #default="{ row }">
              <el-tag size="small" :type="row.opType === 'CREATE' ? 'success' : 'primary'">
                {{ row.opType === 'CREATE' ? '新建' : '变更' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="diffSummary" label="差异摘要" min-width="220" show-overflow-tooltip />
          <el-table-column prop="changeReason" label="原因" width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.changeReason || '—' }}</template>
          </el-table-column>
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ (row.createDate || '').replace('T', ' ').slice(0, 16) }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <!-- 版本对比抽屉 -->
    <el-drawer v-model="cmpVisible" :title="`版本对比：${cmpPolicy.policyNo || ''}`" size="620px">
      <el-form inline>
        <el-form-item label="from">
          <el-select v-model="cmpFrom" placeholder="选择版本" style="width: 150px;">
            <el-option v-for="v in cmpVersions" :key="v.versionNo"
                       :label="`V${v.versionNo}（${v.opType === 'CREATE' ? '新建' : '变更'}）`"
                       :value="v.versionNo" :disabled="v.versionNo === cmpTo" />
          </el-select>
        </el-form-item>
        <el-form-item label="to">
          <el-select v-model="cmpTo" placeholder="选择版本" style="width: 150px;">
            <el-option v-for="v in cmpVersions" :key="v.versionNo"
                       :label="`V${v.versionNo}（${v.opType === 'CREATE' ? '新建' : '变更'}）`"
                       :value="v.versionNo" :disabled="v.versionNo === cmpFrom" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="!cmpFrom || !cmpTo || cmpFrom === cmpTo"
                     :loading="cmpLoading" @click="runDiff">对比</el-button>
        </el-form-item>
      </el-form>
      <el-alert v-if="cmpFrom && cmpTo && cmpFrom === cmpTo" type="warning" :closable="false"
                title="同一版本无需对比" style="margin-bottom: 10px;" />
      <el-table v-if="cmpFields.length" :data="cmpFields" border size="small">
        <el-table-column label="字段" width="130">
          <template #default="{ row }">{{ policyFieldName(row.field) }}</template>
        </el-table-column>
        <el-table-column label="旧值" min-width="170">
          <template #header>旧值 V{{ cmpFrom }}</template>
          <template #default="{ row }"><span class="val-old">{{ row.from || '（空）' }}</span></template>
        </el-table-column>
        <el-table-column label="新值" min-width="170">
          <template #header>新值 V{{ cmpTo }}</template>
          <template #default="{ row }"><span class="val-new">{{ row.to || '（空）' }}</span></template>
        </el-table-column>
      </el-table>
      <el-empty v-else-if="cmpDone" description="两版本无字段差异" :image-size="70" />
      <el-alert v-if="!cmpVersions.length" type="info" :closable="false"
                title="该政策尚无版本快照（存量政策首个快照由本变更后首次写操作生成）" />
    </el-drawer>

    <!-- 政策驱动变更工作台（四步） -->
    <el-drawer v-model="wbVisible" :title="`政策变更工作台：${wbPolicy.policyNo || ''}`" size="90%">
      <el-steps :active="wbStep" align-center style="margin-bottom: 16px;">
        <el-step title="勾选税码" />
        <el-step title="切换参数" />
        <el-step title="预检" />
        <el-step title="提交报告" />
      </el-steps>

      <!-- ① 候选勾选 -->
      <template v-if="wbStep === 0">
        <div class="wb-toolbar">
          <el-input v-model="wbKeyword" placeholder="税码编号筛选" clearable style="width: 200px;" @input="loadCandidates" />
          <span class="tip">已选 {{ wbSelected.length }} 项；计算态/建议切换日取自各税码链尾</span>
        </div>
        <el-table :data="wbCandidates" size="small" border max-height="420"
                  @selection-change="onWBSelect" v-loading="wbLoading">
          <el-table-column type="selection" width="45" />
          <el-table-column prop="taxCode" label="税码" width="110" />
          <el-table-column prop="tailRate" label="现行税率%" width="100" align="right" />
          <el-table-column label="链尾区间" width="200">
            <template #default="{ row }">{{ row.tailEffectiveDate }} ~ {{ row.tailExpireDate }}</template>
          </el-table-column>
          <el-table-column label="计算态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="lcTag(row.lifecycle)">{{ lcName(row.lifecycle) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="建议切换日（尾+1）" width="150">
            <template #default="{ row }">{{ row.suggestedSwitchDate }}</template>
          </el-table-column>
          <el-table-column prop="segments" label="区间数" width="80" />
          <el-table-column prop="currentPolicyNo" label="现行政策文号" min-width="170" show-overflow-tooltip />
        </el-table>
        <el-alert v-if="wbCandidatesHint" type="info" :closable="false" :title="wbCandidatesHint" style="margin-top: 8px;" />
      </template>

      <!-- ② 参数 -->
      <template v-else-if="wbStep === 1">
        <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
          title="统一切换生效日/新段失效日为默认值，行内可单独覆盖；新段的适用范围/计税方式/税率类型继承原记录，政策文号自动=所选政策。"
          description="切换日 ≥ 今天（BR-4.1-18）；历史（已失效）区间零改动。" />
        <el-form inline>
          <el-form-item label="统一切换生效日" required>
            <el-date-picker v-model="wbSwitchDate" type="date" value-format="YYYY-MM-DD" style="width: 160px;" />
          </el-form-item>
          <el-form-item label="统一新段失效日" required>
            <el-date-picker v-model="wbExpireDate" type="date" value-format="YYYY-MM-DD" style="width: 160px;" />
          </el-form-item>
        </el-form>
        <el-table :data="wbRows" size="small" border>
          <el-table-column prop="taxCode" label="税码" width="110" />
          <el-table-column label="新税率%" width="150">
            <template #default="{ row }">
              <el-input-number v-model="row.newRate" :min="0" :max="100" :precision="4"
                               size="small" style="width: 130px;" />
            </template>
          </el-table-column>
          <el-table-column label="切换生效日（行内）" width="200">
            <template #default="{ row }">
              <el-date-picker v-model="row.effectiveDate" type="date" value-format="YYYY-MM-DD"
                              size="small" style="width: 170px;" :placeholder="wbSwitchDate || '统一值'" />
            </template>
          </el-table-column>
          <el-table-column label="新段失效日（行内）" width="200">
            <template #default="{ row }">
              <el-date-picker v-model="row.expireDate" type="date" value-format="YYYY-MM-DD"
                              size="small" style="width: 170px;" :placeholder="wbExpireDate || '统一值'" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ $index }">
              <el-button link type="danger" @click="wbRows.splice($index, 1)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <!-- ③ 预检 -->
      <template v-else-if="wbStep === 2">
        <el-alert v-if="wbPreviewInvalid" type="warning" :closable="false" style="margin-bottom: 10px;"
          :title="`预检失败 ${wbPreviewInvalid} 行（失败行不会被提交执行），可返回上一步修正`" />
        <el-table :data="wbPreviewRows" size="small" border :row-class-name="wbRowClass" max-height="460">
          <el-table-column prop="rowNo" label="行号" width="60" />
          <el-table-column prop="taxCode" label="税码" width="100" />
          <el-table-column label="校验" width="70" align="center">
            <template #default="{ row }">
              <el-tag :type="row.valid ? 'success' : 'danger'" size="small">{{ row.valid ? '通过' : '失败' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="执行计划" min-width="300">
            <template #default="{ row }">
              <div v-for="(s, i) in row.plan" :key="i" style="font-size: 12px;">
                <el-tag size="small" :type="s.action === 'SHORTEN' ? 'warning' : 'success'" style="margin-right: 4px;">
                  {{ s.action === 'SHORTEN' ? '缩短' : '新建' }}
                </el-tag>{{ s.summary }}
              </div>
              <span v-if="row.appliedExpire" class="tip">自动失效日回显：{{ row.appliedExpire }}</span>
            </template>
          </el-table-column>
          <el-table-column label="原因" min-width="240">
            <template #default="{ row }">
              <span :style="row.valid ? '' : 'color:#f56c6c'">{{ row.reason || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <!-- ④ 报告 -->
      <template v-else>
        <el-row :gutter="12" style="margin-bottom: 12px;">
          <el-col :span="6"><el-statistic title="总行数" :value="wbReport.total" /></el-col>
          <el-col :span="6"><el-statistic title="成功" :value="wbReport.succeeded" value-style="color: #67c23a" /></el-col>
          <el-col :span="6"><el-statistic title="失败(含PARTIAL)" :value="wbReport.failed" value-style="color: #f56c6c" /></el-col>
          <el-col :span="6" style="display: flex; align-items: center; gap: 8px;">
            <el-button size="small" type="primary" @click="afterSubmitLink">查看关联变更</el-button>
          </el-col>
        </el-row>
        <el-table :data="wbReport.details" size="small" border max-height="440">
          <el-table-column prop="rowNo" label="行号" width="60" />
          <el-table-column prop="taxCode" label="税码" width="100" />
          <el-table-column label="结果" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.result === 'SUCCESS' ? 'success' : (row.result === 'PARTIAL' ? 'warning' : 'danger')">
                {{ row.result === 'SUCCESS' ? '成功' : (row.result === 'PARTIAL' ? '部分' : '失败') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="动作" min-width="240">
            <template #default="{ row }">
              <div v-for="(a, i) in row.actions" :key="i" style="font-size: 12px;">
                {{ a.action === 'SHORTEN' ? '缩短' : '新建' }}：{{ a.summary }}
                <span :style="a.result === 'OK' ? 'color:#67c23a' : 'color:#f56c6c'">[{{ a.result }}]</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="原因" min-width="240">
            <template #default="{ row }">
              <span :style="row.result === 'SUCCESS' ? '' : 'color:#f56c6c'">{{ row.reason || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template #footer>
        <el-button @click="wbVisible = false">关闭</el-button>
        <el-button v-if="wbStep > 0 && wbStep < 3" @click="wbStep--">上一步</el-button>
        <el-button v-if="wbStep === 0" type="primary" :disabled="!wbSelected.length" @click="toWbParams">
          下一步：切换参数（已选 {{ wbSelected.length }}）
        </el-button>
        <el-button v-if="wbStep === 1" type="primary" :disabled="!wbRows.length" @click="runWbPreview">
          下一步：预检
        </el-button>
        <el-button v-if="wbStep === 2" type="danger" :loading="wbSubmitting"
                   :disabled="!wbPreviewRows.some(r => r.valid)" @click="runWbSubmit">
          {{ wbInvalidCount ? `提交（${wbInvalidCount} 行将失败）` : '确认提交' }}
        </el-button>
        <el-button v-if="wbStep === 3" type="primary" @click="wbReset">再发起一批</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/user'
import {
  getPolicyPageApi, createPolicyApi, updatePolicyApi, deletePolicyApi, getPolicyTaxCodesApi,
  getPolicyVersionsApi, getPolicyDiffApi,
  getWorkbenchCandidatesApi, previewWorkbenchApi, submitWorkbenchApi
} from '@/api/mdm/tax-policy'

const userStore = useUserStore()
const isAdmin = computed(() => (userStore.userInfo?.roles || []).includes('ROLE_ADMIN'))
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ keyword: '', current: 1, size: 10 })

function lcName(lc) {
  return { NOT_EFFECTIVE: '未生效', EFFECTIVE: '生效中', EXPIRED: '已失效' }[lc] || lc
}
function lcTag(lc) {
  return { NOT_EFFECTIVE: 'info', EFFECTIVE: 'success', EXPIRED: 'warning' }[lc]
}
function policyFieldName(f) {
  return {
    policyNo: '政策文号', policyName: '政策名称', issuer: '发文机关',
    issueDate: '发布日期', effectiveDate: '生效日期', summary: '摘要', remark: '备注'
  }[f] || f
}

async function loadData(page) {
  if (page) query.value.current = page
  loading.value = true
  try {
    const res = await getPolicyPageApi(query.value)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

// ---------- 登记/编辑 ----------
const formVisible = ref(false)
const form = ref({})
const formMsg = ref('')

function openCreate() {
  form.value = {
    policyNo: '', policyName: '', issuer: '', issueDate: '', effectiveDate: '', summary: '', remark: ''
  }
  formMsg.value = ''
  formVisible.value = true
}
function openEdit(row) {
  form.value = { ...row }
  formMsg.value = ''
  formVisible.value = true
}
async function submitForm() {
  formMsg.value = ''
  saving.value = true
  try {
    if (form.value.id) {
      await updatePolicyApi(form.value)
      ElMessage.success('政策已更新')
    } else {
      await createPolicyApi(form.value)
      ElMessage.success('政策已登记')
    }
    formVisible.value = false
    loadData()
  } catch (e) {
    if (e?.message) formMsg.value = e.message
  } finally {
    saving.value = false
  }
}
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`删除政策 [${row.policyNo}]？未被税码引用方可删除`, '删除确认', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await deletePolicyApi(row.id)
    ElMessage.success('已删除')
    loadData()
  } catch (e) {
    // 引用禁删 422 由拦截器提示
  }
}

// ---------- 关联变更 ----------
const linkVisible = ref(false)
const linkPolicy = ref({})
const linkData = ref({})

async function openLink(row) {
  linkPolicy.value = row
  linkData.value = {}
  linkVisible.value = true
  const res = await getPolicyTaxCodesApi(row.id)
  linkData.value = res.data
}

// ---------- 版本对比 ----------
const cmpVisible = ref(false)
const cmpPolicy = ref({})
const cmpVersions = ref([])
const cmpFrom = ref(null)
const cmpTo = ref(null)
const cmpFields = ref([])
const cmpLoading = ref(false)
const cmpDone = ref(false)

async function openCompare(row) {
  cmpPolicy.value = row
  cmpFrom.value = null
  cmpTo.value = null
  cmpFields.value = []
  cmpDone.value = false
  cmpVisible.value = true
  const res = await getPolicyVersionsApi(row.id)
  cmpVersions.value = res.data
  if (cmpVersions.value.length >= 2) {
    cmpFrom.value = cmpVersions.value[0].versionNo
    cmpTo.value = cmpVersions.value[cmpVersions.value.length - 1].versionNo
  } else if (cmpVersions.value.length === 1) {
    cmpTo.value = cmpVersions.value[0].versionNo
  }
}
async function runDiff() {
  if (cmpFrom.value === cmpTo.value) return
  cmpLoading.value = true
  try {
    const res = await getPolicyDiffApi(cmpPolicy.value.id, cmpFrom.value, cmpTo.value)
    cmpFields.value = res.data.fields || []
    cmpDone.value = true
  } finally {
    cmpLoading.value = false
  }
}

// ---------- 工作台（四步） ----------
const wbVisible = ref(false)
const wbPolicy = ref({})
const wbStep = ref(0)
const wbLoading = ref(false)
const wbKeyword = ref('')
const wbCandidates = ref([])
const wbCandidatesHint = ref('')
const wbSelected = ref([])
const wbSwitchDate = ref('')
const wbExpireDate = ref('')
const wbRows = ref([])
const wbPreviewRows = ref([])
const wbSubmitting = ref(false)
const wbReport = ref({ total: 0, succeeded: 0, failed: 0, details: [] })

const wbPreviewInvalid = computed(() => wbPreviewRows.value.filter(r => !r.valid).length)
const wbInvalidCount = computed(() => wbPreviewRows.value.filter(r => !r.valid).length)

function wbRowClass({ row }) {
  return row.valid ? '' : 'row-invalid'
}

async function openWorkbench(row) {
  wbPolicy.value = row
  wbStep.value = 0
  wbKeyword.value = ''
  wbSelected.value = []
  wbRows.value = []
  wbPreviewRows.value = []
  wbSwitchDate.value = ''
  wbExpireDate.value = ''
  wbReport.value = { total: 0, succeeded: 0, failed: 0, details: [] }
  wbVisible.value = true
  await loadCandidates()
}

async function loadCandidates() {
  wbLoading.value = true
  try {
    const res = await getWorkbenchCandidatesApi(wbPolicy.value.id, { keyword: wbKeyword.value })
    wbCandidates.value = res.data.candidates
    wbCandidatesHint.value = res.data.hint || ''
  } finally {
    wbLoading.value = false
  }
}

function onWBSelect(sel) {
  wbSelected.value = sel
}

function toWbParams() {
  wbRows.value = wbSelected.value.map(c => ({
    taxCode: c.taxCode,
    newRate: null,
    effectiveDate: '',
    expireDate: '',
    tailRate: c.tailRate,
    suggestedSwitchDate: c.suggestedSwitchDate
  }))
  // 统一切换日默认取所选项中最早的建议切换日
  const suggestions = wbSelected.value.map(c => c.suggestedSwitchDate).sort()
  wbSwitchDate.value = suggestions[0] || ''
  wbStep.value = 1
}

async function runWbPreview() {
  if (!wbSwitchDate.value) {
    ElMessage.warning('统一切换生效日必填')
    return
  }
  const missing = wbRows.value.filter(r => r.newRate == null)
  if (missing.length) {
    ElMessage.warning(`行内新税率必填：${missing.map(r => r.taxCode).join('、')}`)
    return
  }
  wbLoading.value = true
  try {
    const res = await previewWorkbenchApi(wbPolicy.value.id, {
      rows: wbRows.value, switchDate: wbSwitchDate.value, expireDate: wbExpireDate.value
    })
    wbPreviewRows.value = res.data.results
    wbStep.value = 2
  } finally {
    wbLoading.value = false
  }
}

async function runWbSubmit() {
  wbSubmitting.value = true
  try {
    const res = await submitWorkbenchApi(wbPolicy.value.id, {
      rows: wbRows.value, switchDate: wbSwitchDate.value, expireDate: wbExpireDate.value
    })
    wbReport.value = res.data
    wbStep.value = 3
    if (res.data.failed > 0) {
      ElMessage.warning(`提交完成：成功 ${res.data.succeeded} 行，失败/部分 ${res.data.failed} 行`)
    } else {
      ElMessage.success(`政策变更提交成功 ${res.data.succeeded} 行`)
    }
  } catch (e) {
    // 422 上限/空行已单点提示
  } finally {
    wbSubmitting.value = false
  }
}

async function afterSubmitLink() {
  wbVisible.value = false
  await openLink(wbPolicy.value)
}

function wbReset() {
  wbStep.value = 0
  wbSelected.value = []
  wbRows.value = []
  wbPreviewRows.value = []
  loadCandidates()
}

onMounted(() => loadData())
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.wb-toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 10px; }
.tip { font-size: 12px; color: #909399; }
.val-old { background: #fde2e2; color: #c45656; padding: 2px 6px; border-radius: 3px; }
.val-new { background: #d9f7be; color: #52c41a; padding: 2px 6px; border-radius: 3px; }
:deep(.row-invalid) { background: #fef0f0; }
</style>
