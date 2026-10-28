<template>
  <div class="page-wrap">
    <!-- 报文台账（三入口产物统一视图） -->
    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>报文台账（12.4.1）—— 三入口接收 / 状态流转 / MDN 接收结论</span>
          <div>
            <el-select v-model="query.msgType" placeholder="报文类型" clearable size="small"
                       style="width:130px" @change="load">
              <el-option label="ORDERS 采购订单" value="ORDERS" />
              <el-option label="DESADV 发货通知" value="DESADV" />
              <el-option label="INVOIC 发票" value="INVOIC" />
            </el-select>
            <el-select v-model="query.status" placeholder="状态" clearable size="small"
                       style="width:140px;margin-left:8px" @change="load">
              <el-option v-for="(label, code) in STATUS" :key="code" :label="label" :value="code" />
            </el-select>
            <el-input v-model="query.partnerCode" placeholder="伙伴编码" clearable size="small"
                      style="width:140px;margin-left:8px" @keyup.enter="load" />
            <el-button size="small" style="margin-left:8px" @click="load">查询</el-button>
          </div>
        </div>
      </template>
      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="msgNo" label="报文号" width="160" />
        <el-table-column prop="partnerCode" label="伙伴" width="110" />
        <el-table-column prop="msgType" label="类型" width="90" align="center" />
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ STATUS[row.status] || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="MDN 接收结论" min-width="150">
          <template #default="{ row }">
            <el-tag size="mini" :type="row.status === 'REJECTED' ? 'danger' : 'success'">
              {{ row.status === 'REJECTED' ? '拒绝' : '接受' }}
            </el-tag>
            <span class="hint" v-if="row.status === 'REJECTED' && row.remark">{{ row.remark }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="source" label="来源" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="mini" :type="{ OPEN: 'primary', UPLOAD: 'info', SIMULATE: 'warning' }[row.source] || 'info'">
              {{ { OPEN: '开放入口', UPLOAD: '页面上传', SIMULATE: '模拟推送' }[row.source] || row.source }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lineOk" label="成功行" width="80" align="center" />
        <el-table-column prop="lineFail" label="失败行" width="80" align="center" />
        <el-table-column prop="retryCount" label="重试" width="60" align="center" />
        <el-table-column prop="receivedAt" label="接收时间" width="160" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button size="mini" text type="primary"
                       @click="$router.push({ path: '/m/12.4.2', query: { id: row.id } })">校验详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="mt12" layout="total, prev, pager, next" :total="total"
                     :page-size="query.size" :current-page="query.current" @current-change="onPage" />
    </el-card>

    <!-- 三入口 -->
    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head"><span>报文入口（三入口均先落台账，spec 三入口接收）</span></div>
      </template>
      <div style="display:flex;gap:12px;flex-wrap:wrap">
        <el-card shadow="never" style="flex:1;min-width:280px">
          <h4 style="margin:0 0 8px">入口① 开放入口推送（伙伴侧）</h4>
          <div class="hint">
            <p>路径：<code>POST /api/open/edi/{msgType}</code>（msgType = ORDERS / DESADV / INVOIC）</p>
            <p>认证：API Key（Basic Auth）+ HMAC-SHA256 签名 + X-Timestamp（±5 分钟时间窗）</p>
            <p>签名失败 / 时间戳超窗 → HTTP 401 拒收并记安全事件（BR-4.9-22），不产生台账入库记录。</p>
            <p>本页不提供人工代推；对接与凭证签发 → 12.1.3 通道开通。</p>
          </div>
          <el-button size="small" style="margin-top:8px" @click="$router.push('/m/12.3.1')">查看传输层拒绝视图（12.3.1）</el-button>
        </el-card>
        <el-card shadow="never" style="flex:1;min-width:280px">
          <h4 style="margin:0 0 8px">入口② 页面上传报文</h4>
          <div class="hint">粘贴报文 JSON 直接入库校验（来源标记 UPLOAD），用于补录与联调。</div>
          <el-button size="small" type="primary" style="margin-top:8px" @click="openUpload">上传报文</el-button>
        </el-card>
        <el-card shadow="never" style="flex:1;min-width:280px">
          <h4 style="margin:0 0 8px">入口③ 一键模拟伙伴推送</h4>
          <div class="hint">按伙伴密钥自动签名，经开放入口真实链路推送（来源标记 SIMULATE），端到端验证四级校验。</div>
          <el-button size="small" type="warning" style="margin-top:8px" @click="openSimulate">模拟伙伴推送</el-button>
        </el-card>
      </div>
    </el-card>

    <!-- 上传/模拟 推送对话框 -->
    <el-dialog v-model="dlg.visible" :title="dlg.title" width="640px">
      <el-form label-width="110px">
        <el-form-item label="伙伴编码" required>
          <el-input v-model="dlg.form.partnerCode" placeholder="如 partner-a" />
        </el-form-item>
        <el-form-item label="报文类型" required>
          <el-select v-model="dlg.form.msgType" style="width:100%">
            <el-option label="ORDERS 采购订单" value="ORDERS" />
            <el-option label="DESADV 发货通知" value="DESADV" />
            <el-option label="INVOIC 发票" value="INVOIC" />
          </el-select>
        </el-form-item>
        <el-form-item label="报文 JSON" required>
          <el-input v-model="dlg.form.raw" type="textarea" :rows="8"
                    placeholder='{"idempotencyKey":"可选-自定义幂等键","header":{"supplierId":"...","orderDate":"20261028"},"lines":[{"itemCode":"...","qty":1,"unit":"PC","unitPrice":1}]}' />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="dlg.saving" @click="submitPush">{{ dlg.action }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { getMessagesApi, uploadMessageApi, simulatePushApi } from '@/api/proc/edi-message-console'

const STATUS = {
  RECEIVED: '已接收', REJECTED: '已拒绝', PROCESSED: '已入库',
  PARTIAL: '部分成功', FAILED: '入库失败', MANUAL: '待人工'
}
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ msgType: '', status: '', partnerCode: '', current: 1, size: 20 })

function statusType(s) {
  return { RECEIVED: 'info', REJECTED: 'danger', PROCESSED: 'success',
    PARTIAL: 'warning', FAILED: 'danger', MANUAL: 'danger' }[s] || 'info'
}

async function load() {
  loading.value = true
  try {
    const p = { current: query.current, size: query.size }
    if (query.msgType) p.msgType = query.msgType
    if (query.status) p.status = query.status
    if (query.partnerCode) p.partnerCode = query.partnerCode
    const d = (await getMessagesApi(p)).data
    rows.value = d.records || []
    total.value = Number(d.total || 0)
  } finally {
    loading.value = false
  }
}
function onPage(c) { query.current = c; load() }

const dlg = reactive({
  visible: false, title: '', action: '', mode: 'upload', saving: false,
  form: { partnerCode: '', msgType: 'ORDERS', raw: '' }
})
function openUpload() {
  Object.assign(dlg, { visible: true, title: '上传报文（入口②）', action: '上传并校验', mode: 'upload' })
}
function openSimulate() {
  Object.assign(dlg, { visible: true, title: '一键模拟伙伴推送（入口③，真实签名经开放入口）', action: '模拟推送', mode: 'simulate' })
}
async function submitPush() {
  const f = dlg.form
  if (!f.partnerCode || !f.msgType || !f.raw) { ElMessage.warning('伙伴编码、报文类型、报文内容必填'); return }
  dlg.saving = true
  try {
    const fn = dlg.mode === 'upload' ? uploadMessageApi : simulatePushApi
    const r = (await fn({ ...f })).data
    ElMessage.success(`${r.msgNo}：${STATUS[r.status] || r.status}${r.bizNo ? '（' + r.bizNo + '）' : ''}`)
    dlg.visible = false
    load()
  } finally {
    dlg.saving = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
.hint { color: #909399; font-size: 12px; line-height: 1.7; }
</style>
