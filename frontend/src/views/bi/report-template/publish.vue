<template>
  <div>
    <el-page-header content="报表模板管理（14.1）— 设计/测试/发布/版本四叶；口径 diff 硬阻断（C-4.10-01）、三方发布审批、快照回退"
                    style="margin-bottom: 8px" />

    <!-- ========== 14.1.3 模板发布 ========== -->
    <div class="toolbar">
      <el-button size="small" type="primary" @click="load">刷新</el-button>
      <span class="tip">三方串行审批：数据治理（口径）→ 业务负责人（业务准确性）→ IT（性能）；3/5 工作日超时催办升级；发布前二次口径校验（BR-4.10-11）</span>
    </div>
    <el-table :data="rows" v-loading="loading" border stripe size="small" max-height="400">
      <el-table-column prop="code" label="编码" width="130" />
      <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240" align="center">
        <template #default="{ row }">
          <el-button v-if="['DRAFT','TESTING','REJECTED'].includes(row.status)"
                     size="small" type="primary" @click="doSubmit(row)">提交审批</el-button>
          <el-button size="small" type="info" link @click="openApproval(row)">审批进度</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="approvalVisible" :title="`审批进度：${approvalName}`" width="760px">
      <el-timeline v-if="approvalTasks.length">
        <el-timeline-item v-for="t in approvalTasks" :key="t.id"
                          :type="t.status === 'PASSED' ? 'success' : (t.status === 'REJECTED' ? 'danger' : 'primary')"
                          :hollow="t.status === 'PENDING'"
                          :timestamp="(t.opTime || '').replace('T', ' ').slice(0, 16)">
          SEQ{{ t.seq }} · {{ t.nodeName || t.roleRequired }}
          <el-tag size="small" :type="t.status === 'PASSED' ? 'success' : 'info'" style="margin-left: 6px">
            {{ t.status }}
          </el-tag>
          <div v-if="t.opinion" class="tip">意见：{{ t.opinion }}</div>
          <div v-if="t.status === 'PENDING' && canSign(t)" style="margin-top: 6px">
            <el-input v-model="signOpinions[t.id]" size="small" placeholder="签署意见（驳回须 ≥2 字）"
                      style="width: 260px; margin-right: 6px" />
            <el-button size="small" type="success" @click="doPass(t)">通过</el-button>
            <el-button size="small" type="danger" @click="doReject(t)">驳回</el-button>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无审批实例" :image-size="60" />
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 14.1.3 模板发布（拆分自原报表模板操作台）：提交审批 / 三方串行审批进度与签署。
 * 模板列表见 useReportTemplate.js（模板设计页、版本控制页共用），状态映射见 rt-helpers.js。
 */
import { ref, onMounted, onActivated } from 'vue'
import { ElMessage } from 'element-plus'
import { submitTemplateApi, approvalPass, approvalReject } from '@/api/bi/report-template'
import request from '@/utils/request'
import { useReportTemplate } from './useReportTemplate'
import { statusLabel, statusType } from './rt-helpers'

const { rows, loading, load } = useReportTemplate()

// 发布
const approvalVisible = ref(false)
const approvalName = ref('')
const approvalTasks = ref([])
const signOpinions = ref({})

async function doSubmit(row) {
  try {
    await submitTemplateApi(row.id)
    ElMessage.success('已提交三方串行审批（数据治理 → 业务负责人 → IT）')
    await load()
    openApproval(row)
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '提交失败（须有有效 PASS 测试报告且指标全部注册）')
  }
}

function canSign(t) {
  // 简化：任何登录签署人可见按钮，后端节点级授权（角色不符 403）兜底
  return t.status === 'PENDING'
}

async function doPass(t) {
  try {
    await approvalPass(t.id, signOpinions.value[t.id] || '同意')
    ElMessage.success('已签署')
    await openApprovalByName()
    await load()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '签署失败（角色不符或禁自签 403）')
  }
}

async function doReject(t) {
  const opinion = signOpinions.value[t.id] || ''
  if (opinion.length < 2) {
    ElMessage.warning('驳回意见须 ≥2 字')
    return
  }
  try {
    await approvalReject(t.id, opinion)
    ElMessage.success('已驳回')
    await openApprovalByName()
    await load()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '驳回失败')
  }
}

let currentApprovalRow = null
function openApproval(row) {
  currentApprovalRow = row
  openApprovalImpl(row)
}
async function openApprovalByName() {
  if (currentApprovalRow) await openApprovalImpl(currentApprovalRow)
}
async function openApprovalImpl(row) {
  approvalName.value = row.name
  approvalVisible.value = true
  try {
    const res = await request.get('/api/qms/approvals/logs', {
      params: { bizType: 'ReportTemplate', bizId: row.id }
    })
    approvalTasks.value = res.data?.tasks || []
  } catch {
    approvalTasks.value = []
  }
}

onMounted(() => { load() })
onActivated(() => { load() })
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
</style>
