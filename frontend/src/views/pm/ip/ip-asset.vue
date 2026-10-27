<template>
  <div class="ip-asset">
    <el-card style="margin-bottom: 12px;">
      <div class="hint">
        IP 资产建档（FR-4.8-6-5，BR-4.8-26）：成果确认（CONFIRMED）时自动生成档案（同一成果仅一份，幂等）。
        保护期限自动计算：专利 20 年 / 软著 50 年（自完成日起），技术秘密与技术文档不设期限；
        届满前 90 天起日频 L4 提醒续展。档案只增不改（权属变更须走仲裁裁决回写成果后重新确认）。
        申请状态四态（未申请/已递交/已授权/已驳回）由研发负责人/法务更新，每次变更写状态历史留痕。
      </div>
    </el-card>

    <el-card style="margin-bottom: 12px;">
      <template #header><b>筛选</b></template>
      <el-form inline size="default">
        <el-form-item label="类型">
          <el-select v-model="filters.type" clearable placeholder="全部" style="width: 140px;">
            <el-option value="PATENT" label="专利" />
            <el-option value="SOFT_CP" label="软著" />
            <el-option value="TRADE_SECRET" label="技术秘密" />
            <el-option value="TECH_DOC" label="技术文档" />
          </el-select>
        </el-form-item>
        <el-form-item label="申请状态">
          <el-select v-model="filters.applyStatus" clearable placeholder="全部" style="width: 140px;">
            <el-option value="NOT_APPLIED" label="未申请" />
            <el-option value="SUBMITTED" label="已递交" />
            <el-option value="GRANTED" label="已授权" />
            <el-option value="REJECTED" label="已驳回" />
          </el-select>
        </el-form-item>
        <el-form-item label="届满≤">
          <el-date-picker v-model="filters.expireBefore" type="date" value-format="YYYY-MM-DD"
            placeholder="到期日之前" style="width: 160px;" />
        </el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
      </el-form>
    </el-card>

    <el-card>
      <template #header><b>IP 资产档案台账</b></template>
      <el-table :data="list" size="small" border>
        <el-table-column prop="achieveNo" label="成果编号" width="150" />
        <el-table-column prop="name" label="成果名称" min-width="160" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">{{ typeName(row.type) }}</template>
        </el-table-column>
        <el-table-column prop="ownershipResult" label="权属" min-width="140" />
        <el-table-column prop="protectExpireDate" label="保护期届满" width="120">
          <template #default="{ row }">{{ row.protectExpireDate || '不设期限' }}</template>
        </el-table-column>
        <el-table-column label="剩余(天)" width="90">
          <template #default="{ row }">
            <span v-if="row.expireDaysLeft != null" :style="{ color: row.expireDaysLeft <= 90 ? '#F56C6C' : '' }">
              {{ row.expireDaysLeft }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="申请状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="applyTag(row.applyStatus)">{{ applyName(row.applyStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openStatus(row)">更新状态</el-button>
            <el-button link type="info" size="small" @click="openLog(row)">状态历史</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 更新申请状态 -->
    <el-dialog v-model="statusVisible" title="更新申请状态" width="420px">
      <el-form label-width="90px">
        <el-form-item label="申请状态" required>
          <el-select v-model="statusForm.applyStatus" style="width: 100%;">
            <el-option value="NOT_APPLIED" label="未申请" />
            <el-option value="SUBMITTED" label="已递交" />
            <el-option value="GRANTED" label="已授权" />
            <el-option value="REJECTED" label="已驳回" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="statusForm.remark" type="textarea" :rows="2" placeholder="变更说明（留痕）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="statusVisible = false">取消</el-button>
        <el-button type="primary" @click="doStatus">更新</el-button>
      </template>
    </el-dialog>

    <!-- 状态历史 -->
    <el-dialog v-model="logVisible" title="申请状态变更历史" width="560px">
      <el-table :data="logs" size="small" border>
        <el-table-column label="从" width="110">
          <template #default="{ row }">{{ row.fromStatus ? applyName(row.fromStatus) : '—' }}</template>
        </el-table-column>
        <el-table-column label="到" width="110">
          <template #default="{ row }">{{ applyName(row.toStatus) }}</template>
        </el-table-column>
        <el-table-column prop="operator" label="操作人" width="120" />
        <el-table-column prop="operateDate" label="时间" width="160" />
        <el-table-column prop="remark" label="备注" min-width="120" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as api from '@/api/pm/ip'

const list = ref([])
const logs = ref([])
const statusVisible = ref(false)
const logVisible = ref(false)
const filters = ref({ type: '', applyStatus: '', expireBefore: '' })
const statusForm = ref({ assetId: '', applyStatus: 'SUBMITTED', remark: '' })

const TYPE_NAMES = { PATENT: '专利', SOFT_CP: '软著', TRADE_SECRET: '技术秘密', TECH_DOC: '技术文档' }
const APPLY_NAMES = { NOT_APPLIED: '未申请', SUBMITTED: '已递交', GRANTED: '已授权', REJECTED: '已驳回' }
const typeName = t => TYPE_NAMES[t] || t
const applyName = s => APPLY_NAMES[s] || s
const applyTag = s => ({ NOT_APPLIED: 'info', SUBMITTED: 'warning', GRANTED: 'success', REJECTED: 'danger' }[s] || 'info')

async function load() {
  const { data } = await api.assetList(filters.value)
  list.value = data?.items || []
}

function openStatus(row) { statusForm.value = { assetId: row.id, applyStatus: row.applyStatus, remark: '' }; statusVisible.value = true }
async function doStatus() { await api.updateApplyStatus(statusForm.value.assetId, statusForm.value); ElMessage.success('状态已更新（已留痕）'); statusVisible.value = false; await load() }

async function openLog(row) {
  const { data } = await api.assetStatusLog(row.id)
  logs.value = data || []
  logVisible.value = true
}

onMounted(load)
</script>

<style scoped>
.hint { color: #909399; font-size: 13px; line-height: 1.7; }
</style>
