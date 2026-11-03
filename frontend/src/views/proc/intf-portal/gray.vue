<template>
  <div class="intf-page">
    <PageInfo title="限流熔断（15.5）：令牌桶限流、熔断降级、配额档位、灰度切流量" description="复用 open-api-gateway。超限 429+Retry-After（BR-5.5-05）、连续超限自动降档（BR-5.5-06）、错误率熔断半开（BR-5.5-07）。灰度切流量限 ADMIN。" style="margin-bottom:12px" />

    <!-- 15.5.4 灰度发布 -->
    <el-card shadow="never" style="margin-bottom:12px">
      <template #header>
        <div class="card-head">
          <span>灰度规则（双凭证并行 · 阶梯切量 · 自动回滚）</span>
          <el-button type="primary" size="small" @click="grayDrawer=true">配置灰度</el-button>
        </div>
      </template>
      <el-table :data="grays" border size="small">
        <el-table-column prop="channelCode" label="通道/伙伴" width="130" />
        <el-table-column prop="ladderJson" label="阶梯%" width="110" />
        <el-table-column prop="currentStep" label="当前档" width="80" />
        <el-table-column prop="rollbackErrorRate" label="回滚阈值%" width="100" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="gTag(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button link type="primary" size="small"
                       :disabled="row.status!=='RUNNING'&&row.status!=='DRAFT'"
                       @click="stepUp(row)">切量</el-button>
            <el-button link type="warning" size="small"
                       :disabled="row.status!=='RUNNING'"
                       @click="rollback(row)">回滚</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer title="配置灰度规则（ADMIN）" size="400px" v-model="grayDrawer">
      <el-form label-width="100px" size="small">
        <el-form-item label="通道/伙伴" required><el-input v-model="grayForm.channelCode" /></el-form-item>
        <el-form-item label="旧凭证ID" required><el-input v-model="grayForm.oldCredId" /></el-form-item>
        <el-form-item label="新凭证ID" required><el-input v-model="grayForm.newCredId" /></el-form-item>
        <el-form-item label="阶梯%" required>
          <el-input v-model="grayForm.ladderJson" placeholder="[10,50,100]" />
        </el-form-item>
        <el-form-item label="回滚阈值%"><el-input v-model="grayForm.rollbackErrorRate" placeholder="50" /></el-form-item>
        <el-form-item label="观察窗口分"><el-input v-model="grayForm.observeMin" placeholder="60" /></el-form-item>
        <el-form-item>
          <el-button type="primary" size="small" @click="saveGray">保存（ADMIN）</el-button>
          <el-button size="small" @click="grayDrawer=false">取消</el-button>
        </el-form-item>
      </el-form>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * 15.5.4 灰度发布（拆分自原限流熔断操作台）：灰度规则配置 / 切量 / 回滚。
 * 本页独有逻辑，未抽 composable。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getGrayReleasesApi, createGrayReleaseApi, grayStepUpApi, grayRollbackApi } from '@/api/proc/intf-portal'

const grays = ref([])
const grayDrawer = ref(false)
const grayForm = ref({ channelCode: '', oldCredId: '', newCredId: '', ladderJson: '[10,50,100]', rollbackErrorRate: '50', observeMin: '60' })

function gTag(s) {
  return { RUNNING: 'success', DRAFT: 'info', ROLLBACK: 'warning', COMPLETED: 'success', ABORTED: 'danger' }[s] || 'info'
}

async function load() {
  try { const r = await getGrayReleasesApi(); grays.value = r.data?.data || [] } catch { grays.value = [] }
}
onMounted(load)

async function saveGray() {
  await createGrayReleaseApi(grayForm.value)
  ElMessage.success('灰度规则已配置（ADMIN）')
  grayDrawer.value = false
  load()
}
async function stepUp(row) {
  await grayStepUpApi(row.id)
  ElMessage.success('已切量至下一档')
  load()
}
async function rollback(row) {
  await ElMessageBox.confirm('回滚到上一档（首档则中止灰度）？', '提示', { type: 'warning' })
  await grayRollbackApi(row.id)
  ElMessage.success('已回滚')
  load()
}
</script>

<style scoped>
.intf-page { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
</style>
