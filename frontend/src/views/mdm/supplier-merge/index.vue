<template>
  <div class="supplier-merge">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">供应商合并去重</span>
          <el-radio-group v-model="tab" @change="onTabChange">
            <el-radio-button value="logs">合并日志</el-radio-button>
            <el-radio-button value="start">发起合并</el-radio-button>
          </el-radio-group>
        </div>
      </template>

      <!-- ============ Tab1 合并日志 ============ -->
      <template v-if="tab === 'logs'">
        <div class="toolbar">
          <el-input v-model="logQuery.keyword" placeholder="日志号/源/目标编码" clearable
                    :prefix-icon="Search" style="width: 220px;" @keyup.enter="loadLogs(1)" />
          <el-button type="primary" @click="loadLogs(1)">查询</el-button>
          <span class="tip">回退窗口 30 天（BR 行 659）；日志永久保留（BR-4.1-03 / C-4.1-11）</span>
        </div>
        <el-table :data="logs" v-loading="loading" stripe>
          <el-table-column label="日志号" width="100">
            <template #default="{ row }">{{ row.log.logNo }}</template>
          </el-table-column>
          <el-table-column label="源（注销方）" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">
              <b>{{ row.log.sourceCode }}</b> {{ row.sourceName }}
              <el-tag type="danger" size="small">合并前 {{ row.log.preStatus }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="目标（保留方）" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.log.targetCode }} {{ row.targetName }}</template>
          </el-table-column>
          <el-table-column label="影响面" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.log.impactSummary }}</template>
          </el-table-column>
          <el-table-column label="发生时间" width="155">
            <template #default="{ row }">{{ (row.log.mergeAt || '').replace('T', ' ').slice(0, 19) }}</template>
          </el-table-column>
          <el-table-column prop="log.operator" label="操作人" width="100" />
          <el-table-column label="回退" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.log.reverted === '1'" type="info" size="small">
                已回退 {{ (row.log.revertAt || '').slice(5, 10) }}
              </el-tag>
              <el-tag v-else-if="row.revertable" type="success" size="small">可回退</el-tag>
              <el-tooltip v-else content="已过回退期（30 天），须走新建供应商+人工关联历史" placement="top">
                <el-tag type="warning" size="small">已过期</el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button v-if="row.revertable" link type="danger" @click="openRevert(row)">回退</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination layout="total, prev, pager, next" style="margin-top: 12px; justify-content: flex-end;"
          :total="logTotal" :page-size="logQuery.size" :current-change="loadLogs" />
      </template>

      <!-- ============ Tab2 发起合并 ============ -->
      <template v-if="tab === 'start'">
        <div class="toolbar">
          <el-input v-model="candKeyword" placeholder="关键字：编码/名称/税号（查重口径）" clearable
                    :prefix-icon="Search" style="width: 300px;" @keyup.enter="searchCandidates" />
          <el-button type="primary" @click="searchCandidates">搜索候选</el-button>
        </div>
        <el-table :data="candidates" v-loading="loading" size="small" border>
          <el-table-column prop="supplierCode" label="编码" width="110" />
          <el-table-column prop="supplierName" label="名称" min-width="160" show-overflow-tooltip />
          <el-table-column prop="taxNo" label="税号" width="120" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'QUALIFIED' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="选择" width="200">
            <template #default="{ row }">
              <el-button link :type="sourceId === row.id ? 'danger' : 'primary'"
                         @click="sourceId = row.id">设为源</el-button>
              <el-button link :type="targetId === row.id ? 'danger' : 'primary'"
                         @click="targetId = row.id">设为目标</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="selected-bar" v-if="sourceId || targetId">
          <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
            :title="`源：${labelOf(sourceId)}  →  目标：${labelOf(targetId)}`"
            description="方向规则：合格且资质齐全方优先作为目标（FR-4.1-5-1 SOP 提示，不强制）。" />
          <el-button type="primary" :disabled="!sourceId || !targetId" @click="openCompare">差异对比与影响面</el-button>
        </div>
      </template>
    </el-card>

    <!-- 差异对比 + 影响面 + 原因确认 -->
    <el-dialog v-model="compareVisible" title="合并前确认：差异对比与影响面" width="780px">
      <template v-if="compareData">
        <el-alert type="warning" :closable="false" style="margin-bottom: 10px;" :title="compareData.advice" />
        <el-table :data="compareData.rows" size="small" border>
          <el-table-column prop="field" label="字段" width="140" />
          <el-table-column label="源" show-overflow-tooltip>
            <template #default="{ row }">{{ row.sourceValue }}</template>
          </el-table-column>
          <el-table-column label="目标" show-overflow-tooltip>
            <template #default="{ row }">{{ row.targetValue }}</template>
          </el-table-column>
        </el-table>
        <template v-if="impactData">
          <el-divider content-position="left">影响面</el-divider>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="资质证照">
              {{ impactData.certCount }} 份（真实改挂至目标）
              <div style="font-size: 12px; color: #909399;">{{ (impactData.certDetails || []).join('；') || '无' }}</div>
            </el-descriptions-item>
            <el-descriptions-item label="争议单据预检">{{ impactData.disputeStub }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="impactData.migrations" size="small" border style="margin-top: 8px;">
            <el-table-column prop="type" label="未清业务" width="160" />
            <el-table-column label="迁移状态" width="110">
              <template #default><el-tag type="info" size="small">桩</el-tag></template>
            </el-table-column>
            <el-table-column prop="note" label="口径说明" />
          </el-table>
        </template>
        <el-form label-width="90px" style="margin-top: 12px;">
          <el-form-item label="合并原因" required>
            <el-input v-model="mergeReason" type="textarea" :rows="2" maxlength="200" show-word-limit
              placeholder="必填（≥2 字），如：同一实体重复建档，合并至统一编码" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="compareVisible = false">取消</el-button>
        <el-button type="danger" :disabled="!mergeReason.trim()" :loading="saving" @click="submitMerge">
          确认合并（不可撤销）
        </el-button>
      </template>
    </el-dialog>

    <!-- 回退弹窗 -->
    <el-dialog v-model="revertVisible" :title="`合并回退：${revertRow?.log.logNo || ''}`" width="560px">
      <el-alert type="warning" :closable="false" style="margin-bottom: 10px;"
        title="回退将：源恢复合并前状态、证照迁回源、日志标记已回退（30 天窗口内）。" />
      <el-descriptions :column="1" border size="small" style="margin-bottom: 10px;">
        <el-descriptions-item label="源">{{ revertRow?.log.sourceCode }} {{ revertRow?.sourceName }}</el-descriptions-item>
        <el-descriptions-item label="目标">{{ revertRow?.log.targetCode }} {{ revertRow?.targetName }}</el-descriptions-item>
        <el-descriptions-item label="恢复状态">{{ revertRow?.log.preStatus }}</el-descriptions-item>
      </el-descriptions>
      <el-form label-width="90px">
        <el-form-item label="回退原因" required>
          <el-input v-model="revertReason" type="textarea" :rows="3" maxlength="200" show-word-limit
            placeholder="必填（≥2 字）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="revertVisible = false">取消</el-button>
        <el-button type="danger" :disabled="!revertReason.trim()" :loading="saving" @click="submitRevert">确认回退</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import {
  getCandidatesApi, getCompareApi, getMergeImpactApi, mergeApi, revertApi, getLogPageApi
} from '@/api/mdm/supplier-merge'

const tab = ref('logs')
const loading = ref(false)
const saving = ref(false)

// ---------- 合并日志 ----------
const logs = ref([])
const logTotal = ref(0)
const logQuery = ref({ keyword: '', current: 1, size: 10 })

async function loadLogs(page) {
  if (page) logQuery.value.current = page
  loading.value = true
  try {
    const res = await getLogPageApi(logQuery.value)
    logs.value = res.data.records
    logTotal.value = res.data.total
  } finally {
    loading.value = false
  }
}

// ---------- 发起合并 ----------
const candKeyword = ref('')
const candidates = ref([])
const sourceId = ref('')
const targetId = ref('')

async function searchCandidates() {
  if (!candKeyword.value) {
    ElMessage.warning('请输入关键字')
    return
  }
  loading.value = true
  try {
    const res = await getCandidatesApi(candKeyword.value, '')
    candidates.value = res.data
    if (!candidates.value.length) ElMessage.info('无相似候选')
  } finally {
    loading.value = false
  }
}
function labelOf(id) {
  if (!id) return '（未选）'
  const c = candidates.value.find(x => x.id === id)
  return c ? `${c.supplierCode} ${c.supplierName}` : id
}

const compareVisible = ref(false)
const compareData = ref(null)
const impactData = ref(null)
const mergeReason = ref('')

async function openCompare() {
  compareData.value = null
  impactData.value = null
  compareVisible.value = true
  const [cmp, imp] = await Promise.all([
    getCompareApi(sourceId.value, targetId.value),
    getMergeImpactApi(sourceId.value)
  ])
  compareData.value = cmp.data
  impactData.value = imp.data
}
async function submitMerge() {
  saving.value = true
  try {
    const res = await mergeApi(sourceId.value, targetId.value, mergeReason.value.trim())
    ElMessage.success(`合并完成：${res.data.logNo}（源已锁定终态，证照已改挂）`)
    compareVisible.value = false
    sourceId.value = ''
    targetId.value = ''
    mergeReason.value = ''
    candidates.value = []
    tab.value = 'logs'
    loadLogs(1)
  } catch (e) {
    // 422 校验组已单点提示
  } finally {
    saving.value = false
  }
}

// ---------- 回退 ----------
const revertVisible = ref(false)
const revertRow = ref(null)
const revertReason = ref('')

function openRevert(row) {
  revertRow.value = row
  revertReason.value = ''
  revertVisible.value = true
}
async function submitRevert() {
  saving.value = true
  try {
    await ElMessageBox.confirm('确认回退该合并？源将恢复合并前状态，证照迁回源。', '回退确认', { type: 'warning' })
  } catch (e) {
    saving.value = false
    return
  }
  try {
    await revertApi(revertRow.value.log.id, revertReason.value.trim())
    ElMessage.success('回退完成：源已恢复合并前状态')
    revertVisible.value = false
    loadLogs()
  } catch (e) {
    // 422 超期/幂等已提示
  } finally {
    saving.value = false
  }
}

function onTabChange(t) {
  if (t === 'logs') loadLogs(1)
}

onMounted(() => loadLogs(1))
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.tip { font-size: 12px; color: #909399; }
.selected-bar { margin-top: 12px; }
</style>
