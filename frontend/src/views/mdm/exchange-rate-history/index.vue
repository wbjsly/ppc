<template>
  <div class="exchange-rate-history">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">历史汇率</span>
          <el-radio-group v-model="tab" @change="onTabChange">
            <el-radio-button value="sequence">区间历史链</el-radio-button>
            <el-radio-button value="timeline">版本时间线</el-radio-button>
          </el-radio-group>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
        title="历史保留（FR-4.1-3-4 / BR-4.1-18）：本页只读，已发生业务按当时生效汇率核算，历史不可篡改。"
        description="区间历史链按 币对×类型 展示完整 append-only 区间；版本时间线为跨记录快照流水，支持任意两版本对比与按历史值预填维护。" />

      <!-- ========== Tab1 区间历史链 ========== -->
      <template v-if="tab === 'sequence'">
        <el-form inline @submit.prevent>
          <el-form-item label="币对" required>
            <el-input v-model="seqForm.baseCcy" placeholder="BASE 如 USD" maxlength="3"
                      style="width: 100px; text-transform: uppercase;" @keyup.enter="loadSequence" />
            <span style="margin: 0 6px;">/</span>
            <el-input v-model="seqForm.quoteCcy" placeholder="QUOTE 如 CNY" maxlength="3"
                      style="width: 100px; text-transform: uppercase;" @keyup.enter="loadSequence" />
          </el-form-item>
          <el-form-item label="类型" required>
            <el-select v-model="seqForm.rateType" style="width: 130px;">
              <el-option label="中间价" value="MIDDLE" />
              <el-option label="买入价" value="BUY" />
              <el-option label="卖出价" value="SELL" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="loading" @click="loadSequence">查询历史链</el-button>
          </el-form-item>
        </el-form>

        <el-table :data="seqRows" v-loading="loading" stripe>
          <el-table-column label="#" width="50" type="index" />
          <el-table-column label="区间" width="210">
            <template #default="{ row }">{{ row.effectiveDate }} ~ {{ row.expireDate }}</template>
          </el-table-column>
          <el-table-column prop="rate" label="汇率" width="130" align="right" />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <el-tag size="small">{{ typeName(row.rateType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="sourceFileNo" label="来源文件编号" min-width="160" show-overflow-tooltip />
          <el-table-column label="计算态" width="100">
            <template #default="{ row }">
              <el-tag :type="lcTag(row.lifecycle)" size="small">{{ lcName(row.lifecycle) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="verNo" label="版本" width="70" />
          <el-table-column prop="createBy" label="创建人" width="120" />
          <el-table-column prop="createDate" label="创建时间" width="170">
            <template #default="{ row }">{{ (row.createDate || '').replace('T', ' ').slice(0, 19) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="seqSearched && !loading && seqRows.length === 0"
                  description="该序列暂无历史" :image-size="80" />
      </template>

      <!-- ========== Tab2 版本时间线 ========== -->
      <template v-else>
        <el-form inline @submit.prevent>
          <el-form-item label="类型">
            <el-select v-model="tlQuery.rateType" clearable placeholder="全部" style="width: 120px;" @change="loadHistory(1)">
              <el-option label="中间价" value="MIDDLE" />
              <el-option label="买入价" value="BUY" />
              <el-option label="卖出价" value="SELL" />
            </el-select>
          </el-form-item>
          <el-form-item label="操作">
            <el-select v-model="tlQuery.opType" clearable placeholder="全部" style="width: 110px;" @change="loadHistory(1)">
              <el-option label="新建" value="CREATE" />
              <el-option label="变更" value="UPDATE" />
            </el-select>
          </el-form-item>
          <el-form-item label="关键字">
            <el-input v-model="tlQuery.keyword" placeholder="币对（USD/CNY）或来源编号" clearable
                      style="width: 220px;" @keyup.enter="loadHistory(1)" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="loading" @click="loadHistory(1)">查询</el-button>
          </el-form-item>
        </el-form>

        <el-table :data="tlRows" v-loading="loading" stripe>
          <el-table-column label="记录标识" width="180">
            <template #default="{ row }">
              <b>{{ row.baseCcy }}/{{ row.quoteCcy }}</b>
              <el-tag size="small" style="margin-left: 4px;">{{ typeName(row.rateType) }}</el-tag>
              <div style="font-size: 12px; color: #909399;">{{ row.effectiveDate }} ~ {{ row.expireDate }}</div>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="60" align="center">
            <template #default="{ row }">V{{ row.versionNo }}</template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-tag :type="row.opType === 'CREATE' ? 'success' : 'primary'" size="small">
                {{ row.opType === 'CREATE' ? '新建' : '变更' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="diffSummary" label="差异摘要" min-width="220" show-overflow-tooltip />
          <el-table-column prop="changeReason" label="变更原因" width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.changeReason || '—' }}</template>
          </el-table-column>
          <el-table-column prop="rate" label="汇率" width="110" align="right" />
          <el-table-column label="事件" width="190">
            <template #default="{ row }">
              <template v-if="row.event">
                <el-tag type="info" size="small" effect="plain">{{ shortEvent(row.event.eventType) }}</el-tag>
                <el-tag size="small" style="margin-left: 4px;">{{ row.event.status }}</el-tag>
              </template>
              <span v-else style="color: #c0c4cc;">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="createBy" label="操作人" width="110" />
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ (row.createDate || '').replace('T', ' ').slice(0, 19) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="170" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openCompare(row)">对比</el-button>
              <el-button v-if="row.opType === 'UPDATE'" link type="warning" @click="prefillMaintain(row)">
                按此历史值发起维护
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination layout="total, prev, pager, next" style="margin-top: 12px; justify-content: flex-end;"
          :total="tlTotal" :page-size="tlQuery.size" :current-change="loadHistory" />
      </template>
    </el-card>

    <!-- 版本对比抽屉 -->
    <el-drawer v-model="cmpVisible" title="版本对比（逐字段）" size="620px">
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
        <el-table-column prop="field" label="字段" width="140">
          <template #default="{ row }">{{ fieldName(row.field) }}</template>
        </el-table-column>
        <el-table-column label="旧值" min-width="160">
          <template #header>旧值 V{{ cmpFrom }}</template>
          <template #default="{ row }">
            <span class="val-old">{{ row.from || '（空）' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="新值" min-width="160">
          <template #header>新值 V{{ cmpTo }}</template>
          <template #default="{ row }">
            <span class="val-new">{{ row.to || '（空）' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else-if="cmpDone" description="两版本无字段差异" :image-size="70" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getSequenceApi, getHistoryApi, getRateVersionsApi, getRateDiffApi
} from '@/api/mdm/exchange-rate'

const router = useRouter()
const loading = ref(false)
const tab = ref('sequence')

function typeName(t) {
  return { MIDDLE: '中间价', BUY: '买入价', SELL: '卖出价' }[t] || t
}
function lcName(lc) {
  return { NOT_EFFECTIVE: '未生效', EFFECTIVE: '生效中', EXPIRED: '已失效' }[lc] || lc
}
function lcTag(lc) {
  return { NOT_EFFECTIVE: 'info', EFFECTIVE: 'success', EXPIRED: 'warning' }[lc]
}
function shortEvent(type) {
  return (type || '').replace('MDM.RATE.', '')
}
function fieldName(f) {
  return {
    baseCcy: '基础币种', quoteCcy: '报价币种', rateType: '汇率类型',
    effectiveDate: '生效日期', expireDate: '失效日期', rate: '汇率值',
    sourceFileNo: '来源文件编号'
  }[f] || f
}

function onTabChange(t) {
  if (t === 'timeline' && !tlLoaded.value) loadHistory(1)
}

// ---------- 区间历史链 ----------
const seqForm = ref({ baseCcy: '', quoteCcy: '', rateType: 'MIDDLE' })
const seqRows = ref([])
const seqSearched = ref(false)

async function loadSequence() {
  const { baseCcy, quoteCcy, rateType } = seqForm.value
  if (!baseCcy || !quoteCcy || !rateType) {
    ElMessage.warning('币对与汇率类型必填')
    return
  }
  loading.value = true
  try {
    const res = await getSequenceApi({
      baseCcy: baseCcy.trim().toUpperCase(),
      quoteCcy: quoteCcy.trim().toUpperCase(),
      rateType
    })
    seqRows.value = res.data
    seqSearched.value = true
  } finally {
    loading.value = false
  }
}

// ---------- 版本时间线 ----------
const tlQuery = ref({ rateType: '', opType: '', keyword: '', current: 1, size: 10 })
const tlRows = ref([])
const tlTotal = ref(0)
const tlLoaded = ref(false)

async function loadHistory(page) {
  if (page) tlQuery.value.current = page
  loading.value = true
  try {
    const res = await getHistoryApi(tlQuery.value)
    tlRows.value = res.data.records
    tlTotal.value = res.data.total
    tlLoaded.value = true
  } finally {
    loading.value = false
  }
}

// ---------- 版本对比 ----------
const cmpVisible = ref(false)
const cmpLoading = ref(false)
const cmpDone = ref(false)
const cmpEntityId = ref('')
const cmpVersions = ref([])
const cmpFrom = ref(null)
const cmpTo = ref(null)
const cmpFields = ref([])

async function openCompare(row) {
  cmpEntityId.value = row.entityId
  cmpFrom.value = null
  cmpTo.value = null
  cmpFields.value = []
  cmpDone.value = false
  cmpVisible.value = true
  const res = await getRateVersionsApi(row.entityId)
  cmpVersions.value = res.data
  // 默认选中该行版本与其前一版本，便于直接对比
  const idx = cmpVersions.value.findIndex(v => v.versionNo === row.versionNo)
  if (idx >= 0) {
    cmpTo.value = row.versionNo
    if (idx > 0) cmpFrom.value = cmpVersions.value[idx - 1].versionNo
  }
}

async function runDiff() {
  if (cmpFrom.value === cmpTo.value) return
  cmpLoading.value = true
  try {
    const res = await getRateDiffApi(cmpEntityId.value, cmpFrom.value, cmpTo.value)
    cmpFields.value = res.data.fields || []
    cmpDone.value = true
  } finally {
    cmpLoading.value = false
  }
}

// ---------- 按此历史值发起维护（回滚降级为预填，不带日期） ----------
function prefillMaintain(row) {
  const params = new URLSearchParams({
    baseCcy: row.baseCcy,
    quoteCcy: row.quoteCcy,
    rateType: row.rateType,
    rate: row.rate,
    sourceFileNo: row.sourceFileNo || ''
  })
  router.push({ path: '/m/1.5.1', query: { prefill: params.toString() } })
}
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.val-old { background: #fde2e2; color: #c45656; padding: 2px 6px; border-radius: 3px; }
.val-new { background: #d9f7be; color: #52c41a; padding: 2px 6px; border-radius: 3px; }
</style>
