<template>
  <div class="gauge-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="计量器具与校准（6.9）：台账 → 到期预警（30/7 天）→ 校准执行 → 可疑批次追溯评估"
      description="CTQ 器具校准周期 ≤12 个月；校准 FAIL 自动停用并反向追溯自上次合格校准日起用过该器具的检验批；未评估可疑批次阻断放行（C-4.12-12），5 工作日评估时限。" />

    <!-- 到期预警（6.9.3） -->
    <el-row :gutter="12" style="margin-bottom:12px;" v-if="warnings.due7 || warnings.due30">
      <el-col :span="24">
        <el-card shadow="never" class="warn-card">
          <div style="display:flex;gap:24px;align-items:center;flex-wrap:wrap;">
            <b>到期预警</b>
            <el-tag type="danger" size="small">7 天内 {{ (warnings.due7 || []).length }} 台</el-tag>
            <el-tag type="warning" size="small">30 天内 {{ (warnings.due30 || []).length }} 台</el-tag>
            <span v-for="g in (warnings.due7 || []).slice(0, 6)" :key="g.id" class="warn-item">
              {{ g.gaugeCode }} · {{ g.name }}（{{ g.daysLeft }} 天）
            </span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 140px;" @change="load">
          <el-option label="有效" value="VALID" />
          <el-option label="限用" value="LIMITED" />
          <el-option label="停用" value="INVALID" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="器具编码 / 名称" clearable
          style="width: 210px;" @keyup.enter="load" @clear="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <span class="spacer" />
        <el-button v-if="canEdit" type="success" @click="openSave">新增器具</el-button>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading" @row-click="openDetail" row-style="cursor:pointer">
        <el-table-column prop="gaugeCode" label="编码" width="150">
          <template #default="{ row }"><b>{{ row.gaugeCode }}</b></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="CTQ" width="65" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.ctqFlag === '1'" type="danger" size="small">CTQ</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="calCycleMonths" label="周期(月)" width="90" align="center" />
        <el-table-column prop="lastCalDate" label="上次校准" width="105" />
        <el-table-column label="下次校准" width="150">
          <template #default="{ row }">
            <span :style="{ color: row.daysLeft != null && row.daysLeft <= 7 ? '#F56C6C' : '' }">
              {{ row.nextCalDate }}
              <template v-if="row.daysLeft != null">（{{ row.daysLeft }} 天）</template>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{ VALID: 'success', LIMITED: 'warning', INVALID: 'danger' }[row.status]">
              {{ { VALID: '有效', LIMITED: '限用', INVALID: '停用' }[row.status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dept" label="部门" width="100" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canEdit" link type="primary" @click.stop="openCalibrate(row)">校准</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top: 10px; justify-content: flex-end;"
        layout="total, prev, pager, next" :total="total"
        v-model:current-page="filters.current" :page-size="filters.size" @current-change="load" />
    </el-card>

    <!-- 详情：校准历史 + 可疑批次 -->
    <el-drawer v-model="drawer" :title="`器具详情 · ${detail.gauge ? detail.gauge.gaugeCode : ''}`"
      size="680px" destroy-on-close>
      <div v-if="detail.gauge">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="名称">{{ detail.gauge.name }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.gauge.status }}</el-descriptions-item>
          <el-descriptions-item label="周期">{{ detail.gauge.calCycleMonths }} 个月</el-descriptions-item>
          <el-descriptions-item label="CTQ">{{ detail.gauge.ctqFlag === '1' ? '是' : '否' }}</el-descriptions-item>
          <el-descriptions-item label="上次校准">{{ detail.gauge.lastCalDate || '—' }}</el-descriptions-item>
          <el-descriptions-item label="下次校准">{{ detail.gauge.nextCalDate || '—' }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="sec">校准历史</h4>
        <el-table :data="detail.calibrations || []" size="mini" border max-height="220">
          <el-table-column prop="calDate" label="日期" width="105" />
          <el-table-column label="结果" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="{ PASS: 'success', FAIL: 'danger', LIMITED: 'warning' }[row.result]">
                {{ row.result }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="certNo" label="证书号" width="130" />
          <el-table-column prop="calOrg" label="机构" width="120" />
          <el-table-column prop="nextCalDate" label="下次" width="105" />
          <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
        </el-table>

        <h4 class="sec">可疑批次（校准失败反向追溯）</h4>
        <el-table :data="detail.suspectLots || []" size="mini" border max-height="240">
          <el-table-column prop="lotId" label="批次 ID" width="180" show-overflow-tooltip />
          <el-table-column prop="suspectReason" label="原因" min-width="200" show-overflow-tooltip />
          <el-table-column label="状态" width="130" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'EVALUATED' ? 'success' : 'danger'">
                {{ row.status === 'EVALUATED' ? '已评估' : (row.evalOverdue ? '评估超期' : '待评估') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button v-if="canEdit && row.status === 'PENDING_EVAL'" link type="primary"
                @click="openEval(row)">评估</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!(detail.suspectLots || []).length" description="无可疑批次" :image-size="60" />
      </div>
    </el-drawer>

    <!-- 新增/编辑 -->
    <el-dialog v-model="saveVisible" title="器具台账" width="500px">
      <el-form label-width="100px" size="small">
        <el-form-item label="器具编码" required>
          <el-input v-model="saveForm.gaugeCode" placeholder="如 GAUGE-001" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="saveForm.name" />
        </el-form-item>
        <el-form-item label="CTQ 器具">
          <el-switch v-model="saveForm.ctq" />
          <span style="font-size:12px;color:#909399;margin-left:8px;">CTQ 周期须 ≤12 个月</span>
        </el-form-item>
        <el-form-item label="校准周期(月)" required>
          <el-input-number v-model="saveForm.calCycleMonths" :min="1" :max="60" />
        </el-form-item>
        <el-form-item label="下次校准">
          <el-date-picker v-model="saveForm.nextCalDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="部门">
          <el-input v-model="saveForm.dept" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveVisible = false">取消</el-button>
        <el-button type="primary" @click="doSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 校准执行 -->
    <el-dialog v-model="calVisible" :title="`校准执行 · ${calForm.gaugeCode}`" width="480px">
      <el-form label-width="90px" size="small">
        <el-form-item label="结果" required>
          <el-radio-group v-model="calForm.result">
            <el-radio-button value="PASS">合格</el-radio-button>
            <el-radio-button value="LIMITED">限用</el-radio-button>
            <el-radio-button value="FAIL">不合格</el-radio-button>
          </el-radio-group>
          <div v-if="calForm.result === 'FAIL'" style="font-size:12px;color:#F56C6C;margin-top:4px;">
            不合格将停用器具并反向追溯可疑批次（未评估阻断放行）
          </div>
        </el-form-item>
        <el-form-item label="校准日期">
          <el-date-picker v-model="calForm.calDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="证书号">
          <el-input v-model="calForm.certNo" />
        </el-form-item>
        <el-form-item label="校准机构">
          <el-input v-model="calForm.calOrg" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="calForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="calVisible = false">取消</el-button>
        <el-button type="primary" @click="doCalibrate">执行校准</el-button>
      </template>
    </el-dialog>

    <!-- 可疑批次评估 -->
    <el-dialog v-model="evalVisible" title="可疑批次评估（评估前该批放行被阻断）" width="500px">
      <el-input v-model="evalConclusion" type="textarea" :rows="3"
        placeholder="评估结论（复验数据、是否影响判定、放行/退货决定，至少 2 字）" />
      <template #footer>
        <el-button @click="evalVisible = false">取消</el-button>
        <el-button type="primary" @click="doEval">提交评估</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getGaugePageApi, getGaugeDetailApi, saveGaugeApi, calibrateApi,
  warningsApi, evalSuspectApi
} from '@/api/qms/gauge'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const warnings = ref({})
const filters = reactive({ status: '', keyword: '', current: 1, size: 10 })
const canEdit = ['ROLE_ADMIN', 'ROLE_QUALITY_ENG', 'ROLE_QUALITY_MGR']
  .some(r => (userStore.userInfo && userStore.userInfo.roles || []).includes(r))

async function load() {
  loading.value = true
  try {
    const res = await getGaugePageApi({
      current: filters.current, size: filters.size,
      status: filters.status || undefined, keyword: filters.keyword || undefined
    })
    rows.value = (res.data && res.data.records) || []
    total.value = (res.data && res.data.total) || 0
  } finally {
    loading.value = false
  }
}
async function loadWarnings() {
  const res = await warningsApi()
  warnings.value = res.data || {}
}

// ---------- 详情 ----------
const drawer = ref(false)
const detail = ref({})
async function openDetail(row) {
  const res = await getGaugeDetailApi(row.id)
  detail.value = res.data || {}
  drawer.value = true
}

// ---------- 台账 ----------
const saveVisible = ref(false)
const saveForm = reactive({ gaugeCode: '', name: '', ctq: false, calCycleMonths: 12, nextCalDate: '', dept: '' })
function openSave() {
  saveForm.gaugeCode = ''
  saveForm.name = ''
  saveForm.ctq = false
  saveForm.calCycleMonths = 12
  saveForm.nextCalDate = ''
  saveForm.dept = ''
  saveVisible.value = true
}
async function doSave() {
  try {
    await saveGaugeApi({ ...saveForm, ctqFlag: saveForm.ctq ? '1' : '0' })
    ElMessage.success('台账已保存')
    saveVisible.value = false
    load()
    loadWarnings()
  } catch { /* 拦截器已弹错 */ }
}

// ---------- 校准 ----------
const calVisible = ref(false)
const calForm = reactive({ gaugeId: '', gaugeCode: '', result: 'PASS', calDate: '', certNo: '', calOrg: '', remark: '' })
function openCalibrate(row) {
  calForm.gaugeId = row.id
  calForm.gaugeCode = row.gaugeCode
  calForm.result = 'PASS'
  calForm.calDate = ''
  calForm.certNo = ''
  calForm.calOrg = ''
  calForm.remark = ''
  calVisible.value = true
}
async function doCalibrate() {
  try {
    await calibrateApi(calForm.gaugeId, { ...calForm })
    ElMessage.success(calForm.result === 'FAIL'
      ? '校准不合格：器具已停用，可疑批次已生成待评估' : '校准完成')
    calVisible.value = false
    load()
    loadWarnings()
    if (drawer.value && detail.value.gauge) openDetail({ id: detail.value.gauge.id })
  } catch { /* 拦截器已弹错 */ }
}

// ---------- 评估 ----------
const evalVisible = ref(false)
const evalConclusion = ref('')
let evalSuspectId = ''
function openEval(row) {
  evalSuspectId = row.id
  evalConclusion.value = ''
  evalVisible.value = true
}
async function doEval() {
  try {
    await evalSuspectApi(evalSuspectId, evalConclusion.value)
    ElMessage.success('评估完成，该批放行阻断解除')
    evalVisible.value = false
    if (detail.value.gauge) openDetail({ id: detail.value.gauge.id })
  } catch { /* 拦截器已弹错 */ }
}

let timer = null
onMounted(async () => {
  if (!userStore.userInfo) {
    try { await userStore.getUserInfo() } catch { /* ignore */ }
  }
  load()
  loadWarnings()
  timer = setInterval(loadWarnings, 60000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.gauge-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.spacer { flex: 1; }
.sec { margin: 16px 0 8px; font-size: 14px; color: #303133; }
.warn-card { border-left: 3px solid #F56C6C; }
.warn-item { font-size: 12px; color: #606266; }
</style>
