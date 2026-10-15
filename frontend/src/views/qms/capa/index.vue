<template>
  <div class="capa-page">
    <el-alert type="warning" :closable="false" style="margin-bottom: 12px;"
      :title="tabAlert.title" :description="tabAlert.desc" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 160px;" @change="load">
          <el-option label="立项" value="OPEN" />
          <el-option label="分析中" value="ANALYZING" />
          <el-option label="验证中" value="VERIFYING" />
          <el-option label="重新分析" value="REANALYZING" />
          <el-option label="已关闭" value="CLOSED" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="CAPA 号 / 标题 / NCR" clearable
          style="width: 220px;" @keyup.enter="load" @clear="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <span class="spacer" />
        <el-button v-if="canEdit" type="success" @click="openCreate">手工立项</el-button>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading" @row-click="openDetail" row-style="cursor:pointer">
        <el-table-column prop="capaNo" label="CAPA 号" width="150">
          <template #default="{ row }"><b>{{ row.capaNo }}</b></template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="170" show-overflow-tooltip />
        <el-table-column prop="ncrNo" label="关联 NCR" width="145" show-overflow-tooltip />
        <el-table-column label="严重度" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{ CRITICAL: 'danger', MAJOR: 'warning', MINOR: 'info' }[row.severity]">
              {{ row.severity }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="105" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{ OPEN: 'primary', ANALYZING: 'warning', VERIFYING: 'warning',
              REANALYZING: 'danger', CLOSED: 'success' }[row.status]">
              {{ { OPEN: '立项', ANALYZING: '分析中', VERIFYING: '验证中',
                   REANALYZING: '重新分析', CLOSED: '已关闭' }[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="currentStep" label="当前步" width="75" align="center" />
        <!-- 6.6.2 措施执行叶：进度列（超期标红） -->
        <el-table-column v-if="tab === 'actions'" label="措施进度" width="110" align="center">
          <template #default="{ row }">
            <span :style="{ color: row.actionOverdue ? '#F56C6C' : '', fontWeight: row.actionOverdue ? 600 : '' }">
              {{ row.actionsDone ?? 0 }}/{{ row.actionsTotal ?? 0 }}
              <template v-if="row.actionOverdue">超期</template>
            </span>
          </template>
        </el-table-column>
        <!-- 6.6.3 效果验证叶：验证指标与结论列 -->
        <el-table-column v-if="tab === 'verify'" prop="verifyIndicator" label="验证指标" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.verifyIndicator || '—' }}</template>
        </el-table-column>
        <el-table-column v-if="tab === 'verify'" label="验证结论" width="95" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.verifyResult && row.verifyResult !== 'PENDING'" size="small"
              :type="{ VALID: 'success', PARTIAL: 'warning', INVALID: 'danger' }[row.verifyResult]">
              {{ { VALID: '有效', PARTIAL: '部分有效', INVALID: '无效' }[row.verifyResult] }}
            </el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="遏制倒计时" width="140">
          <template #default="{ row }">
            <template v-if="row.containDueTime">
              <span :style="{ color: row.containEscalated === '1' ? '#F56C6C' : '' }">
                {{ row.containEscalated === '1' ? '已升级质量总监' : row.containDueTime }}
              </span>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="时限" width="105">
          <template #default="{ row }">
            <el-tag v-if="row.overdue" type="danger" size="small">超期 {{ row.dueDate }}</el-tag>
            <span v-else>{{ row.dueDate || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">详情</el-button>
            <el-button v-if="tab === 'report'" link type="primary" @click.stop="printReport(row)">报告</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top: 10px; justify-content: flex-end;"
        layout="total, prev, pager, next" :total="total"
        v-model:current-page="filters.current" :page-size="filters.size" @current-change="load" />
    </el-card>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawer"
      :title="detail.capa ? `${detail.capa.capaNo} · ${detail.capa.currentStep}` : 'CAPA 详情'"
      size="780px" destroy-on-close>
      <div v-if="detail.capa">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="标题" :span="2">{{ detail.capa.title }}</el-descriptions-item>
          <el-descriptions-item label="严重度">{{ detail.capa.severity }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ STATUS_TEXT[detail.capa.status] || detail.capa.status }}</el-descriptions-item>
          <el-descriptions-item label="关联 NCR">{{ detail.capa.ncrNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="立项时限">{{ detail.capa.dueDate || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.capa.rootCause" label="根本原因" :span="2">
            {{ detail.capa.rootCause }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.capa.rootCauseEvidence" label="验证证据" :span="2">
            {{ detail.capa.rootCauseEvidence }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.capa.verifyIndicator" label="验证指标" :span="2">
            {{ detail.capa.verifyIndicator }}（目标 {{ detail.capa.verifyTarget || '—' }}）
            <template v-if="detail.capa.verifyData"><br />{{ detail.capa.verifyData }}</template>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.capa.originCapaId" label="复发关联" :span="2">
            <el-tag type="warning" size="small">复发（BR-4.12-36）</el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 8D 步骤 -->
        <h4 class="sec">8D 步骤（顺序推进，不可跳序）</h4>
        <el-steps :active="stepActive" align-center finish-status="success" size="small">
          <el-step v-for="st in (detail.steps || [])" :key="st.stepCode" :title="st.stepCode"
            :description="st.status === 'DONE' ? (st.ownerName || '完成') : '待办'" />
        </el-steps>

        <!-- 当前步骤推进 -->
        <div v-if="canEdit && detail.capa.status !== 'CLOSED'" class="op-block" style="margin-top:12px;">
          <div style="font-weight:600;margin-bottom:8px;">
            推进 {{ detail.capa.currentStep }}
            <span style="color:#909399;font-weight:400;font-size:12px;">
              （{{ STEP_HINT[detail.capa.currentStep] || '' }}）
            </span>
          </div>
          <el-input v-model="stepForm.ownerName" placeholder="步骤负责人（必填）" style="margin-bottom:8px;" />
          <el-input v-model="stepForm.output" type="textarea" :rows="2" placeholder="步骤产出物（必填）" />

          <!-- D1 成员清单（结构化） -->
          <template v-if="detail.capa.currentStep === 'D1'">
            <div style="margin-top:8px;font-size:12px;color:#606266;">8D 团队成员（至少 1 名，指定 Leader；应含质量/技术/生产/采购领域代表）</div>
            <div v-for="(mem, i) in teamForm" :key="i" style="display:flex;gap:6px;margin-top:6px;align-items:center;">
              <el-input v-model="mem.name" placeholder="姓名" style="width:110px;" size="small" />
              <el-select v-model="mem.domain" placeholder="领域" style="width:100px;" size="small">
                <el-option v-for="d in DOMAINS" :key="d" :label="d" :value="d" />
              </el-select>
              <el-input v-model="mem.duty" placeholder="职责" style="flex:1;" size="small" />
              <el-radio-group v-model="leaderIdx" size="small">
                <el-radio-button :value="i">Leader</el-radio-button>
              </el-radio-group>
              <el-button link type="danger" @click="teamForm.splice(i, 1)">删</el-button>
            </div>
            <el-button size="small" style="margin-top:6px;" @click="teamForm.push({ name: '', domain: '质量', duty: '' })">+ 添加成员</el-button>
          </template>

          <!-- D4 根因 -->
          <template v-if="detail.capa.currentStep === 'D4'">
            <el-input v-model="stepForm.rootCause" type="textarea" :rows="2"
              placeholder="根本原因：5Why/鱼骨图分析过程（浅层描述会被退回）" style="margin-top:8px;" />
            <el-input v-model="stepForm.rootCauseEvidence" type="textarea" :rows="2"
              placeholder="数据/实验验证证据（必填，BR-4.12-33）" style="margin-top:8px;" />
          </template>

          <!-- D7 三件套 + 结论（FR-4.12-5-4 数据支撑） -->
          <template v-if="detail.capa.currentStep === 'D7'">
            <el-input v-model="stepForm.verifyIndicator" placeholder="验证指标（必填，如「批次不合格率」）"
              style="margin-top:8px;" size="small" />
            <el-input v-model="stepForm.verifyTarget" placeholder="验证目标（必填，如「≤0.5%」）"
              style="margin-top:6px;" size="small" />
            <div style="display:flex;gap:6px;margin-top:6px;">
              <el-input v-model="stepForm.verifyBefore" placeholder="措施前值（必填）" size="small" />
              <el-input v-model="stepForm.verifyAfter" placeholder="措施后值（必填）" size="small" />
              <el-input v-model="stepForm.verifyPeriod" placeholder="采集周期（必填，如 3 个月）" size="small" />
            </div>
            <el-radio-group v-model="stepForm.verifyResult" style="margin-top:8px;">
              <el-radio-button value="VALID">有效</el-radio-button>
              <el-radio-button value="PARTIAL">部分有效</el-radio-button>
              <el-radio-button value="INVALID">无效（回 D4）</el-radio-button>
            </el-radio-group>
          </template>

          <!-- D8 沉淀物勾选清单 -->
          <template v-if="detail.capa.currentStep === 'D8'">
            <div style="margin-top:8px;font-size:12px;color:#606266;">
              经验沉淀物（至少勾选一类并填关联说明，「不可省略」FR-4.12-5-5）
            </div>
            <div v-for="s in sinkForm" :key="s.type" style="display:flex;gap:6px;margin-top:6px;align-items:center;">
              <el-checkbox v-model="s.checked">{{ SINK_LABEL[s.type] }}</el-checkbox>
              <el-input v-if="s.checked" v-model="s.note" :placeholder="s.type === 'STANDARD'
                ? '关联说明（标准修订将自动挂变更审批 BR-4.12-34）' : '关联说明（文档编号或路径）'"
                size="small" style="flex:1;" />
            </div>
          </template>

          <el-button type="primary" size="small" style="margin-top:8px;" @click="doAdvance">
            完成 {{ detail.capa.currentStep }}
          </el-button>
        </div>
        <el-alert v-if="detail.capa.status === 'CLOSED'" type="success" :closable="false"
          title="已关闭（只读归档，经验已沉淀）" style="margin-top:12px;" />

        <!-- 措施 -->
        <h4 class="sec">纠正/预防措施（{{ (detail.actions || []).length }}）</h4>
        <el-table :data="detail.actions || []" size="mini" border>
          <el-table-column label="类型" width="70" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.actionType === 'CORRECT' ? 'primary' : 'info'">
                {{ row.actionType === 'CORRECT' ? '纠正' : '预防' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="措施" min-width="170" show-overflow-tooltip />
          <el-table-column prop="ownerName" label="负责人" width="80" />
          <el-table-column prop="dueDate" label="完成时间" width="100" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'DONE' ? 'success' : 'warning'">
                {{ row.status === 'DONE' ? '已完成' : '待执行' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="标准变更" width="90" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.standardChangeFlag === '1'" size="small" type="danger">需审批</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button v-if="canEdit && row.status !== 'DONE'" link type="primary"
                @click="doCompleteAction(row)">完成</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="canEdit && detail.capa.status !== 'CLOSED'" style="margin-top:8px;">
          <el-button size="small" @click="addActionVisible = true">新增措施</el-button>
          <el-button size="small" @click="printCurrent">打印 8D 报告</el-button>
        </div>
      </div>
    </el-drawer>

    <!-- 新增措施 -->
    <el-dialog v-model="addActionVisible" title="新增纠正/预防措施" width="520px">
      <el-form label-width="100px" size="small">
        <el-form-item label="类型" required>
          <el-radio-group v-model="actionForm.actionType">
            <el-radio-button value="CORRECT">纠正</el-radio-button>
            <el-radio-button value="PREVENT">预防</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="措施描述" required>
          <el-input v-model="actionForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="负责人" required>
          <el-input v-model="actionForm.ownerName" />
        </el-form-item>
        <el-form-item label="完成时间" required>
          <el-date-picker v-model="actionForm.dueDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="预期效果" required>
          <el-input v-model="actionForm.effectDesc" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="标准/SOP 变更">
          <el-switch v-model="actionForm.standardChange" />
          <span style="font-size:12px;color:#909399;margin-left:8px;">开启后自动挂变更审批（BR-4.12-34）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addActionVisible = false">取消</el-button>
        <el-button type="primary" @click="doAddAction">创建</el-button>
      </template>
    </el-dialog>

    <!-- 手工立项 -->
    <el-dialog v-model="createVisible" title="手工立项 CAPA" width="480px">
      <el-form label-width="90px" size="small">
        <el-form-item label="标题" required>
          <el-input v-model="createForm.title" placeholder="如：客户投诉：标签错贴" />
        </el-form-item>
        <el-form-item label="严重度">
          <el-select v-model="createForm.severity" style="width:100%;">
            <el-option label="Critical" value="CRITICAL" />
            <el-option label="Major" value="MAJOR" />
            <el-option label="Minor" value="MINOR" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源">
          <el-select v-model="createForm.sourceType" style="width:100%;">
            <el-option label="投诉" value="COMPLAINT" />
            <el-option label="审核问题" value="AUDIT" />
            <el-option label="重复不合格" value="REPEAT" />
            <el-option label="手工" value="MANUAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联 NCR">
          <el-input v-model="createForm.ncrId" placeholder="可选：NCR 的 ID（自动按评审时间+5 工作日算时限）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">立项</el-button>
      </template>
    </el-dialog>

    <!-- 8D 报告打印区（隐藏 DOM，window.print） -->
    <div v-if="printRow" class="print-area">
      <h2 style="text-align:center;">8D 报告</h2>
      <table class="print-tbl">
        <tbody>
          <tr><th>CAPA 单号</th><td>{{ printRow.capaNo }}</td><th>严重度</th><td>{{ printRow.severity }}</td></tr>
          <tr><th>标题</th><td colspan="3">{{ printRow.title }}</td></tr>
          <tr><th>关联 NCR</th><td>{{ printRow.ncrNo || '—' }}</td><th>立项时限</th><td>{{ printRow.dueDate || '—' }}</td></tr>
          <tr v-if="printDetail.capa && printDetail.capa.rootCause">
            <th>根本原因</th><td colspan="3">{{ printDetail.capa.rootCause }}</td></tr>
          <tr v-if="printDetail.capa && printDetail.capa.rootCauseEvidence">
            <th>验证证据</th><td colspan="3">{{ printDetail.capa.rootCauseEvidence }}</td></tr>
          <tr v-if="printDetail.capa && printDetail.capa.verifyIndicator">
            <th>验证指标/目标</th>
            <td colspan="3">{{ printDetail.capa.verifyIndicator }}（目标 {{ printDetail.capa.verifyTarget }}）
              {{ printDetail.capa.verifyData }} · 结论 {{ printDetail.capa.verifyResult }}</td></tr>
        </tbody>
      </table>
      <h3>8D 团队</h3>
      <table class="print-tbl">
        <thead><tr><th>姓名</th><th>领域</th><th>职责</th><th>Leader</th></tr></thead>
        <tbody>
          <tr v-for="(m, i) in printTeam" :key="i">
            <td>{{ m.name }}</td><td>{{ m.domain }}</td><td>{{ m.duty }}</td><td>{{ m.leader ? '✓' : '' }}</td></tr>
          <tr v-if="!printTeam.length"><td colspan="4">（未结构化录入，详见步骤产出）</td></tr>
        </tbody>
      </table>
      <h3>D1~D8 步骤</h3>
      <table class="print-tbl">
        <thead><tr><th>步骤</th><th>负责人</th><th>完成时间</th><th>产出物</th></tr></thead>
        <tbody>
          <tr v-for="st in (printDetail.steps || [])" :key="st.stepCode">
            <td>{{ st.stepName || st.stepCode }}</td><td>{{ st.ownerName || '' }}</td>
            <td>{{ st.doneTime || '' }}</td><td>{{ st.outputDesc || '' }}</td></tr>
        </tbody>
      </table>
      <h3>纠正/预防措施</h3>
      <table class="print-tbl">
        <thead><tr><th>类型</th><th>措施</th><th>负责人</th><th>完成时间</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="a in (printDetail.actions || [])" :key="a.id">
            <td>{{ a.actionType === 'CORRECT' ? '纠正' : '预防' }}</td><td>{{ a.description }}</td>
            <td>{{ a.ownerName }}</td><td>{{ a.dueDate }}</td>
            <td>{{ a.status === 'DONE' ? '已完成' : '待执行' }}</td></tr>
        </tbody>
      </table>
      <table class="print-tbl" style="margin-top:24px;">
        <tbody>
          <tr><th style="width:120px;">CAPA 负责人签核</th><td style="height:40px;"></td>
              <th style="width:120px;">质量经理签核</th><td style="height:40px;"></td></tr>
          <tr><th>报告日期</th><td colspan="3">{{ new Date().toISOString().slice(0, 10) }}</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getCapaPageApi, getCapaDetailApi, createCapaApi,
  advanceStepApi, addActionApi, completeActionApi, getCapaByNcrApi
} from '@/api/qms/capa'
import { getNcrDetailApi } from '@/api/qms/ncr'

const route = useRoute()
const userStore = useUserStore()

const STATUS_TEXT = {
  OPEN: '立项', ANALYZING: '分析中', VERIFYING: '验证中',
  REANALYZING: '重新分析', CLOSED: '已关闭'
}
const STEP_HINT = {
  D1: '组建 8D 小组（成员清单含领域与职责，指定 Leader）',
  D2: '问题描述（What/Who/When/Where/Why/How/How many 必填）',
  D3: '临时遏制措施',
  D4: '根因分析（根本原因 + 验证证据缺一不可，浅层描述退回）',
  D5: '纠正措施制定（先录入措施）',
  D6: '措施执行（全部措施完成方可推进）',
  D7: '有效性验证（指标/目标/跟踪数据三件套必填；无效/部分有效自动回 D4）',
  D8: '关闭与经验沉淀（沉淀物至少勾选一类，标准修订自动挂审批）'
}
const DOMAINS = ['质量', '技术', '生产', '采购', '其他']
const SINK_LABEL = { FMEA: 'FMEA 更新', STANDARD: '检验标准修订', TRAINING: '培训教材', POKA_YOKE: '防错清单' }
const TAB_ALERT = {
  report: { title: '6.6.1 8D 报告：CAPA 全过程载体', desc: '列表 + 详情，支持「打印 8D 报告」正式排版（团队/步骤/根因/措施/验证结论/签核位）。' },
  actions: { title: '6.6.2 措施执行：跟踪卡点', desc: '默认只看存在未完成措施的在途 CAPA，进度列显示「已完成/总数」，含超期措施标红。' },
  verify: { title: '6.6.3 效果验证：验证与回炉工作台', desc: '默认看验证中与重新分析（回炉）的 CAPA，展示验证指标与结论；复发自动回炉（BR-4.12-35）。' }
}

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const tab = route.meta.tab || 'report'
const tabAlert = TAB_ALERT[tab] || TAB_ALERT.report
const filters = reactive({ status: '', keyword: '', current: 1, size: 10 })
// 三叶默认视图（后端 view 参数，rework-capa-evidence 4.1）
const viewParam = tab === 'actions' ? 'actions' : tab === 'verify' ? 'verify' : undefined

const myRoles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const canEdit = computed(() => myRoles.value.some(r =>
  ['ROLE_ADMIN', 'ROLE_QUALITY_ENG', 'ROLE_QUALITY_MGR'].includes(r)))

async function load() {
  loading.value = true
  try {
    const res = await getCapaPageApi({
      current: filters.current, size: filters.size,
      status: filters.status || undefined, keyword: filters.keyword || undefined,
      view: viewParam
    })
    rows.value = (res.data && res.data.records) || []
    total.value = (res.data && res.data.total) || 0
  } finally {
    loading.value = false
  }
}

// ---------- 详情 ----------
const drawer = ref(false)
const detail = ref({})
const stepForm = reactive({
  ownerName: '', output: '', rootCause: '', rootCauseEvidence: '', verifyResult: '',
  verifyIndicator: '', verifyTarget: '', verifyBefore: '', verifyAfter: '', verifyPeriod: ''
})
const teamForm = ref([{ name: '', domain: '质量', duty: '' }])
const leaderIdx = ref(0)
const sinkForm = ref(Object.keys(SINK_LABEL).map(t => ({ type: t, checked: false, note: '' })))
const stepActive = computed(() => {
  const steps = detail.value.steps || []
  return steps.filter(s => s.status === 'DONE').length
})

function resetStepForm() {
  Object.assign(stepForm, {
    ownerName: '', output: '', rootCause: '', rootCauseEvidence: '', verifyResult: '',
    verifyIndicator: '', verifyTarget: '', verifyBefore: '', verifyAfter: '', verifyPeriod: ''
  })
  teamForm.value = [{ name: '', domain: '质量', duty: '' }]
  leaderIdx.value = 0
  sinkForm.value = Object.keys(SINK_LABEL).map(t => ({ type: t, checked: false, note: '' }))
  // 回显既有结构化数据（D1/D8 被退回重做时）
  const c = detail.value.capa || {}
  if (c.teamMembers) {
    try { teamForm.value = JSON.parse(c.teamMembers) } catch { /* ignore */ }
  }
  if (c.legacySink) {
    try {
      const saved = JSON.parse(c.legacySink)
      sinkForm.value = sinkForm.value.map(s => {
        const hit = saved.find(x => x.type === s.type)
        return hit ? { type: s.type, checked: true, note: hit.note || '' } : s
      })
    } catch { /* ignore */ }
  }
}

async function openDetail(row) {
  const res = await getCapaDetailApi(row.id)
  detail.value = res.data || {}
  resetStepForm()
  drawer.value = true
}

async function doAdvance() {
  // 前端拦截：D1/D7/D8 结构化必填
  const cur = detail.value.capa.currentStep
  const payload = { stepCode: cur, ownerName: stepForm.ownerName, output: stepForm.output }
  if (cur === 'D1') {
    const mems = teamForm.value.filter(m => m.name && m.domain && m.duty)
    if (!mems.length) { ElMessage.warning('D1 至少 1 名团队成员（姓名/领域/职责齐全）'); return }
    if (leaderIdx.value >= teamForm.value.length) { ElMessage.warning('请指定 Leader'); return }
    payload.teamMembers = JSON.stringify(teamForm.value.map((m, i) => ({ name: m.name, domain: m.domain, duty: m.duty, leader: i === leaderIdx.value })))
  }
  if (cur === 'D4') {
    payload.rootCause = stepForm.rootCause
    payload.rootCauseEvidence = stepForm.rootCauseEvidence
  }
  if (cur === 'D7') {
    if (!stepForm.verifyIndicator || !stepForm.verifyTarget
      || !stepForm.verifyBefore || !stepForm.verifyAfter || !stepForm.verifyPeriod) {
      ElMessage.warning('D7 三件套必填：验证指标 / 目标 / 跟踪数据（前值/后值/周期）')
      return
    }
    if (!stepForm.verifyResult) { ElMessage.warning('请选择验证结论'); return }
    Object.assign(payload, {
      verifyIndicator: stepForm.verifyIndicator, verifyTarget: stepForm.verifyTarget,
      verifyBefore: stepForm.verifyBefore, verifyAfter: stepForm.verifyAfter,
      verifyPeriod: stepForm.verifyPeriod, verifyResult: stepForm.verifyResult
    })
  }
  if (cur === 'D8') {
    const sinks = sinkForm.value.filter(s => s.checked)
    if (!sinks.length) { ElMessage.warning('经验沉淀不可省略：至少勾选一类沉淀物'); return }
    const noNote = sinks.find(s => !s.note)
    if (noNote) { ElMessage.warning(`沉淀物「${SINK_LABEL[noNote.type]}」须填关联说明`); return }
    payload.legacySink = JSON.stringify(sinks.map(s => ({ type: s.type, note: s.note })))
  }
  try {
    const res = await advanceStepApi(detail.value.capa.id, payload)
    const data = (res.data) || {}
    if (data.warnings && data.warnings.length) {
      data.warnings.forEach(w => ElMessage.warning(w))
    }
    if (data.pendingApproval) {
      ElMessage.info(data.message || '已发起标准变更审批，通过后再次提交完成 D8')
    } else {
      ElMessage.success(`${cur} 已完成`)
    }
    openDetail({ id: detail.value.capa.id })
    load()
  } catch { /* 拦截器已弹错 */ }
}

// ---------- 措施 ----------
const addActionVisible = ref(false)
const actionForm = reactive({
  actionType: 'CORRECT', description: '', ownerName: '',
  dueDate: '', effectDesc: '', standardChange: false
})
async function doAddAction() {
  try {
    await addActionApi(detail.value.capa.id, {
      ...actionForm, standardChangeFlag: actionForm.standardChange ? '1' : '0'
    })
    ElMessage.success('措施已创建')
    addActionVisible.value = false
    openDetail({ id: detail.value.capa.id })
  } catch { /* 拦截器已弹错 */ }
}
async function doCompleteAction(row) {
  try {
    const { value } = await ElMessageBox.prompt('完成情况/实际效果（可选）', '措施完成',
      { inputValue: row.effectDesc || '' })
    await completeActionApi(row.id, value)
    ElMessage.success('措施已完成')
    openDetail({ id: detail.value.capa.id })
  } catch { /* 取消或拦截器已处理 */ }
}

// ---------- 立项 ----------
const createVisible = ref(false)
const createForm = reactive({ title: '', severity: 'MAJOR', sourceType: 'COMPLAINT', ncrId: '' })
function openCreate() {
  createForm.title = ''
  createForm.severity = 'MAJOR'
  createForm.sourceType = 'COMPLAINT'
  createForm.ncrId = ''
  createVisible.value = true
}
async function doCreate() {
  try {
    const res = await createCapaApi({ ...createForm })
    ElMessage.success('CAPA 已立项（8 步骤已生成）')
    createVisible.value = false
    load()
    if (res.data) openDetail(res.data)
  } catch { /* 拦截器已弹错 */ }
}

// ---------- 8D 报告打印（6.6.1） ----------
const printRow = ref(null)
const printDetail = ref({})
const printTeam = computed(() => {
  const c = printDetail.value.capa
  if (c && c.teamMembers) {
    try { return JSON.parse(c.teamMembers) } catch { /* ignore */ }
  }
  return []
})
async function printReport(row) {
  printRow.value = row
  const res = await getCapaDetailApi(row.id)
  printDetail.value = res.data || {}
  doPrint(row.capaNo)
}
function printCurrent() {
  const c = detail.value.capa
  printRow.value = { capaNo: c.capaNo, title: c.title, severity: c.severity, ncrNo: c.ncrNo, dueDate: c.dueDate }
  printDetail.value = detail.value
  doPrint(c.capaNo)
}
function doPrint(capaNo) {
  setTimeout(() => {
    const original = document.title
    document.title = `8D报告-${capaNo}`
    window.print()
    document.title = original
    printRow.value = null
  }, 300)
}

// ---------- 待办直达（6.2，query.ncrId） ----------
async function handleTodoQuery() {
  const ncrId = route.query.ncrId
  if (!ncrId) return
  try {
    const res = await getCapaByNcrApi(String(ncrId))
    if (res.data) {
      ElMessage.info('该 NCR 已有 CAPA，打开详情（幂等）')
      openDetail(res.data)
      return
    }
    // 无 CAPA → 打开立项对话框并从 NCR 预填
    const ncrRes = await getNcrDetailApi(String(ncrId))
    const n = (ncrRes.data && ncrRes.data.ncr) || {}
    createForm.title = `NCR ${n.ncrNo || ''}：${n.defectItem || n.defectDesc || '重复不合格整改'}`.trim()
    createForm.severity = n.severity || 'MAJOR'
    createForm.sourceType = 'REPEAT'
    createForm.ncrId = String(ncrId)
    createVisible.value = true
    ElMessage.success('已从待办预填立项信息，确认即可立项')
  } catch { /* 静默降级，不影响页面加载 */ }
}

onMounted(async () => {
  if (!userStore.userInfo) {
    try { await userStore.getUserInfo() } catch { /* ignore */ }
  }
  load()
  handleTodoQuery()
})
</script>

<style scoped>
.capa-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.spacer { flex: 1; }
.sec { margin: 16px 0 8px; font-size: 14px; color: #303133; }
.op-block { background: #fafafa; border: 1px solid #ebeef5; border-radius: 4px; padding: 12px; }
</style>

<style>
/* 打印区（非 scoped：window.print 需全局生效） */
.print-area { display: none; }
@media print {
  body * { visibility: hidden; }
  .print-area, .print-area * { visibility: visible; }
  .print-area { display: block; position: absolute; left: 0; top: 0; width: 100%; padding: 24px; }
  .print-tbl { width: 100%; border-collapse: collapse; margin-bottom: 12px; }
  .print-tbl th, .print-tbl td { border: 1px solid #333; padding: 6px 8px; font-size: 12px; text-align: left; }
  .print-tbl th { background: #f0f0f0; }
}
</style>
