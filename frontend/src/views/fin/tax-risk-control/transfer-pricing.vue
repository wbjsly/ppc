<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>关联交易定价（9.6.3）</span>
          <el-button type="primary" size="small" @click="loadAll">刷新</el-button>
        </div>
      </template>
      <el-alert :closable="false" type="info" show-icon class="mb8"
        title="S-4.14-08：关联交易定价须符合独立交易原则，定价文档备查，特别纳税调整风险提前评估（自评留痕不阻断业务）" />

      <el-row :gutter="12" class="mb8">
        <!-- 提醒 -->
        <el-col :span="10">
          <el-card shadow="never" body-style="padding:12px">
            <div class="warn-title">提醒（备查临期 ≤{{ reminders.warnDays || 90 }} 天 / 年度断档）</div>
            <div v-for="(r, i) in reminders.expiring || []" :key="'e' + i" class="remind-line">
              <el-tag type="warning" size="small">临期</el-tag>
              {{ r.document.partyName }} · {{ docTypeName(r.document.docType) }} ·
              {{ r.document.coverYear }} · 剩余 {{ r.daysToExpire }} 天
            </div>
            <div v-for="(g, i) in reminders.yearGaps || []" :key="'g' + i" class="remind-line">
              <el-tag type="danger" size="small">断档</el-tag>
              {{ g.party.name }} · {{ g.year }} 年度无任何定价文档
            </div>
            <div v-if="!(reminders.expiring || []).length && !(reminders.yearGaps || []).length"
              class="muted">暂无提醒</div>
          </el-card>
        </el-col>
        <!-- 年度自评 -->
        <el-col :span="14">
          <el-card shadow="never" body-style="padding:12px">
            <div class="warn-title">特别纳税调整风险自评（留痕不阻断）</div>
            <el-form inline size="small">
              <el-form-item label="年度"><el-input v-model="assessForm.year" style="width:90px" placeholder="2026" /></el-form-item>
              <el-form-item label="风险项">
                <el-checkbox-group v-model="assessForm.riskItems">
                  <el-checkbox value="PRICING_NOT_ARMS_LENGTH">定价不符独立交易原则</el-checkbox>
                  <el-checkbox value="DOCUMENTS_INCOMPLETE">文档不齐全</el-checkbox>
                  <el-checkbox value="LOCAL_FILE_LATE">同期资料未按期</el-checkbox>
                  <el-checkbox value="RELATED_DISCLOSURE_ERROR">关联交易披露有误</el-checkbox>
                  <el-checkbox value="NONE">无异常</el-checkbox>
                </el-checkbox-group>
              </el-form-item>
              <el-form-item><el-button type="primary" size="small" @click="submitAssessment">提交自评</el-button></el-form-item>
            </el-form>
            <el-table :data="assessments" size="small" max-height="140">
              <el-table-column prop="assessYear" label="年度" width="80" />
              <el-table-column prop="conclusion" label="结论" width="110">
                <template #default="{ row }">
                  <el-tag :type="row.conclusion === 'CLEAN' ? 'success' : 'warning'" size="small">
                    {{ row.conclusion === 'CLEAN' ? '未发现风险' : '有待改进' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="assessBy" label="自评人" width="100" />
              <el-table-column prop="assessAt" label="时间" min-width="150" />
            </el-table>
          </el-card>
        </el-col>
      </el-row>

      <!-- 关联方登记 -->
      <el-form inline class="mb8" size="small">
        <el-form-item label="名称"><el-input v-model="partyForm.name" style="width:160px" /></el-form-item>
        <el-form-item label="识别号"><el-input v-model="partyForm.taxNo" style="width:170px" /></el-form-item>
        <el-form-item label="关系">
          <el-select v-model="partyForm.relType" style="width:130px">
            <el-option label="母子公司" value="PARENT" />
            <el-option label="同受控制" value="COMMON_CONTROL" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="境外"><el-switch v-model="partyForm.overseas" /></el-form-item>
        <el-form-item><el-button type="primary" @click="registerParty">登记关联方</el-button></el-form-item>
      </el-form>

      <!-- 定价文档登记 -->
      <el-form inline class="mb8" size="small">
        <el-form-item label="关联方">
          <el-select v-model="docForm.partyId" style="width:160px" placeholder="选择">
            <el-option v-for="p in parties" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="文档类型">
          <el-select v-model="docForm.docType" style="width:120px">
            <el-option label="本地文档" value="LOCAL" />
            <el-option label="主体文档" value="MASTER" />
            <el-option label="国别报告" value="COUNTRY" />
          </el-select>
        </el-form-item>
        <el-form-item label="年度"><el-input v-model="docForm.coverYear" style="width:80px" placeholder="2026" /></el-form-item>
        <el-form-item label="定价方法">
          <el-select v-model="docForm.pricingMethod" style="width:110px">
            <el-option label="可比非受控(CUP)" value="CUP" />
            <el-option label="成本加成(CPM)" value="CPM" />
            <el-option label="再销售价格(RPM)" value="RPM" />
            <el-option label="交易净利润(TNMM)" value="TNMM" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="备查至">
          <el-date-picker v-model="docForm.validUntil" type="date" value-format="YYYY-MM-DD" style="width:140px" />
        </el-form-item>
        <el-form-item><el-button type="primary" @click="registerDoc">登记文档</el-button></el-form-item>
      </el-form>

      <!-- 文档台账 -->
      <el-table :data="documents" size="small" border>
        <el-table-column prop="docNo" label="文档编号" width="140" />
        <el-table-column prop="partyName" label="关联方" min-width="140" show-overflow-tooltip />
        <el-table-column prop="docType" label="类型" width="100">
          <template #default="{ row }">{{ docTypeName(row.docType) }}</template>
        </el-table-column>
        <el-table-column prop="coverYear" label="年度" width="80" />
        <el-table-column prop="pricingMethod" label="定价方法" width="110" />
        <el-table-column prop="validUntil" label="备查至" width="110" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'READY' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  partyList, partyRegister, docList, docRegister, tpReminders,
  assessmentList, assessmentSubmit
} from '@/api/fin/tax-risk-control'

const parties = ref([])
const documents = ref([])
const reminders = ref({})
const assessments = ref([])
const partyForm = ref({ name: '', taxNo: '', relType: 'COMMON_CONTROL', overseas: false })
const docForm = ref({ partyId: '', docType: 'LOCAL', coverYear: String(new Date().getFullYear()), pricingMethod: 'TNMM', validUntil: '' })
const assessForm = ref({ year: String(new Date().getFullYear()), riskItems: ['NONE'] })

const docTypeName = (t) => ({ LOCAL: '本地文档', MASTER: '主体文档', COUNTRY: '国别报告' }[t] || t)

async function loadAll() {
  try { parties.value = (await partyList()).data || [] } catch (e) { /* ignore */ }
  try { documents.value = (await docList({})).data || [] } catch (e) { /* ignore */ }
  try { reminders.value = (await tpReminders()).data || {} } catch (e) { /* ignore */ }
  try { assessments.value = (await assessmentList()).data || [] } catch (e) { /* ignore */ }
}

async function registerParty() {
  try {
    await partyRegister(partyForm.value)
    ElMessage.success('关联方登记成功')
    partyForm.value = { name: '', taxNo: '', relType: 'COMMON_CONTROL', overseas: false }
    loadAll()
  } catch (e) { /* ignore */ }
}

async function registerDoc() {
  try {
    await docRegister(docForm.value)
    ElMessage.success('定价文档登记成功')
    docForm.value = { ...docForm.value, validUntil: '' }
    loadAll()
  } catch (e) { /* ignore */ }
}

async function submitAssessment() {
  try {
    const { data } = await assessmentSubmit(assessForm.value)
    ElMessage.success('自评已留痕：' + (data.conclusion === 'CLEAN' ? '未发现风险' : '有待改进'))
    assessForm.value = { year: assessForm.value.year, riskItems: ['NONE'] }
    loadAll()
  } catch (e) { /* ignore */ }
}

onMounted(loadAll)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.mb8 { margin-bottom: 8px; }
.warn-title { font-weight: 600; margin-bottom: 8px; }
.remind-line { font-size: 13px; margin-bottom: 6px; }
.muted { color: #909399; font-size: 12px; }
</style>
