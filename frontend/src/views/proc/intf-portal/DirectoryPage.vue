<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="接口目录（15.1）：契约登记、评审队列、语义化版本台账"
      description="复用接入治理数据（interface-onboarding）。契约未登记/未发布不得签发生产凭证（BR-5.5-01）。" />

    <el-tabs v-model="tab">
      <!-- 15.1.1 目录登记 -->
      <el-tab-pane label="目录登记" name="register">
        <div class="tab-bar">
          <el-button type="primary" size="small" @click="openCreate">新建契约</el-button>
        </div>
        <el-table :data="contracts" border size="small">
          <el-table-column prop="contractNo" label="契约号" width="150" />
          <el-table-column prop="name" label="名称" min-width="160" />
          <el-table-column prop="version" label="版本" width="90" />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="stTag(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reviewer" label="评审人" width="110" />
          <el-table-column prop="publishAt" label="发布时间" width="160" />
        </el-table>
      </el-tab-pane>

      <!-- 15.1.2 契约评审 -->
      <el-tab-pane label="契约评审" name="review">
        <el-table :data="reviewQueue" border size="small">
          <el-table-column prop="contractNo" label="契约号" width="150" />
          <el-table-column prop="name" label="名称" min-width="160" />
          <el-table-column prop="version" label="版本" width="90" />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="stTag(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200">
            <template #default="{ row }">
              <el-button v-if="row.status === 'DRAFT'" type="primary" size="small"
                         @click="submitReview(row)">提交评审</el-button>
              <template v-if="row.status === 'REVIEWING'">
                <el-button type="success" size="small" @click="review(row, 'APPROVED')">通过</el-button>
                <el-button type="danger" size="small" @click="review(row, 'REJECTED')">驳回</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <el-alert v-if="!reviewQueue.length" type="info" :closable="false" style="margin-top:10px"
                  title="评审队列为空（DRAFT 可提交评审，REVIEWING 可通过/驳回）" />
      </el-tab-pane>

      <!-- 15.1.3 版本登记 -->
      <el-tab-pane label="版本登记" name="version">
        <el-table :data="contracts" border size="small">
          <el-table-column prop="contractNo" label="契约号" width="150" />
          <el-table-column prop="name" label="名称" min-width="150" />
          <el-table-column prop="version" label="当前版本" width="100" />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="stTag(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <el-button type="primary" size="small" @click="openVersion(row)">版本变更</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 新建契约抽屉 -->
    <el-drawer title="新建接口契约" size="440px" v-model="createDrawer">
      <el-form label-width="90px" size="small">
        <el-form-item label="契约名称" required><el-input v-model="cf.name" placeholder="如：订单查询接口" /></el-form-item>
        <el-form-item label="初始版本" required><el-input v-model="cf.version" placeholder="如 1.0.0" /></el-form-item>
        <el-form-item label="说明"><el-input type="textarea" :rows="3" v-model="cf.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDrawer=false">取消</el-button>
        <el-button type="primary" @click="create">登记</el-button>
      </template>
    </el-drawer>

    <!-- 版本变更抽屉 -->
    <el-drawer title="版本变更" size="440px" v-model="verDrawer">
      <el-form label-width="90px" size="small">
        <el-form-item label="契约"><el-input :model-value="verRow?.contractNo" disabled /></el-form-item>
        <el-form-item label="新版本" required><el-input v-model="vf.version" placeholder="如 1.1.0" /></el-form-item>
        <el-form-item label="变更说明" required><el-input type="textarea" :rows="3" v-model="vf.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="verDrawer=false">取消</el-button>
        <el-button type="primary" @click="changeVersion">登记版本</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getContractsApi, createContractApi, submitReviewApi, reviewContractApi,
  changeVersionApi } from '@/api/proc/interface-integration'

const route = useRoute()
const tab = ref(route.meta.tab || 'register')

const contracts = ref([])
const createDrawer = ref(false)
const verDrawer = ref(false)
const verRow = ref(null)
const cf = ref({ name: '', version: '1.0.0', remark: '' })
const vf = ref({ version: '', remark: '' })

const reviewQueue = computed(() =>
  contracts.value.filter(c => c.status === 'DRAFT' || c.status === 'REVIEWING'))

function stTag(s) {
  return { PUBLISHED: 'success', DRAFT: 'info', REVIEWING: 'warning', REJECTED: 'danger' }[s] || 'info'
}

async function load() {
  try { const r = await getContractsApi(); contracts.value = r.data?.data || [] } catch { contracts.value = [] }
}
onMounted(load)

function openCreate() {
  cf.value = { name: '', version: '1.0.0', remark: '' }
  createDrawer.value = true
}
async function create() {
  if (!cf.value.name || !cf.value.version) { ElMessage.warning('名称与版本必填'); return }
  await createContractApi(cf.value)
  ElMessage.success('契约已登记')
  createDrawer.value = false
  load()
}
async function submitReview(row) {
  await submitReviewApi(row.id)
  ElMessage.success('已提交评审')
  load()
}
async function review(row, opinion) {
  await reviewContractApi(row.id, { opinion })
  ElMessage.success(opinion === 'APPROVED' ? '评审通过' : '已驳回')
  load()
}
function openVersion(row) {
  verRow.value = row
  vf.value = { version: '', remark: '' }
  verDrawer.value = true
}
async function changeVersion() {
  if (!vf.value.version || !vf.value.remark) { ElMessage.warning('新版本与变更说明必填'); return }
  await changeVersionApi(verRow.value.id, vf.value)
  ElMessage.success('版本已登记')
  verDrawer.value = false
  load()
}
</script>

<style scoped>
.intf-page { padding: 12px; }
.tab-bar { margin-bottom: 10px; display: flex; justify-content: flex-end; }
</style>
