<template>
  <div>
    <PdaShell :title="'移动报工 · ' + tabTitle" scan @scan="onScan" @sync="doSync">
      <!-- 扫码领料校验（选中工单后显示） -->
      <template v-if="current">
        <el-card shadow="never" style="margin-bottom:10px">
          <div style="font-size:13px;color:#606266">
            {{ current.moNo }} · {{ current.productName }} · 工序 {{ current.opSeq }}
            <el-tag size="small" style="margin-left:6px" :type="faType(current.firstArticleStatus)">
              首件：{{ faText(current.firstArticleStatus) }}
            </el-tag>
          </div>
        </el-card>

        <!-- 领料校验结果 -->
        <el-alert v-if="verifyResult" type="success" :closable="false" style="margin-bottom:8px"
                  :title="`领料校验通过：${verifyResult.itemCode}`"
                  :description="verifyResult.warning || verifyResult.overReason || ''" />
        <el-alert v-if="verifyError" type="error" :closable="false" style="margin-bottom:8px"
                  :title="verifyError" />

        <!-- 首件提交（无记录时强制） -->
        <el-card v-if="faState === 'NONE' || faState === 'PENDING'" shadow="never" style="margin-bottom:10px">
          <template #header>
            <span style="font-weight:600">首件报工（BR-4.11-1-3 强制）</span>
          </template>
          <el-input v-model="ctqJson" type="textarea" :rows="2"
                    placeholder='CTQ 数据 JSON，如 {"dimension":"42.0mm"}' />
          <div style="margin-top:8px;display:flex;gap:8px">
            <el-input v-model="photoUrl" placeholder="首件照片 URL" style="flex:1" />
            <el-button size="small" @click="submitFa">提交首件</el-button>
          </div>
          <div v-if="faOffline" style="margin-top:6px">
            <el-switch v-model="faOfflineFlag" active-text="离线条件放行（BR-4.11-10，联网须复核）" />
          </div>
          <div v-if="faState === 'PENDING'" style="margin-top:8px;color:#909399;font-size:12px">
            待 IPQC 确认 —— 确认通过后方可批量报工
          </div>
        </el-card>

        <!-- 批量报工录入 -->
        <el-form label-width="90px" size="small">
          <el-form-item label="合格产出">
            <el-input-number v-model="goodQty" :min="0" :precision="0" style="width:160px" />
          </el-form-item>
          <el-form-item label="不良产出">
            <el-input-number v-model="ngQty" :min="0" :precision="0" style="width:160px" />
          </el-form-item>
          <el-form-item label="幂等键">
            <el-input v-model="idemKey" placeholder="工单+工序+时间戳+设备（防重复过账）" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="doReport">提交报工</el-button>
          </el-form-item>
        </el-form>

        <el-alert v-if="reportResult" :type="reportResult.type" :closable="false"
                  :title="reportResult.title" :description="reportResult.desc" />
      </template>
    </PdaShell>
  </div>
</template>

<script setup>
/**
 * 13.1.2 工序报工（拆分自原移动报工操作台）：扫码领料校验 + 首件提交 + 批量录入。
 * 工单选中态（current/faState/idemKey）与首件标签辅助见 useReporting.js。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PdaShell from '../_pda/PdaShell.vue'
import { verifyMaterialApi, submitFirstArticleApi, reportOutputApi } from '@/api/iot/iot'
import { useReporting } from './useReporting'

const route = useRoute()
const tabTitle = computed(() => route.meta.title || '移动报工')

const { current, faState, idemKey, faType, faText, doSync } = useReporting()

const verifyResult = ref(null)
const verifyError = ref('')
const ctqJson = ref('')
const photoUrl = ref('')
const faOfflineFlag = ref(true)
const faOffline = computed(() => faState.value === 'NONE')
const goodQty = ref(0)
const ngQty = ref(0)
const submitting = ref(false)
const reportResult = ref(null)
const faId = ref(null)

async function onScan(code) {
  // 扫码即领料校验（13.1.2 FR-4.11-1-2 四校验）
  if (!current.value) {
    ElMessage.warning('请先在工单列表选择工单')
    return
  }
  verifyResult.value = null
  verifyError.value = ''
  try {
    const r = await verifyMaterialApi({
      dispatchId: current.value.dispatchId,
      scanCode: code,
      qty: goodQty.value > 0 ? goodQty.value : 10,
      reason: null,
    })
    verifyResult.value = r.data
  } catch (e) {
    verifyError.value = e?.response?.data?.message || e.message || '校验失败'
  }
}

async function submitFa() {
  try {
    const r = await submitFirstArticleApi({
      dispatchId: current.value.dispatchId,
      ctqJson: ctqJson.value,
      photoUrl: photoUrl.value,
      offline: faOfflineFlag.value,
    })
    faState.value = r.data.status
    faId.value = r.data.id
    ElMessage.success('首件已提交，待 IPQC 确认')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '提交失败')
  }
}

async function doReport() {
  submitting.value = true
  try {
    const r = await reportOutputApi({
      dispatchId: current.value.dispatchId,
      goodQty: goodQty.value,
      ngQty: ngQty.value,
      idempotentKey: idemKey.value,
      deviceCode: 'PDA-WEB',
      overprodAck: false,
    })
    const d = r.data
    if (d.duplicate) {
      reportResult.value = { type: 'warning', title: '重复提交已去重', desc: d.message }
    } else if (d.status === 'PENDING_REVIEW') {
      reportResult.value = { type: 'warning', title: '超产待车间主任审核', desc: '审核通过后过账' }
    } else if (d.status === 'CONFIRMED') {
      reportResult.value = {
        type: d.qualityAlert ? 'warning' : 'success',
        title: '报工已确认',
        desc: d.qualityAlert ? '连续 5 不良质量告警已生成' : '',
      }
    }
    faState.value = 'CONFIRMED'
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '报工失败')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  idemKey.value = `PDA-${Date.now()}`
})
</script>
