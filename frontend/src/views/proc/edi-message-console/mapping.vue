<template>
  <div class="page-wrap">
    <!-- 映射规则维护（admin-only） -->
    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>EDI-ERP 映射规则（伙伴 × 报文类型 —— 缺失即 L1 阻断，BR-4.9-25）</span>
          <div>
            <el-input v-model="ruleQuery.partnerCode" placeholder="伙伴" clearable size="small"
                      style="width:130px" @keyup.enter="loadRules" />
            <el-select v-model="ruleQuery.msgType" placeholder="报文类型" clearable size="small"
                       style="width:130px;margin-left:8px" @change="loadRules">
              <el-option label="ORDERS" value="ORDERS" />
              <el-option label="DESADV" value="DESADV" />
              <el-option label="INVOIC" value="INVOIC" />
            </el-select>
            <el-button size="small" style="margin-left:8px" @click="loadRules">查询</el-button>
            <el-button size="small" type="primary" style="margin-left:8px" @click="openRule()">新增/更新规则</el-button>
          </div>
        </div>
      </template>
      <el-table :data="rules" size="small" border>
        <el-table-column prop="partnerCode" label="伙伴" width="130" />
        <el-table-column prop="msgType" label="报文类型" width="100" align="center" />
        <el-table-column prop="version" label="规则版本" width="100" align="center" />
        <el-table-column prop="fieldMap" label="字段映射（EDI → ERP）" min-width="320" show-overflow-tooltip />
        <el-table-column prop="createDate" label="创建时间" width="160" />
      </el-table>
      <div class="hint mt12">
        规则保存仅限管理员（<code>ROLE_ADMIN</code>，接口运维只读 403）；
        规则内容支持代码表转换、日期格式 YYYYMMDD→YYYY-MM-DD、计量单位换算。
        映射规则缺失的报文将被硬阻断并产生配置缺失告警。
      </div>
    </el-card>

    <!-- 入库结果 -->
    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>入库结果（生成单据 / 行级明细 / 幂等跳过 / 部分成功）</span>
          <div>
            <el-select v-model="msgQuery.status" placeholder="状态" clearable size="small"
                       style="width:140px" @change="loadMessages">
              <el-option label="已入库 PROCESSED" value="PROCESSED" />
              <el-option label="部分成功 PARTIAL" value="PARTIAL" />
              <el-option label="入库失败 FAILED" value="FAILED" />
              <el-option label="已拒绝 REJECTED" value="REJECTED" />
            </el-select>
            <el-button size="small" style="margin-left:8px" @click="loadMessages">查询</el-button>
          </div>
        </div>
      </template>
      <el-table :data="messages" size="small" border @row-click="loadDetail" style="cursor:pointer">
        <el-table-column prop="msgNo" label="报文号" width="160" />
        <el-table-column prop="partnerCode" label="伙伴" width="100" />
        <el-table-column prop="msgType" label="类型" width="80" align="center" />
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ STATUS[row.status] || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="生成单据" width="180">
          <template #default="{ row }">
            <template v-if="row.bizNo">
              <el-tag size="mini" type="success">{{ row.bizType }}</el-tag> {{ row.bizNo }}
            </template>
            <span v-else class="hint">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="lineOk" label="成功行" width="80" align="center" />
        <el-table-column prop="lineFail" label="失败行" width="80" align="center" />
        <el-table-column prop="finishedAt" label="完成时间" width="160" />
        <el-table-column prop="remark" label="被拒原因" min-width="180" show-overflow-tooltip />
      </el-table>

      <!-- 详情：行级结果 + 回执历史 -->
      <template v-if="detail">
        <el-divider content-position="left">{{ detail.message.msgNo }} —— 行级结果与回执</el-divider>
        <el-descriptions :column="3" size="small" border class="mb12">
          <el-descriptions-item label="生成单据">
            <template v-if="detail.message.bizNo">
              <el-tag size="mini" type="success">{{ detail.message.bizType }}</el-tag> {{ detail.message.bizNo }}
            </template>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="幂等键">{{ detail.message.idempotencyKey }}</el-descriptions-item>
          <el-descriptions-item label="继承卡控">
            <span v-if="business && business.rule" class="hint">violated_rule={{ business.rule }}（业务层直调既有服务，卡控不旁路）</span>
            <span v-else class="hint">直调既有服务（价控/预算/审批/超收）</span>
          </el-descriptions-item>
        </el-descriptions>

        <h4>行级明细</h4>
        <el-table v-if="bizErrors.length" :data="bizErrors" size="small" border class="mb12">
          <el-table-column prop="line" label="行号" width="80" align="center" />
          <el-table-column prop="seg" label="段" width="90" align="center" />
          <el-table-column prop="field" label="字段" width="90" align="center" />
          <el-table-column prop="message" label="被拒原因" min-width="300" />
        </el-table>
        <div v-else-if="business && business.success" class="hint mb12">
          全部行入库成功（{{ detail.message.lineOk }} 行）
        </div>
        <div v-else class="hint mb12">无行级失败记录</div>

        <h4>回执历史（每次处理结论各留一条，含重复跳过）</h4>
        <el-table :data="receipts" size="small" border>
          <el-table-column label="结论" width="130" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="conclusionType(row.conclusion)">{{ conclusionName(row.conclusion) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="receiptAt" label="生成时间" width="170" />
          <el-table-column label="送达标记" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="mini" :type="row.sentFlag === '1' ? 'success' : 'info'">
                {{ row.sentFlag === '1' ? '已送达' : '未发送' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="回执内容" min-width="320">
            <template #default="{ row }">
              <span class="hint">{{ summarizeReceipt(row.receiptJson) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="hint mt12">
          回执内容与台账入库结果强一致（同一处理路径写入）；「未发送」为预留送达标记
          （异步回执发送器另行立项，design D4）——当前回执经接收通道同步返回调用方。
        </div>
      </template>
    </el-card>

    <!-- 规则编辑对话框 -->
    <el-dialog v-model="ruleDlg.visible" title="保存/更新映射规则（伙伴 × 报文类型）" width="620px">
      <el-form label-width="110px">
        <el-form-item label="伙伴编码" required>
          <el-input v-model="ruleDlg.form.partnerCode" placeholder="如 partner-a" />
        </el-form-item>
        <el-form-item label="报文类型" required>
          <el-select v-model="ruleDlg.form.msgType" style="width:100%">
            <el-option label="ORDERS 采购订单" value="ORDERS" />
            <el-option label="DESADV 发货通知" value="DESADV" />
            <el-option label="INVOIC 发票" value="INVOIC" />
          </el-select>
        </el-form-item>
        <el-form-item label="字段映射 JSON" required>
          <el-input v-model="ruleDlg.form.fieldMap" type="textarea" :rows="8"
                    placeholder='{"version":"v1","header":{"orderDate":{"fmt":"yyyy-MM-dd"},"supplierId":{"map":"supplier"}},"lines":{"itemCode":{"map":"materialCode"}}}' />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="ruleDlg.saving" @click="saveRule">保存（仅管理员）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getMessagesApi, getMessageDetailApi,
  getMapRulesApi, saveMapRuleApi
} from '@/api/proc/edi-message-console'

const STATUS = {
  RECEIVED: '已接收', REJECTED: '已拒绝', PROCESSED: '已入库',
  PARTIAL: '部分成功', FAILED: '入库失败', MANUAL: '待人工'
}
const CONCLUSION = {
  PROCESSED: ['已入库', 'success'], PARTIAL: ['部分成功', 'warning'],
  FAILED: ['失败', 'danger'], REJECTED: ['已拒绝', 'danger'], SKIPPED: ['重复跳过', 'info']
}
const rules = ref([])
const ruleQuery = reactive({ partnerCode: '', msgType: '' })
const messages = ref([])
const msgQuery = reactive({ status: 'PROCESSED', current: 1, size: 20 })
const detail = ref(null)
const business = ref(null)
const bizErrors = ref([])
const receipts = ref([])

function statusType(s) {
  return { RECEIVED: 'info', REJECTED: 'danger', PROCESSED: 'success',
    PARTIAL: 'warning', FAILED: 'danger', MANUAL: 'danger' }[s] || 'info'
}
function conclusionName(c) { return (CONCLUSION[c] || [c, 'info'])[0] }
function conclusionType(c) { return (CONCLUSION[c] || [c, 'info'])[1] }
function summarizeReceipt(json) {
  if (!json) return '—'
  try {
    const r = JSON.parse(json)
    return `${r.status || ''}${r.skipped ? '（重复跳过，引用首次处理结果）' : ''}${r.bizNo ? '｜单据 ' + r.bizNo : ''}` +
           `${r.errors && r.errors.length ? '｜错误 ' + r.errors.length + ' 项' : ''}`
  } catch { return String(json).slice(0, 120) }
}

async function loadRules() {
  const p = { current: 1, size: 50 }
  if (ruleQuery.partnerCode) p.partnerCode = ruleQuery.partnerCode
  if (ruleQuery.msgType) p.msgType = ruleQuery.msgType
  rules.value = ((await getMapRulesApi(p)).data.records) || []
}

const ruleDlg = reactive({
  visible: false, saving: false,
  form: { partnerCode: '', msgType: 'ORDERS', fieldMap: '' }
})
function openRule() { Object.assign(ruleDlg, { visible: true }); }
async function saveRule() {
  const f = ruleDlg.form
  if (!f.partnerCode || !f.msgType || !f.fieldMap) { ElMessage.warning('三项必填'); return }
  ruleDlg.saving = true
  try {
    await saveMapRuleApi({ partnerCode: f.partnerCode, msgType: f.msgType, fieldMap: f.fieldMap })
    ElMessage.success('映射规则已保存')
    ruleDlg.visible = false
    loadRules()
  } catch (e) {
    // admin-only：403 时透出提示
    ElMessage.error(e?.response?.data?.message || e?.message || '保存失败（规则维护仅限管理员）')
  } finally {
    ruleDlg.saving = false
  }
}

async function loadMessages() {
  const p = { current: msgQuery.current, size: msgQuery.size }
  if (msgQuery.status) p.status = msgQuery.status
  messages.value = ((await getMessagesApi(p)).data.records) || []
}
async function loadDetail(row) {
  const d = (await getMessageDetailApi(row.id)).data
  detail.value = d
  business.value = d.business
  bizErrors.value = Array.isArray(d.business?.errors) ? d.business.errors
    : (Array.isArray(d.syntax) ? d.syntax : [])
  receipts.value = d.receipts || []
}

onMounted(() => { loadRules(); loadMessages() })
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
.hint { color: #909399; font-size: 12px; line-height: 1.7; }
</style>
