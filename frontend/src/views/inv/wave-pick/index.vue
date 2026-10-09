<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>4.8.1 波次拣货</span>
          <div class="header-actions">
            <el-button type="primary" :loading="previewing" @click="doPreview">按规则生成波次</el-button>
          </div>
        </div>
      </template>

      <el-tabs v-model="tab">
        <!-- ===== 波次列表 ===== -->
        <el-tab-pane label="波次列表" name="list">
          <div class="query-bar">
            <el-select v-model="query.status" clearable placeholder="状态" style="width: 150px;" @change="load(1)">
              <el-option v-for="(n, v) in STATUS" :key="v" :label="n" :value="v" />
            </el-select>
            <el-input v-model="query.keyword" placeholder="波次号/聚类键" clearable style="width: 200px;" @keyup.enter="load(1)" />
            <el-button @click="load(1)">查询</el-button>
          </div>
          <el-table :data="rows" v-loading="loading" size="small" border>
            <el-table-column prop="waveNo" label="波次号" width="130" />
            <el-table-column label="聚类" width="170">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.clusterType }}</el-tag>
                <span class="mono">{{ row.clusterKey }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" size="small">{{ STATUS[row.status] || row.status }}</el-tag>
                <div v-if="row.status === 'PAUSED' && row.pauseFreezeNo" class="pause-tip">
                  冻结 {{ row.pauseFreezeNo }}
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="docCount" label="单据数" width="70" />
            <el-table-column prop="createDate" label="创建时间" width="160" />
            <el-table-column label="操作" width="240">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="openDetail(row)">详情</el-button>
                <el-button v-if="row.status === 'CREATED' || row.status === 'ALLOCATED'"
                  size="small" text type="danger"
                  @click="doCancel(row)">作废</el-button>
                <!-- 冻结挂起恢复（freeze-management 需求①：来源冻结未解除时后端 422） -->
                <el-button v-if="canWh && row.status === 'PAUSED'"
                  size="small" text type="warning"
                  @click="doResume(row)">恢复</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination style="margin-top: 10px; justify-content: flex-end;" layout="total, prev, pager, next"
            :total="total" :page-size="query.size" :current-page="query.current" @current-change="load" />
        </el-tab-pane>

        <!-- ===== 配送线路 ===== -->
        <el-tab-pane label="配送线路" name="route">
          <div class="query-bar">
            <el-input v-model="routeQuery.keyword" placeholder="线路编码/名称" clearable style="width: 200px;" @keyup.enter="loadRoutes(1)" />
            <el-button @click="loadRoutes(1)">查询</el-button>
            <el-button type="primary" @click="openRouteForm()">新增线路</el-button>
          </div>
          <el-table :data="routeRows" size="small" border>
            <el-table-column prop="routeCode" label="编码" width="130" />
            <el-table-column prop="routeName" label="名称" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                  {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="180">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="openRouteForm(row)">编辑</el-button>
                <el-button size="small" text :type="row.status === 'ACTIVE' ? 'warning' : 'success'"
                  @click="toggleRoute(row)">
                  {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination style="margin-top: 10px; justify-content: flex-end;" layout="total, prev, pager, next"
            :total="routeTotal" :page-size="routeQuery.size" :current-page="routeQuery.current"
            @current-change="loadRoutes" />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- ===== 候选预览对话框 ===== -->
    <el-dialog v-model="previewVisible" title="按规则生成波次 — 候选预览（确认后才落库）" width="860px">
      <el-alert v-if="previewData.message" :title="previewData.message" type="info" show-icon :closable="false"
        style="margin-bottom: 10px;" />
      <template v-else>
        <p class="tip">
          聚类规则：配送线路 &gt; 承运商 &gt; 客户（BR-4.4-42），单组超
          WAVE_MAX_DOCS={{ previewData.maxDocs }} 自动拆分；确认后按组分别建波次。
        </p>
        <div v-for="(g, i) in previewData.groups" :key="i" class="group-box">
          <div class="group-head">
            <el-tag size="small">{{ g.clusterType }}</el-tag>
            <b>{{ g.clusterLabel }}</b>
            <el-tag v-if="g.splitReason" type="warning" size="small">{{ g.splitReason }}</el-tag>
            <span class="tip">{{ g.docs.length }} 单</span>
          </div>
          <el-table :data="g.docs" size="mini" border>
            <el-table-column prop="shipNo" label="发货单" width="140" />
            <el-table-column prop="customerName" label="客户" />
            <el-table-column prop="logisticsCo" label="承运商" width="110" />
            <el-table-column prop="totalQty" label="数量" width="90" />
          </el-table>
        </div>
      </template>
      <template #footer>
        <el-button @click="previewVisible = false">取消</el-button>
        <el-button v-if="previewData.groups && previewData.groups.length" type="primary" :loading="confirming"
          @click="confirmPreview">确认建波次</el-button>
      </template>
    </el-dialog>

    <!-- ===== 波次详情对话框 ===== -->
    <el-dialog v-model="detailVisible" :title="`波次 ${detail.wave?.waveNo || ''}`" width="1080px">
      <el-descriptions :column="4" size="small" border style="margin-bottom: 10px;">
        <el-descriptions-item label="状态">
          <el-tag :type="statusType(detail.wave?.status)" size="small">
            {{ STATUS[detail.wave?.status] || detail.wave?.status }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="聚类">{{ detail.wave?.clusterKey }}</el-descriptions-item>
        <el-descriptions-item label="单据数">{{ detail.wave?.docCount }}</el-descriptions-item>
        <el-descriptions-item label="波次号">{{ detail.wave?.waveNo }}</el-descriptions-item>
      </el-descriptions>

      <!-- 状态操作条 -->
      <div class="op-bar" v-if="detail.wave?.status === 'CREATED'">
        <el-button type="primary" plain :loading="opLoading" @click="doAllocate">① 波次级分配</el-button>
        <el-button type="success" :disabled="!hasLines" :loading="opLoading" @click="doConfirmAllocate">
          ② 确认分配（生成 WAVE 任务）
        </el-button>
        <span class="tip" v-if="!hasLines">先执行分配生成分配行，方可确认</span>
      </div>
      <div class="op-bar" v-else-if="detail.wave?.status === 'ALLOCATED'">
        <el-tag type="success">已确认分配：拣货执行请前往 4.7.2 扫码确认（WAVE 任务）</el-tag>
      </div>

      <!-- 分配行（含改批） -->
      <el-divider content-position="left">分配行（{{ detail.lines?.length || 0 }}）</el-divider>
      <el-table :data="detail.lines || []" size="mini" border max-height="260">
        <el-table-column prop="lineNo" label="行" width="50" />
        <el-table-column prop="itemCode" label="物料" width="120" />
        <el-table-column prop="batchNo" label="批次" width="120" />
        <el-table-column prop="binCode" label="仓位" width="100" />
        <el-table-column prop="qty" label="数量" width="90" />
        <el-table-column label="锁" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.lockFlag === '1'" type="danger" size="small">改批审批中</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button v-if="row.lockFlag !== '1' && detail.wave?.status !== 'CANCELLED'
              && detail.wave?.status !== 'CLOSED'"
              size="small" text type="primary" @click="openAdjust(row)">改批</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 单据归属 -->
      <el-divider content-position="left">单据归属</el-divider>
      <el-table :data="detail.docs || []" size="mini" border>
        <el-table-column prop="shipNo" label="发货单" width="140" />
        <el-table-column label="归属" width="100">
          <template #default="{ row }">
            <el-tag :type="row.bindStatus === 'BOUND' ? 'success' : 'info'" size="small">
              {{ row.bindStatus === 'BOUND' ? '在波次' : '已拆出' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="unboundReason" label="拆出原因" />
        <el-table-column label="分播" width="90">
          <template #default="{ row }">{{ row.sortStatus === 'PASSED' ? '✓' : '待' }}</template>
        </el-table-column>
        <el-table-column label="装车" width="90">
          <template #default="{ row }">{{ row.loadStatus === 'LOADED' ? '✓' : '待' }}</template>
        </el-table-column>
      </el-table>

      <!-- 改批记录 -->
      <el-divider content-position="left">改批记录（C-4.4-08 留痕）</el-divider>
      <el-table :data="detail.adjusts || []" size="mini" border max-height="160">
        <el-table-column prop="field" label="字段" width="70" />
        <el-table-column prop="oldValue" label="前值" width="110" />
        <el-table-column prop="newValue" label="后值" width="110" />
        <el-table-column prop="reason" label="原因" />
        <el-table-column prop="status" label="审批状态" width="100" />
      </el-table>
    </el-dialog>

    <!-- ===== 改批对话框 ===== -->
    <el-dialog v-model="adjustVisible" title="人工改批（C-4.4-08 强制审批）" width="520px">
      <el-form label-width="110px">
        <el-form-item label="调整字段">
          <el-radio-group v-model="adjustForm.field">
            <el-radio label="BATCH">批次</el-radio>
            <el-radio label="BIN">仓位</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="adjustForm.field === 'BATCH' ? '新批次' : '新仓位'" required>
          <el-input v-model="adjustForm.newValue" :placeholder="adjustForm.field === 'BATCH' ? '目标批次号' : '目标仓位号'" />
        </el-form-item>
        <el-form-item label="调整原因" required>
          <el-input v-model="adjustForm.reason" type="textarea" :rows="2" maxlength="500"
            placeholder="必填：提交仓库主管审批，行冻结拣货至审批完成" />
        </el-form-item>
      </el-form>
      <el-alert type="warning" show-icon :closable="false"
        title="提交后该行 LOCKED 冻结拣货；审批通过生效新值、驳回保持原值；发起人不可自签（C-0-03）。" />
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="opLoading" @click="submitAdjust">提交审批</el-button>
      </template>
    </el-dialog>

    <!-- ===== 线路表单 ===== -->
    <el-dialog v-model="routeFormVisible" :title="routeForm.id ? '编辑线路' : '新增线路'" width="440px">
      <el-form label-width="90px">
        <el-form-item label="线路编码" required>
          <el-input v-model="routeForm.routeCode" :disabled="!!routeForm.id" placeholder="如 RT-HD-01" />
        </el-form-item>
        <el-form-item label="线路名称" required>
          <el-input v-model="routeForm.routeName" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="routeForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="routeFormVisible = false">取消</el-button>
        <el-button type="primary" :loading="opLoading" @click="submitRoute">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  previewWaveApi, createWaveApi, getWavePageApi, getWaveDetailApi, cancelWaveApi,
  allocateWaveApi, confirmAllocateApi, adjustWaveApi, resumeWaveApi,
  getRoutePageApi, createRouteApi, updateRouteApi, changeRouteStatusApi
} from '@/api/inv/wave'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const canWh = ((userStore.userInfo && userStore.userInfo.roles) || [])
  .some(r => r === 'ROLE_ADMIN' || r === 'ROLE_WAREHOUSE')

const STATUS = {
  CREATED: '已生成', ALLOCATED: '已分配', PICKING: '拣货中', SORTING: '分播中',
  STAGING: '装车中', SHIPPING: '发运中', CLOSED: '已关闭', CANCELLED: '已作废',
  PAUSED: '冻结挂起'
}
const statusType = s => ({
  CREATED: 'info', ALLOCATED: 'primary', PICKING: 'warning', SORTING: 'warning',
  STAGING: 'warning', SHIPPING: 'success', CLOSED: 'success', CANCELLED: 'info',
  PAUSED: 'danger'
}[s] || 'info')

const tab = ref('list')
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ status: '', keyword: '', current: 1, size: 20 })

async function load(page) {
  if (page) query.value.current = page
  loading.value = true
  try {
    const res = await getWavePageApi(query.value)
    rows.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (e) { /* 拦截器已 toast（422/409 等），吞掉防 unhandled rejection */
  } finally {
    loading.value = false
  }
}

// ---------- 预览与建波次 ----------
const previewing = ref(false)
const previewVisible = ref(false)
const previewData = ref({ groups: [] })
const confirming = ref(false)

async function doPreview() {
  previewing.value = true
  try {
    const res = await previewWaveApi()
    previewData.value = res.data
    previewVisible.value = true
  } catch (e) { /* 拦截器已 toast（422/409 等），吞掉防 unhandled rejection */
  } finally {
    previewing.value = false
  }
}

async function confirmPreview() {
  confirming.value = true
  try {
    const res = await createWaveApi(previewData.value.groups || [])
    const waves = res.data.waves || []
    const skipped = res.data.skipped || []
    ElMessage.success(`已建 ${waves.length} 个波次` + (skipped.length ? `，剔除 ${skipped.length} 单` : ''))
    previewVisible.value = false
    load(1)
  } catch (e) { /* 拦截器已 toast（422/409 等），吞掉防 unhandled rejection */
  } finally {
    confirming.value = false
  }
}

// ---------- 详情 ----------
const detailVisible = ref(false)
const detail = ref({})
const hasLines = ref(false)
const opLoading = ref(false)

async function openDetail(row) {
  const res = await getWaveDetailApi(row.id)
  detail.value = res.data
  hasLines.value = (res.data.lines || []).length > 0
  detailVisible.value = true
}

async function doAllocate() {
  opLoading.value = true
  try {
    const res = await allocateWaveApi(detail.value.wave.id)
    ElMessage.success(`分配完成：${res.data.allocatedLines} 段` +
      (res.data.unboundDocs > 0 ? `，${res.data.unboundDocs} 单批次不足拆出` : ''))
    if (res.data.allUnbound) {
      ElMessage.warning(res.data.message)
      detailVisible.value = false
      load(1)
      return
    }
    await openDetail(detail.value.wave)
  } catch (e) { /* 拦截器已 toast（422/409 等），吞掉防 unhandled rejection */
  } finally {
    opLoading.value = false
  }
}

async function doConfirmAllocate() {
  opLoading.value = true
  try {
    await confirmAllocateApi(detail.value.wave.id)
    ElMessage.success('已确认分配并生成 WAVE 合并拣货任务（执行请前往 4.7.2）')
    await openDetail(detail.value.wave)
    load(1)
  } catch (e) { /* 拦截器已 toast（422/409 等），吞掉防 unhandled rejection */
  } finally {
    opLoading.value = false
  }
}

async function doCancel(row) {
  let reason
  try {
    reason = await ElMessageBox.prompt('作废原因（必填，单据解绑后回到单据链路）', '作废波次',
      { inputPattern: /\S{2,}/, inputErrorMessage: '原因至少 2 字' })
  } catch { return }
  await cancelWaveApi(row.id, reason.value).catch(() => {})
  ElMessage.success('波次已作废')
  load(1)
}

async function doResume(row) {
  const res = await resumeWaveApi(row.id).catch(() => null)
  if (res) {
    ElMessage.success('已恢复至挂起前状态（来源冻结须已解冻）')
    load(1)
  }
}

// ---------- 改批 ----------
const adjustVisible = ref(false)
const adjustForm = ref({ lineId: '', field: 'BATCH', newValue: '', reason: '' })
const adjustLine = ref(null)

function openAdjust(row) {
  adjustLine.value = row
  adjustForm.value = { lineId: row.id, field: 'BATCH', newValue: '', reason: '' }
  adjustVisible.value = true
}

async function submitAdjust() {
  const f = adjustForm.value
  if (!f.newValue.trim()) { ElMessage.warning('新值必填'); return }
  if (!f.reason.trim()) { ElMessage.warning('调整原因必填'); return }
  opLoading.value = true
  try {
    const res = await adjustWaveApi(detail.value.wave.id, f)
    ElMessage.success(`已提交审批 ${res.data.apprNo}（行已冻结拣货）`)
    adjustVisible.value = false
    await openDetail(detail.value.wave)
  } catch (e) { /* 拦截器已 toast（422/409 等），吞掉防 unhandled rejection */
  } finally {
    opLoading.value = false
  }
}

// ---------- 线路 ----------
const routeQuery = ref({ keyword: '', current: 1, size: 20 })
const routeRows = ref([])
const routeTotal = ref(0)
const routeFormVisible = ref(false)
const routeForm = ref({})

async function loadRoutes(page) {
  if (page) routeQuery.value.current = page
  const res = await getRoutePageApi(routeQuery.value)
  routeRows.value = res.data.records || []
  routeTotal.value = res.data.total || 0
}

function openRouteForm(row) {
  routeForm.value = row ? { ...row } : { routeCode: '', routeName: '', remark: '' }
  routeFormVisible.value = true
}

async function submitRoute() {
  const f = routeForm.value
  if (!f.routeCode || !f.routeName) { ElMessage.warning('编码与名称必填'); return }
  opLoading.value = true
  try {
    if (f.id) {
      await updateRouteApi(f.id, f)
      ElMessage.success('线路已更新')
    } else {
      await createRouteApi(f)
      ElMessage.success('线路已创建')
    }
    routeFormVisible.value = false
    loadRoutes(1)
  } catch (e) { /* 拦截器已 toast（422/409 等），吞掉防 unhandled rejection */
  } finally {
    opLoading.value = false
  }
}

async function toggleRoute(row) {
  await changeRouteStatusApi(row.id, row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE').catch(() => {})
  ElMessage.success(row.status === 'ACTIVE' ? '已停用（存量快照不受影响）' : '已启用')
  loadRoutes(1)
}

onMounted(() => { load(1); loadRoutes(1) })
</script>

<style scoped>
.page { padding: 4px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-actions { display: flex; gap: 8px; }
.query-bar { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; margin: 0 8px; }
.pause-tip { color: #f56c6c; font-size: 11px; line-height: 14px; margin-top: 2px; }
.mono { font-family: monospace; margin-left: 6px; font-size: 12px; }
.group-box { border: 1px solid #ebeef5; border-radius: 4px; padding: 8px; margin-bottom: 10px; }
.group-head { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; }
.op-bar { display: flex; gap: 10px; align-items: center; margin-bottom: 10px; }
</style>
