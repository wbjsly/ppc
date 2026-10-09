<template>
  <div class="credit-limit">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">信用额度</span>
          <div class="header-actions">
            <el-input v-model="query.keyword" placeholder="客户编码/名称/税号" clearable
                      :prefix-icon="Search" style="width: 220px;" @keyup.enter="loadData(1)" />
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 110px;" @change="loadData(1)">
              <el-option label="启用" value="1" />
              <el-option label="停用" value="0" />
              <el-option label="冻结" value="2" />
            </el-select>
            <el-button type="primary" @click="loadData(1)">查询</el-button>
          </div>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
        title="可用额度公式：可用 = 信用额度（含有效期内临时额度） - 应收账款余额 - 未清SO预占用 - 本次订单金额"
        description="应收/未清SO 涉及财务与销售域，模块未接入时暂不计入（试算值 = 当前有效额度）。额度调整即时生效并入版本快照。" />

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column type="expand">
          <template #default="{ row }">
            <div style="padding: 8px 48px;">
              <el-table :data="row.views" size="small">
                <el-table-column label="法人主体" width="170">
                  <template #default="{ row }">{{ leName(row.view.legalEntityId) }}</template>
                </el-table-column>
                <el-table-column label="常规额度（求和口径）" width="150" align="right">
                  <template #default="{ row }">{{ row.view.creditLimit ?? '—' }}</template>
                </el-table-column>
                <el-table-column label="临时额度" width="170" align="right">
                  <template #default="{ row }">
                    <template v-if="row.view.tempCreditLimit != null">
                      {{ row.view.tempCreditLimit }}
                      <el-tag :type="row.tempActive ? 'warning' : 'info'" size="small">
                        至 {{ row.view.tempExpireDate }}{{ row.tempActive ? '' : '（已到期待回滚）' }}
                      </el-tag>
                    </template>
                    <span v-else style="color: #c0c4cc;">—</span>
                  </template>
                </el-table-column>
                <el-table-column label="复审压缩" width="110" align="right">
                  <template #default="{ row }">
                    <template v-if="row.compressed">
                      {{ row.view.compressedLimit }}
                      <el-tag type="danger" size="small">压缩中</el-tag>
                    </template>
                    <span v-else style="color: #c0c4cc;">—</span>
                  </template>
                </el-table-column>
                <el-table-column label="当前有效额度" width="120" align="right">
                  <template #default="{ row }"><b>{{ row.effectiveLimit }}</b></template>
                </el-table-column>
                <el-table-column label="年度复审" width="180">
                  <template #default="{ row }">
                    <el-tag v-if="row.reviewOverdue" type="danger" size="small">复审超期</el-tag>
                    <span v-else>{{ row.view.lastReviewDate || '未复审（宽限）' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="240">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="openAdjust(row.view)">调整额度</el-button>
                    <el-button link type="primary" @click="openTrial(row.view)">可用试算</el-button>
                    <el-button link type="warning" @click="confirmReview(row.view)">复审通过</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="group.customerCode" label="客户编码" width="110" />
        <el-table-column label="客户名称" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.group.customerName }}</template>
        </el-table-column>
        <el-table-column label="评级" width="70">
          <template #default="{ row }">{{ row.group.creditRating || '—' }}</template>
        </el-table-column>
        <el-table-column label="集团总额度" width="110" align="right">
          <template #default="{ row }">{{ row.group.creditLimitTotal ?? '未配置' }}</template>
        </el-table-column>
        <el-table-column label="Σ法人常规" width="110" align="right">
          <template #default="{ row }">{{ row.creditSum }}</template>
        </el-table-column>
        <el-table-column label="占用率" width="150">
          <template #default="{ row }">
            <template v-if="row.occupancy.configured">
              <el-progress :percentage="Math.min(100, Number(row.occupancy.occupancyRate))"
                           :status="row.occupancy.over80 ? 'warning' : ''"
                           :stroke-width="14" style="width: 90px;" />
              <el-tag v-if="row.occupancy.over80" type="warning" size="small">超80%</el-tag>
            </template>
            <span v-else style="color: #909399;">未配置</span>
          </template>
        </el-table-column>
        <el-table-column label="复审超期" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.overdueCount > 0" type="danger" size="small">{{ row.overdueCount }} 项</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openGroupAdjust(row.group)">调整总额度/评级</el-button>
            <el-button link type="primary" @click="openTimeline(row.group)">时间轴</el-button>
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

    <!-- 法人额度调整弹窗 -->
    <el-dialog v-model="adjustVisible" :title="`调整额度：${leName(adjustView?.legalEntityId)}`" width="640px">
      <el-form label-width="150px">
        <el-form-item label="常规额度（求和口径）">
          <el-input-number v-model="adjustForm.creditLimit" :min="0" :precision="2" style="width: 100%;" />
          <div class="form-tip">保存即校验 Σ本集团法人常规额度 ≤ 集团总额度 × ratio（超限 422，BR-4.1-31）</div>
        </el-form-item>
        <el-form-item label="临时额度（BR-4.1-33）">
          <div style="display: flex; gap: 10px; width: 100%;">
            <el-input-number v-model="adjustForm.tempCreditLimit" :min="0" :precision="2"
                             placeholder="不设请留空" style="flex: 1;" />
            <el-date-picker v-model="adjustForm.tempExpireDate" type="date" value-format="YYYY-MM-DD"
                            placeholder="有效期（必填 > 今天）" style="flex: 1;" />
          </div>
          <div class="form-tip">设临时额度时有效期必填且须晚于当天；到期由定时任务自动回滚并入快照</div>
        </el-form-item>
        <el-form-item label="调整原因" required>
          <el-input v-model="adjustForm.changeReason" maxlength="255" placeholder="必填（≥2 字）" />
        </el-form-item>
        <el-form-item label="口径提示">
          <div class="impact-brief">
            <div>求和口径：仅常规额度参与 Σ ≤ 集团总额度校验</div>
            <div>试算口径：有效额度 = 压缩值(如有) + 常规 + 未过期临时</div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitAdjust">保存</el-button>
      </template>
    </el-dialog>

    <!-- 集团基准调整弹窗 -->
    <el-dialog v-model="groupVisible" :title="`集团基准调整：${groupRow?.customerName || ''}`" width="560px">
      <el-form label-width="140px">
        <el-form-item label="集团信用总额度">
          <el-input-number v-model="groupForm.creditLimitTotal" :min="0" :precision="2" style="width: 100%;" />
          <div class="form-tip">求和校验与占用率的分母基准；清空 = 未配置（校验放行、占用率不计算）</div>
        </el-form-item>
        <el-form-item label="集团信用评级">
          <el-select v-model="groupForm.creditRating" clearable style="width: 100%;">
            <el-option v-for="r in ['AAA', 'AA', 'A', 'BBB', 'BB', 'B']" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item label="调整原因" required>
          <el-input v-model="groupForm.reason" maxlength="255" placeholder="必填（≥2 字）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="groupVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitGroup">保存</el-button>
      </template>
    </el-dialog>

    <!-- 复审通过确认 -->
    <el-dialog v-model="reviewVisible" :title="`年度复审通过：${leName(reviewView?.legalEntityId)}`" width="520px">
      <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
        title="复审通过将更新复审日期为今天并解除复审超期压缩（C-4.3-13），生成版本快照。" />
      <el-form label-width="90px">
        <el-form-item label="复审说明" required>
          <el-input v-model="reviewReason" type="textarea" :rows="3" maxlength="200" show-word-limit
            placeholder="如：年度复审，额度维持/调整依据……" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!reviewReason.trim()" :loading="saving" @click="submitReview">确认通过</el-button>
      </template>
    </el-dialog>

    <!-- 可用额度试算抽屉 -->
    <el-drawer v-model="trialVisible" :title="`可用额度试算：${leName(trialView?.legalEntityId)}`" size="460px">
      <template v-if="trialData">
        <el-alert type="info" :closable="false" style="margin-bottom: 12px;" :title="trialData.formula" />
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="基础额度（常规/压缩）">
            {{ trialData.baseLimit }}
            <el-tag v-if="trialData.baseIsCompressed" type="danger" size="small">复审压缩中</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="有效临时额度">
            {{ trialData.tempLimit }}
            <span v-if="trialData.tempLimit > 0" style="font-size: 12px; color: #909399;">
              至 {{ trialData.tempExpireDate }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="应收账款余额">
            {{ trialData.accountsReceivable.note }}
          </el-descriptions-item>
          <el-descriptions-item label="未清SO预占用">
            {{ trialData.openSoReserved.note }}
          </el-descriptions-item>
          <el-descriptions-item label="可用信用额度">
            <b style="font-size: 16px;">{{ trialData.available }}</b>
          </el-descriptions-item>
        </el-descriptions>
        <div class="form-tip" style="margin-top: 10px;">{{ trialData.stubNote }}</div>
      </template>
      <el-skeleton v-else :rows="5" animated />
    </el-drawer>

    <!-- 时间轴抽屉 -->
    <el-drawer v-model="timelineVisible" :title="`额度时间轴：${timelineGroup?.customerName || ''}`" size="640px">
      <div class="diff-bar" v-if="timelineItems.length">
        <el-select v-model="diffFrom" placeholder="起始版本" style="width: 140px;">
          <el-option v-for="(t, i) in timelineItems" :key="i" :label="labelOf(t)" :value="i" />
        </el-select>
        <span>→</span>
        <el-select v-model="diffTo" placeholder="目标版本" style="width: 140px;">
          <el-option v-for="(t, i) in timelineItems" :key="i" :label="labelOf(t)" :value="i" />
        </el-select>
        <el-button type="primary" plain :disabled="diffFrom === null || diffTo === null || diffFrom === diffTo"
                   @click="showCompare">对比</el-button>
      </div>
      <el-timeline v-if="timelineItems.length" style="padding-left: 4px;">
        <el-timeline-item v-for="(t, i) in timelineItems" :key="i"
                          :timestamp="`${(t.createDate || '').replace('T', ' ')} ${t.createBy || ''}`"
                          :type="t.opType === 'MERGE' ? 'danger' : 'primary'" placement="top">
          <div class="tl-item">
            <b>[{{ t.entityType }} {{ t.entityLabel }}]</b> V{{ t.versionNo }} · {{ opMap[t.opType] || t.opType }}
            <el-tag v-if="t.changeReason" size="small" type="info" style="margin-left: 6px;">{{ t.changeReason }}</el-tag>
            <div class="form-tip">{{ t.diffSummary }}</div>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无额度相关变更" :image-size="60" />

      <template v-if="compareFields.length">
        <el-divider content-position="left">选中两点差异</el-divider>
        <el-table :data="compareFields" size="small" border>
          <el-table-column prop="field" label="字段" width="150" />
          <el-table-column prop="from" label="起始值" show-overflow-tooltip />
          <el-table-column prop="to" label="目标值" show-overflow-tooltip />
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import {
  getOverviewApi, adjustViewLimitApi, adjustGroupLimitApi, reviewPassedApi,
  getTimelineApi, getTrialApi
} from '@/api/mdm/credit-limit'
import { getLegalEntityPageApi } from '@/api/mdm/legal-entity'

const opMap = { CREATE: '新建', UPDATE: '调整', DISABLE: '停用', FREEZE: '冻结', MERGE: '合并' }

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ keyword: '', status: '', current: 1, size: 10 })
const legalEntities = ref([])

async function loadData(page) {
  if (page) query.value.current = page
  loading.value = true
  try {
    const res = await getOverviewApi(query.value)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function leName(id) {
  const le = legalEntities.value.find(x => x.id === id)
  return le ? `${le.leCode} ${le.leName}` : id || ''
}

// ---------- 法人额度调整 ----------
const adjustVisible = ref(false)
const adjustView = ref(null)
const adjustForm = ref({})

function openAdjust(view) {
  adjustView.value = view
  adjustForm.value = {
    creditLimit: view.creditLimit,
    tempCreditLimit: view.tempCreditLimit,
    tempExpireDate: view.tempExpireDate,
    changeReason: ''
  }
  adjustVisible.value = true
}
async function submitAdjust() {
  saving.value = true
  try {
    await adjustViewLimitApi({ id: adjustView.value.id, ...adjustForm.value })
    ElMessage.success('额度调整已生效')
    adjustVisible.value = false
    loadData()
  } catch (e) {
    // 422 求和/有效期校验已单点提示
  } finally {
    saving.value = false
  }
}

// ---------- 集团基准调整 ----------
const groupVisible = ref(false)
const groupRow = ref(null)
const groupForm = ref({})

function openGroupAdjust(g) {
  groupRow.value = g
  groupForm.value = { creditLimitTotal: g.creditLimitTotal, creditRating: g.creditRating, reason: '' }
  groupVisible.value = true
}
async function submitGroup() {
  saving.value = true
  try {
    await adjustGroupLimitApi(groupRow.value.id, groupForm.value.creditLimitTotal,
      groupForm.value.creditRating, groupForm.value.reason.trim())
    ElMessage.success('集团基准已调整')
    groupVisible.value = false
    loadData()
  } catch (e) {
    // 已单点提示
  } finally {
    saving.value = false
  }
}

// ---------- 复审通过 ----------
const reviewVisible = ref(false)
const reviewView = ref(null)
const reviewReason = ref('')

function confirmReview(view) {
  reviewView.value = view
  reviewReason.value = ''
  reviewVisible.value = true
}
async function submitReview() {
  saving.value = true
  try {
    await reviewPassedApi(reviewView.value.id, reviewReason.value.trim())
    ElMessage.success('复审通过，压缩已解除')
    reviewVisible.value = false
    loadData()
  } catch (e) {
    // 幂等 422 已提示
  } finally {
    saving.value = false
  }
}

// ---------- 可用试算 ----------
const trialVisible = ref(false)
const trialView = ref(null)
const trialData = ref(null)

async function openTrial(view) {
  trialView.value = view
  trialData.value = null
  trialVisible.value = true
  const res = await getTrialApi(view.id)
  trialData.value = res.data
}

// ---------- 时间轴 ----------
const timelineVisible = ref(false)
const timelineGroup = ref(null)
const timelineItems = ref([])
const diffFrom = ref(null)
const diffTo = ref(null)
const compareFields = ref([])

async function openTimeline(g) {
  timelineGroup.value = g
  timelineItems.value = []
  diffFrom.value = null
  diffTo.value = null
  compareFields.value = []
  timelineVisible.value = true
  const res = await getTimelineApi(g.id)
  timelineItems.value = res.data
}
function labelOf(t) {
  return `${t.entityType}/${t.entityLabel} V${t.versionNo}`
}
function showCompare() {
  const a = timelineItems.value[diffFrom.value]
  const b = timelineItems.value[diffTo.value]
  compareFields.value = diffRows(a?.diffSummary || '', b?.diffSummary || '')
}
/** 把两条 diff 摘要解析为字段级对比（摘要格式：field: old → new；field2: ...） */
function diffRows(diffA, diffB) {
  const parse = (s) => {
    const map = {}
    for (const part of s.split('；')) {
      const idx = part.indexOf(': ')
      if (idx > 0) map[part.slice(0, idx).split('（')[0].trim()] = part.slice(idx + 2)
    }
    return map
  }
  const ma = parse(diffA)
  const mb = parse(diffB)
  const keys = [...new Set([...Object.keys(ma), ...Object.keys(mb)])]
  return keys
    .filter(k => k !== '原因')
    .map(k => ({ field: k, from: ma[k] ?? '（无）', to: mb[k] ?? '（无）' }))
}

onMounted(async () => {
  loadData()
  const le = await getLegalEntityPageApi({ current: 1, size: 100 })
  legalEntities.value = le.data.records
})
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.form-tip { font-size: 12px; color: #909399; line-height: 1.5; margin-top: 2px; }
.impact-brief { font-size: 12px; color: #606266; line-height: 1.7; }
.diff-bar { margin-bottom: 12px; display: flex; gap: 8px; align-items: center; }
.tl-item { font-size: 13px; }
</style>
