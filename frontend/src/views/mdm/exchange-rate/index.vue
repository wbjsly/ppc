<template>
  <div class="exchange-rate">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">汇率维护</span>
          <div class="header-actions">
            <el-input v-model="query.keyword" placeholder="币对（如 CNY/USD）" clearable
                      :prefix-icon="Search" style="width: 170px;" @keyup.enter="loadData(1)" />
            <el-select v-model="query.rateType" placeholder="类型" clearable style="width: 120px;" @change="loadData(1)">
              <el-option label="中间价" value="MIDDLE" />
              <el-option label="买入价" value="BUY" />
              <el-option label="卖出价" value="SELL" />
            </el-select>
            <el-select v-model="query.lifecycle" placeholder="计算态" clearable style="width: 120px;" @change="loadData(1)">
              <el-option label="未生效" value="NOT_EFFECTIVE" />
              <el-option label="生效中" value="EFFECTIVE" />
              <el-option label="已失效" value="EXPIRED" />
            </el-select>
            <el-button type="primary" @click="loadData(1)">查询</el-button>
            <el-button type="primary" :icon="Plus" @click="openCreate">新建汇率</el-button>
          </div>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
        title="区间规则（BR-4.1-17）：同币对×类型 的区间须首尾衔接（不重叠、不断档）；已失效历史不可篡改（BR-4.1-18）。"
        description="来源文件编号（C-4.1-04）与生效/失效日期必填；汇率 6 位小数精度。" />

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="币对" width="110">
          <template #default="{ row }"><b>{{ row.baseCcy }}/{{ row.quoteCcy }}</b></template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small">{{ typeName(row.rateType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="区间" width="210">
          <template #default="{ row }">{{ row.effectiveDate }} ~ {{ row.expireDate }}</template>
        </el-table-column>
        <el-table-column prop="rate" label="汇率" width="120" align="right" />
        <el-table-column prop="sourceFileNo" label="来源文件编号" min-width="160" show-overflow-tooltip />
        <el-table-column label="计算态" width="100">
          <template #default="{ row }">
            <el-tag :type="lcTag(row.lifecycle)" size="small">{{ lcName(row.lifecycle) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <template v-if="row.lifecycle === 'EXPIRED'">
              <span style="color: #909399; font-size: 12px;">历史只读</span>
            </template>
            <template v-else>
              <el-button link type="primary" @click="openEdit(row)">变更</el-button>
              <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination layout="total, prev, pager, next" style="margin-top: 12px; justify-content: flex-end;"
        :total="total" :page-size="query.size" :current-change="loadData" />

      <el-divider content-position="left">汇率试算查询（FR-4.6-1-5 预铺）</el-divider>
      <el-form inline>
        <el-form-item label="币对">
          <el-input v-model="trialForm.baseCcy" placeholder="BASE 如 CNY" style="width: 100px;" />
          <span style="margin: 0 6px;">/</span>
          <el-input v-model="trialForm.quoteCcy" placeholder="QUOTE 如 USD" style="width: 100px;" />
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker v-model="trialForm.date" type="date" value-format="YYYY-MM-DD" style="width: 150px;" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="trialForm.rateType" clearable placeholder="缺省自动回退" style="width: 140px;">
            <el-option label="中间价" value="MIDDLE" />
            <el-option label="买入价" value="BUY" />
            <el-option label="卖出价" value="SELL" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="doTrial">试算</el-button>
        </el-form-item>
      </el-form>
      <el-card v-if="trialResult" shadow="never">
        <template v-if="trialResult.applicable">
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="汇率"><b>{{ trialResult.rate }}</b></el-descriptions-item>
            <el-descriptions-item label="实际类型">{{ typeName(trialResult.rateType) }}</el-descriptions-item>
            <el-descriptions-item label="来源编号">{{ trialResult.sourceFileNo }}</el-descriptions-item>
            <el-descriptions-item label="生效区间" :span="2">{{ trialResult.effectiveDate }} ~ {{ trialResult.expireDate }}</el-descriptions-item>
            <el-descriptions-item label="回退">{{ trialResult.fallbackFrom || '显式类型直查' }}</el-descriptions-item>
          </el-descriptions>
        </template>
        <el-result v-else icon="warning" :title="trialResult.message" :sub-title="trialResult.hint">
          <template #extra>
            <ul style="text-align: left; color: #909399;">
              <li v-for="(r, i) in trialResult.reasons" :key="i">{{ r }}</li>
            </ul>
          </template>
        </el-result>
      </el-card>
    </el-card>

    <!-- 新建/变更弹窗（760px：label 130 + 3 列 span-8 需 ≥110px 输入位，620 会把 input-number 挤到 0 宽） -->
    <el-dialog v-model="formVisible" :title="form.id ? '变更汇率记录' : '新建汇率'" width="760px">
      <el-alert v-if="conflictMsg" type="error" show-icon :closable="false" :title="conflictMsg" style="margin-bottom: 10px;" />
      <el-form label-width="130px">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="基础币种" required>
              <el-input v-model="form.baseCcy" :disabled="!!form.id" maxlength="3" placeholder="CNY"
                        style="text-transform: uppercase;" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="报价币种" required>
              <el-input v-model="form.quoteCcy" :disabled="!!form.id" maxlength="3" placeholder="USD"
                        style="text-transform: uppercase;" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="类型" required>
              <el-select v-model="form.rateType" :disabled="!!form.id" style="width: 100%;">
                <el-option label="中间价" value="MIDDLE" />
                <el-option label="买入价" value="BUY" />
                <el-option label="卖出价" value="SELL" />
              </el-select>
            </el-form-item>
          </el-col>
          <div v-if="form.id" class="form-tip" style="margin-bottom: 8px; grid-column: span 24;">
            币对与类型为区间序列键，创建后不可修改（变更仅可调区间/汇率值/来源编号）
          </div>
          <el-col :span="8">
            <el-form-item label="生效日期" required>
              <el-date-picker v-model="form.effectiveDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="失效日期" required>
              <el-date-picker v-model="form.expireDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="汇率" required>
              <el-input-number v-model="form.rate" :min="0.000001" :precision="6" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item label="来源文件编号" required>
              <el-input v-model="form.sourceFileNo" maxlength="64" placeholder="如：央行公告2026-10（C-4.1-04）" />
            </el-form-item>
          </el-col>
          <el-col v-if="form.id" :span="24">
            <el-form-item label="变更原因" required>
              <el-input v-model="form.changeReason" maxlength="255" placeholder="必填（≥2 字）" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import {
  getRatePageApi, createRateApi, updateRateApi, deleteRateApi, trialApi
} from '@/api/mdm/exchange-rate'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ keyword: '', rateType: '', lifecycle: '', current: 1, size: 10 })

function typeName(t) {
  return { MIDDLE: '中间价', BUY: '买入价', SELL: '卖出价' }[t] || t
}
function lcName(lc) {
  return { NOT_EFFECTIVE: '未生效', EFFECTIVE: '生效中', EXPIRED: '已失效' }[lc] || lc
}
function lcTag(lc) {
  return { NOT_EFFECTIVE: 'info', EFFECTIVE: 'success', EXPIRED: 'warning' }[lc]
}

async function loadData(page) {
  if (page) query.value.current = page
  loading.value = true
  try {
    const params = { ...query.value }
    // keyword 支持 "CNY/USD" 拆成币对筛选（简化：整体走 baseCcy 模糊交给后端等值匹配，斜杠拆分）
    if (params.keyword && params.keyword.includes('/')) {
      const [b, q] = params.keyword.split('/')
      params.baseCcy = b.trim()
      params.quoteCcy = q.trim()
    }
    delete params.keyword
    const res = await getRatePageApi(params)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

// ---------- 新建/变更 ----------
const formVisible = ref(false)
const form = ref({})
const conflictMsg = ref('')

function openCreate() {
  form.value = { baseCcy: '', quoteCcy: '', rateType: 'MIDDLE', effectiveDate: '', expireDate: '', rate: null, sourceFileNo: '' }
  conflictMsg.value = ''
  formVisible.value = true
}
function openEdit(row) {
  form.value = { ...row, changeReason: '' }
  conflictMsg.value = ''
  formVisible.value = true
}
async function submitForm() {
  conflictMsg.value = ''
  saving.value = true
  try {
    if (form.value.id) {
      await updateRateApi(form.value)
      ElMessage.success('变更已生效')
    } else {
      await createRateApi(form.value)
      ElMessage.success('新建成功')
    }
    formVisible.value = false
    loadData()
  } catch (e) {
    const msg = e?.message || ''
    // 冲突/断档/必填 422 → 弹窗内 Alert 回显清单（前端保留弹窗便于修正）
    if (msg.includes('区间') || msg.includes('断档') || msg.includes('缺口') || msg.includes('必填')
        || msg.includes('ISO') || msg.includes('不可修改') || msg.includes('篡改')) {
      conflictMsg.value = msg
    }
  } finally {
    saving.value = false
  }
}
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`删除该汇率区间 [${row.effectiveDate}, ${row.expireDate}]？`, '删除确认', { type: 'warning' })
  } catch (e) {
    return
  }
  await deleteRateApi(row.id)
  ElMessage.success('已删除')
  loadData()
}

// ---------- 试算 ----------
const trialForm = ref({ baseCcy: 'CNY', quoteCcy: 'USD', date: '', rateType: '' })
const trialResult = ref(null)

async function doTrial() {
  if (!trialForm.value.baseCcy || !trialForm.value.quoteCcy) {
    ElMessage.warning('请填写币对')
    return
  }
  loading.value = true
  try {
    const res = await trialApi(trialForm.value)
    trialResult.value = res.data
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  // 历史页（1.5.3）「按此历史值发起维护」预填：币对/类型/汇率/来源编号，生效日期留空
  const prefill = route.query.prefill
  if (prefill) {
    const p = new URLSearchParams(String(prefill))
    openCreate()
    form.value = {
      ...form.value,
      baseCcy: (p.get('baseCcy') || '').toUpperCase(),
      quoteCcy: (p.get('quoteCcy') || '').toUpperCase(),
      rateType: p.get('rateType') || 'MIDDLE',
      rate: p.get('rate') ? Number(p.get('rate')) : null,
      sourceFileNo: p.get('sourceFileNo') || ''
    }
    router.replace({ path: '/m/1.5.1' })
    ElMessage.info('已按历史版本预填，请重新指定当期生效区间后保存（BR-4.1-17 照常校验）')
  }
  loadData()
})
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.form-tip { font-size: 12px; color: #909399; line-height: 1.5; }
</style>
