<template>
  <div class="legal-entity">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">法人主体</span>
          <div class="header-actions">
            <el-input
              v-model="query.keyword" placeholder="编码/名称" clearable
              :prefix-icon="Search" style="width: 200px;" @keyup.enter="loadData(1)"
            />
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 110px;">
              <el-option label="启用" value="1" />
              <el-option label="停用" value="0" />
            </el-select>
            <el-button type="primary" @click="loadData(1)">查询</el-button>
            <el-button type="primary" :icon="Plus" @click="openForm()">新建</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="leCode" label="主体编码" width="110" />
        <el-table-column prop="leName" label="主体名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="uscc" label="统一社会信用代码" width="180" />
        <el-table-column prop="bookkeepingCurrency" label="本位币" width="80" />
        <el-table-column prop="regPlace" label="注册地" width="90" />
        <el-table-column prop="fiscalCalendarType" label="会计日历" width="110">
          <template #default="{ row }">{{ calendarMap[row.fiscalCalendarType] || row.fiscalCalendarType }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === '1' ? 'success' : 'info'" size="small">
              {{ row.status === '1' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="verNo" label="版本" width="70" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" :disabled="row.status === '0'" @click="openForm(row)">变更</el-button>
            <el-button link type="danger" :disabled="row.status === '0'" @click="handleDisable(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        style="margin-top: 14px; justify-content: flex-end;"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.size"
        :current-change="loadData"
      />
    </el-card>

    <!-- 新建 / 变更弹窗 -->
    <el-dialog v-model="formVisible" :title="form.id ? '变更法人主体' : '新建法人主体'" width="720px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="150px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="主体编码">
              <el-input v-model="form.leCode" disabled :placeholder="form.id ? '' : '保存后自动生成 LE-XXXX'" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="主体名称" prop="leName">
              <el-input v-model="form.leName" maxlength="128" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="统一社会信用代码" prop="uscc">
              <el-input v-model="form.uscc" maxlength="32" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="记账本位币" prop="bookkeepingCurrency">
              <el-select v-model="form.bookkeepingCurrency" style="width: 100%;">
                <el-option v-for="c in currencies" :key="c" :label="c" :value="c" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="注册地" prop="regPlace">
              <el-input v-model="form.regPlace" maxlength="64" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="会计日历类型" prop="fiscalCalendarType">
              <el-select v-model="form.fiscalCalendarType" style="width: 100%;">
                <el-option label="自然年制" value="NATURAL" />
                <el-option label="4 月制" value="APRIL" />
                <el-option label="7 月制" value="JULY" />
                <el-option label="52-53 周制" value="WEEK" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="默认时区" prop="defaultTimezone">
              <el-select v-model="form.defaultTimezone" filterable style="width: 100%;">
                <el-option v-for="tz in timezones" :key="tz" :label="tz" :value="tz" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="币种小数位" prop="currencyDecimals">
              <el-input-number v-model="form.currencyDecimals" :min="0" :max="4" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="本地化合规字段包">
              <el-select v-model="form.l10nPack" clearable style="width: 100%;">
                <el-option label="中国（纳税人识别号）" value="CN" />
                <el-option label="日本（登录番号）" value="JP" />
                <el-option label="欧盟（VAT ID）" value="EU" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="本地税号">
              <el-input v-model="form.localTaxNo" maxlength="32" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="法人代表">
              <el-input v-model="form.legalRepresentative" maxlength="64" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="注册资本（万元）">
              <el-input-number v-model="form.registeredCapital" :min="0" :precision="2" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="注册地址" label-width="150px">
              <el-input v-model="form.regAddress" maxlength="255" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="开户行">
              <el-input v-model="form.bankName" maxlength="128" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="关联成本中心" label-width="150px">
              <template v-if="!form.id">
                <span class="form-tip">暂无关联 —— 请先在成本中心模块创建并挂载本主体，保存后重新打开本表单可见</span>
              </template>
              <template v-else-if="formCostCenters.length">
                <el-tag v-for="c in formCostCenters" :key="c.id" size="small" style="margin: 0 6px 6px 0;">
                  {{ c.code }} {{ c.name }}
                </el-tag>
              </template>
              <span v-else class="form-tip">本主体名下暂无启用的成本中心</span>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="关联利润中心" label-width="150px">
              <template v-if="!form.id">
                <span class="form-tip">暂无关联 —— 请先在利润中心模块创建并挂载本主体，保存后重新打开本表单可见</span>
              </template>
              <template v-else-if="formProfitCenters.length">
                <el-tag v-for="p in formProfitCenters" :key="p.id" size="small" type="warning" style="margin: 0 6px 6px 0;">
                  {{ p.code }} {{ p.name }}
                </el-tag>
              </template>
              <span v-else class="form-tip">本主体名下暂无启用的利润中心</span>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉（含版本历史与对比） -->
    <el-drawer v-model="detailVisible" title="法人主体详情" size="620px">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="主体编码">{{ detail.leCode }}</el-descriptions-item>
        <el-descriptions-item label="主体名称">{{ detail.leName }}</el-descriptions-item>
        <el-descriptions-item label="统一社会信用代码">{{ detail.uscc }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="detail.status === '1' ? 'success' : 'info'" size="small">
            {{ detail.status === '1' ? '启用' : '停用' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="记账本位币">{{ detail.bookkeepingCurrency }}</el-descriptions-item>
        <el-descriptions-item label="注册地">{{ detail.regPlace }}</el-descriptions-item>
        <el-descriptions-item label="会计日历">{{ calendarMap[detail.fiscalCalendarType] || detail.fiscalCalendarType }}</el-descriptions-item>
        <el-descriptions-item label="默认时区">{{ detail.defaultTimezone }}</el-descriptions-item>
        <el-descriptions-item label="本地化字段包">{{ detail.l10nPack || '-' }}</el-descriptions-item>
        <el-descriptions-item label="本地税号">{{ detail.localTaxNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="法人代表">{{ detail.legalRepresentative || '-' }}</el-descriptions-item>
        <el-descriptions-item label="注册资本">{{ detail.registeredCapital ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="注册地址">{{ detail.regAddress || '-' }}</el-descriptions-item>
        <el-descriptions-item label="开户行">{{ detail.bankName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="关联成本中心">
          <template v-if="detailCostCenters.length">
            <el-tag v-for="c in detailCostCenters" :key="c.id" size="small" style="margin: 0 6px 6px 0;">
              {{ c.code }} {{ c.name }}
            </el-tag>
          </template>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="关联利润中心">
          <template v-if="detailProfitCenters.length">
            <el-tag v-for="p in detailProfitCenters" :key="p.id" size="small" type="warning" style="margin: 0 6px 6px 0;">
              {{ p.code }} {{ p.name }}
            </el-tag>
          </template>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="当前版本">V{{ detail.verNo }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">版本历史</el-divider>
      <div style="margin-bottom: 10px; display: flex; gap: 8px; align-items: center;">
        <el-select v-model="diffFrom" placeholder="起始版本" style="width: 120px;">
          <el-option v-for="v in versions" :key="v.versionNo" :label="`V${v.versionNo}`" :value="v.versionNo" />
        </el-select>
        <span>→</span>
        <el-select v-model="diffTo" placeholder="目标版本" style="width: 120px;">
          <el-option v-for="v in versions" :key="v.versionNo" :label="`V${v.versionNo}`" :value="v.versionNo" />
        </el-select>
        <el-button type="primary" plain :disabled="!diffFrom || !diffTo || diffFrom === diffTo" @click="loadDiff">
          对比
        </el-button>
      </div>

      <el-table :data="versions" size="small" stripe>
        <el-table-column prop="versionNo" label="版本" width="60">
          <template #default="{ row }">V{{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column prop="opType" label="操作" width="80">
          <template #default="{ row }">{{ opMap[row.opType] || row.opType }}</template>
        </el-table-column>
        <el-table-column prop="diffSummary" label="变更摘要" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.diffSummary || '初始版本' }}</template>
        </el-table-column>
        <el-table-column prop="createBy" label="操作人" width="110" />
        <el-table-column prop="createDate" label="时间" width="160">
          <template #default="{ row }">{{ (row.createDate || '').replace('T', ' ') }}</template>
        </el-table-column>
      </el-table>

      <template v-if="diffFields.length">
        <el-divider content-position="left">差异对比</el-divider>
        <el-table :data="diffFields" size="small" border>
          <el-table-column prop="field" label="字段" width="170" />
          <el-table-column prop="from" label="起始值" show-overflow-tooltip />
          <el-table-column prop="to" label="目标值" show-overflow-tooltip />
        </el-table>
      </template>
      <el-empty v-else-if="diffRequested" description="两版本无差异" :image-size="60" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import {
  getLegalEntityPageApi, getLegalEntityApi, createLegalEntityApi,
  updateLegalEntityApi, disableLegalEntityApi, getVersionsApi, getDiffApi
} from '@/api/mdm/legal-entity'
import { getCostCenterOptionsApi } from '@/api/mdm/cost-center'
import { getProfitCenterOptionsApi } from '@/api/mdm/profit-center'

const calendarMap = { NATURAL: '自然年制', APRIL: '4 月制', JULY: '7 月制', WEEK: '52-53 周制' }
const opMap = { CREATE: '新建', UPDATE: '变更', DISABLE: '停用' }
const currencies = ['CNY', 'USD', 'EUR', 'JPY', 'HKD']
const timezones = ['Asia/Shanghai', 'Asia/Tokyo', 'Asia/Hong_Kong', 'Europe/Berlin', 'America/New_York', 'UTC']

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ current: 1, size: 10, keyword: '', status: '' })

const formVisible = ref(false)
const formRef = ref(null)
const form = ref({})
const rules = {
  leName: [{ required: true, message: '请输入主体名称', trigger: 'blur' }],
  uscc: [{ required: true, message: '请输入统一社会信用代码', trigger: 'blur' }],
  bookkeepingCurrency: [{ required: true, message: '请选择记账本位币', trigger: 'change' }],
  regPlace: [{ required: true, message: '请输入注册地', trigger: 'blur' }],
  fiscalCalendarType: [{ required: true, message: '请选择会计日历类型', trigger: 'change' }],
  defaultTimezone: [{ required: true, message: '请选择默认时区', trigger: 'change' }],
  currencyDecimals: [{ required: true, message: '请设置币种小数位', trigger: 'change' }]
}

const detailVisible = ref(false)
const detail = ref({})
const versions = ref([])
const diffFrom = ref(null)
const diffTo = ref(null)
const diffFields = ref([])
const diffRequested = ref(false)

// 派生关联中心（方案 A：中心 → 主体方向，实时反查，只读展示）
const formCostCenters = ref([])
const formProfitCenters = ref([])
const detailCostCenters = ref([])
const detailProfitCenters = ref([])

/** 按主体 id 反查名下全部启用中心；id 为空返回空 */
async function loadCenters(entityId) {
  if (!entityId) return { costs: [], profits: [] }
  const [c, p] = await Promise.all([
    getCostCenterOptionsApi(entityId),
    getProfitCenterOptionsApi(entityId)
  ])
  return { costs: c.data, profits: p.data }
}

async function loadData(page) {
  if (page) query.value.current = page
  loading.value = true
  try {
    const res = await getLegalEntityPageApi(query.value)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function openForm(row) {
  form.value = row
    ? { ...row }
    : {
        leName: '', uscc: '', bookkeepingCurrency: 'CNY', regPlace: '',
        fiscalCalendarType: 'NATURAL', defaultTimezone: 'Asia/Shanghai',
        currencyDecimals: 2, l10nPack: 'CN'
      }
  loadCenters(row ? row.id : '').then(({ costs, profits }) => {
    formCostCenters.value = costs
    formProfitCenters.value = profits
  })
  formVisible.value = true
}

async function submitForm() {
  await formRef.value.validate()
  saving.value = true
  try {
    // 退役字段不进提交体（spec：关联中心由中心侧派生展示）
    const { costCenterCode, profitCenterCode, ...payload } = form.value
    if (payload.id) {
      await updateLegalEntityApi(payload)
      ElMessage.success('变更已生效')
    } else {
      await createLegalEntityApi(payload)
      ElMessage.success('新建成功')
    }
    formVisible.value = false
    loadData()
  } finally {
    saving.value = false
  }
}

async function handleDisable(row) {
  await ElMessageBox.confirm(
    `确认停用「${row.leName}」？停用后新业务不可引用，历史数据仍可查询。`,
    '停用确认', { type: 'warning' }
  )
  await disableLegalEntityApi(row.id)
  ElMessage.success('已停用')
  loadData()
}

async function openDetail(row) {
  const res = await getLegalEntityApi(row.id)
  detail.value = res.data
  const [{ costs, profits }, vRes] = await Promise.all([
    loadCenters(row.id),
    getVersionsApi(row.id)
  ])
  detailCostCenters.value = costs
  detailProfitCenters.value = profits
  versions.value = vRes.data
  diffFrom.value = null
  diffTo.value = null
  diffFields.value = []
  diffRequested.value = false
  detailVisible.value = true
}

async function loadDiff() {
  const res = await getDiffApi(detail.value.id, diffFrom.value, diffTo.value)
  diffFields.value = res.data.fields
  diffRequested.value = true
}

onMounted(() => loadData())
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-actions {
  display: flex;
  gap: 8px;
}
.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>
