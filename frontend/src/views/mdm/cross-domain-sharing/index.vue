<template>
  <div class="cross-domain">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">跨域共享</span>
          <el-radio-group v-model="tab" @change="onTabChange">
            <el-radio-button value="events">事件流</el-radio-button>
            <el-radio-button value="agreements">价格协议</el-radio-button>
            <el-radio-button value="trial">价格试算</el-radio-button>
          </el-radio-group>
        </div>
      </template>

      <!-- ============ Tab1 事件流 ============ -->
      <template v-if="tab === 'events'">
        <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
          title="消息总线未接入，PENDING 为预期态（投递桩口径）"
          description="事件按 7.2 统一信封写入 erp_ops_outbox，与主操作同事务；幂等键 业务编码:vN 同键重放拒绝（C-0-06）。" />
        <div class="toolbar">
          <el-select v-model="evtQuery.eventType" placeholder="事件类型" clearable style="width: 260px;" @change="loadEvents(1)">
            <el-option v-for="t in EVENT_TYPES" :key="t" :label="t" :value="t" />
          </el-select>
          <el-select v-model="evtQuery.status" placeholder="状态" clearable style="width: 110px;" @change="loadEvents(1)">
            <el-option label="PENDING" value="PENDING" />
            <el-option label="DELIVERED" value="DELIVERED" />
            <el-option label="DEAD" value="DEAD" />
          </el-select>
          <el-input v-model="evtQuery.keyword" placeholder="业务编码/幂等键/payload" clearable
                    :prefix-icon="Search" style="width: 240px;" @keyup.enter="loadEvents(1)" />
          <el-button type="primary" @click="loadEvents(1)">查询</el-button>
          <el-button link type="primary" @click="catalogVisible = true">事件目录</el-button>
        </div>
        <el-table :data="events" v-loading="loading" stripe>
          <el-table-column prop="eventType" label="事件类型" width="250" />
          <el-table-column prop="idempotencyKey" label="幂等键" width="230" show-overflow-tooltip />
          <el-table-column prop="recordVersion" label="记录版本" width="80" align="center" />
          <el-table-column prop="legalEntityId" label="关联法人" width="130">
            <template #default="{ row }">{{ row.legalEntityId || '—' }}</template>
          </el-table-column>
          <el-table-column label="发生时间" width="160">
            <template #default="{ row }">{{ (row.occurredAt || '').replace('T', ' ').slice(0, 19) }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'PENDING' ? 'info' : 'success'" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEvent(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination layout="total, prev, pager, next" style="margin-top: 12px; justify-content: flex-end;"
          :total="evtTotal" :page-size="evtQuery.size" :current-change="loadEvents" />
      </template>

      <!-- ============ Tab2 价格协议 ============ -->
      <template v-if="tab === 'agreements'">
        <div class="toolbar">
          <el-input v-model="paQuery.keyword" placeholder="协议编码/名称" clearable
                    :prefix-icon="Search" style="width: 200px;" @keyup.enter="loadAgreements(1)" />
          <el-select v-model="paQuery.agreementType" placeholder="类型" clearable style="width: 140px;" @change="loadAgreements(1)">
            <el-option label="客户专属价" value="EXCLUSIVE" />
            <el-option label="量价阶梯" value="LADDER" />
            <el-option label="时间价" value="TIME" />
          </el-select>
          <el-select v-model="paQuery.status" placeholder="状态" clearable style="width: 110px;" @change="loadAgreements(1)">
            <el-option label="未生效" value="0" />
            <el-option label="生效中" value="1" />
            <el-option label="过期" value="2" />
            <el-option label="停用" value="3" />
          </el-select>
          <el-button type="primary" @click="loadAgreements(1)">查询</el-button>
          <el-button type="primary" :icon="Plus" @click="openCreate">新建协议</el-button>
        </div>
        <el-table :data="agreements" v-loading="loading" stripe>
          <el-table-column prop="paCode" label="协议编码" width="110" />
          <el-table-column prop="paName" label="名称" min-width="160" show-overflow-tooltip />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">{{ typeName(row.agreementType) }}</template>
          </el-table-column>
          <el-table-column label="挂靠" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <el-tag size="small" :type="row.customerViewId ? 'warning' : undefined">
                {{ row.customerViewId ? '法人视图' : '客户集团' }}
              </el-tag>
              <span style="margin-left: 6px;">{{ attachName(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="有效期" width="200">
            <template #default="{ row }">{{ row.effectiveDate }} ~ {{ row.expireDate || '不限期' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="240" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === '0' || row.status === '1'" link type="primary"
                         @click="openEdit(row)">编辑</el-button>
              <el-button v-if="row.status === '0' || row.status === '1'" link type="danger"
                         @click="confirmStop(row, '停用')">停用</el-button>
              <el-button v-if="row.status === '3'" link type="success"
                         @click="confirmStop(row, '恢复')">恢复</el-button>
              <span v-if="row.status === '2'" style="color: #909399; font-size: 12px;">终态</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination layout="total, prev, pager, next" style="margin-top: 12px; justify-content: flex-end;"
          :total="paTotal" :page-size="paQuery.size" :current-change="loadAgreements" />
      </template>

      <!-- ============ Tab3 试算 ============ -->
      <template v-if="tab === 'trial'">
        <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
          title="试算优先级：EXCLUSIVE > LADDER > TIME；法人视图级 > 客户集团级；LADDER 按数量闭区间匹配"
          description="SO 创建时的价格绑定与阻断属销售域，本接口为其预铺（FR-4.3-4-3 优先级子集）。" />
        <el-form inline>
          <el-form-item label="客户">
            <el-select v-model="trialForm.groupId" placeholder="客户集团" clearable style="width: 240px;">
              <el-option v-for="g in groups" :key="g.id" :label="`[${g.customerCode}] ${g.customerName}`" :value="g.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="法人视图（可选）">
            <el-select v-model="trialForm.viewId" placeholder="法人级优先" clearable style="width: 200px;"
                       :disabled="!trialForm.groupId" @visible-change="loadViews">
              <el-option v-for="v in trialViews" :key="v.id" :label="leName(v.legalEntityId)" :value="v.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="SKU">
            <el-select v-model="trialForm.itemCode" filterable style="width: 200px;">
              <el-option v-for="o in itemOptions" :key="o.code" :label="`[${o.code}] ${o.name}`" :value="o.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="数量">
            <el-input-number v-model="trialForm.qty" :min="1" style="width: 130px;" />
          </el-form-item>
          <el-form-item label="日期">
            <el-date-picker v-model="trialForm.date" type="date" value-format="YYYY-MM-DD" style="width: 160px;" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="loading" @click="doTrial">试算</el-button>
          </el-form-item>
        </el-form>
        <el-card v-if="trialResult" shadow="never">
          <template v-if="trialResult.applicable">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="命中协议">{{ trialResult.paCode }} {{ trialResult.paName }}</el-descriptions-item>
              <el-descriptions-item label="类型">{{ typeName(trialResult.agreementType) }}</el-descriptions-item>
              <el-descriptions-item label="挂靠级别">
                <el-tag size="small" :type="trialResult.attachLevel === '法人视图' ? 'warning' : undefined">{{ trialResult.attachLevel }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="适用单价"><b>{{ trialResult.unitPrice }}</b></el-descriptions-item>
              <el-descriptions-item label="有效期" :span="2">{{ trialResult.effectiveDate }} ~ {{ trialResult.expireDate || '不限期' }}</el-descriptions-item>
            </el-descriptions>
          </template>
          <el-result v-else icon="warning" :title="trialResult.message" :sub-title="trialResult.checkedTypes">
            <template #extra>
              <ul style="text-align: left; color: #909399;">
                <li v-for="(r, i) in trialResult.reasons" :key="i">{{ r }}</li>
              </ul>
            </template>
          </el-result>
        </el-card>
      </template>
    </el-card>

    <!-- 事件详情抽屉 -->
    <el-drawer v-model="eventVisible" title="事件详情" size="520px">
      <template v-if="eventDetail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="事件类型">{{ eventDetail.event.eventType }}</el-descriptions-item>
          <el-descriptions-item label="event_id">{{ eventDetail.event.eventId }}</el-descriptions-item>
          <el-descriptions-item label="幂等键">{{ eventDetail.event.idempotencyKey }}</el-descriptions-item>
          <el-descriptions-item label="Schema 版本">v{{ eventDetail.event.version }}（记录版本 v{{ eventDetail.event.recordVersion }}）</el-descriptions-item>
          <el-descriptions-item label="生产方">{{ eventDetail.event.source }}</el-descriptions-item>
          <el-descriptions-item label="发生时间">{{ (eventDetail.event.occurredAt || '').replace('T', ' ') }}</el-descriptions-item>
          <el-descriptions-item label="投递状态">
            {{ eventDetail.event.status }}
            <div style="font-size: 12px; color: #909399;">{{ eventDetail.deliveryNote }}</div>
          </el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">payload</el-divider>
        <pre class="payload">{{ JSON.stringify(JSON.parse(eventDetail.event.payload || '{}'), null, 2) }}</pre>
        <el-divider content-position="left">事件目录</el-divider>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="生产方">{{ eventDetail.catalog.producer }}</el-descriptions-item>
          <el-descriptions-item label="消费方">{{ eventDetail.catalog.consumers }}</el-descriptions-item>
          <el-descriptions-item label="幂等键规则">{{ eventDetail.catalog.idempotencyRule }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </el-drawer>

    <!-- 事件目录弹窗 -->
    <el-dialog v-model="catalogVisible" title="事件目录（7.2 事件契约登记）" width="620px">
      <el-table :data="catalogRows" size="small" border>
        <el-table-column prop="type" label="event_type" width="250" />
        <el-table-column prop="desc" label="语义" />
      </el-table>
      <div class="form-tip" style="margin-top: 10px;">
        生产方：MDM（mdm-service）｜消费方：销售/财务/CRM（未接入，桩）｜Schema v1｜
        幂等键：业务编码:vN（同键重放拒绝）｜投递：At-Least-Once（消息总线接入后生效）
      </div>
    </el-dialog>

    <!-- 协议新建/编辑弹窗 -->
    <el-dialog v-model="paVisible" :title="paForm.id ? `编辑协议：${paForm.paCode}` : '新建价格协议'" width="880px">
      <el-form label-width="130px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="协议名称" required>
              <el-input v-model="paForm.paName" maxlength="128" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="协议类型" required>
              <el-select v-model="paForm.agreementType" :disabled="!!paForm.id" style="width: 100%;">
                <el-option label="客户专属价（EXCLUSIVE）" value="EXCLUSIVE" />
                <el-option label="量价阶梯（LADDER）" value="LADDER" />
                <el-option label="时间价（TIME）" value="TIME" />
              </el-select>
              <div v-if="paForm.id" class="form-tip">类型创建后不可改（换类型请新建协议）</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="挂靠：客户集团" required>
              <el-select v-model="paForm.customerGroupId" clearable :disabled="!!paForm.id"
                         style="width: 100%;" @change="paForm.customerViewId = ''">
                <el-option v-for="g in groups" :key="g.id" :label="`[${g.customerCode}] ${g.customerName}`" :value="g.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="挂靠：法人视图" required>
              <el-select v-model="paForm.customerViewId" clearable :disabled="!!paForm.id || !paForm.customerGroupId"
                         style="width: 100%;" @change="paForm.customerGroupId = ''">
                <el-option v-for="v in trialViews" :key="v.id" :label="leName(v.legalEntityId)" :value="v.id" />
              </el-select>
              <div class="form-tip">二选一（FR-4.1-6-2）：选法人视图时自动取消集团挂靠</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="生效日期" required>
              <el-date-picker v-model="paForm.effectiveDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="失效日期">
              <el-date-picker v-model="paForm.expireDate" type="date" value-format="YYYY-MM-DD"
                              clearable placeholder="空 = 不限期" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col v-if="paForm.id" :span="24">
            <el-form-item label="变更原因" required>
              <el-input v-model="paForm.changeReason" maxlength="255" placeholder="必填（≥2 字）" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <el-divider content-position="left">价格行（≥1 行；SKU 须为启用物料）</el-divider>
      <el-table :data="paForm.lines" size="small" border>
        <el-table-column label="SKU" min-width="200">
          <template #default="{ row }">
            <el-select v-model="row.itemCode" filterable style="width: 100%;">
              <el-option v-for="o in itemOptions" :key="o.code" :label="`[${o.code}] ${o.name}`" :value="o.code" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="单价" width="160">
          <template #default="{ row }">
            <el-input-number v-model="row.unitPrice" :min="0.0001" :precision="4" style="width: 100%;" />
          </template>
        </el-table-column>
        <template v-if="paForm.agreementType === 'LADDER'">
          <el-table-column label="最小数量（含）" width="160">
            <template #default="{ row }">
              <el-input-number v-model="row.minQty" :min="0" :precision="3" style="width: 100%;" />
            </template>
          </el-table-column>
          <el-table-column label="最大数量（含）" width="160">
            <template #default="{ row }">
              <el-input-number v-model="row.maxQty" :min="0" :precision="3" style="width: 100%;" />
            </template>
          </el-table-column>
        </template>
        <el-table-column label="操作" width="70">
          <template #default="{ $index }">
            <el-button link type="danger" @click="paForm.lines.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button link type="primary" style="margin-top: 8px;" @click="addLine">+ 添加价格行</el-button>

      <template #footer>
        <el-button @click="paVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitPa">保存</el-button>
      </template>
    </el-dialog>

    <!-- 停用/恢复确认 -->
    <el-dialog v-model="stopVisible" :title="`${stopMeta.title}：${stopRow?.paName || ''}`" width="520px">
      <el-alert v-if="stopMeta.kind === 'stop'" type="warning" :closable="false" style="margin-bottom: 10px;"
        title="停用后该协议不再参与试算（可在过期前恢复）。" />
      <el-alert v-else type="info" :closable="false" style="margin-bottom: 10px;"
        title="恢复将按当前日期重新判定 未生效/生效中（已过期协议不可恢复）。" />
      <el-form label-width="80px">
        <el-form-item label="原因" required>
          <el-input v-model="stopReason" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stopVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!stopReason.trim()" :loading="saving" @click="submitStop">
          确认{{ stopMeta.title }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import {
  getOutboxPageApi, getOutboxDetailApi,
  getAgreementPageApi, getAgreementApi, createAgreementApi, updateAgreementApi,
  stopAgreementApi, trialApi
} from '@/api/mdm/cross-domain-sharing'
import { getGroupPageApi, getViewsApi } from '@/api/mdm/customer'
import { getItemOptionsApi } from '@/api/mdm/item'
import { getLegalEntityPageApi } from '@/api/mdm/legal-entity'

const EVENT_TYPES = [
  'MDM.CUSTOMER.CREATED', 'MDM.CUSTOMER.UPDATED', 'MDM.CUSTOMER.DISABLED',
  'MDM.CUSTOMER.ENABLED', 'MDM.CUSTOMER.FROZEN', 'MDM.CUSTOMER.UNFROZEN',
  'MDM.CUSTOMER.MERGED', 'MDM.CUSTOMER.CREDIT_UPDATED', 'MDM.CUSTOMER.REVIEWED',
  'MDM.CUSTOMER.TEMP_ROLLBACK',
  'MDM.SUPPLIER.CREATED', 'MDM.SUPPLIER.UPDATED', 'MDM.SUPPLIER.REVIEWED',
  'MDM.SUPPLIER.REJECTED', 'MDM.SUPPLIER.FROZEN', 'MDM.SUPPLIER.UNFROZEN',
  'MDM.SUPPLIER.DISABLED', 'MDM.SUPPLIER.ENABLED', 'MDM.SUPPLIER.CERT_EXPIRED',
  'MDM.SUPPLIER.CERT_RENEWED',
  'MDM.SUPPLIER.MERGED', 'MDM.SUPPLIER.MERGE_REVERTED',
  'MDM.RATE.CREATED', 'MDM.RATE.UPDATED'
]
const catalogRows = EVENT_TYPES.map(t => ({ type: t, desc: descOf(t) }))
function descOf(t) {
  return ({
    'MDM.CUSTOMER.CREATED': '客户集团建档', 'MDM.CUSTOMER.UPDATED': '客户信息/基准变更',
    'MDM.CUSTOMER.DISABLED': '客户停用', 'MDM.CUSTOMER.ENABLED': '客户启用',
    'MDM.CUSTOMER.FROZEN': '客户冻结（级联法人）', 'MDM.CUSTOMER.UNFROZEN': '客户解冻',
    'MDM.CUSTOMER.MERGED': '客户合并（源锁定终态）', 'MDM.CUSTOMER.CREDIT_UPDATED': '信用额度调整',
    'MDM.CUSTOMER.REVIEWED': '年度复审通过', 'MDM.CUSTOMER.TEMP_ROLLBACK': '临时额度到期回滚',
    'MDM.SUPPLIER.CREATED': '供应商建档（待审核）', 'MDM.SUPPLIER.UPDATED': '供应商变更/证照增改',
    'MDM.SUPPLIER.REVIEWED': '准入审核通过', 'MDM.SUPPLIER.REJECTED': '准入审核驳回',
    'MDM.SUPPLIER.FROZEN': '供应商冻结', 'MDM.SUPPLIER.UNFROZEN': '供应商解冻',
    'MDM.SUPPLIER.DISABLED': '供应商停用', 'MDM.SUPPLIER.ENABLED': '供应商启用',
    'MDM.SUPPLIER.CERT_EXPIRED': '证照到期受限（行 805）', 'MDM.SUPPLIER.CERT_RENEWED': '证照核验解除',
    'MDM.SUPPLIER.MERGED': '供应商合并（源锁定终态）', 'MDM.SUPPLIER.MERGE_REVERTED': '合并回退（30 天窗口）',
    'MDM.RATE.CREATED': '汇率记录创建', 'MDM.RATE.UPDATED': '汇率记录变更'
  })[t] || t
}

const tab = ref('events')
const loading = ref(false)
const saving = ref(false)

// ---------- 事件流 ----------
const events = ref([])
const evtTotal = ref(0)
const evtQuery = ref({ eventType: '', status: '', keyword: '', current: 1, size: 10 })
const eventVisible = ref(false)
const eventDetail = ref(null)
const catalogVisible = ref(false)

async function loadEvents(page) {
  if (page) evtQuery.value.current = page
  loading.value = true
  try {
    const res = await getOutboxPageApi(evtQuery.value)
    events.value = res.data.records
    evtTotal.value = res.data.total
  } finally {
    loading.value = false
  }
}
async function openEvent(row) {
  const res = await getOutboxDetailApi(row.id)
  eventDetail.value = res.data
  eventVisible.value = true
}

// ---------- 价格协议 ----------
const agreements = ref([])
const paTotal = ref(0)
const paQuery = ref({ keyword: '', agreementType: '', status: '', current: 1, size: 10 })
const paVisible = ref(false)
const paForm = ref({})
const groups = ref([])
const trialViews = ref([])
const legalEntities = ref([])
const itemOptions = ref([])

async function loadAgreements(page) {
  if (page) paQuery.value.current = page
  loading.value = true
  try {
    const res = await getAgreementPageApi(paQuery.value)
    agreements.value = res.data.records
    paTotal.value = res.data.total
  } finally {
    loading.value = false
  }
}

function typeName(t) {
  return { EXCLUSIVE: '客户专属价', LADDER: '量价阶梯', TIME: '时间价' }[t] || t
}
function statusName(s) {
  return { 0: '未生效', 1: '生效中', 2: '过期', 3: '停用' }[s] || s
}
function statusTag(s) {
  return { 0: 'info', 1: 'success', 2: 'warning', 3: 'danger' }[s]
}
function leName(id) {
  const le = legalEntities.value.find(x => x.id === id)
  return le ? `${le.leCode} ${le.leName}` : id || ''
}
function attachName(row) {
  if (row.customerViewId) {
    const v = [...trialViews.value, ...(row._views || [])].find(x => x.id === row.customerViewId)
    return v ? leName(v.legalEntityId) : '(法人视图)'
  }
  const g = groups.value.find(x => x.id === row.customerGroupId)
  return g ? `[${g.customerCode}] ${g.customerName}` : '(客户集团)'
}

function openCreate() {
  paForm.value = {
    id: null, paName: '', agreementType: 'EXCLUSIVE',
    customerGroupId: '', customerViewId: '',
    effectiveDate: new Date().toISOString().slice(0, 10), expireDate: '',
    changeReason: '', lines: [{ itemCode: '', unitPrice: null, minQty: null, maxQty: null }]
  }
  paVisible.value = true
}
async function openEdit(row) {
  const res = await getAgreementApi(row.id)
  const detail = res.data
  paForm.value = {
    ...detail,
    expireDate: detail.expireDate || '',
    changeReason: '',
    lines: (detail.lines || []).map(l => ({
      itemCode: l.itemCode, unitPrice: l.unitPrice, minQty: l.minQty, maxQty: l.maxQty
    }))
  }
  if (!paForm.value.lines.length) {
    paForm.value.lines = [{ itemCode: '', unitPrice: null, minQty: null, maxQty: null }]
  }
  paVisible.value = true
}
function addLine() {
  paForm.value.lines.push({ itemCode: '', unitPrice: null, minQty: null, maxQty: null })
}
async function submitPa() {
  if (!paForm.value.lines.length) {
    ElMessage.warning('至少一行价格行')
    return
  }
  saving.value = true
  try {
    const payload = { ...paForm.value, lines: paForm.value.lines }
    if (paForm.value.id) {
      await updateAgreementApi(payload)
      ElMessage.success('协议已更新')
    } else {
      const res = await createAgreementApi(payload)
      ElMessage.success(`新建成功：${res.data.paCode}`)
    }
    paVisible.value = false
    loadAgreements()
  } catch (e) {
    // 422 挂靠/区间/编码校验已单点提示
  } finally {
    saving.value = false
  }
}

const stopVisible = ref(false)
const stopRow = ref(null)
const stopKind = ref('stop')
const stopReason = ref('')
const stopMeta = computed(() => stopKind.value === 'stop'
  ? { title: '停用', kind: 'stop' } : { title: '恢复', kind: 'restore' })

function confirmStop(row, kind) {
  stopRow.value = row
  stopKind.value = kind === '恢复' ? 'restore' : 'stop'
  stopReason.value = ''
  stopVisible.value = true
}
async function submitStop() {
  saving.value = true
  try {
    await stopAgreementApi(stopRow.value.id, stopReason.value.trim())
    ElMessage.success(`${stopMeta.value.title}成功`)
    stopVisible.value = false
    loadAgreements()
  } catch (e) {
    // 已单点提示
  } finally {
    saving.value = false
  }
}

// ---------- 试算 ----------
const trialForm = ref({ groupId: '', viewId: '', itemCode: '', qty: 1, date: '' })
const trialResult = ref(null)

async function loadViews(open) {
  if (!open || !trialForm.value.groupId) return
  const res = await getViewsApi(trialForm.value.groupId)
  trialViews.value = res.data
}
async function doTrial() {
  if (!trialForm.value.groupId) {
    ElMessage.warning('请选择客户集团')
    return
  }
  if (!trialForm.value.itemCode) {
    ElMessage.warning('请选择 SKU')
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

function onTabChange(t) {
  if (t === 'events') loadEvents(1)
  if (t === 'agreements') loadAgreements(1)
}

onMounted(async () => {
  loadEvents(1)
  const [gs, les, opts] = await Promise.all([
    getGroupPageApi({ current: 1, size: 100 }),
    getLegalEntityPageApi({ current: 1, size: 100 }),
    getItemOptionsApi()
  ])
  groups.value = gs.data.records
  legalEntities.value = les.data.records
  itemOptions.value = opts.data
  // 编辑/挂靠选择时可展开法人视图：预载第一个集团
  if (groups.value.length) {
    const v = await getViewsApi(groups.value[0].id)
    trialViews.value = v.data
  }
})
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.form-tip { font-size: 12px; color: #909399; line-height: 1.5; margin-top: 2px; }
.payload { background: #f5f7fa; padding: 10px; border-radius: 4px; font-size: 12px; overflow: auto; }
</style>
