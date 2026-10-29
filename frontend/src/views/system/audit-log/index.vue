<template>
  <div class="app-container">
    <el-tabs v-model="tab">
      <!-- ==================== 16.7.1 日志检索 ==================== -->
      <el-tab-pane label="日志检索" name="search">
        <el-form :inline="true" size="small" @submit.prevent>
          <el-form-item label="操作人">
            <el-input v-model="q.changeBy" placeholder="CHANGE_BY 模糊" clearable style="width:140px" />
          </el-form-item>
          <el-form-item label="动作">
            <el-input v-model="q.fieldName" placeholder="FIELD_NAME 模糊" clearable style="width:160px" />
          </el-form-item>
          <el-form-item label="对象">
            <el-input v-model="q.empId" placeholder="EMP_ID 模糊" clearable style="width:140px" />
          </el-form-item>
          <el-form-item label="关键词">
            <el-input v-model="q.keyword" placeholder="前后值/说明" clearable style="width:160px" />
          </el-form-item>
          <el-form-item label="时间范围">
            <el-date-picker v-model="q.range" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss"
              start-placeholder="起" end-placeholder="止" style="width:340px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="loadLogs(1)">查询</el-button>
            <el-button @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>
        <el-alert type="info" :closable="false" style="margin-bottom:8px"
          title="敏感内容（前后值/说明）已按脱敏策略自动掩码展示；导出取证包同样应用掩码。审计日志禁止修改与删除（BR-5.6-13，数据库触发器硬阻断）。" />
        <el-table :data="logs.rows" size="small" v-loading="logs.loading" border>
          <el-table-column prop="changeAt" label="时间" width="160" />
          <el-table-column prop="changeBy" label="操作人" width="110" />
          <el-table-column prop="fieldName" label="动作" width="170" show-overflow-tooltip />
          <el-table-column prop="empId" label="对象" width="140" show-overflow-tooltip />
          <el-table-column prop="oldValue" label="前值" show-overflow-tooltip />
          <el-table-column prop="newValue" label="后值" show-overflow-tooltip />
          <el-table-column prop="reason" label="说明" show-overflow-tooltip />
          <el-table-column prop="sourceIp" label="来源 IP" width="120" />
        </el-table>
        <el-pagination style="margin-top:8px;text-align:right" background layout="total, prev, pager, next"
          :total="logs.total" :page-size="logs.size" :current-page="logs.page"
          @current-change="loadLogs" />

        <el-divider content-position="left">防篡改尝试记录（BR-5.6-13）</el-divider>
        <el-button size="small" @click="loadTamper">刷新尝试记录</el-button>
        <el-button size="small" type="warning" @click="runTamperSweep">触发告警扫描（幂等）</el-button>
        <el-table :data="tamper.rows" size="small" border style="margin-top:8px">
          <el-table-column prop="attemptAt" label="尝试时间" width="160" />
          <el-table-column prop="actionType" label="动作" width="90" />
          <el-table-column prop="empId" label="对象" width="140" show-overflow-tooltip />
          <el-table-column prop="fieldName" label="字段" width="150" show-overflow-tooltip />
          <el-table-column prop="changeBy" label="操作者" width="120" />
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              <el-tag size="mini" :type="row.bypass === '1' ? 'warning' : 'danger'">
                {{ row.bypass === '1' ? '豁免放行' : '已拒绝' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ==================== 16.7.2 取证导出 ==================== -->
      <el-tab-pane label="取证导出" name="forensic">
        <el-form :inline="true" size="small" @submit.prevent>
          <el-form-item label="调阅目的">
            <el-input v-model="applyForm.purpose" placeholder="≥10 字，说明取证事由" style="width:320px" />
          </el-form-item>
          <el-form-item label="时间范围">
            <el-date-picker v-model="applyForm.range" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss"
              start-placeholder="起" end-placeholder="止" style="width:340px" />
          </el-form-item>
          <el-form-item label="操作类型">
            <el-input v-model="applyForm.actionFilter" placeholder="FIELD_NAME 过滤（可空）" clearable style="width:160px" />
          </el-form-item>
          <el-form-item label="对象范围">
            <el-input v-model="applyForm.objectFilter" placeholder="EMP_ID 前缀（可空）" clearable style="width:140px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="submitApply">提交调阅申请</el-button>
          </el-form-item>
        </el-form>
        <el-alert type="info" :closable="false" style="margin-bottom:8px"
          title="流程：申请 → 内审（ROLE_UAR_AUDITOR）签署 → DPO（ROLE_DPO）签署 → 生成取证包（SHA-256 封条）→ 分片下载（7 天有效期）→ 使用完毕销毁。下载/销毁动作自身留痕。" />
        <el-table :data="requests" size="small" v-loading="reqLoading" border>
          <el-table-column prop="createDate" label="申请时间" width="150" />
          <el-table-column prop="purpose" label="目的" show-overflow-tooltip />
          <el-table-column label="时间范围" width="230">
            <template #default="{ row }">{{ row.rangeFrom }} ~ {{ row.rangeTo }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="mini" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="内审签" width="80">
            <template #default="{ row }">{{ row.auditSignAt ? '✓' : '—' }}</template>
          </el-table-column>
          <el-table-column label="DPO 签" width="80">
            <template #default="{ row }">{{ row.dpoSignAt ? '✓' : '—' }}</template>
          </el-table-column>
          <el-table-column label="取证包" width="220">
            <template #default="{ row }">
              <template v-if="row.packageId">
                <span>{{ row.totalRows }} 条 / {{ row.shardCount }} 片</span>
                <el-tag size="mini" :type="row.packageStatus === 'GENERATED' ? 'success' : 'info'" style="margin:0 4px">
                  {{ row.packageStatus === 'GENERATED' ? '可下载' : '已销毁' }}
                </el-tag>
                <el-link v-if="row.packageStatus === 'GENERATED'" type="primary"
                  @click="downloadShards(row)">下载</el-link>
                <el-link v-if="row.packageStatus === 'GENERATED'" type="danger" style="margin-left:6px"
                  @click="doConfirmUsed(row)">确认销毁</el-link>
              </template>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200">
            <template #default="{ row }">
              <template v-if="!row.auditSignAt && row.status === 'APPLYING'">
                <el-button size="mini" type="primary" @click="doSign(row, 'audit')">内审签</el-button>
              </template>
              <template v-if="row.auditSignAt && !row.dpoSignAt">
                <el-button size="mini" type="success" @click="doSign(row, 'dpo')">DPO 签</el-button>
              </template>
              <template v-if="row.dpoSignAt && !row.packageId">
                <el-button size="mini" type="warning" @click="doGenerate(row)">生成取证包</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <div style="margin-top:8px">
          <el-button size="small" type="danger" plain @click="doSweep">过期销毁扫描（7 天届满，幂等）</el-button>
        </div>
      </el-tab-pane>

      <!-- ==================== 16.7.3 脱敏策略 ==================== -->
      <el-tab-pane label="脱敏策略" name="mask">
        <el-button size="small" type="primary" @click="openRule()">新建规则</el-button>
        <el-table :data="rules" size="small" border style="margin-top:8px">
          <el-table-column prop="code" label="编码" width="130" />
          <el-table-column prop="name" label="名称" width="120" />
          <el-table-column prop="pattern" label="正则" show-overflow-tooltip />
          <el-table-column prop="maskTemplate" label="掩码模板" width="140" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag size="mini" :type="row.status === '1' ? 'success' : 'info'">
                {{ row.status === '1' ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" show-overflow-tooltip />
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button size="mini" @click="openRule(row)">编辑</el-button>
              <el-button size="mini" type="danger" @click="doDeleteRule(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-dialog :title="ruleForm.id ? '编辑规则' : '新建规则'" v-model="ruleDialog" width="560px">
          <el-form :model="ruleForm" label-width="90px" size="small">
            <el-form-item label="编码" required>
              <el-input v-model="ruleForm.code" placeholder="唯一编码，如 ID_CARD" />
            </el-form-item>
            <el-form-item label="名称" required>
              <el-input v-model="ruleForm.name" />
            </el-form-item>
            <el-form-item label="正则" required>
              <el-input v-model="ruleForm.pattern" placeholder="Java 正则，如 (1[3-9]\d)\d{4}(\d{4})" />
            </el-form-item>
            <el-form-item label="掩码模板" required>
              <el-input v-model="ruleForm.maskTemplate" placeholder="支持 $1/$2 捕获组引用，如 $1****$2" />
            </el-form-item>
            <el-form-item label="状态">
              <el-switch v-model="ruleForm.status" active-value="1" inactive-value="0" active-text="启用" inactive-text="停用" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="ruleForm.remark" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="ruleDialog = false">取消</el-button>
            <el-button type="primary" @click="saveRule">保存</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  searchLogs, tamperList, tamperSweep,
  listRequests, applyRequest, signAudit, signDpo, generatePackage, downloadShard, confirmUsed, sweepExpired,
  listMaskRules, saveMaskRule, deleteMaskRule
} from '@/api/system/audit-log'

const route = useRoute()

// ---------- Tab 定位（沿 16.1~16.6 模式）----------
const TAB_WHITELIST = ['search', 'forensic', 'mask']
const pickTab = () => {
  const m = route.meta?.tab
  const q = route.query?.tab
  if (TAB_WHITELIST.includes(m)) return m
  if (TAB_WHITELIST.includes(q)) return q
  return 'search'
}
const tab = ref(pickTab())
watch(() => route.meta?.tab, () => { tab.value = pickTab() })

// ---------- 日志检索 ----------
const q = reactive({ changeBy: '', fieldName: '', empId: '', keyword: '', range: null })
const logs = reactive({ rows: [], total: 0, page: 1, size: 50, loading: false })

const loadLogs = async (page) => {
  if (page) logs.page = page
  logs.loading = true
  try {
    const params = { page: logs.page, size: logs.size }
    if (q.changeBy) params.changeBy = q.changeBy
    if (q.fieldName) params.fieldName = q.fieldName
    if (q.empId) params.empId = q.empId
    if (q.keyword) params.keyword = q.keyword
    if (q.range && q.range.length === 2) { params.from = q.range[0]; params.to = q.range[1] }
    const { data } = await searchLogs(params)
    logs.rows = data.rows || []
    logs.total = data.total || 0
  } finally {
    logs.loading = false
  }
}
const resetQuery = () => {
  q.changeBy = ''; q.fieldName = ''; q.empId = ''; q.keyword = ''; q.range = null
  loadLogs(1)
}

// ---------- 防篡改尝试 ----------
const tamper = reactive({ rows: [] })
const loadTamper = async () => {
  const { data } = await tamperList(100)
  tamper.rows = data || []
}
const runTamperSweep = async () => {
  const { data } = await tamperSweep()
  ElMessage.success(`扫描完成：新增 ${data.fresh} 条，通知 ${data.notified} 条`)
  loadTamper()
}

// ---------- 取证导出 ----------
const applyForm = reactive({ purpose: '', range: null, actionFilter: '', objectFilter: '' })
const requests = ref([])
const reqLoading = ref(false)

const loadRequests = async () => {
  reqLoading.value = true
  try {
    const { data } = await listRequests()
    requests.value = data || []
  } finally {
    reqLoading.value = false
  }
}
const submitApply = async () => {
  if (!applyForm.purpose || applyForm.purpose.trim().length < 10) {
    ElMessage.warning('调阅目的不少于 10 字'); return
  }
  if (!applyForm.range || applyForm.range.length !== 2) {
    ElMessage.warning('请选择时间范围'); return
  }
  await applyRequest({
    purpose: applyForm.purpose, rangeFrom: applyForm.range[0], rangeTo: applyForm.range[1],
    actionFilter: applyForm.actionFilter || null, objectFilter: applyForm.objectFilter || null
  })
  ElMessage.success('调阅申请已提交，待内审签署')
  applyForm.purpose = ''; applyForm.range = null; applyForm.actionFilter = ''; applyForm.objectFilter = ''
  loadRequests()
}
const doSign = async (row, kind) => {
  try {
    const { value } = await ElMessageBox.prompt(
      kind === 'audit' ? '内审签署意见' : 'DPO 签署意见', '签署', { inputPattern: /.{2,}/, inputErrorMessage: '意见 ≥2 字' })
    if (kind === 'audit') await signAudit(row.id, value)
    else await signDpo(row.id, value)
    ElMessage.success('签署完成')
    loadRequests()
  } catch (e) { /* 取消 */ }
}
const doGenerate = async (row) => {
  await generatePackage(row.id)
  ElMessage.success('取证包已生成（封条 SHA-256 已预计算）')
  loadRequests()
}
const downloadShards = async (row) => {
  for (let s = 0; s < row.shardCount; s++) {
    const res = await downloadShard(row.packageId, s)
    const blob = new Blob([res], { type: 'text/csv;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `audit-forensic-${row.packageId}-shard${s}.csv`
    a.click()
    URL.revokeObjectURL(a.href)
  }
  ElMessage.success(`已下载 ${row.shardCount} 片（下载动作已留痕）`)
  loadRequests()
}
const doConfirmUsed = async (row) => {
  await ElMessageBox.confirm('确认使用完毕并销毁该取证包？销毁后不可恢复（操作留痕）。', '确认销毁', { type: 'warning' })
  await confirmUsed(row.packageId)
  ElMessage.success('已销毁')
  loadRequests()
}
const doSweep = async () => {
  const { data } = await sweepExpired()
  ElMessage.success(`过期扫描完成：销毁 ${data.destroyed} 个`)
  loadRequests()
}
const statusType = (s) => ({ APPLYING: 'info', READY: 'warning', PACKAGED: 'success', REJECTED: 'danger' }[s] || 'info')
const statusText = (s) => ({ APPLYING: '待签', READY: '双签齐', PACKAGED: '已出包', REJECTED: '已驳回' }[s] || s)

// ---------- 脱敏规则 ----------
const rules = ref([])
const ruleDialog = ref(false)
const ruleForm = reactive({ id: '', code: '', name: '', pattern: '', maskTemplate: '', status: '1', remark: '' })

const loadRules = async () => {
  const { data } = await listMaskRules()
  rules.value = data || []
}
const openRule = (row) => {
  Object.assign(ruleForm, row
    ? { id: row.id, code: row.code, name: row.name, pattern: row.pattern, maskTemplate: row.maskTemplate, status: row.status, remark: row.remark }
    : { id: '', code: '', name: '', pattern: '', maskTemplate: '', status: '1', remark: '' })
  ruleDialog.value = true
}
const saveRule = async () => {
  await saveMaskRule({ ...ruleForm })
  ElMessage.success('规则已保存（即刻生效）')
  ruleDialog.value = false
  loadRules()
}
const doDeleteRule = async (row) => {
  await ElMessageBox.confirm(`删除规则「${row.name}」？删除后该模式不再掩码。`, '确认删除', { type: 'warning' })
  await deleteMaskRule(row.id)
  ElMessage.success('已删除')
  loadRules()
}

// ---------- 挂载 ----------
watch(tab, (t) => {
  if (t === 'forensic' && !requests.value.length) loadRequests()
  if (t === 'mask' && !rules.value.length) loadRules()
  if (t === 'search' && !tamper.rows.length) loadTamper()
})
onMounted(() => {
  loadLogs(1)
  loadTamper()
  if (tab.value === 'forensic') loadRequests()
  if (tab.value === 'mask') loadRules()
})
</script>
