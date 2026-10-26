<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">社保扣缴（10.5.1）</span>
        <el-button @click="reloadAll">刷新</el-button>
      </div>
      <el-tabs v-model="tab">
        <!-- ==================== 页签一：属地政策 ==================== -->
        <el-tab-pane label="属地政策" name="policy">
          <div class="toolbar" style="margin-bottom:12px">
            <div>
              <el-input v-model="city" placeholder="城市" clearable style="width:120px;margin-right:8px" @change="load" />
              <el-input v-model="year" placeholder="年度 如 2026" clearable style="width:110px;margin-right:8px" @change="load" />
              <el-button type="primary" @click="openSave">维护政策</el-button>
              <el-button @click="copyYear">年度复制</el-button>
            </div>
          </div>
          <el-alert type="warning" :closable="false" style="margin-bottom:12px"
                    title="缴费基数 = MIN(MAX(月薪, 下限), 上限)；员工缴纳地未配置当年政策 → 核算 L1 硬阻断（C-4.7-02）；每年 7 月基数调整，6 月扫描提醒。DEMO市 为演示占位数据" />
          <el-table :data="policies" height="400">
            <el-table-column prop="city" label="城市" width="120" />
            <el-table-column prop="year" label="年度" width="80" />
            <el-table-column prop="insType" label="险种" width="110" />
            <el-table-column prop="baseMin" label="基数下限" width="110" />
            <el-table-column prop="baseMax" label="基数上限" width="110" />
            <el-table-column label="单位比例" width="100">
              <template #default="{ row }">{{ (row.empRate * 100).toFixed(2) }}%</template>
            </el-table-column>
            <el-table-column label="个人比例" width="100">
              <template #default="{ row }">{{ (row.eeRate * 100).toFixed(2) }}%</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ==================== 页签二：增减员单据（social-insurance-ops） ==================== -->
        <el-tab-pane label="增减员单据" name="change">
          <div class="toolbar" style="margin-bottom:12px">
            <div>
              <el-select v-model="chgTypeF" placeholder="类型" clearable style="width:110px;margin-right:8px" @change="loadChanges">
                <el-option label="增员" value="ADD" />
                <el-option label="减员" value="REMOVE" />
                <el-option label="转移" value="TRANSFER" />
              </el-select>
              <el-select v-model="statusF" placeholder="状态" clearable style="width:120px;margin-right:8px" @change="loadChanges">
                <el-option label="待申报" value="DRAFT" />
                <el-option label="已申报" value="DECLARED" />
                <el-option label="已完成" value="DONE" />
                <el-option label="失败" value="FAILED" />
                <el-option label="已作废" value="VOID" />
              </el-select>
              <el-button type="primary" @click="openManual">手工补开</el-button>
            </div>
          </div>
          <el-alert type="info" :closable="false" style="margin-bottom:12px"
                    title="人事事件自动开单：入职过审→增员 / 离职结算→减员 / 调岗生效（变更缴纳地）→转移；在途单防重；申报登记回执后置 DONE。接口直连为目标态留桩（本期手工登记）" />
          <el-table :data="changes" height="400">
            <el-table-column prop="chgNo" label="单号" width="160" />
            <el-table-column label="类型" width="80">
              <template #default="{ row }">
                <el-tag :type="row.chgType === 'ADD' ? 'success' : row.chgType === 'REMOVE' ? 'danger' : 'warning'" size="small">
                  {{ { ADD: '增员', REMOVE: '减员', TRANSFER: '转移' }[row.chgType] || row.chgType }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="empNo" label="工号" width="110" />
            <el-table-column prop="empName" label="姓名" width="90" />
            <el-table-column prop="targetPlace" label="缴纳地" width="100" />
            <el-table-column label="触发源" width="90">
              <template #default="{ row }">
                {{ { ONBOARDING: '入职', EXIT: '离职', TRANSFER: '调岗', MANUAL: '手工' }[row.srcType] || row.srcType }}
              </template>
            </el-table-column>
            <el-table-column prop="changeDate" label="生效日" width="100" />
            <el-table-column prop="receiptNo" label="回执号" width="120" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'DONE' ? 'success' : row.status === 'FAILED' ? 'danger' : 'info'" size="small">
                  {{ { DRAFT: '待申报', DECLARED: '已申报', DONE: '已完成', FAILED: '失败', VOID: '已作废' }[row.status] || row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200">
              <template #default="{ row }">
                <el-button v-if="row.status === 'DRAFT' || row.status === 'FAILED'" link type="success"
                           size="small" @click="openDeclare(row)">申报登记</el-button>
                <el-button v-if="row.status === 'DRAFT' || row.status === 'FAILED'" link type="danger"
                           size="small" @click="doVoid(row)">作废</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="saveVisible" title="维护政策行" width="440px">
      <el-form label-width="90px">
        <el-form-item label="城市"><el-input v-model="form.city" /></el-form-item>
        <el-form-item label="年度"><el-input-number v-model="form.year" :min="2020" :max="2100" /></el-form-item>
        <el-form-item label="险种">
          <el-select v-model="form.insType" style="width:100%">
            <el-option v-for="t in types" :key="t.v" :label="t.l" :value="t.v" />
          </el-select>
        </el-form-item>
        <el-form-item label="基数下限"><el-input-number v-model="form.baseMin" :min="0" :step="100" /></el-form-item>
        <el-form-item label="基数上限"><el-input-number v-model="form.baseMax" :min="0" :step="100" /></el-form-item>
        <el-form-item label="单位比例"><el-input-number v-model="form.empRate" :min="0" :max="1" :step="0.005" /></el-form-item>
        <el-form-item label="个人比例"><el-input-number v-model="form.eeRate" :min="0" :max="1" :step="0.005" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveVisible = false">取消</el-button>
        <el-button type="primary" @click="doSave">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="manualVisible" title="手工补开社保单据" width="440px">
      <el-form label-width="90px">
        <el-form-item label="员工">
          <el-select v-model="manualForm.empId" filterable placeholder="选择员工" style="width:100%">
            <el-option v-for="e in emps" :key="e.id" :label="`${e.empNo} ${e.name}`" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="manualForm.chgType" style="width:100%">
            <el-option label="增员" value="ADD" />
            <el-option label="减员" value="REMOVE" />
            <el-option label="转移" value="TRANSFER" />
          </el-select>
        </el-form-item>
        <el-form-item label="缴纳地"><el-input v-model="manualForm.targetPlace" placeholder="增员/转移必填" /></el-form-item>
        <el-form-item label="生效日"><el-input v-model="manualForm.changeDate" placeholder="YYYY-MM-DD（可空）" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="manualForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualVisible = false">取消</el-button>
        <el-button type="primary" @click="doManual">开立</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="declareVisible" title="申报登记" width="420px">
      <el-form label-width="90px">
        <el-form-item label="单号"><span>{{ declareRow?.chgNo }}</span></el-form-item>
        <el-form-item label="回执号"><el-input v-model="declareForm.receiptNo" placeholder="银行/社保回执编号" /></el-form-item>
        <el-form-item label="经办人"><el-input v-model="declareForm.operator" placeholder="默认当前账号" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="declareForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="declareVisible = false">取消</el-button>
        <el-button type="primary" @click="doDeclare">登记完成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { policyList, savePolicy, copyPolicyYear, socialChangeList, socialChangeOpen,
  socialChangeDeclare, socialChangeVoid } from '@/api/hr/payroll'
import { empList } from '@/api/hr/employee-archive'

const types = [
  { v: 'PENSION', l: '养老保险' }, { v: 'MEDICAL', l: '医疗保险' },
  { v: 'UNEMPLOY', l: '失业保险' }, { v: 'INJURY', l: '工伤保险' },
  { v: 'MATERNITY', l: '生育保险' }, { v: 'PROVIDENT', l: '住房公积金' }
]
const tab = ref('policy')
const city = ref('')
const year = ref('')
const policies = ref([])
const saveVisible = ref(false)
const form = ref({ city: '', year: new Date().getFullYear(), insType: 'PENSION', baseMin: 4000, baseMax: 35000, empRate: 0.16, eeRate: 0.08 })

// 增减员单据
const chgTypeF = ref('')
const statusF = ref('')
const changes = ref([])
const emps = ref([])
const manualVisible = ref(false)
const manualForm = ref({ empId: '', chgType: 'ADD', targetPlace: '', changeDate: '', note: '' })
const declareVisible = ref(false)
const declareRow = ref(null)
const declareForm = ref({ receiptNo: '', operator: '', note: '' })

async function load() {
  const params = {}
  if (city.value) params.city = city.value
  if (year.value) params.year = year.value
  const { data } = await policyList(params)
  policies.value = data || []
}
async function loadChanges() {
  const params = {}
  if (chgTypeF.value) params.chgType = chgTypeF.value
  if (statusF.value) params.status = statusF.value
  const { data } = await socialChangeList(params)
  changes.value = data || []
}
async function loadEmps() {
  const { data } = await empList({})
  emps.value = (data || []).filter(e => e.empStatus !== 'RESIGNED')
}
function reloadAll() { load(); loadChanges() }
function openSave() { saveVisible.value = true }
async function doSave() {
  try {
    await savePolicy(form.value)
    ElMessage.success('已保存'); saveVisible.value = false; load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function copyYear() {
  const y = new Date().getFullYear()
  const { value } = await ElMessageBox.prompt(`复制 ${y} → ${y + 1} 年政策（跳过已存在）`, '年度复制', {
    inputValue: String(y + 1), inputPattern: /^\d{4}$/, inputErrorMessage: '四位年度'
  })
  const out = await copyPolicyYear({ fromYear: y, toYear: Number(value) })
  ElMessage.success(`复制 ${out.data?.copied} 行`); load()
}
function openManual() {
  manualForm.value = { empId: '', chgType: 'ADD', targetPlace: '', changeDate: '', note: '' }
  manualVisible.value = true
  if (!emps.value.length) loadEmps()
}
async function doManual() {
  try {
    await socialChangeOpen(manualForm.value)
    ElMessage.success('已开立（待申报）'); manualVisible.value = false; loadChanges()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
function openDeclare(row) {
  declareRow.value = row
  declareForm.value = { receiptNo: '', operator: '', note: '' }
  declareVisible.value = true
}
async function doDeclare() {
  try {
    await socialChangeDeclare(declareRow.value.id, declareForm.value)
    ElMessage.success('申报登记完成'); declareVisible.value = false; loadChanges()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
async function doVoid(row) {
  const { value } = await ElMessageBox.prompt('作废原因', '作废单据', { inputPattern: /\S+/, inputErrorMessage: '原因必填' })
  try {
    await socialChangeVoid(row.id, value)
    ElMessage.success('已作废'); loadChanges()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
onMounted(() => { load(); loadChanges() })
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
