<template>
  <div class="standard-detail" v-loading="loading">
    <el-card>
      <template #header>
        <div class="card-header">
          <div class="hd-left">
            <el-button link type="primary" @click="$router.back()">← 返回列表</el-button>
            <span class="ttl">{{ std.standardCode || '…' }} {{ std.name }}</span>
            <el-tag v-if="std.status" size="small" :type="stdStatusTag(std.status)">{{ stdStatusName(std.status) }}</el-tag>
            <el-tag v-if="std.riskLevel" size="small">风险 {{ std.riskLevel }}</el-tag>
          </div>
          <div>
            <el-button v-if="canEdit && std.status !== 'RETIRED'" type="primary" size="small"
                       @click="verDialog = true">新建版本</el-button>
            <el-button v-if="canEdit && std.status !== 'RETIRED'" type="danger" size="small"
                       @click="doRetire">退役</el-button>
            <el-button size="small" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-row :gutter="16">
        <el-col :span="10">
          <el-card shadow="never">
            <template #header><span class="sec">版本时间线</span></template>
            <VersionTimeline :versions="versions" :double-signs="doubleSigns" />
          </el-card>
        </el-col>
        <el-col :span="14">
          <el-card shadow="never" class="sec-card">
            <template #header>
              <div class="sec-row">
                <span class="sec">检验特性</span>
                <el-select v-model="activeVersionId" size="small" style="width: 190px;"
                           placeholder="选择版本" @change="onVersionChange">
                  <el-option v-for="v in versions" :key="v.id"
                             :label="`V${v.versionNo} · ${verStatusName(v.status)}`" :value="v.id" />
                </el-select>
              </div>
            </template>
            <CharacteristicEditor v-if="activeVersionId" :key="'c' + activeVersionId"
                                  :standard-id="std.id" :version-id="activeVersionId"
                                  :editable="canEdit && activeVersion.status === 'DRAFT'"
                                  :version-payload="versionPayload"
                                  @saved="load" />
            <el-empty v-else description="请选择或新建版本" :image-size="70" />
          </el-card>

          <el-card shadow="never" class="sec-card">
            <template #header><span class="sec">适用范围</span></template>
            <ApplicabilityEditor v-if="activeVersionId" :key="'a' + activeVersionId"
                                 :standard-id="std.id" :version-id="activeVersionId"
                                 :editable="canEdit && activeVersion.status === 'DRAFT'"
                                 :version-payload="versionPayload"
                                 @saved="load" />
            <el-empty v-else description="请选择或新建版本" :image-size="70" />
          </el-card>

          <!-- 发布操作 -->
          <el-card v-if="activeVersionId" shadow="never" class="sec-card">
            <template #header><span class="sec">发布与退役</span></template>
            <div class="pub-row">
              <template v-if="activeVersion.status === 'DRAFT'">
                <el-button type="primary" :loading="saving" @click="doPublish">
                  提交发布
                </el-button>
                <span class="pub-hint">发布前校验：必填结构 + 适用范围/生效区间冲突（C-4.12-18）；含 CTQ/法规特性时质量经理 + 技术负责人双签</span>
              </template>
              <el-tag v-else-if="activeVersion.status === 'PENDING_APPROVE'" type="warning">
                审批中 — 签署请前往「审批待办」（/m/qms-approval）
              </el-tag>
              <el-tag v-else-if="activeVersion.status === 'RELEASED'" type="success">
                已发布（只读，修订请新建版本 · BR-4.12-57）
              </el-tag>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </el-card>

    <!-- 新建版本 -->
    <el-dialog v-model="verDialog" title="新建草稿版本" width="520px">
      <el-form label-width="110px">
        <el-form-item label="生效日期" required>
          <el-date-picker v-model="verForm.effectiveFrom" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="变更原因" required>
          <el-input v-model="verForm.changeReason" />
        </el-form-item>
        <el-form-item label="AQL 等级">
          <el-input v-model="verForm.aqlLevel" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="verDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="createVersion">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getStandardDetailApi, getPendingVersionsApi,
  createVersionApi, publishVersionApi, retireStandardApi
} from '@/api/qms/standard'
import VersionTimeline from './components/VersionTimeline.vue'
import CharacteristicEditor from './components/CharacteristicEditor.vue'
import ApplicabilityEditor from './components/ApplicabilityEditor.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const roles = computed(() => userStore.userInfo?.roles || [])
const canEdit = computed(() => roles.value.includes('ROLE_ADMIN') || roles.value.includes('ROLE_QUALITY_ENG'))

const loading = ref(false)
const saving = ref(false)
const std = ref({})
const versions = ref([])
const applicabilities = ref([])
const doubleSigns = ref({}) // versionId -> doubleSign
const activeVersionId = ref('')
const verDialog = ref(false)
const verForm = ref({ effectiveFrom: '', changeReason: '', aqlLevel: 'GB/T 2828.1 一般检验水平 II' })

const activeVersion = computed(() => versions.value.find(v => v.id === activeVersionId.value) || {})
/** 版本编辑保存时携带的基础字段（编辑器只覆盖自己的段） */
const versionPayload = computed(() => {
  const v = activeVersion.value
  return {
    samplingPlanId: v.samplingPlanId || '',
    aqlLevel: v.aqlLevel || '',
    inspectionLevel: v.inspectionLevel || 'II',
    effectiveFrom: v.effectiveFrom || '',
    changeReason: v.changeReason || ''
  }
})

function stdStatusName(s) { return { DRAFT: '草稿', ACTIVE: '已启用', RETIRED: '已退役' }[s] || s }
function stdStatusTag(s) { return { DRAFT: 'info', ACTIVE: 'success', RETIRED: 'danger' }[s] || 'info' }
function verStatusName(s) { return { DRAFT: '草稿', PENDING_APPROVE: '审批中', RELEASED: '已发布', RETIRED: '已退役' }[s] || s }

async function load() {
  loading.value = true
  try {
    const id = route.params.id
    const res = await getStandardDetailApi(id)
    std.value = res.data?.standard || {}
    versions.value = res.data?.versions || []
    applicabilities.value = res.data?.applicabilities || []
    // 双签状态聚合（pending-versions 提供审批中版本的节点结果）
    try {
      const pv = await getPendingVersionsApi()
      const map = {}
      for (const p of (pv.data || [])) map[p.versionId] = p.doubleSign
      doubleSigns.value = map
    } catch (e) { /* 聚合失败不阻断详情 */ }
    // 版本选中：URL ?v= 优先 → 草稿 → 最新
    const qv = route.query.v
    if (qv && versions.value.some(v => v.id === qv)) activeVersionId.value = qv
    else if (!activeVersionId.value || !versions.value.some(v => v.id === activeVersionId.value)) {
      const draft = versions.value.find(v => v.status === 'DRAFT')
      activeVersionId.value = draft ? draft.id : (versions.value[0]?.id || '')
    }
  } catch (e) { /* 拦截器提示 */ } finally { loading.value = false }
}

function onVersionChange() { /* 编辑器按 key 重载 */ }

async function createVersion() {
  const f = verForm.value
  if (!f.effectiveFrom) { ElMessage.warning('生效日期必填'); return }
  if (!f.changeReason) { ElMessage.warning('变更原因必填'); return }
  saving.value = true
  try {
    const res = await createVersionApi(std.value.id, {
      ...f, inspectionLevel: 'II',
      characteristics: [{
        characteristicName: '', ctqFlag: '1', regulatoryFlag: '0', specType: 'NUMERIC',
        lowerLimit: '', upperLimit: '', targetValue: '', unit: '', methodName: ''
      }],
      applicabilities: [{ priority: 50 }]
    })
    ElMessage.success('草稿版本已创建')
    verDialog.value = false
    const vid = res.data?.version?.id
    if (vid) activeVersionId.value = vid
    await load()
  } catch (e) { /* 拦截器提示 */ } finally { saving.value = false }
}

async function doPublish() {
  saving.value = true
  try {
    const res = await publishVersionApi(activeVersionId.value)
    const info = res.data || {}
    ElMessage.success(info.doubleSign
      ? `已提交发布（双签：质量经理 + 技术负责人），审批单 ${info.apprNo || ''}`
      : `已提交发布（质量经理审批），审批单 ${info.apprNo || ''}`)
    await load()
  } catch (e) { /* 拦截器提示 */ } finally { saving.value = false }
}

async function doRetire() {
  await ElMessageBox.confirm(
    `确认退役 ${std.value.standardCode}？退役后不再生成新检验任务，历史追溯不变。`,
    '标准退役', { type: 'warning' })
  saving.value = true
  try {
    await retireStandardApi(std.value.id)
    ElMessage.success('已退役')
    await load()
  } catch (e) { /* 拦截器提示 */ } finally { saving.value = false }
}

onMounted(load)
</script>

<style scoped>
.standard-detail { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.hd-left { display: flex; align-items: center; gap: 10px; }
.ttl { font-weight: bold; font-size: 16px; }
.sec { font-weight: 600; }
.sec-row { display: flex; justify-content: space-between; align-items: center; }
.sec-card { margin-bottom: 14px; }
.pub-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.pub-hint { font-size: 12px; color: #909399; }
</style>
