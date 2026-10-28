<template>
  <div class="page-wrap">
    <!-- 报文选择 -->
    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>格式校验（12.4.2）—— 四级校验详情 / 重试轨迹 / 待人工工单联动</span>
          <div>
            <el-select v-model="pick" placeholder="选择报文（优先 REJECTED / MANUAL）" filterable
                       size="small" style="width:420px" @change="loadDetail">
              <el-option v-for="m in messages" :key="m.id"
                         :label="`${m.msgNo}｜${m.partnerCode}｜${m.msgType}｜${STATUS[m.status] || m.status}`"
                         :value="m.id" />
            </el-select>
            <el-button size="small" style="margin-left:8px" @click="loadMessages">刷新列表</el-button>
          </div>
        </div>
      </template>
      <el-alert v-if="!pick" type="info" :closable="false" show-icon
                title="从右上角选择一份报文；可先在 12.4.1 上传/模拟推送制造校验用例" />
    </el-card>

    <template v-if="detail">
      <!-- 四级校验结果 -->
      <el-card shadow="never" class="mb12">
        <template #header>
          <div class="card-head">
            <span>四级校验结果 —— {{ detail.message.msgNo }}</span>
            <el-tag size="small" :type="statusType(detail.message.status)">
              {{ STATUS[detail.message.status] || detail.message.status }}
            </el-tag>
          </div>
        </template>
        <el-descriptions :column="3" size="small" border class="mb12">
          <el-descriptions-item label="伙伴">{{ detail.message.partnerCode }}</el-descriptions-item>
          <el-descriptions-item label="类型/版本">{{ detail.message.msgType }} / {{ detail.message.msgVersion || 'v1' }}</el-descriptions-item>
          <el-descriptions-item label="幂等键">{{ detail.message.idempotencyKey }}</el-descriptions-item>
          <el-descriptions-item label="行级结果">成功 {{ detail.message.lineOk ?? 0 }} / 失败 {{ detail.message.lineFail ?? 0 }} / 共 {{ detail.message.lineTotal ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="重试次数">{{ detail.message.retryCount ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="接收时间">{{ detail.message.receivedAt }}</el-descriptions-item>
        </el-descriptions>

        <!-- 语法层错误：行/段/字段定位 -->
        <h4>语法层（错误定位到 行号 + 段号 + 字段号，BR-4.9-23）</h4>
        <el-table v-if="syntaxErrors.length" :data="syntaxErrors" size="small" border class="mb12">
          <el-table-column prop="line" label="行号" width="80" align="center" />
          <el-table-column prop="seg" label="段号" width="100" align="center" />
          <el-table-column prop="field" label="字段号" width="100" align="center" />
          <el-table-column prop="message" label="错误说明" min-width="260" />
        </el-table>
        <div v-else class="hint mb12">语法层通过（无错误定位记录）</div>

        <!-- 映射层 -->
        <h4>映射层（BR-4.9-25 规则缺失即 L1 阻断）</h4>
        <div class="hint mb12">
          <template v-if="mapping && mapping.blocked">
            <el-tag size="small" type="danger">硬阻断</el-tag> {{ mapping.reason }}
          </template>
          <template v-else-if="mapping && mapping.applied">
            <el-tag size="small" type="success">已映射</el-tag> 规则版本 {{ mapping.ruleVersion }}
          </template>
          <template v-else>未进入映射环节</template>
        </div>

        <!-- 业务层 -->
        <h4>业务层（直调既有服务，继承卡控）</h4>
        <div class="hint">
          <template v-if="business">
            <template v-if="business.success">
              <el-tag size="small" type="success">入库成功</el-tag>
              生成 {{ business.bizType }}：<strong>{{ business.bizNo }}</strong>
            </template>
            <template v-else>
              <el-tag size="small" type="danger">入库失败</el-tag>
              {{ business.error }}
              <span v-if="business.rule">（violated_rule={{ business.rule }}）</span>
            </template>
          </template>
          <template v-else>未进入业务层</template>
        </div>

        <div v-if="detail.message.status === 'MANUAL'" class="mt12">
          <el-alert type="warning" :closable="false" show-icon
                    :title="`重试已达 API_RETRY_MAX（3 次），已终止自动重试并生成人工处理工单`"
                    :description="`工单附全量错误位置清单（见下方重试轨迹）；人工确认后在下方「人工重放」，保留原幂等键防重复入库（C-4.9-02）。工单台账见 12.2.3。`" />
        </div>
      </el-card>

      <!-- 重试轨迹 -->
      <el-card shadow="never" class="mb12">
        <template #header>
          <div class="card-head">
            <span>重试轨迹（逐次留痕不可覆盖 —— 工单「附错误位置清单」的数据源）</span>
            <el-button size="small" type="primary"
                       @click="doReplay" :loading="replaying">人工重放（保留原幂等键）</el-button>
          </div>
        </template>
        <el-table v-if="trace.length" :data="trace" size="small" border>
          <el-table-column prop="attemptNo" label="第 N 次" width="90" align="center" />
          <el-table-column prop="failAt" label="失败时间" width="170" />
          <el-table-column label="错误位置清单（快照，不随下次重发覆盖）" min-width="380">
            <template #default="{ row }">
              <el-tag v-for="(e, i) in parseErrors(row.errorsJson)" :key="i" size="mini"
                      type="danger" style="margin:2px 4px 2px 0">
                行 {{ e.line }} / 段 {{ e.seg }} / 字段 {{ e.field }}：{{ e.message }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-alert v-else type="success" :closable="false" show-icon
                  title="无语法层失败记录（该报文一次通过语法校验）" />
        <div class="hint mt12">
          轨迹在每次对方重发（或人工重放）触发重处理时逐条留存；修复后重发通过语法校验时，
          历史失败轨迹仍保留供回溯（spec：轨迹不可改删）。
        </div>
      </el-card>

      <!-- 原始报文 -->
      <el-card shadow="never">
        <template #header><div class="card-head"><span>原始报文（留存 ≥ AUDIT_RETENTION_DAYS，可回溯）</span></div></template>
        <pre class="hint" style="white-space:pre-wrap;word-break:break-all">{{ detail.message.rawPayload }}</pre>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMessagesApi, getMessageDetailApi, replayMessageApi } from '@/api/proc/edi-message-console'

const STATUS = {
  RECEIVED: '已接收', REJECTED: '已拒绝', PROCESSED: '已入库',
  PARTIAL: '部分成功', FAILED: '入库失败', MANUAL: '待人工'
}
const route = useRoute()
const messages = ref([])
const pick = ref('')
const detail = ref(null)
const replaying = ref(false)

const syntaxErrors = ref([])
const mapping = ref(null)
const business = ref(null)
const trace = ref([])

function statusType(s) {
  return { RECEIVED: 'info', REJECTED: 'danger', PROCESSED: 'success',
    PARTIAL: 'warning', FAILED: 'danger', MANUAL: 'danger' }[s] || 'info'
}
function parseErrors(json) {
  if (!json) return []
  try { return JSON.parse(json) || [] } catch { return [{ line: '-', seg: '-', field: '-', message: String(json) }] }
}

async function loadMessages() {
  // 优先展示需要校验处理的报文（REJECTED/MANUAL 前置）
  const d = (await getMessagesApi({ current: 1, size: 100 })).data
  const list = d.records || []
  const rank = m => ({ MANUAL: 0, REJECTED: 1, FAILED: 2, RECEIVED: 3, PARTIAL: 4, PROCESSED: 5 }[m.status] ?? 9)
  messages.value = [...list].sort((a, b) => rank(a) - rank(b))
}

async function loadDetail(id) {
  if (!id) return
  const d = (await getMessageDetailApi(id)).data
  detail.value = d
  syntaxErrors.value = Array.isArray(d.syntax) ? d.syntax : []
  mapping.value = d.mapping
  business.value = d.business
  trace.value = d.retryTrace || []
}

async function doReplay() {
  await ElMessageBox.confirm('重放将保留原幂等键重新走四级校验与入库（已成功行按幂等跳过），确认？', '人工重放', { type: 'warning' })
  const r = (await replayMessageApi(detail.value.message.id)).data
  ElMessage.success(`${r.msgNo}：${STATUS[r.status] || r.status}`)
  await loadDetail(pick.value)
  loadMessages()
}

onMounted(async () => {
  await loadMessages()
  if (route.query.id) { pick.value = String(route.query.id); loadDetail(pick.value) }
})
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
.hint { color: #909399; font-size: 12px; line-height: 1.7; }
</style>
