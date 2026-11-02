<template>
  <div>
    <PdaShell :title="'移动审批 · ' + tabTitle" :scan="false" @sync="doSync">
      <el-radio-group v-model="tab" size="small" style="margin-bottom:10px">
        <el-radio-button value="inout">出入审批</el-radio-button>
        <el-radio-button value="exception">异常审批</el-radio-button>
        <el-radio-button value="concession">让步签审</el-radio-button>
        <el-radio-button value="message">消息待办</el-radio-button>
      </el-radio-group>

      <!-- 签审双因子弹窗（BR-4.11-45：密码二次确认 + 终端注册态） -->
      <el-alert type="info" :closable="false" style="margin-bottom:8px"
                :title="`签审双因子：密码二次确认${webAuthn ? ' + WebAuthn 生物识别可用' : '（WebAuthn 不可用，密码必选）'}`" />

      <el-table :data="filtered" size="small" v-loading="loading">
        <el-table-column prop="title" label="待办" show-overflow-tooltip />
        <el-table-column prop="assignee" label="处理人" width="110" />
        <el-table-column label="财务签署" width="76">
          <template #default="{ row }">
            <el-tag v-if="row.needFinSign === '1'" size="small" type="danger">需</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dueAt" label="超时计时" width="150" />
        <el-table-column prop="status" label="状态" width="86" />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button size="small" type="success" link @click="openSign(row, true)">通过</el-button>
              <el-button size="small" type="danger" link @click="openSign(row, false)">驳回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <div style="margin-top:8px;display:flex;gap:8px">
        <el-button size="small" @click="load">刷新</el-button>
        <el-button size="small" @click="scanEscalate">30min 超时升级扫描</el-button>
      </div>

      <!-- 签审弹窗 -->
      <el-dialog v-model="signDlg" title="移动签审（双因子）" width="400px">
        <el-form label-width="80px" size="small">
          <el-form-item label="审批意见">
            <el-input v-model="signOpinion" placeholder="意见（通过/驳回说明）" />
          </el-form-item>
          <el-form-item label="登录密码">
            <el-input v-model="signPwd" type="password" show-password placeholder="二次输入登录密码" />
          </el-form-item>
          <el-form-item label="终端码">
            <el-input v-model="signDevice" placeholder="已注册设备编码" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="signDlg = false">取消</el-button>
          <el-button type="primary" :loading="signing" @click="submitSign">签审提交</el-button>
        </template>
      </el-dialog>

      <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:8px"
                :title="result.title" :description="result.desc" />
    </PdaShell>
  </div>
</template>

<script setup>
/**
 * 13.7 移动审批四叶（add-iot-mobile）：出入/异常/让步/消息待办统一聚合 +
 * 双因子签审弹窗（密码二次确认 + 终端注册态，BR-4.11-45）。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PdaShell from '../_pda/PdaShell.vue'
import {
  todoAggregateApi, todoHandleApi, todoScanEscalateApi, verifySignApi,
} from '@/api/iot/todo'

const route = useRoute()
const tab = computed(() => route.meta.tab || 'inout')
const tabTitle = computed(() => route.meta.title || '移动审批')

const TYPE_MAP = { inout: 'APPROVAL', exception: 'EXCEPTION', concession: 'APPROVAL',
  message: 'REPORT' }
const loading = ref(false)
const todos = ref([])
const result = ref(null)
const signDlg = ref(false)
const signing = ref(false)
const signPwd = ref('')
const signDevice = ref('PDA-WEB')
const signOpinion = ref('')
const signRow = ref(null)
const signPass = ref(true)
const webAuthn = typeof window !== 'undefined'
    && window.PublicKeyCredential !== undefined

const filtered = computed(() => {
  const t = TYPE_MAP[tab.value]
  return todos.value.filter(x => !t || x.todoType === t
      || (tab.value === 'concession' && x.refType === 'Concession')
      || (tab.value === 'message' && x.todoType === 'REPORT'))
})

async function load() {
  loading.value = true
  try {
    const r = await todoAggregateApi({ status: 'PENDING' })
    todos.value = r.data || []
  } finally {
    loading.value = false
  }
}

function openSign(row, pass) {
  signRow.value = row
  signPass.value = pass
  signOpinion.value = pass ? '同意' : '驳回'
  signPwd.value = ''
  signDlg.value = true
}

async function submitSign() {
  if (!signPwd.value) {
    ElMessage.error('签审须二次输入登录密码（双因子）')
    return
  }
  if (!signDevice.value) {
    ElMessage.error('须携带已注册终端码（C-4.11-18）')
    return
  }
  signing.value = true
  try {
    // 双因子第一因子：终端签审校验（注册态 + 密码）
    await verifySignApi({ deviceCode: signDevice.value, password: signPwd.value })
    // 处理待办并回写原单
    await todoHandleApi(signRow.value.id, {
      opinion: signOpinion.value,
      result: signPass.value ? 'APPROVE' : 'REJECT',
    })
    signDlg.value = false
    result.value = { type: 'success',
      title: signPass.value ? '签审通过' : '签审驳回',
      desc: `待办已处理并回写原单：${signRow.value.title}` }
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '签审失败')
  } finally {
    signing.value = false
  }
}

async function scanEscalate() {
  const r = await todoScanEscalateApi()
  ElMessage.info(`升级 ${(r.data?.details || []).length} 条：${(r.data?.details || []).join('、')}`)
  load()
}

function doSync() {
  ElMessage.info('联网状态下队列已实时同步')
}

watch(tab, load)
onMounted(load)
</script>
