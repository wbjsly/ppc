<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="限流熔断（15.5）：令牌桶限流、熔断降级、配额档位、灰度切流量"
      description="复用 open-api-gateway。超限 429+Retry-After（BR-5.5-05）、连续超限自动降档（BR-5.5-06）、错误率熔断半开（BR-5.5-07）。灰度切流量限 ADMIN。" />
    <el-tabs v-model="tab">
      <!-- 15.5.1 令牌限流 -->
      <el-tab-pane label="令牌限流" name="ratelimit">
        <el-table :data="channels" border size="small">
          <el-table-column prop="partnerCode" label="伙伴" width="120" />
          <el-table-column prop="rateTier" label="限流档" width="110" />
          <el-table-column label="档位调整" width="180">
            <template #default="{ row }">
              <el-select :value="row.rateTier" size="small" style="width:140px"
                         @change="v => setTier(row, v)">
                <el-option label="战略500" value="STRATEGIC" />
                <el-option label="普通100" value="NORMAL" />
                <el-option label="新伙伴30" value="NEW" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column prop="rateHit" label="近期超限" width="100">
            <template #default="{ row }">{{ row.rateHit ?? '—' }}</template>
          </el-table-column>
        </el-table>
        <el-alert type="info" :closable="false" style="margin-top:10px"
                  title="令牌桶限流：超限 429 + Retry-After（BR-5.5-05），连续超限自动降档（BR-5.5-06）" />
      </el-tab-pane>

      <!-- 15.5.2 熔断降级 -->
      <el-tab-pane label="熔断降级" name="circuit">
        <el-table :data="channels" border size="small">
          <el-table-column prop="partnerCode" label="伙伴" width="120" />
          <el-table-column label="熔断状态" width="130">
            <template #default="{ row }">
              <el-tag size="small" :type="row.circuitState === 'OPEN' ? 'danger' : row.circuitState === 'HALF_OPEN' ? 'warning' : 'success'">
                {{ row.circuitState || 'CLOSED' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="circuitHit" label="触发次数" width="100" align="center">
            <template #default="{ row }">{{ row.circuitHit ?? 0 }}</template>
          </el-table-column>
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <el-button size="small" type="warning" :disabled="row.circuitState === 'CLOSED'"
                         @click="resetCircuit(row)">解除熔断</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-alert type="warning" :closable="false" style="margin-top:10px"
                  title="错误率熔断半开探测（BR-5.5-07）：OPEN 拒绝流量 → 半开试探 → 恢复 CLOSED；解除熔断立即恢复全量" />
      </el-tab-pane>

      <!-- 15.5.3 配额管理 -->
      <el-tab-pane label="配额管理" name="quota">
        <el-row :gutter="12" style="margin-bottom:12px">
          <el-col :span="8" v-for="t in TIERS" :key="t.value">
            <el-card shadow="never" class="tier-card">
              <div class="tier-name">{{ t.label }}</div>
              <div class="tier-qps">{{ t.qps }} req/s</div>
              <div class="tier-desc">{{ t.desc }}</div>
              <div class="tier-used">当前使用伙伴：{{ tierCount(t.value) }} 家</div>
            </el-card>
          </el-col>
        </el-row>
        <el-table :data="channels" border size="small">
          <el-table-column prop="partnerCode" label="伙伴" width="140" />
          <el-table-column prop="rateTier" label="配额档" width="130">
            <template #default="{ row }">
              <el-tag size="small">{{ tierLabel(row.rateTier) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="调整配额" width="180">
            <template #default="{ row }">
              <el-select :value="row.rateTier" size="small" style="width:140px"
                         @change="v => setTier(row, v)">
                <el-option v-for="t in TIERS" :key="t.value" :label="t.label" :value="t.value" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
        <el-alert type="info" :closable="false" style="margin-top:10px"
                  title="配额档位与限流档同源：调档即调整该伙伴的令牌桶配额，档位变更留痕（rate-tier-change）" />
      </el-tab-pane>

      <!-- 15.5.4 灰度发布 -->
      <el-tab-pane label="灰度发布" name="gray">
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
      </el-tab-pane>
    </el-tabs>

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
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getChannelsApi, resetCircuitApi, setRateTierApi } from '@/api/proc/interface-integration'
import { getGrayReleasesApi, createGrayReleaseApi, grayStepUpApi, grayRollbackApi } from '@/api/proc/intf-portal'

const route = useRoute()
const tab = ref(route.meta.tab || 'ratelimit')
const channels = ref([])
const grays = ref([])
const grayDrawer = ref(false)
const grayForm = ref({ channelCode: '', oldCredId: '', newCredId: '', ladderJson: '[10,50,100]', rollbackErrorRate: '50', observeMin: '60' })

const TIERS = [
  { value: 'STRATEGIC', label: '战略配额', qps: 500, desc: '核心战略伙伴，全量保障' },
  { value: 'NORMAL', label: '普通配额', qps: 100, desc: '常规业务伙伴标准档' },
  { value: 'NEW', label: '新伙伴配额', qps: 30, desc: '接入观察期低配额' }
]
function tierLabel(v) { return (TIERS.find(t => t.value === v) || {}).label || v }
function tierCount(v) { return channels.value.filter(c => c.rateTier === v).length }

function gTag(s) {
  return { RUNNING: 'success', DRAFT: 'info', ROLLBACK: 'warning', COMPLETED: 'success', ABORTED: 'danger' }[s] || 'info'
}
async function load() {
  try { const r = await getChannelsApi(); channels.value = r.data?.data || [] } catch { channels.value = [] }
  try { const r = await getGrayReleasesApi(); grays.value = r.data?.data || [] } catch { grays.value = [] }
}
async function setTier(row, tier) {
  await setRateTierApi(row.id, tier)
  ElMessage.success('配额档已更新（变更留痕）')
  load()
}
async function resetCircuit(row) {
  await ElMessageBox.confirm(`解除伙伴 ${row.partnerCode} 的熔断？`, '提示', { type: 'warning' })
  await resetCircuitApi(row.id)
  ElMessage.success('已解除熔断')
  load()
}
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
onMounted(load)
</script>

<style scoped>
.intf-page { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.tier-card { text-align: center; }
.tier-name { font-size: 13px; color: #909399; }
.tier-qps { font-size: 22px; font-weight: bold; color: #303133; margin: 6px 0; }
.tier-desc { font-size: 12px; color: #909399; }
.tier-used { font-size: 12px; color: #409eff; margin-top: 6px; }
</style>
