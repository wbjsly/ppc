<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="接口对接（2.8.3）：开放入口真实生效的签名 / 时间戳 / 限流 / 熔断 / 审计，事件投递与 EDI 四级校验、接入治理与 SLA 月报"
      description="凭证紧急吊销、生产放行会签、SLA 报告归档与发布、限流降档与解除熔断四类高危动作仅管理员可执行（C-0-03 发起人≠复核人）；接口运维角色可读可处置但无高危按钮。" />

    <el-tabs v-model="tab">
      <!-- ==================== Tab1 运行总览 ==================== -->
      <el-tab-pane label="运行总览" name="overview">
        <el-row :gutter="12" class="kpi-row">
          <el-col :span="4" v-for="c in kpiCards" :key="c.label">
            <el-card shadow="never" class="kpi-card">
              <div class="kpi-label">{{ c.label }}</div>
              <div class="kpi-value" :class="c.cls">{{ c.value }}</div>
              <div class="kpi-hint">{{ c.hint }}</div>
            </el-card>
          </el-col>
        </el-row>

        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>通道与防护（限流档位 / 熔断状态 / 证书台账）</span>
              <span>
                <el-button size="small" @click="loadChannels">刷新</el-button>
                <el-button size="small" type="primary" @click="openSim">模拟调用方</el-button>
              </span>
            </div>
          </template>
          <el-table :data="channels" size="small" border v-loading="loading">
            <el-table-column prop="partnerCode" label="伙伴编码" width="130" />
            <el-table-column prop="partnerName" label="伙伴名称" min-width="140" />
            <el-table-column prop="protocol" label="协议" width="90" />
            <el-table-column prop="status" label="通道状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'PROD' ? 'success' : 'info'">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="rateTier" label="限流档位" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.rateTier === 'LOWEST' ? 'danger' : ''">
                  {{ row.rateTier }} / {{ row.limitPerMin }} 次每分
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="circuitState" label="熔断状态" width="110">
              <template #default="{ row }">
                <el-tag size="small"
                  :type="row.circuitState === 'CLOSED' ? 'success' : row.circuitState === 'HALF_OPEN' ? 'warning' : 'danger'">
                  {{ row.circuitState }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="certExpireDate" label="证书到期" width="110" />
            <el-table-column label="操作" width="230">
              <template #default="{ row }">
                <el-button size="mini" type="warning" v-if="isAdmin"
                  @click="doResetCircuit(row)">解除熔断</el-button>
                <el-button size="mini" v-if="isAdmin" @click="openTier(row)">调整档位</el-button>
                <el-button size="mini" type="info" @click="viewCalls(row.partnerCode)">调用审计</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" style="margin-top: 12px;">
          <template #header>
            <div class="card-head">
              <span>调用审计日志（敏感字段脱敏，fail-closed：写失败即 503）</span>
              <el-button size="small" @click="loadCalls">刷新</el-button>
            </div>
          </template>
          <el-table :data="calls" size="small" border>
            <el-table-column prop="callAt" label="时间" width="170" />
            <el-table-column prop="caller" label="调用方" width="140" />
            <el-table-column prop="apiPath" label="接口路径" min-width="200" />
            <el-table-column prop="httpMethod" label="方法" width="70" />
            <el-table-column prop="respCode" label="状态码" width="90">
              <template #default="{ row }">
                <el-tag size="mini" :type="row.respCode < 400 ? 'success' : row.respCode < 500 ? 'warning' : 'danger'">
                  {{ row.respCode }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="costMs" label="耗时ms" width="90" />
            <el-table-column prop="reqId" label="X-Request-Id" width="260" show-overflow-tooltip />
            <el-table-column prop="paramSummary" label="参数摘要（脱敏）" min-width="200" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ==================== Tab2 事件与报文 ==================== -->
      <el-tab-pane label="事件与报文" name="events">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>出站事件投递台账（至少一次投递 → 指数退避 → 死信）</span>
              <span>
                <el-select v-model="dlvFilter.status" placeholder="状态" clearable size="small"
                  style="width: 150px;" @change="loadDeliveries">
                  <el-option label="待投递" value="PENDING" />
                  <el-option label="重试中" value="RETRYING" />
                  <el-option label="暂存" value="STAGED" />
                  <el-option label="已投递" value="DELIVERED" />
                  <el-option label="死信" value="DEAD" />
                  <el-option label="冲突丢弃" value="CONFLICT_DROPPED" />
                </el-select>
                <el-button size="small" type="primary" @click="doScan">立即投递扫描</el-button>
              </span>
            </div>
          </template>
          <el-table :data="deliveries" size="small" border v-loading="loading">
            <el-table-column prop="eventType" label="事件类型" width="170" />
            <el-table-column prop="bizCode" label="业务对象" min-width="150" show-overflow-tooltip />
            <el-table-column prop="idempotencyKey" label="幂等键（重放保留）" min-width="180" show-overflow-tooltip />
            <el-table-column prop="status" label="状态" width="130">
              <template #default="{ row }">
                <el-tag size="mini" :type="dlvTag(row.status)">{{ dlvText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="retryCount" label="重试" width="60" />
            <el-table-column prop="replayCount" label="重放" width="60" />
            <el-table-column prop="failReason" label="失败原因" min-width="180" show-overflow-tooltip />
            <el-table-column label="操作" width="170">
              <template #default="{ row }">
                <el-button size="mini" type="primary" v-if="row.status !== 'DELIVERED'"
                  @click="doReplay(row)">重放</el-button>
                <el-button size="mini" type="danger" v-if="row.status === 'DEAD' && isAdmin"
                  @click="doAbandon(row)">放弃</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" style="margin-top: 12px;">
          <template #header>
            <div class="card-head">
              <span>人工处理工单（死信 / 报文待人工 / 版本不兼容 / 幂等冲突）</span>
              <el-button size="small" @click="loadTickets">刷新</el-button>
            </div>
          </template>
          <el-table :data="tickets" size="small" border>
            <el-table-column prop="ticketNo" label="工单号" width="150" />
            <el-table-column prop="source" label="来源" width="110" />
            <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
            <el-table-column prop="idempotencyKey" label="原幂等键" min-width="160" show-overflow-tooltip />
            <el-table-column prop="status" label="状态" width="100" />
            <el-table-column prop="assignTo" label="分派" width="100" />
            <el-table-column prop="dueAt" label="处置时限" width="160" />
            <el-table-column label="操作" width="200">
              <template #default="{ row }">
                <el-button size="mini" type="primary" v-if="row.status !== 'DONE'"
                  @click="handleTicket(row, true)">重放</el-button>
                <el-button size="mini" v-if="row.status !== 'DONE'"
                  @click="handleTicket(row, false)">关闭</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" style="margin-top: 12px;">
          <template #header>
            <div class="card-head">
              <span>EDI 报文台账（四级校验：传输层 → 语法层 → 映射层 → 业务层）</span>
              <span>
                <el-select v-model="msgFilter.status" placeholder="状态" clearable size="small"
                  style="width: 130px;" @change="loadMessages">
                  <el-option label="已接收" value="RECEIVED" />
                  <el-option label="已拒绝" value="REJECTED" />
                  <el-option label="已入库" value="PROCESSED" />
                  <el-option label="部分成功" value="PARTIAL" />
                  <el-option label="入库失败" value="FAILED" />
                  <el-option label="待人工" value="MANUAL" />
                </el-select>
                <el-button size="small" @click="openUpload">上传报文</el-button>
                <el-button size="small" type="primary" @click="openPush">模拟伙伴推送</el-button>
                <el-button size="small" @click="openRules">映射规则</el-button>
              </span>
            </div>
          </template>
          <el-table :data="messages" size="small" border v-loading="loading">
            <el-table-column prop="msgNo" label="报文号" width="150" />
            <el-table-column prop="msgType" label="类型" width="90" />
            <el-table-column prop="partnerCode" label="伙伴" width="120" />
            <el-table-column prop="status" label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="mini" :type="msgTag(row.status)">{{ msgText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="行(成功/失败)" width="120">
              <template #default="{ row }">{{ row.lineOk }}/{{ row.lineTotal }} / {{ row.lineFail }}</template>
            </el-table-column>
            <el-table-column prop="bizNo" label="生成单据" width="150" />
            <el-table-column prop="retryCount" label="重发" width="60" />
            <el-table-column prop="remark" label="说明" min-width="180" show-overflow-tooltip />
            <el-table-column label="操作" width="170">
              <template #default="{ row }">
                <el-button size="mini" @click="viewMessage(row)">校验详情</el-button>
                <el-button size="mini" type="primary" v-if="row.status !== 'PROCESSED'"
                  @click="doMsgReplay(row)">重放</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ==================== Tab3 接入治理 ==================== -->
      <el-tab-pane label="接入治理" name="onboarding">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>接口目录与契约（评审 3 工作日 / 版本治理 / 脱敏规则必填）</span>
              <el-button size="small" type="primary" @click="openContract">登记接口需求</el-button>
            </div>
          </template>
          <el-table :data="contracts" size="small" border v-loading="loading">
            <el-table-column prop="contractNo" label="契约号" width="150" />
            <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
            <el-table-column prop="partnerCode" label="伙伴" width="120" />
            <el-table-column prop="version" label="版本" width="90" />
            <el-table-column prop="status" label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="mini" :type="row.status === 'PUBLISHED' ? 'success' : 'info'">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="compatLevel" label="兼容性" width="110" />
            <el-table-column prop="reviewDueAt" label="评审截止" width="160" />
            <el-table-column prop="rejectCount" label="驳回" width="60" />
            <el-table-column label="操作" width="300">
              <template #default="{ row }">
                <el-button size="mini" @click="doSubmitReview(row)">提交评审</el-button>
                <el-button size="mini" type="success" @click="doReview(row, 'PASS')">通过</el-button>
                <el-button size="mini" type="warning" @click="doReview(row, 'REJECT')">驳回</el-button>
                <el-button size="mini" @click="openVersion(row)">版本变更</el-button>
                <el-button size="mini" type="info" @click="doRequestRelease(row)">申请放行</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" style="margin-top: 12px;">
          <template #header>
            <div class="card-head">
              <span>生产放行单（沙箱通过率须 100% / 双人会签 / 7 天观察期）</span>
              <el-button size="small" @click="loadReleases">刷新</el-button>
            </div>
          </template>
          <el-table :data="releases" size="small" border>
            <el-table-column prop="releaseNo" label="放行单号" width="150" />
            <el-table-column prop="partnerCode" label="伙伴" width="120" />
            <el-table-column prop="passRate" label="通过率" width="90" />
            <el-table-column prop="status" label="状态" width="110" />
            <el-table-column prop="initiatedBy" label="发起人" width="110" />
            <el-table-column prop="reviewedBy" label="复核人" width="110" />
            <el-table-column prop="trialEnd" label="观察期至" width="110" />
            <el-table-column label="操作" width="300">
              <template #default="{ row }">
                <el-button size="mini" type="success" v-if="row.status === 'PENDING' && isAdmin"
                  @click="doReviewRelease(row)">会签放行</el-button>
                <el-button size="mini" @click="viewDaily(row)">观察日报</el-button>
                <el-button size="mini" v-if="row.status === 'APPROVED'" @click="doObserve(row)">期满转正式</el-button>
                <el-button size="mini" v-if="row.status === 'FORMAL'" @click="doArchive(row)">归档</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ==================== Tab4 凭证与防护 ==================== -->
      <el-tab-pane label="凭证与防护" name="credentials">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>API 凭证生命周期（SHA-256 加盐 / 90 天轮换 / 紧急吊销 3 秒生效）</span>
              <span>
                <el-button size="small" @click="doSweep">到期治理扫描</el-button>
                <el-button size="small" type="primary" @click="openIssue">签发凭证</el-button>
              </span>
            </div>
          </template>
          <el-table :data="credentials" size="small" border v-loading="loading">
            <el-table-column prop="apiKey" label="API Key" width="170" />
            <el-table-column prop="partnerCode" label="伙伴" width="120" />
            <el-table-column prop="env" label="环境" width="90">
              <template #default="{ row }">
                <el-tag size="mini" :type="row.env === 'PROD' ? 'success' : 'warning'">{{ row.env }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="mini" :type="credTag(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="scope" label="Scope" min-width="170" show-overflow-tooltip />
            <el-table-column prop="rateTier" label="档位" width="100" />
            <el-table-column prop="expireAt" label="到期" width="160" />
            <el-table-column label="操作" width="270">
              <template #default="{ row }">
                <el-button size="mini" @click="doLink(row)">分发链接</el-button>
                <el-button size="mini" type="warning" @click="doRotate(row)">轮换</el-button>
                <el-button size="mini" type="danger" v-if="isAdmin && row.status !== 'REVOKED'"
                  @click="doRevoke(row)">紧急吊销</el-button>
                <el-button size="mini" type="info" @click="doTrace(row)">泄露追溯</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" style="margin-top: 12px;">
          <template #header>
            <div class="card-head">
              <span>通道限流与熔断参数</span>
              <span class="muted">
                令牌桶：桶容量 = 档位 × 1.5；连续 3 窗口 429 → 自动降档；5 分钟失败率 &gt;50% 且 ≥100 笔 → 半熔断 30 分钟
              </span>
            </div>
          </template>
          <el-table :data="channels" size="small" border>
            <el-table-column prop="partnerCode" label="伙伴" width="130" />
            <el-table-column prop="rateTier" label="当前档位" width="110" />
            <el-table-column prop="limitPerMin" label="限值(次/分)" width="110" />
            <el-table-column label="桶容量" width="100">
              <template #default="{ row }">{{ Math.ceil(row.limitPerMin * 1.5) }}</template>
            </el-table-column>
            <el-table-column prop="downgradeAt" label="最近降档" width="160" />
            <el-table-column prop="downgradeFrom" label="降档前" width="100" />
            <el-table-column prop="circuitTripCount" label="24h熔断次数" width="110" />
            <el-table-column prop="circuitRecoverAt" label="半开探测起始" width="160" />
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ==================== Tab5 SLA 监控 ==================== -->
      <el-tab-pane label="SLA 监控" name="sla">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>SLA 指标（每分钟采集：可用率 / P95 / 错误率 / 端到端延迟）</span>
              <el-button size="small" type="primary" @click="doCollect">立即采集</el-button>
            </div>
          </template>
          <el-table :data="metrics" size="small" border>
            <el-table-column prop="metricKey" label="指标" width="140" />
            <el-table-column prop="metricValue" label="实际值" width="120" />
            <el-table-column prop="unit" label="单位" width="70" />
            <el-table-column prop="targetValue" label="目标值" width="110" />
            <el-table-column prop="level" label="级别" width="130">
              <template #default="{ row }">
                <el-tag size="mini" :type="levelTag(row.level)">{{ levelText(row.level) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sampleCount" label="样本" width="80" />
            <el-table-column prop="windowStart" label="窗口起" width="165" />
            <el-table-column prop="monthTag" label="月份" width="80" />
          </el-table>
        </el-card>

        <el-card shadow="never" style="margin-top: 12px;">
          <template #header>
            <div class="card-head">
              <span>告警与升级链（Warning / Critical / Emergency，5 分钟聚合去重，连续 3 次 Critical 升级）</span>
              <el-button size="small" @click="loadAlerts">刷新</el-button>
            </div>
          </template>
          <el-table :data="alerts" size="small" border>
            <el-table-column prop="alertAt" label="时间" width="165" />
            <el-table-column prop="metricKey" label="指标" width="140" />
            <el-table-column prop="level" label="级别" width="110">
              <template #default="{ row }">
                <el-tag size="mini" :type="levelTag(row.level)">{{ row.level }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="message" label="内容" min-width="260" show-overflow-tooltip />
            <el-table-column prop="escalationLevel" label="升级级" width="90" />
            <el-table-column prop="escalatedTo" label="升级至" width="140" />
            <el-table-column prop="responseDueAt" label="响应截止" width="160" />
            <el-table-column prop="status" label="状态" width="90" />
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button size="mini" type="primary" v-if="row.status === 'OPEN'"
                  @click="doRespond(row)">响应</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" style="margin-top: 12px;">
          <template #header>
            <div class="card-head">
              <span>月度 SLA 报告（生成 → 审核 → 归档 → 发布，归档前发布硬阻断）</span>
              <el-button size="small" type="primary" @click="doGenerate">生成月报</el-button>
            </div>
          </template>
          <el-table :data="reports" size="small" border>
            <el-table-column prop="monthTag" label="月份" width="90" />
            <el-table-column prop="title" label="标题" min-width="200" />
            <el-table-column prop="version" label="版本" width="70" />
            <el-table-column prop="status" label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="mini" :type="reportTag(row.status)">{{ reportText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="generatedBy" label="生成作业" width="110" />
            <el-table-column prop="reviewedBy" label="审核人" width="110" />
            <el-table-column prop="archivedAt" label="归档时间" width="165" />
            <el-table-column label="操作" width="260">
              <template #default="{ row }">
                <el-button size="mini" type="success" v-if="row.status === 'GENERATED'"
                  @click="doReviewReport(row)">审核</el-button>
                <el-button size="mini" v-if="row.status === 'REVIEWED' && isAdmin"
                  @click="doArchiveReport(row)">归档</el-button>
                <el-button size="mini" type="primary" v-if="row.status !== 'PUBLISHED' && isAdmin"
                  @click="doPublishReport(row)">对外发布</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 通用输入对话框 -->
    <el-dialog v-model="dlg.show" :title="dlg.title" width="640px">
      <pre class="dlg-pre" v-if="dlg.text">{{ dlg.text }}</pre>
      <div v-for="f in dlg.fields" :key="f.key" style="margin-bottom: 12px;">
        <div class="f-label">{{ f.label }}</div>
        <el-input v-if="f.type === 'textarea'" type="textarea" :rows="f.rows || 4" v-model="dlg.form[f.key]"
          :placeholder="f.placeholder || ''" />
        <el-input v-else v-model="dlg.form[f.key]" :placeholder="f.placeholder || ''" />
      </div>
      <template #footer>
        <el-button @click="dlg.show = false">取消</el-button>
        <el-button type="primary" v-if="dlg.action" @click="dlg.action">{{ dlg.okText || '确定' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import * as api from '@/api/proc/interface-integration'

const userStore = useUserStore()
const roles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))

const tab = ref('overview')
const loading = ref(false)

const kpi = ref({})
const kpiCards = computed(() => {
  const k = kpi.value || {}
  const empty = k.hasData === false
  return [
    { label: '今日调用量', value: k.totalCalls ?? 0, hint: empty ? '暂无数据' : '含成功与失败' },
    { label: '成功率', value: k.successRate ?? 0, hint: empty ? '暂无数据' : '%' , cls: 'ok' },
    { label: 'P95(ms)', value: k.p95 ?? 0, hint: empty ? '暂无数据' : '目标 ≤500' },
    { label: '限流次数', value: k.rateHits ?? 0, hint: empty ? '暂无数据' : '今日 429 触发', cls: (k.rateHits || 0) > 0 ? 'warn' : '' },
    { label: '熔断状态', value: k.circuitState || 'CLOSED', hint: k.rateTier ? `档位 ${k.rateTier}` : '—', cls: k.circuitState === 'CLOSED' ? 'ok' : 'bad' },
    { label: '未处理告警', value: k.openAlerts ?? 0, hint: 'SLA 与安全告警', cls: (k.openAlerts || 0) > 0 ? 'warn' : '' },
    { label: '死信积压', value: k.deadBacklog ?? 0, hint: '事件投递死信', cls: (k.deadBacklog || 0) > 0 ? 'bad' : '' },
    { label: '在用凭证', value: k.activeCredentials ?? 0, hint: 'ACTIVE 状态', cls: 'ok' }
  ]
})

const channels = ref([])
const calls = ref([])
const deliveries = ref([])
const tickets = ref([])
const messages = ref([])
const contracts = ref([])
const releases = ref([])
const credentials = ref([])
const metrics = ref([])
const alerts = ref([])
const reports = ref([])

const dlvFilter = reactive({ status: '' })
const msgFilter = reactive({ status: '' })

const dlg = reactive({ show: false, title: '', text: '', fields: [], form: {}, action: null, okText: '确定' })

function openDialog(title, fields, form, action, text, okText) {
  dlg.title = title
  dlg.fields = fields || []
  dlg.form = form || {}
  dlg.action = action || null
  dlg.text = text || ''
  dlg.okText = okText || '确定'
  dlg.show = true
}

// ---------------- Tab1 ----------------
async function loadKpi() { try { kpi.value = (await api.getKpiApi()).data } catch (e) { /* 忽略 */ } }
async function loadChannels() { channels.value = ((await api.getChannelsApi()).data) || [] }
async function loadCalls(params) { calls.value = ((await api.getCallLogsApi(params || { current: 1, size: 30 })).data.records) || [] }
function viewCalls(partnerCode) { loadCalls({ current: 1, size: 30, caller: partnerCode }) }

async function doResetCircuit(row) {
  await api.resetCircuitApi(row.id)
  ElMessage.success('已解除熔断并恢复限流档位')
  loadChannels(); loadKpi()
}
function openTier(row) {
  openDialog('调整限流档位（高危：仅管理员）',
    [{ key: 'tier', label: '目标档位（STRATEGIC / NORMAL / NEW / LOWEST）', placeholder: 'NORMAL' }],
    { tier: row.rateTier },
    async () => {
      try {
        await api.setRateTierApi(row.id, dlg.form.tier)
        ElMessage.success('档位已调整并留痕'); dlg.show = false; loadChannels(); loadKpi()
      } catch (e) { ElMessage.error(e?.response?.data?.message || '调整失败') }
    })
}
function openSim() {
  openDialog('模拟调用方（真实签名经开放入口，可造 400/401/429/503）', [
    { key: 'partnerCode', label: '伙伴编码', placeholder: 'INTF-DEMO' },
    { key: 'mode', label: '模式（OK / BAD_SIGNATURE / EXPIRED_TIMESTAMP / STORM / RATE_STORM）', placeholder: 'OK' },
    { key: 'count', label: '次数', placeholder: '1' }
  ], { partnerCode: 'INTF-DEMO', mode: 'OK', count: '1' },
    async () => {
      try {
        const res = (await api.runSimulationApi(dlg.form)).data
        dlg.text = JSON.stringify(res, null, 2)
        dlg.fields = []; dlg.action = null; dlg.okText = '关闭'
        loadCalls(); loadChannels(); loadKpi()
      } catch (e) { ElMessage.error(e?.response?.data?.message || '模拟失败') }
    }, '', '发起')
}

// ---------------- Tab2 ----------------
async function loadDeliveries() {
  deliveries.value = ((await api.getDeliveriesApi({
    current: 1, size: 30, status: dlvFilter.status || undefined
  })).data.records) || []
}
async function loadTickets() { tickets.value = ((await api.getTicketsApi()).data) || [] }
async function loadMessages() {
  messages.value = ((await api.getMessagesApi({
    current: 1, size: 30, status: msgFilter.status || undefined
  })).data.records) || []
}
async function doScan() {
  const n = (await api.scanDeliveriesApi()).data
  ElMessage.success(`本轮处理 ${n} 条事件`); loadDeliveries(); loadTickets(); loadKpi()
}
async function doReplay(row) {
  const r = (await api.replayDeliveryApi(row.id)).data
  ElMessage.success(r.skipped ? r.message : `已重放（原幂等键 ${r.idempotencyKey}）`)
  loadDeliveries()
}
async function doAbandon(row) {
  await ElMessageBox.confirm('放弃该死信将不再投递，确认？', '高危操作', { type: 'warning' })
  await api.abandonDeliveryApi(row.id, '人工判定放弃')
  ElMessage.success('已放弃并留痕'); loadDeliveries()
}
async function handleTicket(row, replay) {
  await api.handleTicketApi(row.id, { replay, note: replay ? '' : '人工处理完成' })
  ElMessage.success(replay ? '已按原幂等键重放' : '工单已关闭')
  loadTickets(); loadDeliveries()
}
function viewMessage(row) {
  api.getMessageDetailApi(row.id).then(res => {
    const d = res.data
    dlg.title = `报文 ${d.message.msgNo} 四级校验详情`
    dlg.text = JSON.stringify({
      status: d.message.status, bizType: d.message.bizType, bizNo: d.message.bizNo,
      idempotencyKey: d.message.idempotencyKey,
      syntax: d.syntax, mapping: d.mapping, business: d.business
    }, null, 2)
    dlg.fields = []; dlg.action = null; dlg.okText = '关闭'; dlg.show = true
  })
}
function openUpload() {
  openDialog('上传报文（入口②）', [
    { key: 'partnerCode', label: '伙伴编码', placeholder: 'INTF-DEMO' },
    { key: 'msgType', label: '报文类型（ORDERS / DESADV / INVOIC）', placeholder: 'ORDERS' },
    { key: 'raw', label: '报文 JSON', type: 'textarea', rows: 8, placeholder: '{"version":"v1","header":{...},"lines":[...]}' }
  ], { partnerCode: 'INTF-DEMO', msgType: 'ORDERS', raw: '' },
    async () => {
      try {
        const r = (await api.uploadMessageApi(dlg.form)).data
        dlg.text = JSON.stringify(r, null, 2); dlg.fields = []; dlg.action = null; dlg.okText = '关闭'
        loadMessages()
      } catch (e) { ElMessage.error(e?.response?.data?.message || '上传失败') }
    }, '', '提交')
}
function openPush() {
  openDialog('模拟伙伴推送（入口③：按伙伴密钥真实签名经开放入口）', [
    { key: 'partnerCode', label: '伙伴编码', placeholder: 'INTF-DEMO' },
    { key: 'msgType', label: '报文类型', placeholder: 'ORDERS' },
    { key: 'raw', label: '报文 JSON', type: 'textarea', rows: 8 }
  ], { partnerCode: 'INTF-DEMO', msgType: 'ORDERS', raw: '' },
    async () => {
      try {
        const r = (await api.simulatePushApi(dlg.form)).data
        dlg.text = JSON.stringify(r, null, 2); dlg.fields = []; dlg.action = null; dlg.okText = '关闭'
        loadMessages(); loadCalls()
      } catch (e) { ElMessage.error(e?.response?.data?.message || '推送失败') }
    }, '', '推送')
}
function openRules() {
  openDialog('维护 EDI-ERP 映射规则（伙伴 × 报文类型，缺失即 L1 阻断）', [
    { key: 'partnerCode', label: '伙伴编码', placeholder: 'INTF-DEMO' },
    { key: 'msgType', label: '报文类型', placeholder: 'ORDERS' },
    { key: 'version', label: '契约登记版本', placeholder: 'v1' },
    { key: 'ruleJson', label: '规则 JSON（dateFields / unitMap / defaults）', type: 'textarea', rows: 6,
      placeholder: '{"dateFields":["header.orderDate"],"unitMap":{"PCE":"EA"},"defaults":{"currency":"CNY"}}' }
  ], { partnerCode: 'INTF-DEMO', msgType: 'ORDERS', version: 'v1', ruleJson: '' },
    async () => {
      try {
        await api.saveMapRuleApi(dlg.form)
        ElMessage.success('映射规则已保存'); dlg.show = false
      } catch (e) { ElMessage.error(e?.response?.data?.message || '保存失败') }
    }, '', '保存')
}
async function doMsgReplay(row) {
  const r = (await api.replayMessageApi(row.id)).data
  ElMessage.success(`重放完成：${r.status}${r.bizNo ? '，单据 ' + r.bizNo : ''}`)
  loadMessages()
}

// ---------------- Tab3 ----------------
async function loadContracts() {
  contracts.value = ((await api.getContractsApi({ current: 1, size: 30 })).data.records) || []
}
async function loadReleases() {
  releases.value = ((await api.getReleasesApi({ current: 1, size: 30 })).data.records) || []
}
function openContract() {
  openDialog('接口需求登记 → 契约创建（v1.0.0 起，脱敏规则必填）', [
    { key: 'name', label: '契约名称', placeholder: '供应商订单下达接口' },
    { key: 'partnerCode', label: '伙伴编码', placeholder: 'INTF-DEMO' },
    { key: 'protocol', label: '协议（REST / EDI）', placeholder: 'REST' },
    { key: 'endpointPath', label: '端点', placeholder: '/api/open/edi/ORDERS' },
    { key: 'idempotencyRule', label: '幂等键规则', placeholder: '调用方+类型+业务单号+版本' },
    { key: 'desensitizeRule', label: '脱敏规则（必填，否则阻断）', type: 'textarea', rows: 3,
      placeholder: '{"bankAccount":"mask","unitPrice":"mask"}' }
  ], { desensitizeRule: '' },
    async () => {
      try {
        await api.createContractApi(dlg.form)
        ElMessage.success('契约已创建（v1.0.0，DRAFT）'); dlg.show = false; loadContracts()
      } catch (e) { ElMessage.error(e?.response?.data?.message || '创建失败') }
    }, '', '创建')
}
async function doSubmitReview(row) {
  await api.submitReviewApi(row.id); ElMessage.success('已提交评审，3 个工作日内完成'); loadContracts()
}
async function doReview(row, result) {
  const { value } = await ElMessageBox.prompt('审核意见（必填）', result === 'PASS' ? '评审通过' : '评审驳回',
    { inputPattern: /.{2,}/, inputErrorMessage: '意见不少于 2 字' })
  await api.reviewContractApi(row.id, { result, opinion: value })
  ElMessage.success(result === 'PASS' ? '契约已发布' : '已驳回（累计 3 次自动关闭）'); loadContracts()
}
function openVersion(row) {
  openDialog('版本变更（不兼容变更须升 Major + 旧版本保留 180 天）', [
    { key: 'version', label: '新版本号', placeholder: 'v1.1.0 或 v2.0.0' },
    { key: 'incompatible', label: '是否不兼容变更（true / false）', placeholder: 'false' },
    { key: 'fieldsJson', label: '字段清单 JSON（可空）', type: 'textarea', rows: 4 }
  ], { version: '', incompatible: 'false', fieldsJson: '' },
    async () => {
      try {
        const p = { ...dlg.form, incompatible: dlg.form.incompatible === 'true' }
        const r = (await api.changeVersionApi(row.id, p)).data
        dlg.text = JSON.stringify(r, null, 2); dlg.fields = []; dlg.action = null; dlg.okText = '关闭'
        loadContracts()
      } catch (e) { ElMessage.error(e?.response?.data?.message || '变更失败') }
    }, '', '提交')
}
async function doRequestRelease(row) {
  try {
    const r = (await api.requestReleaseApi({ contractId: row.id, securityNote: '安全评估结论已登记' })).data
    ElMessage.success(`放行单 ${r.releaseNo} 已生成，待双人会签`); loadReleases()
  } catch (e) { ElMessage.error(e?.response?.data?.message || '申请失败（通过率须 100%）') }
}
async function doReviewRelease(row) {
  try {
    const r = (await api.reviewReleaseApi(row.id)).data
    ElMessage.success(`会签通过，生产凭证：${r.prodApiKey} / ${r.prodSecret}（仅此一次展示）`)
    loadReleases(); loadCredentials()
  } catch (e) { ElMessage.error(e?.response?.data?.message || '会签失败（发起人≠复核人）') }
}
async function doObserve(row) {
  try { await api.observeCompleteApi(row.id); ElMessage.success('观察期完成，已转正式运行'); loadReleases() }
  catch (e) { ElMessage.error(e?.response?.data?.message || '转正式失败') }
}
async function doArchive(row) {
  await api.archiveReleaseApi(row.id); ElMessage.success('已归档'); loadReleases()
}
async function viewDaily(row) {
  const r = (await api.getObservationDailyApi(row.id)).data
  dlg.title = `观察期日报 ${r.releaseNo}`; dlg.text = JSON.stringify(r, null, 2)
  dlg.fields = []; dlg.action = null; dlg.okText = '关闭'; dlg.show = true
}

// ---------------- Tab4 ----------------
async function loadCredentials() {
  credentials.value = ((await api.getCredentialsApi({ current: 1, size: 30 })).data.records) || []
}
function openIssue() {
  openDialog('签发凭证（明文仅本次返回）', [
    { key: 'partnerCode', label: '伙伴编码', placeholder: 'INTF-DEMO' },
    { key: 'scope', label: 'Scope（逗号分隔，必填）', placeholder: 'EDI_WRITE,PO_READ' },
    { key: 'tier', label: '限流档位（STRATEGIC / NORMAL / NEW）', placeholder: 'NORMAL' },
    { key: 'env', label: '环境（SANDBOX / PROD）', placeholder: 'SANDBOX' }
  ], { partnerCode: 'INTF-DEMO', scope: 'EDI_WRITE,PO_READ', tier: 'NORMAL', env: 'SANDBOX' },
    async () => {
      try {
        const r = (await api.issueCredentialApi(dlg.form)).data
        dlg.text = JSON.stringify(r, null, 2); dlg.fields = []; dlg.action = null; dlg.okText = '关闭'
        loadCredentials()
      } catch (e) { ElMessage.error(e?.response?.data?.message || '签发失败（ACTIVE ≤ 2 套）') }
    }, '', '签发')
}
async function doLink(row) {
  const r = (await api.createCredentialLinkApi(row.id)).data
  dlg.title = '一次性分发链接（10 分钟有效，下载后即失效）'
  dlg.text = JSON.stringify(r, null, 2); dlg.fields = []; dlg.action = null; dlg.okText = '关闭'; dlg.show = true
}
async function doRotate(row) {
  const r = (await api.rotateCredentialApi(row.id)).data
  dlg.title = '轮换结果（灰度观察期，旧凭证到期后 DEPRECATED 保留 7 天）'
  dlg.text = JSON.stringify(r, null, 2); dlg.fields = []; dlg.action = null; dlg.okText = '关闭'; dlg.show = true
  loadCredentials()
}
async function doRevoke(row) {
  const { value } = await ElMessageBox.prompt('吊销原因（必填）', '紧急吊销（3 秒内全部请求 401）',
    { inputPattern: /.{2,}/, inputErrorMessage: '原因不少于 2 字' })
  try {
    await api.revokeCredentialApi(row.id, value)
    ElMessage.success('已吊销并清空令牌桶'); loadCredentials()
  } catch (e) { ElMessage.error(e?.response?.data?.message || '吊销失败（仅管理员）') }
}
async function doTrace(row) {
  const r = (await api.getCredentialTraceApi(row.partnerCode)).data
  dlg.title = '泄露追溯（近 30 天 Trace）'; dlg.text = JSON.stringify(r, null, 2)
  dlg.fields = []; dlg.action = null; dlg.okText = '关闭'; dlg.show = true
}
async function doSweep() {
  const n = (await api.sweepCredentialsApi()).data
  ElMessage.success(`到期治理处理 ${n} 条`); loadCredentials()
}

// ---------------- Tab5 ----------------
async function loadMetrics() {
  metrics.value = ((await api.getMetricsApi({ current: 1, size: 30 })).data.records) || []
}
async function loadAlerts() {
  alerts.value = ((await api.getAlertsApi({ current: 1, size: 30 })).data.records) || []
}
async function loadReports() {
  reports.value = ((await api.getReportsApi({ current: 1, size: 20 })).data.records) || []
}
async function doCollect() {
  const n = (await api.collectSlaApi()).data
  ElMessage.success(`采集完成，写入 ${n} 条指标`); loadMetrics(); loadAlerts()
}
async function doRespond(row) {
  await api.respondAlertApi(row.id); ElMessage.success('已记录响应'); loadAlerts()
}
async function doGenerate() {
  try { const r = (await api.generateReportApi({})).data
    ElMessage.success(`已生成 ${r.monthTag} 报告（未达标 ${r.unreachCount} 项）`); loadReports()
  } catch (e) { ElMessage.error(e?.response?.data?.message || '生成失败') }
}
async function doReviewReport(row) {
  const { value } = await ElMessageBox.prompt('审核意见（必填）', '审核月度 SLA 报告',
    { inputPattern: /.{2,}/, inputErrorMessage: '意见不少于 2 字' })
  try { await api.reviewReportApi(row.id, value); ElMessage.success('已审核'); loadReports() }
  catch (e) { ElMessage.error(e?.response?.data?.message || '审核失败') }
}
async function doArchiveReport(row) {
  try { await api.archiveReportApi(row.id); ElMessage.success('已归档（内容只读）'); loadReports() }
  catch (e) { ElMessage.error(e?.response?.data?.message || '归档失败（仅管理员）') }
}
async function doPublishReport(row) {
  try { await api.publishReportApi(row.id, '全部合作方'); ElMessage.success('已对外发布'); loadReports() }
  catch (e) { ElMessage.error(e?.response?.data?.message || '发布被阻断（未归档不可发布）') }
}

// ---------------- 标签映射 ----------------
function dlvTag(s) {
  return s === 'DELIVERED' ? 'success' : s === 'DEAD' ? 'danger'
    : s === 'CONFLICT_DROPPED' ? 'info' : 'warning'
}
function dlvText(s) {
  return { PENDING: '待投递', DELIVERING: '投递中', DELIVERED: '已投递', RETRYING: '重试中',
    DEAD: '死信', STAGED: '暂存', ABANDONED: '已放弃', CONFLICT_DROPPED: '冲突丢弃' }[s] || s
}
function msgTag(s) {
  return s === 'PROCESSED' ? 'success' : s === 'PARTIAL' ? 'warning'
    : s === 'FAILED' || s === 'MANUAL' ? 'danger' : 'info'
}
function msgText(s) {
  return { RECEIVED: '已接收', REJECTED: '已拒绝', PROCESSED: '已入库', PARTIAL: '部分成功',
    FAILED: '入库失败', MANUAL: '待人工' }[s] || s
}
function credTag(s) {
  return s === 'ACTIVE' ? 'success' : s === 'REVOKED' || s === 'EXPIRED' ? 'danger' : 'info'
}
function levelTag(l) {
  return l === 'EMERGENCY' || l === 'CRITICAL' ? 'danger' : l === 'WARNING' ? 'warning' : l === 'DATA_MISSING' ? 'info' : 'success'
}
function levelText(l) {
  return { OK: '达标', WARNING: 'Warning', CRITICAL: 'Critical', EMERGENCY: 'Emergency', DATA_MISSING: '数据缺失' }[l] || l
}
function reportTag(s) {
  return s === 'PUBLISHED' ? 'success' : s === 'ARCHIVED' ? 'warning' : 'info'
}
function reportText(s) {
  return { GENERATED: '已生成', REVIEWED: '已审核', ARCHIVED: '已归档', PUBLISHED: '已发布' }[s] || s
}

function refreshAll() {
  loadKpi(); loadChannels(); loadCalls(); loadDeliveries(); loadTickets(); loadMessages()
  loadContracts(); loadReleases(); loadCredentials(); loadMetrics(); loadAlerts(); loadReports()
}
onMounted(refreshAll)
</script>

<style scoped>
.intf-page { padding: 4px; }
.kpi-row { margin-bottom: 12px; }
.kpi-card { text-align: center; }
.kpi-label { font-size: 12px; color: #909399; }
.kpi-value { font-size: 22px; font-weight: 600; margin: 4px 0; }
.kpi-value.ok { color: #67c23a; }
.kpi-value.warn { color: #e6a23c; }
.kpi-value.bad { color: #f56c6c; }
.kpi-hint { font-size: 11px; color: #c0c4cc; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.dlg-pre { background: #f5f7fa; padding: 10px; border-radius: 4px; max-height: 320px; overflow: auto;
  font-size: 12px; white-space: pre-wrap; word-break: break-all; }
.f-label { font-size: 12px; color: #606266; margin-bottom: 4px; }
.muted { color: #909399; font-size: 12px; }
</style>
