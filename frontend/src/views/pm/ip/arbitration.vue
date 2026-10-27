<template>
  <div class="ip-arb">
    <el-card style="margin-bottom: 12px;">
      <div class="hint">
        权属冲突仲裁（FR-4.8-6-4，C-4.8-04）：多方权属主张强制走仲裁。
        双入口：确认环节"多方主张"异议自动带出（主路径）+ 法务独立申请（兜底）；同一成果仅一个在途仲裁单（409 防重）。
        流程：双方提交主张依据 → 法务仲裁出具意见 → 双方确认 → 裁决回写成果权属（覆盖原判定，成果回待确认重确认）。
        意见不被接受 → 升级外部（人工跟进）。维护权限：法务/管理员。
      </div>
    </el-card>

    <el-card style="margin-bottom: 12px;">
      <template #header><b>法务独立申请仲裁（兜底入口）</b></template>
      <el-form inline size="default">
        <el-form-item label="成果 ID" required>
          <el-input v-model="manualForm.achieveId" placeholder="输入成果 ID" style="width: 320px;" />
        </el-form-item>
        <el-button type="primary" :disabled="!manualForm.achieveId" @click="doCreateManual">创建仲裁单</el-button>
      </el-form>
    </el-card>

    <el-card>
      <template #header><b>仲裁单台账</b></template>
      <el-table :data="list" size="small" border>
        <el-table-column prop="arbNo" label="仲裁单号" width="150" />
        <el-table-column prop="achieveId" label="成果" min-width="120" />
        <el-table-column label="来源" width="110">
          <template #default="{ row }">{{ row.source === 'CONFIRM_OBJECTION' ? '确认异议' : '独立申请' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="opinion" label="仲裁意见" min-width="160" />
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openClaim(row)">提交主张</el-button>
            <el-button link type="primary" size="small" :disabled="!isLegalReviewing(row)"
              @click="openOpinion(row)">出具意见</el-button>
            <el-button link type="success" size="small" :disabled="!isOpinionIssued(row)"
              @click="doConfirm(row, 'ACCEPT')">确认接受</el-button>
            <el-button link type="warning" size="small" :disabled="!isOpinionIssued(row)"
              @click="doConfirm(row, 'REJECT')">拒绝升级</el-button>
            <el-button link type="danger" size="small" :disabled="!isPartyConfirmed(row)"
              @click="openAdjudicate(row)">裁决回写</el-button>
          </template>
        </el-table-column>
        <el-table-column label="主张明细" type="expand">
          <template #default="{ row }">
            <el-table :data="row.claims || []" size="small" border>
              <el-table-column label="主张方" width="110">
                <template #default="{ row: c }">{{ c.claimant === 'PARTY_A' ? '甲方' : '甲方' }}</template>
              </el-table-column>
              <el-table-column prop="claimantName" label="名称" width="160" />
              <el-table-column prop="claimRate" label="主张比例%" width="110" />
              <el-table-column prop="evidence" label="依据说明" min-width="200" />
            </el-table>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 提交主张 -->
    <el-dialog v-model="claimVisible" title="提交权属主张" width="480px">
      <el-form label-width="90px">
        <el-form-item label="主张方" required>
          <el-select v-model="claimForm.claimant" style="width: 100%;">
            <el-option value="PARTY_A" label="甲方（公司）" />
            <el-option value="PARTY_B" label="乙方（合作方/个人）" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="claimForm.claimantName" placeholder="主张方全称" />
        </el-form-item>
        <el-form-item label="主张比例%">
          <el-input-number v-model="claimForm.claimRate" :min="0" :max="100" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="依据说明">
          <el-input v-model="claimForm.evidence" type="textarea" :rows="3" placeholder="权属主张依据（协议条款/贡献证据等）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="claimVisible = false">取消</el-button>
        <el-button type="primary" @click="doClaim">提交</el-button>
      </template>
    </el-dialog>

    <!-- 出具意见 -->
    <el-dialog v-model="opinionVisible" title="出具仲裁意见" width="480px">
      <el-input v-model="opinionForm.opinion" type="textarea" :rows="4" placeholder="法务仲裁意见" />
      <template #footer>
        <el-button @click="opinionVisible = false">取消</el-button>
        <el-button type="primary" @click="doOpinion">出具</el-button>
      </template>
    </el-dialog>

    <!-- 裁决回写 -->
    <el-dialog v-model="adjVisible" title="裁决回写（覆盖成果权属）" width="480px">
      <el-input v-model="adjForm.adjudicateResult" type="textarea" :rows="3"
        placeholder='裁决权属 JSON，如 {"company":50,"partner":50}' />
      <div class="field-hint">裁决后成果权属被覆盖，状态回到"待确认"由研发负责人重新确认</div>
      <template #footer>
        <el-button @click="adjVisible = false">取消</el-button>
        <el-button type="danger" @click="doAdjudicate">裁决回写</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as api from '@/api/pm/ip'

const list = ref([])
const manualForm = ref({ achieveId: '' })
const claimVisible = ref(false)
const opinionVisible = ref(false)
const adjVisible = ref(false)
const claimForm = ref({ arbId: '', claimant: 'PARTY_A', claimantName: '', claimRate: 100, evidence: '' })
const opinionForm = ref({ arbId: '', opinion: '' })
const adjForm = ref({ arbId: '', adjudicateResult: '' })

const STATUS_NAMES = {
  CLAIMS_SUBMITTED: '主张提交', LEGAL_REVIEWING: '法务仲裁中', OPINION_ISSUED: '意见出具',
  PARTY_CONFIRMED: '双方确认', ADJUDICATED: '已裁决', ESCALATED_EXTERNAL: '升级外部'
}
const statusName = s => STATUS_NAMES[s] || s
const statusTag = s => ({ CLAIMS_SUBMITTED: 'info', LEGAL_REVIEWING: 'warning', OPINION_ISSUED: 'warning', PARTY_CONFIRMED: 'primary', ADJUDICATED: 'success', ESCALATED_EXTERNAL: 'danger' }[s] || 'info')
const isLegalReviewing = r => r.status === 'LEGAL_REVIEWING' || r.status === 'CLAIMS_SUBMITTED'
const isOpinionIssued = r => r.status === 'OPINION_ISSUED'
const isPartyConfirmed = r => r.status === 'PARTY_CONFIRMED'

async function load() {
  const { data } = await api.arbitrationList({})
  list.value = data?.items || []
}

async function doCreateManual() {
  await api.createArbitration(manualForm.value)
  ElMessage.success('仲裁单已创建')
  manualForm.value.achieveId = ''
  await load()
}

function openClaim(row) { claimForm.value = { arbId: row.id, claimant: 'PARTY_A', claimantName: '', claimRate: 100, evidence: '' }; claimVisible.value = true }
async function doClaim() { await api.addClaim(claimForm.value.arbId, claimForm.value); ElMessage.success('主张已提交'); claimVisible.value = false; await load() }

function openOpinion(row) { opinionForm.value = { arbId: row.id, opinion: '' }; opinionVisible.value = true }
async function doOpinion() { await api.issueOpinion(opinionForm.value.arbId, opinionForm.value); ElMessage.success('意见已出具'); opinionVisible.value = false; await load() }

async function doConfirm(row, action) {
  await api.confirmOpinion(row.id, { action, reason: action === 'REJECT' ? '不接受仲裁意见，升级外部法律途径' : '' })
  ElMessage.success(action === 'REJECT' ? '已升级外部（人工跟进）' : '已确认接受意见')
  await load()
}

function openAdjudicate(row) { adjForm.value = { arbId: row.id, adjudicateResult: '' }; adjVisible.value = true }
async function doAdjudicate() { await api.adjudicate(adjForm.value.arbId, adjForm.value); ElMessage.success('裁决已回写，成果回到待确认'); adjVisible.value = false; await load() }

onMounted(load)
</script>

<style scoped>
.hint { color: #909399; font-size: 13px; line-height: 1.7; }
.field-hint { color: #c0c4cc; font-size: 12px; margin-top: 4px; }
</style>
