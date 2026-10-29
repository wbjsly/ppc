<template>
  <div class="pr-page">
    <el-tabs v-model="tab">
      <!-- ================= 16.3.1 权限申请 ================= -->
      <el-tab-pane label="权限申请" name="requests">
        <div class="toolbar">
          <el-select v-model="reqQuery.status" placeholder="状态" clearable style="width: 150px"
            @change="loadRequests">
            <el-option label="审批中" value="PENDING_APPROVAL" />
            <el-option label="待生效" value="APPROVED_PENDING" />
            <el-option label="已生效" value="ACTIVE" />
            <el-option label="已驳回" value="REJECTED" />
            <el-option label="已到期" value="EXPIRED" />
          </el-select>
          <el-input v-model="reqQuery.keyword" placeholder="用户/角色/理由" clearable
            style="width: 200px" @keyup.enter="loadRequests" @clear="loadRequests" />
          <el-button type="primary" @click="loadRequests">查询</el-button>
          <el-button type="success" @click="openCreate">新建申请</el-button>
          <el-button @click="windowDlg = true">变更窗口</el-button>
        </div>
        <el-table :data="requests" v-loading="reqLoading" border stripe>
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.kind === 'EXCEPTION'" type="warning" size="small">例外</el-tag>
              <el-tag v-else type="info" size="small">常规</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="targetUsername" label="被授权人" width="130" />
          <el-table-column label="目标角色" min-width="200">
            <template #default="{ row }">{{ fmtRoles(row.rolesJson) }}</template>
          </el-table-column>
          <el-table-column label="风险" width="90">
            <template #default="{ row }">
              <el-tag :type="riskType(row.riskLevel)" size="small">{{ riskLabel(row.riskLevel) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="validMonths" label="有效期(月)" width="90" />
          <el-table-column label="生效策略" width="100">
            <template #default="{ row }">{{ row.activationMode === 'SCHEDULED' ? '指定时点' : '立即' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ================= 16.3.2 职责预检 ================= -->
      <el-tab-pane label="职责预检" name="precheck">
        <el-row :gutter="16">
          <el-col :span="10">
            <el-card shadow="never">
              <template #header>预检沙盘（实时返回超基线 / 互斥 / 现有权限）</template>
              <el-form label-width="90px">
                <el-form-item label="被检用户">
                  <el-select v-model="pcUserId" filterable clearable placeholder="默认当前用户"
                    style="width: 100%">
                    <el-option v-for="u in users" :key="u.id"
                      :label="u.username + (u.nickName ? '（' + u.nickName + '）' : '')"
                      :value="u.id" />
                  </el-select>
                </el-form-item>
                <el-form-item label="目标角色">
                  <el-select v-model="pcRoles" multiple filterable placeholder="选择角色"
                    style="width: 100%">
                    <el-option v-for="r in allRoles" :key="r.roleCode"
                      :label="r.roleCode + '（' + r.roleName + '）'" :value="r.roleCode" />
                  </el-select>
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" :disabled="!pcRoles.length" @click="runPrecheck">开始预检</el-button>
                </el-form-item>
              </el-form>
              <template v-if="pcResult">
                <el-alert v-if="!pcResult.conflicts.length && !pcResult.beyondBaseline.length"
                  type="success" :closable="false" show-icon title="预检通过：无超基线、无互斥冲突" />
                <template v-else>
                  <el-alert v-if="pcResult.beyondBaseline.length" type="warning" :closable="false"
                    show-icon :title="'超基线角色：' + pcResult.beyondBaseline.join('、')" />
                  <el-table v-if="pcResult.conflicts.length" :data="pcResult.conflicts" size="small"
                    style="margin-top: 8px">
                    <el-table-column prop="roleA" label="互斥角色 A" />
                    <el-table-column prop="roleB" label="互斥角色 B" />
                    <el-table-column prop="remark" label="说明" />
                  </el-table>
                </template>
                <div class="muted" style="margin-top: 8px">
                  现有权限：{{ (pcResult.existing || []).join('、') || '无' }}
                </div>
                <div class="muted">岗位基线：{{ (pcResult.baseline || []).join('、') || '无基线' }}</div>
              </template>
            </el-card>
          </el-col>
          <el-col :span="14">
            <el-card shadow="never">
              <template #header>
                <span>互斥规则矩阵</span>
                <el-button style="float: right" type="primary" size="small"
                  @click="ruleDlg = true">新增规则</el-button>
              </template>
              <el-table :data="rules" v-loading="ruleLoading" border size="small">
                <el-table-column prop="roleA" label="角色 A" />
                <el-table-column prop="roleB" label="角色 B" />
                <el-table-column prop="remark" label="说明" min-width="180" />
                <el-table-column label="操作" width="80">
                  <template #default="{ row }">
                    <el-button link type="danger" @click="removeRule(row)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
          </el-col>
        </el-row>
      </el-tab-pane>

      <!-- ================= 16.3.3 到期回收 ================= -->
      <el-tab-pane label="到期回收" name="reclaim">
        <div class="toolbar">
          <el-select v-model="ledQuery.status" placeholder="状态" clearable style="width: 150px"
            @change="loadLedger">
            <el-option label="生效中" value="ACTIVE" />
            <el-option label="已回收" value="RECLAIMED" />
            <el-option label="已过期" value="EXPIRED" />
          </el-select>
          <el-button type="primary" @click="loadLedger">查询</el-button>
          <el-button type="warning" plain @click="doSweep">到期扫描（回收+7日提醒）</el-button>
        </div>
        <el-table :data="ledger" v-loading="ledLoading" border stripe>
          <el-table-column prop="username" label="用户" width="130" />
          <el-table-column label="角色" min-width="160">
            <template #default="{ row }">
              {{ row.roleCode }}<span class="muted" v-if="row.roleName">（{{ row.roleName }}）</span>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.source === 'EXCEPTION'" type="warning" size="small">例外</el-tag>
              <el-tag v-else type="info" size="small">常规</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="到期时间" width="170">
            <template #default="{ row }">
              <span :style="expiring(row) ? 'color:#E6A23C;font-weight:600' : ''">
                {{ fmtTime(row.expireAt) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="ledStatusType(row.status)" size="small">{{ ledStatusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="申请单" width="150">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetailById(row.applyId)">查看</el-button>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="170">
            <template #default="{ row }">
              <template v-if="row.status === 'ACTIVE'">
                <el-button link type="danger" @click="doReclaim(row)">回收</el-button>
                <el-button link type="primary" @click="renew(row)">续期</el-button>
              </template>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ================= 16.4.2 窗口外升级审批 ================= -->
      <el-tab-pane label="窗口外升级" name="outwindow">
        <div class="toolbar">
          <el-select v-model="owQuery.status" placeholder="状态" clearable style="width: 150px"
            @change="loadOutWindow">
            <el-option label="审批中" value="PENDING_APPROVAL" />
            <el-option label="待生效" value="APPROVED_PENDING" />
            <el-option label="已生效" value="ACTIVE" />
            <el-option label="已驳回" value="REJECTED" />
          </el-select>
          <el-input v-model="owQuery.keyword" placeholder="用户/角色/理由" clearable
            style="width: 200px" @keyup.enter="loadOutWindow" @clear="loadOutWindow" />
          <el-button type="primary" @click="loadOutWindow">查询</el-button>
          <el-tag type="warning" style="margin-left: 8px">
            窗口外提交的申请自动升级 CCB 三方会签（运维负责人 + 架构委员会代表 + 业务 Owner）
          </el-tag>
        </div>
        <el-table :data="outWindowRows" v-loading="owLoading" border stripe>
          <el-table-column prop="targetUsername" label="被授权人" width="130" />
          <el-table-column label="目标角色" min-width="180">
            <template #default="{ row }">{{ fmtRoles(row.rolesJson) }}</template>
          </el-table-column>
          <el-table-column label="风险" width="90">
            <template #default="{ row }">
              <el-tag :type="riskType(row.riskLevel)" size="small">{{ riskLabel(row.riskLevel) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="窗口外变更" width="100" align="center">
            <template #default>
              <el-tag type="warning" size="small">窗口外</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createDate" label="提交时间" width="170" :formatter="(_, r) => fmtTime(r.createDate)" />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情/签署</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ================= 16.4.3 双人复核台账 ================= -->
      <el-tab-pane label="双人复核" name="review">
        <div class="toolbar">
          <el-button type="primary" @click="loadLedgerRows">刷新</el-button>
          <el-tag type="info">只读证据面：每笔窗口外申请的 CCB 三方会签记录（会签即双人复核）</el-tag>
        </div>
        <el-table :data="reviewRows" v-loading="revLoading" border stripe
                  :row-key="(r) => r.id" >
          <el-table-column type="expand">
            <template #default="{ row }">
              <el-table :data="row.ccbSigns || []" border size="small" style="margin: 8px 24px; width: calc(100% - 48px)">
                <el-table-column prop="nodeName" label="CCB 节点" width="220" />
                <el-table-column prop="signerName" label="签署人" width="130">
                  <template #default="{ row: s }">{{ s.signerName || s.signerId || '—' }}</template>
                </el-table-column>
                <el-table-column label="结果" width="110">
                  <template #default="{ row: s }">
                    <el-tag :type="taskType(s.signStatus)" size="small">{{ taskLabel(s.signStatus) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="opinion" label="审批意见" min-width="180">
                  <template #default="{ row: s }">{{ s.opinion || '—' }}</template>
                </el-table-column>
                <el-table-column label="签署时间" width="170">
                  <template #default="{ row: s }">{{ fmtTime(s.opTime) }}</template>
                </el-table-column>
              </el-table>
            </template>
          </el-table-column>
          <el-table-column prop="targetUsername" label="被授权人" width="130" />
          <el-table-column label="目标角色" min-width="180">
            <template #default="{ row }">{{ fmtRoles(row.rolesJson) }}</template>
          </el-table-column>
          <el-table-column label="风险" width="90">
            <template #default="{ row }">
              <el-tag :type="riskType(row.riskLevel)" size="small">{{ riskLabel(row.riskLevel) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="提交时间" width="170" :formatter="(_, r) => fmtTime(r.submitTime)" />
          <el-table-column label="最终结果" width="110">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="申请单" width="90">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetailById(row.id)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 新建申请对话框 -->
    <el-dialog v-model="createDlg" title="新建权限申请" width="640px">
      <el-form label-width="100px">
        <el-form-item label="被授权人" required>
          <el-select v-model="form.targetUserId" filterable placeholder="选择用户" style="width: 100%">
            <el-option v-for="u in users" :key="u.id"
              :label="u.username + (u.nickName ? '（' + u.nickName + '）' : '')" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标角色" required>
          <el-select v-model="form.roleCodes" multiple filterable placeholder="选择角色"
            style="width: 100%">
            <el-option v-for="r in allRoles" :key="r.roleCode"
              :label="r.roleCode + '（' + r.roleName + '）'" :value="r.roleCode" />
          </el-select>
          <div class="muted">含特权角色须填故障单/项目编号；命中互斥将自动追加合规官</div>
        </el-form-item>
        <el-form-item label="数据范围" required>
          <el-input v-model="form.dataScope" placeholder="法人主体/组织单元/部门，如：LE-002 / OU-PROC-01" />
        </el-form-item>
        <el-form-item label="业务理由" required>
          <el-input v-model="form.reason" type="textarea" :rows="2"
            placeholder="不少于 10 字的业务理由" />
        </el-form-item>
        <el-form-item label="有效期(月)" required>
          <el-input-number v-model="form.validMonths" :min="1" :max="12" />
          <span class="muted">最长 12 个月，到期自动回收</span>
        </el-form-item>
        <el-form-item label="生效策略">
          <el-radio-group v-model="form.activationMode">
            <el-radio value="IMMEDIATE">立即（审批通过即生效）</el-radio>
            <el-radio value="SCHEDULED">指定时点</el-radio>
          </el-radio-group>
          <el-date-picker v-if="form.activationMode === 'SCHEDULED'" v-model="form.activateAt"
            type="datetime" placeholder="选择生效时点" style="margin-left: 12px" />
        </el-form-item>
        <el-form-item label="故障单号">
          <el-input v-model="form.ticketNo" placeholder="特权申请必填（INC- / 项目编号）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDlg = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">提交审批</el-button>
      </template>
    </el-dialog>

    <!-- 申请详情抽屉 -->
    <el-drawer v-model="detailDlg" title="申请单详情" size="620px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="类型">{{ detail.request.kind === 'EXCEPTION' ? '例外' : '常规' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusLabel(detail.request.status) }}</el-descriptions-item>
          <el-descriptions-item label="被授权人">{{ detail.targetUsername }}</el-descriptions-item>
          <el-descriptions-item label="风险">{{ riskLabel(detail.request.riskLevel) }}</el-descriptions-item>
          <el-descriptions-item label="目标角色" :span="2">{{ fmtRoles(detail.request.rolesJson) }}</el-descriptions-item>
          <el-descriptions-item label="数据范围" :span="2">{{ detail.request.dataScope }}</el-descriptions-item>
          <el-descriptions-item label="理由" :span="2">{{ detail.request.reason }}</el-descriptions-item>
          <el-descriptions-item label="有效期">{{ detail.request.validMonths }} 个月</el-descriptions-item>
          <el-descriptions-item label="生效策略">{{ detail.request.activationMode === 'SCHEDULED' ? '指定时点' : '立即' }}</el-descriptions-item>
        </el-descriptions>

        <h4>提交时预检快照</h4>
        <el-alert v-if="!snapConflicts.length && !snapBeyond.length" type="success" :closable="false"
          show-icon title="无超基线、无互斥冲突" />
        <template v-else>
          <el-alert v-if="snapBeyond.length" type="warning" :closable="false" show-icon
            :title="'超基线：' + snapBeyond.join('、')" style="margin-bottom: 6px" />
          <el-table v-if="snapConflicts.length" :data="snapConflicts" size="small">
            <el-table-column prop="roleA" label="互斥 A" />
            <el-table-column prop="roleB" label="互斥 B" />
            <el-table-column prop="remark" label="说明" />
          </el-table>
        </template>

        <h4>审批链与日志</h4>
        <el-table :data="logTasks" size="small" border>
          <el-table-column prop="seq" label="序" width="50" />
          <el-table-column prop="nodeName" label="节点" min-width="130" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="taskType(row.status)">{{ taskLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="signerName" label="签署人" width="90" />
          <el-table-column prop="opinion" label="意见" min-width="90" />
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <template v-if="row.status === 'ACTIVE'">
                <el-button link type="primary" @click="signTask(row, true)">同意</el-button>
                <el-button link type="danger" @click="signTask(row, false)">驳回</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <div class="muted" style="margin-top: 8px">
          就地签署对接既有审批端点；按人节点仅指定签署人可签（其他人操作将返回 403）
        </div>
      </template>
    </el-drawer>

    <!-- 互斥规则新增 -->
    <el-dialog v-model="ruleDlg" title="新增互斥规则" width="460px">
      <el-form label-width="80px">
        <el-form-item label="角色 A">
          <el-select v-model="ruleForm.roleA" filterable style="width: 100%">
            <el-option v-for="r in allRoles" :key="r.roleCode" :label="r.roleCode" :value="r.roleCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色 B">
          <el-select v-model="ruleForm.roleB" filterable style="width: 100%">
            <el-option v-for="r in allRoles" :key="r.roleCode" :label="r.roleCode" :value="r.roleCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="ruleForm.remark" placeholder="互斥业务含义" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDlg = false">取消</el-button>
        <el-button type="primary" @click="submitRule">确定</el-button>
      </template>
    </el-dialog>

    <!-- 变更窗口维护 -->
    <el-dialog v-model="windowDlg" title="变更窗口维护（特权权限仅窗口内生效）" width="640px">
      <el-table :data="windows" size="small" border>
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="星期" width="90">
          <template #default="{ row }">周{{ ['一','二','三','四','五','六','日'][row.dayOfWeek - 1] }}</template>
        </el-table-column>
        <el-table-column label="时段" width="140">
          <template #default="{ row }">{{ row.startTime }} - {{ row.endTime }}</template>
        </el-table-column>
        <el-table-column label="状态" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === '1' ? 'success' : 'info'">
              {{ row.status === '1' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeWindow(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-divider content-position="left">新增窗口</el-divider>
      <el-form inline>
        <el-form-item label="名称"><el-input v-model="winForm.name" style="width: 150px" /></el-form-item>
        <el-form-item label="星期">
          <el-select v-model="winForm.dayOfWeek" style="width: 90px">
            <el-option v-for="d in 7" :key="d" :label="'周' + ['一','二','三','四','五','六','日'][d-1]" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="起">
          <el-time-select v-model="winForm.startTime" start="00:00" step="00:30" end="23:30"
            style="width: 100px" />
        </el-form-item>
        <el-form-item label="止">
          <el-time-select v-model="winForm.endTime" start="00:30" step="00:30" end="23:59"
            style="width: 100px" />
        </el-form-item>
        <el-button type="primary" @click="submitWindow">添加</el-button>
      </el-form>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/system/permission-request'
import { roleListAll } from '@/api/system/permission'
import { accountPage } from '@/api/system/account'

const route = useRoute()
const router = useRouter()

// Tab 初始定位（沿 12.9/16.1/16.2 模式）：meta.tab 优先，白名单外回落 requests
const TAB_WHITELIST = ['requests', 'precheck', 'reclaim', 'outwindow', 'review']
const pickTab = () => {
  const m = route.meta?.tab
  const q = route.query?.tab
  if (TAB_WHITELIST.includes(m)) return m
  if (TAB_WHITELIST.includes(q)) return q
  return 'requests'
}
const tab = ref(pickTab())
watch(() => route.meta?.tab, () => { tab.value = pickTab() })

// ---------- 公共数据 ----------
const allRoles = ref([])
const users = ref([])

async function loadCommon() {
  const [r, u] = await Promise.all([roleListAll(), accountPage({ current: 1, size: 500 })])
  allRoles.value = r.data || []
  users.value = (u.data && u.data.records) || []
}

// ---------- Tab1 权限申请 ----------
const requests = ref([])
const reqLoading = ref(false)
const reqQuery = ref({ status: '', keyword: '' })
const createDlg = ref(false)
const detailDlg = ref(false)
const detail = ref(null)
const windowDlg = ref(false)
const windows = ref([])
const winForm = ref({ name: '', dayOfWeek: 3, startTime: '22:00', endTime: '24:00' })
const form = ref(blankForm())

function blankForm() {
  return {
    targetUserId: '', roleCodes: [], dataScope: '', reason: '',
    validMonths: 12, activationMode: 'IMMEDIATE', activateAt: null, ticketNo: ''
  }
}

async function loadRequests() {
  reqLoading.value = true
  try {
    const { data } = await api.requestList({
      status: reqQuery.value.status || undefined,
      keyword: reqQuery.value.keyword || undefined
    })
    requests.value = data || []
  } finally {
    reqLoading.value = false
  }
}

function openCreate() {
  form.value = blankForm()
  createDlg.value = true
}

async function submitCreate() {
  const f = form.value
  if (!f.targetUserId || !f.roleCodes.length || !f.dataScope || !f.reason) {
    ElMessage.error('被授权人 / 目标角色 / 数据范围 / 理由必填')
    return
  }
  if (f.reason.trim().length < 10) {
    ElMessage.error('业务理由不少于 10 字')
    return
  }
  if (f.activationMode === 'SCHEDULED' && !f.activateAt) {
    ElMessage.error('请选择生效时点')
    return
  }
  const payload = {
    ...f,
    activateAt: f.activationMode === 'SCHEDULED' && f.activateAt
      ? new Date(f.activateAt).toISOString().slice(0, 19) : undefined
  }
  await api.submitRequest(payload)
  ElMessage.success('已提交，进入风险路由审批')
  createDlg.value = false
  loadRequests()
}

const logTasks = computed(() => (detail.value && detail.value.approvalLogs
  && detail.value.approvalLogs.tasks) || [])
const snapConflicts = computed(() => (detail.value && detail.value.sodSnapshot
  && detail.value.sodSnapshot.conflicts) || [])
const snapBeyond = computed(() => (detail.value && detail.value.sodSnapshot
  && detail.value.sodSnapshot.beyondBaseline) || [])

async function openDetail(row) {
  await openDetailById(row.id)
}

async function openDetailById(id) {
  const { data } = await api.requestDetail(id)
  detail.value = data
  detailDlg.value = true
}

/** 就地签署/驳回（复用既有审批端点；无集中审批台页面，D8 跳转降级为就地操作） */
async function signTask(row, pass) {
  let opinion = '同意'
  if (pass) {
    const { value } = await ElMessageBox.prompt('审批意见（默认：同意）', '签署', {
      inputValue: '同意'
    })
    opinion = value || '同意'
  } else {
    const { value } = await ElMessageBox.prompt('驳回理由（至少 2 字）', '驳回', {
      inputValidator: (v) => (v && v.trim().length >= 2) || '驳回理由至少 2 字'
    })
    opinion = value
  }
  if (pass) {
    await api.approvalPass(row.id, opinion)
  } else {
    await api.approvalReject(row.id, opinion)
  }
  ElMessage.success(pass ? '已签署通过' : '已驳回')
  await openDetailById(detail.value.request.id)
  loadRequests()
}

// 变更窗口
async function loadWindows() {
  const { data } = await api.windowList()
  windows.value = data || []
}
async function submitWindow() {
  const w = winForm.value
  if (!w.name || !w.startTime || !w.endTime) {
    ElMessage.error('名称与时段必填')
    return
  }
  await api.saveWindow({ ...w })
  ElMessage.success('窗口已保存')
  winForm.value = { name: '', dayOfWeek: w.dayOfWeek, startTime: '22:00', endTime: '24:00' }
  loadWindows()
}
async function removeWindow(row) {
  await ElMessageBox.confirm(`删除窗口「${row.name}」？`, '确认', { type: 'warning' })
  await api.deleteWindow(row.id)
  ElMessage.success('已删除')
  loadWindows()
}

// ---------- Tab2 职责预检 ----------
const pcUserId = ref('')
const pcRoles = ref([])
const pcResult = ref(null)
const rules = ref([])
const ruleLoading = ref(false)
const ruleDlg = ref(false)
const ruleForm = ref({ roleA: '', roleB: '', remark: '' })

async function runPrecheck() {
  const { data } = await api.precheck({
    targetUserId: pcUserId.value || undefined,
    roleCodes: pcRoles.value
  })
  pcResult.value = data
}

async function loadRules() {
  ruleLoading.value = true
  try {
    const { data } = await api.sodRuleList()
    rules.value = data || []
  } finally {
    ruleLoading.value = false
  }
}
async function submitRule() {
  if (!ruleForm.value.roleA || !ruleForm.value.roleB) {
    ElMessage.error('互斥角色对必填')
    return
  }
  await api.addSodRule(ruleForm.value)
  ElMessage.success('规则已保存')
  ruleDlg.value = false
  ruleForm.value = { roleA: '', roleB: '', remark: '' }
  loadRules()
}
async function removeRule(row) {
  await ElMessageBox.confirm('删除该互斥规则？后续申请将不再命中', '确认', { type: 'warning' })
  await api.deleteSodRule(row.id)
  ElMessage.success('已删除')
  loadRules()
}

// ---------- Tab3 到期回收 ----------
const ledger = ref([])
const ledLoading = ref(false)
const ledQuery = ref({ status: 'ACTIVE' })

async function loadLedger() {
  ledLoading.value = true
  try {
    const { data } = await api.ledgerList({ status: ledQuery.value.status || undefined })
    ledger.value = data || []
  } finally {
    ledLoading.value = false
  }
}

function expiring(row) {
  if (row.status !== 'ACTIVE' || !row.expireAt) return false
  const diff = new Date(row.expireAt).getTime() - Date.now()
  return diff > 0 && diff <= 7 * 24 * 3600 * 1000
}

async function doReclaim(row) {
  await ElMessageBox.confirm(
    `回收 ${row.username} 的角色 ${row.roleCode}？回收前自动留权限快照`, '确认回收',
    { type: 'warning' })
  const { data } = await api.reclaimLedger(row.id)
  ElMessage.success(data.reclaimed ? '已回收' : '该台账已是 ' + data.status)
  loadLedger()
}

async function doSweep() {
  const { data } = await api.sweepLedger()
  ElMessage.success(`扫描完成：回收 ${data.reclaimed}，提醒 ${data.reminded}，失败 ${data.failed}`)
  loadLedger()
}

/** 续期 = 重新提交完整申请单（带入原角色），原授予到期照常回收 */
function renew(row) {
  form.value = blankForm()
  form.value.targetUserId = row.targetUserId
  form.value.roleCodes = [row.roleCode]
  form.value.reason = '到期续期申请（原申请单 ' + row.applyId + '）'
  tab.value = 'requests'
  createDlg.value = true
}

// ---------- Tab4 窗口外升级（16.4.2）/ Tab5 双人复核台账（16.4.3） ----------
const outWindowRows = ref([])
const owLoading = ref(false)
const owQuery = ref({ status: '', keyword: '' })
const reviewRows = ref([])
const revLoading = ref(false)

async function loadOutWindow() {
  owLoading.value = true
  try {
    const { data } = await api.requestList({
      status: owQuery.value.status || undefined,
      keyword: owQuery.value.keyword || undefined,
      outWindow: 'true'
    })
    outWindowRows.value = data || []
  } finally {
    owLoading.value = false
  }
}

async function loadLedgerRows() {
  revLoading.value = true
  try {
    const { data } = await api.outWindowLedger()
    reviewRows.value = data || []
  } finally {
    revLoading.value = false
  }
}

// ---------- 展示辅助 ----------
function fmtRoles(json) {
  try {
    return (JSON.parse(json || '[]')).join('、')
  } catch (e) {
    return json
  }
}
function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}
function riskType(r) {
  return r === 'PRIVILEGED' ? 'danger' : r === 'SENSITIVE' ? 'warning' : 'info'
}
function riskLabel(r) {
  return r === 'PRIVILEGED' ? '特权' : r === 'SENSITIVE' ? '敏感' : '普通'
}
function statusType(s) {
  return ({ PENDING_APPROVAL: 'warning', APPROVED_PENDING: 'info', ACTIVE: 'success',
    REJECTED: 'danger', EXPIRED: 'info', CANCELLED: 'info' })[s] || 'info'
}
function statusLabel(s) {
  return ({ PENDING_APPROVAL: '审批中', APPROVED_PENDING: '待生效', ACTIVE: '已生效',
    REJECTED: '已驳回', EXPIRED: '已到期', CANCELLED: '已取消' })[s] || s
}
function ledStatusType(s) {
  return s === 'ACTIVE' ? 'success' : s === 'RECLAIMED' ? 'info' : 'warning'
}
function ledStatusLabel(s) {
  return s === 'ACTIVE' ? '生效中' : s === 'RECLAIMED' ? '已回收' : '已过期'
}
function taskType(s) {
  return s === 'PASSED' ? 'success' : s === 'ACTIVE' ? 'warning'
    : s === 'REJECTED' ? 'danger' : 'info'
}
function taskLabel(s) {
  return ({ ACTIVE: '待签', PASSED: '已签', REJECTED: '驳回', PENDING: '未到',
    SKIPPED: '跳过' })[s] || s
}

// ---------- 挂载 ----------
watch(tab, (t) => {
  if (t === 'precheck' && !rules.value.length) loadRules()
  if (t === 'reclaim' && !ledger.value.length) loadLedger()
  if (t === 'requests' && !windows.value.length) loadWindows()
  if (t === 'outwindow') loadOutWindow()
  if (t === 'review') loadLedgerRows()
})

onMounted(async () => {
  await loadCommon()
  loadRequests()
  if (tab.value === 'precheck') loadRules()
  if (tab.value === 'reclaim') loadLedger()
  if (tab.value === 'requests') loadWindows()
  if (tab.value === 'outwindow') loadOutWindow()
  if (tab.value === 'review') loadLedgerRows()
})
</script>

<style scoped>
.pr-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.muted { color: #909399; font-size: 12px; }
h4 { margin: 14px 0 8px; }
</style>
