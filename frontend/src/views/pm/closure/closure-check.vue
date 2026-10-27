<template>
  <div class="closure-check">
    <el-card style="margin-bottom: 12px;">
      <div class="hint">
        结项检查（S-4.8-07 / C-4.8-05 / BR-4.8-27）：PM 发起结项生成检查单，六项自动校验落数值证据快照
        （WBS 完成度/未过里程碑/成本偏差率/IP 协议为自动项；交付物验收、文档归档为人工项，勾通过须上传凭证附件）。
        强制项全过方可提交 PMO 审批；非强制自定义行未过可走「有条件结项」（遗留工单 90 天闭环，逾期升级 PMO）。
        提交后检查单冻结留档；驳回或撤回退回执行中可改。
      </div>
    </el-card>

    <el-card style="margin-bottom: 12px;">
      <template #header>
        <div class="card-head">
          <b>项目</b>
          <div>
            <el-input v-model="projectId" placeholder="项目 ID" style="width: 260px;" clearable />
            <el-button type="primary" style="margin-left: 8px;" @click="load">加载检查单</el-button>
          </div>
        </div>
      </template>
      <el-alert v-if="!hasCheck" type="info" :closable="false" show-icon
        title="该项目尚无结项检查单。发起结项将生成检查单并把项目置为「结项中（CLOSING）」——期间变更发起被拦，工时/成本可补录。">
        <template #default>
          <el-button type="primary" size="small" style="margin-top: 8px;"
            :disabled="!projectId" @click="doInitiate">发起结项</el-button>
        </template>
      </el-alert>
      <template v-else>
        <el-descriptions :column="4" size="small" border style="margin-bottom: 12px;">
          <el-descriptions-item label="状态">
            <el-tag :type="check.status === 'FROZEN' ? 'warning' : 'info'" size="small">
              {{ check.status === 'FROZEN' ? '审批中' : '起草中' }}
            </el-tag>
            <el-tag v-if="check.conditionalFlag === '1'" type="warning" size="small"
              style="margin-left: 4px;">有条件结项</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="提交人">{{ check.submitBy || '—' }}</el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ fmtTs(check.submitDate) }}</el-descriptions-item>
          <el-descriptions-item label="结算状态">
            <el-tag :type="settled ? 'success' : 'danger'" size="small">
              {{ settled ? '已结算' : '未结算（提交前须完成 11.12.2）' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <div class="ops">
          <el-button size="small" :disabled="check.status !== 'DRAFT'" @click="doResubmit">重新校验</el-button>
          <el-button size="small" type="primary" :disabled="check.status !== 'DRAFT'"
            @click="doSubmit">提交 PMO 审批</el-button>
          <el-button size="small" type="warning" :disabled="check.status !== 'FROZEN'"
            @click="doWithdraw">撤回结项</el-button>
        </div>
      </template>
    </el-card>

    <el-card v-if="hasCheck" style="margin-bottom: 12px;">
      <template #header><b>检查项（六项固定 + 自定义）</b></template>
      <el-table :data="items" size="small" border>
        <el-table-column prop="lineNo" label="#" width="46" align="center" />
        <el-table-column prop="itemName" label="检查项" min-width="200" />
        <el-table-column label="性质" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.mandatoryFlag === '1' ? 'danger' : 'info'">
              {{ row.mandatoryFlag === '1' ? '强制' : '非强制' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="80" align="center">
          <template #default="{ row }">{{ row.autoFlag === '1' ? '自动' : '人工' }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.result === 'PASS' ? 'success'
              : row.result === 'FAIL' ? 'danger' : 'info'">{{ row.result }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数值证据" min-width="220">
          <template #default="{ row }">
            <code v-if="row.evidenceJson" class="ev">{{ row.evidenceJson }}</code>
            <span v-else-if="row.autoFlag === '0'">
              {{ row.attachIds ? '附件已传' : '待上传凭证附件' }}
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" align="center">
          <template #default="{ row }">
            <template v-if="check.status === 'DRAFT' && row.autoFlag === '0'">
              <el-button size="small" type="success" link
                :disabled="row.result === 'PASS'"
                @click="confirmItem(row, 'PASS')">确认通过</el-button>
              <el-button size="small" type="danger" link
                @click="confirmItem(row, 'FAIL')">标未过</el-button>
            </template>
            <el-button v-if="row.itemKey && row.itemKey.startsWith('CUSTOM_')"
              size="small" type="danger" link :disabled="check.status !== 'DRAFT'"
              @click="removeItem(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 10px;">
        <el-input v-model="newItemName" placeholder="自定义非强制检查项名称（未过走遗留通道）"
          style="width: 320px;" size="small" />
        <el-button size="small" style="margin-left: 8px;" :disabled="check.status !== 'DRAFT'"
          @click="addItem">添加检查项</el-button>
      </div>
    </el-card>

    <el-card v-if="hasCheck">
      <template #header>
        <div class="card-head">
          <b>遗留跟踪工单（有条件结项，90 天闭环 BR-4.8-28）</b>
          <el-button v-if="check.status === 'FROZEN'" size="small" @click="remediationVisible = true">
            登记遗留项（PMO）
          </el-button>
        </div>
      </template>
      <el-table :data="remediations" size="small" border>
        <el-table-column prop="content" label="遗留事项" min-width="220" />
        <el-table-column prop="ownerName" label="责任人" width="110" />
        <el-table-column label="期限" width="170">
          <template #default="{ row }">{{ fmtTs(row.dueDate) || '审批通过后生成（+90 天）' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'DONE' ? 'success'
              : row.status === 'OPEN' ? 'warning' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="逾期升级" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.escalatedFlag === '1'" type="danger" size="small">已升级</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="remediationVisible" title="登记遗留项（审批通过时转 OPEN，期限 +90 天）"
      width="640px">
      <el-table :data="remediationDrafts" size="small" border>
        <el-table-column label="遗留事项" min-width="260">
          <template #default="{ row }">
            <el-input v-model="row.content" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="责任人 ID" width="160">
          <template #default="{ row }">
            <el-input v-model="row.ownerId" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="责任人姓名" width="130">
          <template #default="{ row }">
            <el-input v-model="row.ownerName" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="" width="70" align="center">
          <template #default="{ $index }">
            <el-button type="danger" link @click="remediationDrafts.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" style="margin-top: 8px;" @click="remediationDrafts.push({})">+ 加一行</el-button>
      <template #footer>
        <el-button @click="remediationVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRemediationDrafts">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/pm/closure'

const projectId = ref('')
const check = ref({})
const items = ref([])
const remediations = ref([])
const settlement = ref({})
const newItemName = ref('')
const remediationVisible = ref(false)
const remediationDrafts = ref([])

const hasCheck = computed(() => !!check.value && check.value.id)
const settled = computed(() => !!settlement.value && !!settlement.value.id)

async function load() {
  if (!projectId.value) return
  const { data } = await api.closureDetail(projectId.value)
  check.value = data.check || {}
  items.value = data.items || []
  remediations.value = data.remediations || []
  settlement.value = data.settlement || {}
}

async function doInitiate() {
  await api.initiateClosure(projectId.value)
  ElMessage.success('结项已发起，检查单已生成')
  load()
}

async function doResubmit() {
  await api.resubmitClosure(projectId.value)
  ElMessage.success('已重新校验（自动项刷新，人工项保留）')
  load()
}

async function doSubmit() {
  await ElMessageBox.confirm('提交后检查单冻结并进入 PMO 审批，确认提交？', '提交结项审批',
    { type: 'warning' })
  await api.submitClosure(projectId.value)
  ElMessage.success('已提交 PMO 审批')
  load()
}

async function doWithdraw() {
  await ElMessageBox.confirm('撤回后项目回到执行中，检查单解锁可改，确认撤回？', '撤回结项',
    { type: 'warning' })
  await api.withdrawClosure(projectId.value)
  ElMessage.success('已撤回，项目回到执行中')
  load()
}

async function confirmItem(row, result) {
  let attachIds = row.attachIds || ''
  if (result === 'PASS') {
    // 凭证附件必传：弹文件选择
    const file = await pickFile()
    if (!file) return
    await api.uploadAttach(file, 'PROJECT_CLOSURE', row.id)
    attachIds = 'uploaded'
  }
  await api.confirmItem(row.id, { result, attachIds })
  ElMessage.success(result === 'PASS' ? '已确认通过' : '已标记未过（可提交前再改）')
  load()
}

function pickFile() {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.onchange = () => resolve(input.files && input.files[0])
    input.oncancel = () => resolve(null)
    input.click()
  })
}

async function addItem() {
  if (!newItemName.value.trim()) return ElMessage.warning('名称必填')
  await api.addCustomItem(projectId.value, { itemName: newItemName.value.trim() })
  newItemName.value = ''
  ElMessage.success('已添加（非强制）')
  load()
}

async function removeItem(row) {
  await api.removeCustomItem(row.id)
  ElMessage.success('已删除')
  load()
}

async function saveRemediationDrafts() {
  const items2 = remediationDrafts.value.filter(r => r.content && r.ownerId)
  if (!items2.length) return ElMessage.warning('至少一行有效（描述+责任人）')
  await api.saveRemediations(projectId.value, items2)
  remediationVisible.value = false
  remediationDrafts.value = []
  ElMessage.success('遗留项草稿已保存（审批通过时转 OPEN）')
  load()
}

function fmtTs(v) {
  if (!v) return ''
  return String(v).replace('T', ' ').slice(0, 19)
}
</script>

<style scoped>
.hint { color: #909399; font-size: 12px; line-height: 1.7; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.ops { margin-bottom: 10px; }
.ev { background: #f5f7fa; padding: 2px 6px; border-radius: 3px; font-size: 12px; }
</style>
