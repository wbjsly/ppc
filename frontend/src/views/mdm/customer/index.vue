<template>
  <div class="customer-admission">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">客户准入</span>
          <div class="header-actions">
            <el-input v-model="query.keyword" placeholder="编码/名称/税号" clearable
                      :prefix-icon="Search" style="width: 200px;" @keyup.enter="loadData(1)" />
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 110px;" @change="loadData(1)">
              <el-option label="启用" value="1" />
              <el-option label="停用" value="0" />
              <el-option label="冻结" value="2" />
              <el-option label="已合并" value="3" />
            </el-select>
            <el-button type="primary" @click="loadData(1)">查询</el-button>
            <el-button type="primary" :icon="Plus" @click="openCreate">新建集团</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="customerCode" label="客户编码" width="120" />
        <el-table-column prop="customerName" label="客户名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="taxNo" label="税号" width="130">
          <template #default="{ row }">{{ row.taxNo || '—' }}</template>
        </el-table-column>
        <el-table-column prop="creditRating" label="评级" width="70">
          <template #default="{ row }">{{ row.creditRating || '—' }}</template>
        </el-table-column>
        <el-table-column label="总额度" width="110" align="right">
          <template #default="{ row }">{{ row.creditLimitTotal ?? '未配置' }}</template>
        </el-table-column>
        <el-table-column label="法人视图" width="80" align="center">
          <template #default="{ row }">{{ viewCounts[row.id] ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="330" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status !== '3'" link type="primary" @click="openChange(row)">变更</el-button>
            <el-button link type="primary" @click="openViews(row)">法人视图</el-button>
            <template v-if="row.status === '1'">
              <el-button link type="danger" @click="confirmStatus(row, '0', '停用')">停用</el-button>
              <el-button link type="warning" @click="confirmFreeze(row, '冻结')">冻结</el-button>
            </template>
            <el-button v-if="row.status === '0'" link type="success" @click="confirmStatus(row, '1', '启用')">启用</el-button>
            <el-button v-if="row.status === '2'" link type="success" @click="confirmFreeze(row, '解冻')">解冻</el-button>
            <el-button v-if="row.status !== '3'" link type="warning" @click="openMerge(row)">合并</el-button>
            <span v-if="row.status === '3'" style="color: #909399; font-size: 12px;">
              终态 → {{ row.mergedTo }}
            </span>
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

    <!-- 新建 / 变更集团弹窗 -->
    <el-dialog v-model="formVisible" :title="form.id ? '变更客户' : '新建客户集团视图'" width="640px">
      <el-alert v-if="similarHits.length" type="warning" show-icon :closable="false" style="margin-bottom: 10px;"
        :title="`名称相似 ${similarHits.length} 条：${similarHits.map(s => s.customerCode + ' ' + s.customerName).join('、')}`"
        description="确认为不同客户请填写差异说明后继续提交（系统将放行）。" />
      <el-form label-width="140px">
        <el-form-item label="客户编码">
          <el-input :model-value="form.id ? form.customerCode : '保存时自动生成（CUST-NNNN）'" disabled />
        </el-form-item>
        <el-form-item label="客户名称" required>
          <el-input v-model="form.customerName" maxlength="128" @blur="checkSimilar" />
          <div class="form-tip">查重：税号精确 + 名称编辑距离 ≤3（命中展示最近 3 条）</div>
        </el-form-item>
        <el-form-item label="税号">
          <el-input v-model="form.taxNo" maxlength="32" placeholder="跨集团视图唯一（BR-4.1-30）" />
        </el-form-item>
        <el-form-item label="统一社会信用代码">
          <el-input v-model="form.uscc" maxlength="32" />
        </el-form-item>
        <el-form-item label="集团信用评级">
          <el-select v-model="form.creditRating" clearable style="width: 100%;">
            <el-option v-for="r in ['AAA', 'AA', 'A', 'BBB', 'BB', 'B']" :key="r" :label="r" :value="r" />
          </el-select>
          <div class="form-tip">外部征信 + 内部履约，每半年复评（本期人工维护）</div>
        </el-form-item>
        <el-form-item label="集团信用总额度">
          <el-input-number :model-value="undefined" disabled style="width: 100%;"
                           placeholder="请在 1.3.2 信用额度菜单调整" />
          <div class="form-tip">额度类字段单一入口：建档后在「信用额度」菜单调整总额度/评级（求和校验基准，BR-4.1-31）</div>
        </el-form-item>
        <el-form-item v-if="form.id" label="变更原因" required>
          <el-input v-model="form.changeReason" maxlength="255" placeholder="必填（≥2 字）" />
        </el-form-item>
        <el-form-item v-if="!form.id && similarHits.length" label="差异说明">
          <el-input v-model="form.dupNote" maxlength="255" placeholder="确认非重复时必填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 法人视图弹窗 -->
    <el-dialog v-model="viewsVisible" :title="`法人视图：${viewsGroup?.customerName || ''}`" width="860px">
      <el-alert v-if="viewsGroup?.creditLimitTotal == null" type="info" :closable="false" style="margin-bottom: 10px;"
        title="本客户未配置集团信用总额度，额度求和校验当前放行（配置后生效）。" />
      <div class="credit-summary">
        额度合计：{{ creditSum }} / 集团总额度：{{ viewsGroup?.creditLimitTotal ?? '未配置' }}
        <el-tag v-if="creditExceeded" type="danger" size="small" style="margin-left: 8px;">超限（BR-4.1-31）</el-tag>
      </div>
      <el-table :data="views" size="small" stripe v-loading="viewsLoading">
        <el-table-column label="法人主体" width="140">
          <template #default="{ row }">{{ legalEntityName(row.legalEntityId) }}</template>
        </el-table-column>
        <el-table-column prop="shipAddress" label="收货地址" min-width="180" show-overflow-tooltip />
        <el-table-column prop="contactName" label="联系人" width="90" />
        <el-table-column prop="contactPhone" label="电话" width="120" />
        <el-table-column prop="paymentTerms" label="付款条件" width="90" />
        <el-table-column label="额度" width="90" align="right">
          <template #default="{ row }">{{ row.creditLimit ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === '1' ? 'success' : row.status === '2' ? 'warning' : 'info'" size="small">
              {{ row.status === '1' ? '启用' : row.status === '2' ? '冻结(级联)' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="viewsGroup?.status === '3'" @click="editView(row)">变更</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="viewsVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="viewsGroup?.status === '3'" @click="editView(null)">挂载法人视图</el-button>
      </template>
    </el-dialog>

    <!-- 法人视图编辑弹窗 -->
    <el-dialog v-model="viewFormVisible" :title="viewForm.id ? '变更法人视图' : '挂载法人视图'" width="620px">
      <el-form label-width="120px">
        <el-form-item label="法人主体" required>
          <el-select v-model="viewForm.legalEntityId" :disabled="!!viewForm.id" style="width: 100%;">
            <el-option v-for="le in legalEntities" :key="le.id" :label="`${le.leCode} ${le.leName}`" :value="le.id" />
          </el-select>
          <div v-if="viewForm.id" class="form-tip">法人主体创建后不可改（同客户编码锁定语义）</div>
        </el-form-item>
        <el-form-item label="收货地址" required>
          <el-input v-model="viewForm.shipAddress" maxlength="255" />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="viewForm.contactName" maxlength="64" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="viewForm.contactPhone" maxlength="32" />
        </el-form-item>
        <el-form-item label="付款条件">
          <el-select v-model="viewForm.paymentTerms" clearable style="width: 100%;">
            <el-option v-for="p in ['NET30', 'NET60', 'NET90', '月结30天', '款到发货']" :key="p" :label="p" :value="p" />
          </el-select>
        </el-form-item>
        <el-form-item label="本法人信用额度">
          <el-input-number :model-value="viewForm.creditLimit" disabled style="width: 100%;"
                           placeholder="请在 1.3.2 信用额度菜单调整" />
          <div class="form-tip">额度类字段单一入口：在「信用额度」菜单调整（含临时额度、求和校验 BR-4.1-31）</div>
        </el-form-item>
        <el-form-item v-if="viewForm.id" label="变更原因" required>
          <el-input v-model="viewForm.changeReason" maxlength="255" placeholder="必填（≥2 字）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="viewFormVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitView">保存</el-button>
      </template>
    </el-dialog>

    <!-- 合并弹窗 -->
    <el-dialog v-model="mergeVisible" :title="`合并客户：${mergeSource?.customerName || ''}`" width="640px">
      <el-form label-width="110px">
        <el-form-item label="源客户">
          <el-input :model-value="`${mergeSource?.customerCode} ${mergeSource?.customerName}`" disabled />
        </el-form-item>
        <el-form-item label="目标客户" required>
          <el-select v-model="mergeTargetId" filterable remote :remote-method="loadCandidates"
                     placeholder="输入编码/名称/税号搜索（税号相同优先）" style="width: 100%;" @focus="loadCandidates('')">
            <el-option v-for="c in candidates" :key="c.id"
                       :label="`${c.customerCode} ${c.customerName}（税号 ${c.taxNo || '-'}）`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="影响面">
          <div v-if="mergeImpact !== null" class="impact-brief">
            源客户下法人视图 <b>{{ mergeImpact }}</b> 个将全部改挂目标客户；源编码合并后永久锁定（终态）。
            <div class="form-tip">同法人主体冲突将被阻断（UK 冲突预检），请先调整任一侧视图。</div>
          </div>
          <span v-else style="color: #909399; font-size: 12px;">选择目标客户后展示</span>
        </el-form-item>
        <el-form-item label="合并原因" required>
          <el-input v-model="mergeReason" type="textarea" :rows="3" maxlength="200" show-word-limit
            placeholder="必填，如：税号重复建档，合并至统一主体" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mergeVisible = false">取消</el-button>
        <el-button type="danger" :disabled="!mergeTargetId || !mergeReason.trim()" :loading="saving" @click="submitMerge">
          确认合并
        </el-button>
      </template>
    </el-dialog>

    <!-- 状态确认（停用/启用/冻结/解冻，原因必填） -->
    <el-dialog v-model="statusVisible" :title="`${statusMeta.title}：${statusRow?.customerName || ''}`" width="560px">
      <el-alert v-if="statusMeta.kind === 'freeze'" type="warning" :closable="false" style="margin-bottom: 10px;"
        title="冻结将级联其下全部法人视图（BR-4.1-34），解冻同步恢复。" />
      <el-form label-width="90px">
        <el-form-item :label="statusMeta.label" required>
          <el-input v-model="statusReason" type="textarea" :rows="3" :placeholder="statusMeta.placeholder"
                    maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item v-if="statusMeta.kind === 'disable'" label="影响分析">
          <div v-if="impactData" class="impact-brief">
            <div>法人视图：{{ impactData.viewCount }} 个</div>
            <div>下游引用（销售订单）：{{ impactData.downstreamNote }}</div>
          </div>
          <span v-else style="color: #909399; font-size: 12px;">加载中…</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="statusVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!statusReason.trim()" :loading="saving" @click="submitStatus">
          确认{{ statusMeta.title }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉（版本历史 + 对比） -->
    <el-drawer v-model="detailVisible" :title="`客户详情：${detail.customerName || ''}`" size="640px">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="客户编码">{{ detail.customerCode }}</el-descriptions-item>
        <el-descriptions-item label="客户名称">{{ detail.customerName }}</el-descriptions-item>
        <el-descriptions-item label="税号">{{ detail.taxNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="统一社会信用代码">{{ detail.uscc || '-' }}</el-descriptions-item>
        <el-descriptions-item label="信用评级">{{ detail.creditRating || '-' }}</el-descriptions-item>
        <el-descriptions-item label="总额度">{{ detail.creditLimitTotal ?? '未配置' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTag(detail.status)" size="small">{{ statusName(detail.status) }}</el-tag>
          <span v-if="detail.status === '3'" style="margin-left: 6px; font-size: 12px;">→ {{ detail.mergedTo }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="当前版本">V{{ detail.verNo }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">版本历史</el-divider>
      <div class="diff-bar">
        <el-select v-model="diffFrom" placeholder="起始版本" style="width: 110px;">
          <el-option v-for="v in versions" :key="v.versionNo" :label="`V${v.versionNo}`" :value="v.versionNo" />
        </el-select>
        <span>→</span>
        <el-select v-model="diffTo" placeholder="目标版本" style="width: 110px;">
          <el-option v-for="v in versions" :key="v.versionNo" :label="`V${v.versionNo}`" :value="v.versionNo" />
        </el-select>
        <el-button type="primary" plain :disabled="!diffFrom || !diffTo || diffFrom === diffTo" @click="loadDiff">对比</el-button>
      </div>
      <el-table :data="versions" size="small" stripe>
        <el-table-column prop="versionNo" label="版本" width="60">
          <template #default="{ row }">V{{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column prop="opType" label="操作" width="90">
          <template #default="{ row }">{{ opMap[row.opType] || row.opType }}</template>
        </el-table-column>
        <el-table-column prop="changeReason" label="原因" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.changeReason || '-' }}</template>
        </el-table-column>
        <el-table-column prop="diffSummary" label="摘要" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.diffSummary || '初始版本' }}</template>
        </el-table-column>
        <el-table-column prop="createDate" label="时间" width="155">
          <template #default="{ row }">{{ (row.createDate || '').replace('T', ' ') }}</template>
        </el-table-column>
      </el-table>

      <template v-if="diffFields.length">
        <el-divider content-position="left">差异对比</el-divider>
        <el-table :data="diffFields" size="small" border>
          <el-table-column prop="field" label="字段" width="150" />
          <el-table-column prop="from" label="起始值" show-overflow-tooltip />
          <el-table-column prop="to" label="目标值" show-overflow-tooltip />
        </el-table>
      </template>
      <el-empty v-else-if="diffRequested" description="两版本无差异" :image-size="60" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import {
  getGroupPageApi, getGroupApi, createGroupApi, updateGroupApi,
  getGroupVersionsApi, getGroupDiffApi,
  changeStatusApi, freezeApi, unfreezeApi, getImpactApi,
  getMergeCandidatesApi, mergeApi,
  getViewsApi, saveViewApi
} from '@/api/mdm/customer'
import { getLegalEntityPageApi } from '@/api/mdm/legal-entity'

const opMap = { CREATE: '新建', UPDATE: '变更', DISABLE: '停用', FREEZE: '冻结/解冻', MERGE: '合并' }

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ keyword: '', status: '', current: 1, size: 10 })
const viewCounts = ref({})
const legalEntities = ref([])

async function loadData(page) {
  if (page) query.value.current = page
  loading.value = true
  try {
    const res = await getGroupPageApi(query.value)
    rows.value = res.data.records
    total.value = res.data.total
    // 法人视图计数（列表辅助列）
    const counts = {}
    await Promise.all(rows.value.map(async r => {
      const v = await getViewsApi(r.id)
      counts[r.id] = v.data.length
    }))
    viewCounts.value = counts
  } finally {
    loading.value = false
  }
}

function statusName(s) {
  return s === '1' ? '启用' : s === '0' ? '停用' : s === '2' ? '冻结' : s === '3' ? '已合并' : '未知'
}
function statusTag(s) {
  return s === '1' ? 'success' : s === '0' ? 'info' : s === '2' ? 'warning' : 'danger'
}
function legalEntityName(id) {
  const le = legalEntities.value.find(x => x.id === id)
  return le ? `${le.leCode} ${le.leName}` : id
}

// ---------- 新建 / 变更 ----------
const formVisible = ref(false)
const form = ref({})
const similarHits = ref([])

function openCreate() {
  form.value = { customerName: '', taxNo: '', uscc: '', creditRating: '', creditLimitTotal: null }
  similarHits.value = []
  formVisible.value = true
}
function openChange(row) {
  form.value = { ...row, changeReason: '' }
  similarHits.value = []
  formVisible.value = true
}
async function checkSimilar() {
  if (!form.value.customerName || form.value.id) return
  try {
    const res = await getGroupPageApi({ keyword: form.value.customerName, current: 1, size: 5 })
    similarHits.value = res.data.records.filter(r => r.customerName !== form.value.customerName)
  } catch (e) { /* 查重失败不阻断表单 */ }
}
async function submitForm() {
  saving.value = true
  try {
    if (form.value.id) {
      await updateGroupApi(form.value)
      ElMessage.success('变更已生效')
    } else {
      if (similarHits.value.length && !(form.value.dupNote && form.value.dupNote.trim().length >= 2)) {
        ElMessage.warning('名称相似，确认非重复请填写差异说明（≥2 字）')
        saving.value = false
        return
      }
      const res = await createGroupApi(form.value, similarHits.value.length > 0)
      ElMessage.success(`新建成功：${res.data.customerCode}`)
    }
    formVisible.value = false
    loadData()
  } catch (e) {
    // 409 相似提示 / 422 税号阻断已由拦截器单点提示
  } finally {
    saving.value = false
  }
}

// ---------- 法人视图 ----------
const viewsVisible = ref(false)
const viewsGroup = ref(null)
const views = ref([])
const viewsLoading = ref(false)
const viewFormVisible = ref(false)
const viewForm = ref({})

const creditSum = computed(() =>
  views.value.reduce((s, v) => s + (Number(v.creditLimit) || 0), 0))
const creditExceeded = computed(() =>
  viewsGroup.value?.creditLimitTotal != null && creditSum.value > viewsGroup.value.creditLimitTotal)

async function openViews(row) {
  viewsGroup.value = row
  viewsVisible.value = true
  await loadViews()
}
async function loadViews() {
  viewsLoading.value = true
  try {
    const res = await getViewsApi(viewsGroup.value.id)
    views.value = res.data
  } finally {
    viewsLoading.value = false
  }
}
function editView(row) {
  viewForm.value = row
    ? { ...row, changeReason: '' }
    : { groupId: viewsGroup.value.id, legalEntityId: '', shipAddress: '', contactName: '',
        contactPhone: '', paymentTerms: '', creditLimit: null }
  viewFormVisible.value = true
}
async function submitView() {
  if (!viewForm.value.legalEntityId || !viewForm.value.shipAddress) {
    ElMessage.warning('法人主体与收货地址必填')
    return
  }
  saving.value = true
  try {
    await saveViewApi(viewForm.value)
    ElMessage.success(viewForm.value.id ? '法人视图已更新' : '法人视图已挂载')
    viewFormVisible.value = false
    await loadViews()
    loadData() // 刷新列表法人数与（可能变化的）额度
  } catch (e) {
    // 422 额度超限/重复挂载已单点提示
  } finally {
    saving.value = false
  }
}

// ---------- 状态操作 ----------
const statusVisible = ref(false)
const statusKind = ref('disable') // disable | enable | freeze | unfreeze
const statusRow = ref(null)
const statusReason = ref('')
const impactData = ref(null)

const STATUS_META = {
  disable: { title: '停用', label: '停用原因', placeholder: '如：经营调整，暂停合作', kind: 'disable' },
  enable: { title: '启用', label: '启用原因', placeholder: '如：恢复合作', kind: 'enable' },
  freeze: { title: '冻结', label: '冻结原因', placeholder: '如：信用恶化，临时冻结', kind: 'freeze' },
  unfreeze: { title: '解冻', label: '解冻原因', placeholder: '如：信用恢复', kind: 'unfreeze' }
}
const statusMeta = computed(() => STATUS_META[statusKind.value])

async function confirmStatus(row, toStatus, title) {
  statusKind.value = toStatus === '0' ? 'disable' : 'enable'
  statusRow.value = row
  statusReason.value = ''
  impactData.value = null
  statusVisible.value = true
  if (statusKind.value === 'disable') {
    const res = await getImpactApi(row.id)
    impactData.value = res.data
  }
}
function confirmFreeze(row, title) {
  statusKind.value = title === '冻结' ? 'freeze' : 'unfreeze'
  statusRow.value = row
  statusReason.value = ''
  impactData.value = null
  statusVisible.value = true
}
async function submitStatus() {
  saving.value = true
  try {
    const id = statusRow.value.id
    const reason = statusReason.value.trim()
    const k = statusKind.value
    if (k === 'disable' || k === 'enable') {
      await changeStatusApi(id, k === 'disable' ? '0' : '1', reason)
    } else if (k === 'freeze') {
      await freezeApi(id, reason)
    } else {
      await unfreezeApi(id, reason)
    }
    ElMessage.success(`${statusMeta.value.title}成功`)
    statusVisible.value = false
    loadData()
  } catch (e) {
    // 业务阻断已单点提示
  } finally {
    saving.value = false
  }
}

// ---------- 合并 ----------
const mergeVisible = ref(false)
const mergeSource = ref(null)
const mergeTargetId = ref('')
const mergeReason = ref('')
const candidates = ref([])
const mergeImpact = ref(null)

async function loadCandidates(keyword) {
  const res = await getMergeCandidatesApi(keyword || '', mergeSource.value?.id)
  candidates.value = res.data
}
async function openMerge(row) {
  mergeSource.value = row
  mergeTargetId.value = ''
  mergeReason.value = ''
  mergeImpact.value = null
  candidates.value = []
  mergeVisible.value = true
  await loadCandidates('')
}
async function submitMerge() {
  const target = candidates.value.find(c => c.id === mergeTargetId.value)
  if (target) {
    try {
      const v = await getViewsApi(mergeSource.value.id)
      mergeImpact.value = v.data.length
    } catch (e) { /* 影响面查询失败不阻断 */ }
  }
  const impactText = mergeImpact.value != null
    ? `源客户下 ${mergeImpact.value} 个法人视图将改挂目标客户，源编码永久锁定（终态）。`
    : ''
  try {
    await ElMessageBox.confirm(
      `确认将「${mergeSource.value.customerName}」合并至「${target?.customerName || ''}」？${impactText}此操作不可回退。`,
      '合并确认', { type: 'warning' }
    )
  } catch (e) {
    return // 用户取消
  }
  saving.value = true
  try {
    await mergeApi(mergeSource.value.id, mergeTargetId.value, mergeReason.value.trim())
    ElMessage.success('合并完成：法人视图已改挂，源客户已锁定为终态')
    mergeVisible.value = false
    loadData()
  } catch (e) {
    // 422 冲突预检/终态阻断已单点提示
  } finally {
    saving.value = false
  }
}

// ---------- 详情与版本 ----------
const detailVisible = ref(false)
const detail = ref({})
const versions = ref([])
const diffFrom = ref(null)
const diffTo = ref(null)
const diffFields = ref([])
const diffRequested = ref(false)

async function openDetail(row) {
  const res = await getGroupApi(row.id)
  detail.value = res.data
  versions.value = await getGroupVersionsApi(row.id).then(r => r.data)
  diffFrom.value = null
  diffTo.value = null
  diffFields.value = []
  diffRequested.value = false
  detailVisible.value = true
}
async function loadDiff() {
  const res = await getGroupDiffApi(detail.value.id, diffFrom.value, diffTo.value)
  diffFields.value = res.data.fields
  diffRequested.value = true
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
.credit-summary { font-size: 13px; color: #606266; margin-bottom: 10px; }
.diff-bar { margin-bottom: 10px; display: flex; gap: 8px; align-items: center; }
</style>
