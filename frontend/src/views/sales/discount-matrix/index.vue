<template>
  <div class="dm-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="折扣矩阵（3.6.2）"
      description="三层折扣规则配置：渠道折扣（按客户渠道属性）/ 时间促销（窗口内重叠取最大）/ 量价阶梯（复用 LADDER 协议，见专属折扣页）；叠加规则默认最优单层，可切逐层递减（BR-4.3-31）。"
    />

    <el-tabs v-model="tab">
      <!-- ============ 渠道折扣 ============ -->
      <el-tab-pane label="渠道折扣" name="channel">
        <el-card>
          <template #header>
            <div class="card-header">
              <span style="font-weight: bold;">渠道折扣配置（FR-4.3-5-1）</span>
              <el-button type="primary" size="small" :icon="Plus" @click="openChannel()">新增渠道折扣</el-button>
            </div>
          </template>
          <el-table :data="channels" v-loading="loading" stripe>
            <el-table-column label="渠道" width="130">
              <template #default="{ row }">
                <el-tag>{{ channelName(row.channel) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="折扣率" width="110" align="center">
              <template #default="{ row }"><b>{{ pct(row.discountRate) }}</b></template>
            </el-table-column>
            <el-table-column label="生效窗口" width="200">
              <template #default="{ row }">{{ row.effectiveFrom }} ~ {{ row.effectiveTo || '长期' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                  {{ row.status === 'ACTIVE' ? '生效' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openChannel(row)">编辑</el-button>
                <el-button v-if="row.status === 'ACTIVE'" link type="danger"
                           @click="stopChannel(row)">停用</el-button>
                <el-button v-else link type="success" @click="enableCh(row)">启用</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ============ 时间促销 ============ -->
      <el-tab-pane label="时间促销" name="promo">
        <el-card>
          <template #header>
            <div class="card-header">
              <span style="font-weight: bold;">时间促销活动（FR-4.3-5-3，重叠取最大 BR-4.3-33）</span>
              <el-button type="primary" size="small" :icon="Plus" @click="openPromo()">新增活动</el-button>
            </div>
          </template>
          <el-table :data="promos" v-loading="loading" stripe>
            <el-table-column prop="promoNo" label="活动编号" width="150" />
            <el-table-column prop="promoName" label="名称" min-width="140" show-overflow-tooltip />
            <el-table-column prop="itemCode" label="SKU" width="140" />
            <el-table-column label="折扣率" width="94" align="center">
              <template #default="{ row }"><b>{{ pct(row.discountRate) }}</b></template>
            </el-table-column>
            <el-table-column label="窗口" width="200">
              <template #default="{ row }">{{ row.startDate }} ~ {{ row.endDate }}</template>
            </el-table-column>
            <el-table-column label="状态" width="94">
              <template #default="{ row }">
                <el-tag size="small" :type="promoType(row.status)">{{ promoName(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status !== 'PUBLISHED'" link type="primary"
                           @click="openPromo(row)">编辑</el-button>
                <el-button v-if="row.status !== 'PUBLISHED' && row.status !== 'STOPPED'"
                           link type="success" @click="publishPromo(row)">发布</el-button>
                <el-button v-if="row.status === 'PUBLISHED'" link type="danger"
                           @click="stopPromo(row)">停用</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ============ 叠加规则 ============ -->
      <el-tab-pane label="叠加规则" name="stack">
        <el-card>
          <template #header><span style="font-weight: bold;">叠加规则参数（FR-4.3-5-4 / BR-4.3-31）</span></template>
          <el-radio-group v-model="stackMode" :disabled="!stackEditable" @change="stackDirty = true">
            <el-radio label="BEST_SINGLE">
              <b>最优单层（默认）</b>：同��命中多来源时取折扣最大的一层，不求和叠加
            </el-radio>
            <br /><br />
            <el-radio label="STACKED">
              <b>逐层递减</b>：按「渠道 > 量价 > 时间」优先级逐层打折；三层折扣率之和超过 100% 或
              叠加结果低于毛利阈值且为求和叠加时，提交被阻断（价格计算异常需人工确认，C-4.3-03）
            </el-radio>
          </el-radio-group>
          <div style="margin-top: 16px;">
            <el-button type="primary" :disabled="!stackDirty || !stackEditable" :loading="saving" @click="saveStack">
              保存叠加规则
            </el-button>
            <el-button v-if="!stackEditable" @click="stackEditable = true">解锁修改</el-button>
            <span class="tip" style="margin-left: 12px;">修改留痕到参数调整历史（DISCOUNT_STACK_MODE）</span>
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 渠道折扣对话框 -->
    <el-dialog v-model="chDialog" :title="chForm.id ? '编辑渠道折扣' : '新增渠道折扣'" width="520px">
      <el-form :model="chForm" label-width="100px">
        <el-form-item label="渠道" required>
          <el-select v-model="chForm.channel" style="width: 100%;">
            <el-option v-for="c in channelDict" :key="c.code" :label="c.name" :value="c.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="折扣率" required>
          <el-input-number v-model="chForm.discountRate" :min="0" :max="0.5" :step="0.01" :precision="4" />
          <span class="tip" style="margin-left: 8px;">0.05 = 5%</span>
        </el-form-item>
        <el-form-item label="生效起">
          <el-date-picker v-model="chForm.effectiveFrom" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="生效止">
          <el-date-picker v-model="chForm.effectiveTo" type="date" value-format="YYYY-MM-DD"
                          placeholder="空 = 长期有效" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="chForm.remark" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="chDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCh">保存</el-button>
      </template>
    </el-dialog>

    <!-- 促销对话框 -->
    <el-dialog v-model="prDialog" :title="prForm.id ? '编辑促销活动' : '新增促销活动'" width="560px">
      <el-form :model="prForm" label-width="90px">
        <el-form-item label="活动名称" required>
          <el-input v-model="prForm.promoName" maxlength="200" />
        </el-form-item>
        <el-form-item label="SKU" required>
          <el-input v-model="prForm.itemCode" maxlength="64" placeholder="单 SKU 行级活动" />
        </el-form-item>
        <el-form-item label="折扣率" required>
          <el-input-number v-model="prForm.discountRate" :min="0.0001" :max="0.5" :step="0.01" :precision="4" />
        </el-form-item>
        <el-form-item label="活动窗口" required>
          <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
                          start-placeholder="开始" end-placeholder="结束" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="prForm.remark" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="prDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePr">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  getChannelsApi, saveChannelApi, stopChannelApi, enableChannelApi,
  getPromotionsApi, savePromotionApi, publishPromotionApi, stopPromotionApi,
  getStackModeApi, setStackModeApi
} from '@/api/sd/pricing'
import request from '@/utils/request'

const tab = ref('channel')
const loading = ref(false)
const saving = ref(false)

const channels = ref([])
const promos = ref([])
const stackMode = ref('BEST_SINGLE')
const stackEditable = ref(false)
const stackDirty = ref(false)

const channelDict = [
  { code: 'DIRECT', name: '直销' },
  { code: 'DEALER', name: '经销' },
  { code: 'ECOM', name: '电商' },
  { code: 'KA', name: 'KA 大客户' }
]

onMounted(() => {
  loadChannels()
  loadPromos()
  loadStack()
})

async function loadChannels() {
  loading.value = true
  try {
    const res = await getChannelsApi({ current: 1, size: 50 })
    channels.value = res.data.records || []
  } finally {
    loading.value = false
  }
}
async function loadPromos() {
  loading.value = true
  try {
    const res = await getPromotionsApi({ current: 1, size: 50 })
    promos.value = res.data.records || []
  } finally {
    loading.value = false
  }
}
async function loadStack() {
  try {
    const res = await getStackModeApi()
    stackMode.value = res.data.mode
  } catch (e) { /* 参数接口不可用时用默认 */ }
}

// ---------- 渠道 ----------
const chDialog = ref(false)
const chForm = reactive({ id: '', channel: 'DEALER', discountRate: 0.05, effectiveFrom: '', effectiveTo: '', remark: '' })

function openChannel(row) {
  Object.assign(chForm, row
    ? { id: row.id, channel: row.channel, discountRate: Number(row.discountRate),
        effectiveFrom: row.effectiveFrom, effectiveTo: row.effectiveTo, remark: row.remark }
    : { id: '', channel: 'DEALER', discountRate: 0.05, effectiveFrom: '', effectiveTo: '', remark: '' })
  chDialog.value = true
}

async function saveCh() {
  saving.value = true
  try {
    await saveChannelApi({ ...chForm })
    ElMessage.success('渠道折扣已保存')
    chDialog.value = false
    loadChannels()
  } finally {
    saving.value = false
  }
}

async function stopChannel(row) {
  const { value } = await ElMessageBox.prompt('停用原因必填', `停用 ${channelName(row.channel)} 折扣`, {
    type: 'warning', inputPattern: /.{2,}/, inputErrorMessage: '原因至少 2 字'
  })
  await stopChannelApi(row.id, value)
  ElMessage.success('已停用')
  loadChannels()
}

async function enableCh(row) {
  await enableChannelApi(row.id)
  ElMessage.success('已启用')
  loadChannels()
}

// ---------- 促销 ----------
const prDialog = ref(false)
const prForm = reactive({ id: '', promoName: '', itemCode: '', discountRate: 0.08, remark: '' })
const range = ref([])

function openPromo(row) {
  Object.assign(prForm, row
    ? { id: row.id, promoName: row.promoName, itemCode: row.itemCode,
        discountRate: Number(row.discountRate), remark: row.remark }
    : { id: '', promoName: '', itemCode: '', discountRate: 0.08, remark: '' })
  range.value = row ? [row.startDate, row.endDate] : []
  prDialog.value = true
}

async function savePr() {
  if (!range.value || range.value.length !== 2) {
    ElMessage.warning('活动窗口必填')
    return
  }
  saving.value = true
  try {
    await savePromotionApi({
      ...prForm,
      startDate: range.value[0],
      endDate: range.value[1]
    })
    ElMessage.success('促销活动已保存（草稿，发布后参与计算）')
    prDialog.value = false
    loadPromos()
  } finally {
    saving.value = false
  }
}

async function publishPromo(row) {
  await ElMessageBox.confirm(`发布活动 ${row.promoNo}？发布后窗口内即参与取价计算。`, '发布确认', { type: 'warning' })
  await publishPromotionApi(row.id)
  ElMessage.success('已发布')
  loadPromos()
}

async function stopPromo(row) {
  const { value } = await ElMessageBox.prompt('停用原因必填', `停用 ${row.promoNo}`, {
    type: 'warning', inputPattern: /.{2,}/, inputErrorMessage: '原因至少 2 字'
  })
  await stopPromotionApi(row.id, value)
  ElMessage.success('已停用')
  loadPromos()
}

// ---------- 叠加规则 ----------
async function saveStack() {
  await ElMessageBox.confirm(
    `确认切换叠加规则为「${stackMode.value === 'STACKED' ? '逐层递减' : '最优单层'}」？该参数影响全部取价计算。`,
    '切换叠加规则', { type: 'warning' })
  await setStackModeApi(stackMode.value, 'ui')
  ElMessage.success('叠加规则已保存（参数历史留痕）')
  stackDirty.value = false
  stackEditable.value = false
}

// ---------- 辅助 ----------
function channelName(c) {
  return (channelDict.find(x => x.code === c) || {}).name || c
}
function pct(v) { return (Number(v) * 100).toFixed(1) + '%' }
function promoName(s) {
  return { DRAFT: '草稿', PUBLISHED: '已发布', STOPPED: '已停用' }[s] || s
}
function promoType(s) {
  return { DRAFT: 'info', PUBLISHED: 'success', STOPPED: 'info' }[s] || 'info'
}
</script>

<style scoped>
.dm-page { padding: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.tip { color: #909399; font-size: 12px; }
</style>
