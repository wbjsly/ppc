<template>
  <div class="standard-approval">
    <el-card>
      <template #header>
        <div class="card-header">
          <span class="ttl">标准审批（6.1.2）— 审批中版本待办</span>
          <div>
            <el-button size="small" @click="$router.push('/m/qms-approval')">全局审批待办</el-button>
            <el-button size="small" type="primary" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
                title="版本维度跨标准平铺（spec inspection-standard 标准审批独立待办页）"
                description="本页只读展示审批中版本与双签状态；签署动作在「全局审批待办」完成，点击条目进入标准详情查看特性与发布进度。" />

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="standardCode" label="标准编码" width="170">
          <template #default="{ row }"><b>{{ row.standardCode }}</b></template>
        </el-table-column>
        <el-table-column prop="standardName" label="标准名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="版本" width="80" align="center">
          <template #default="{ row }">V{{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column prop="changeReason" label="变更原因" min-width="200" show-overflow-tooltip />
        <el-table-column prop="effectiveFrom" label="生效起" width="110" />
        <el-table-column label="双签状态" min-width="220">
          <template #default="{ row }">
            <template v-if="row.doubleSign && row.doubleSign.nodes && row.doubleSign.nodes.length">
              <div class="nodes">
                <span v-for="(n, i) in row.doubleSign.nodes" :key="i" class="node">
                  <el-tag size="small" :type="nodeTag(n.status)">{{ n.nodeName || n.roleRequired }}</el-tag>
                  <span v-if="n.signerName" class="signer">{{ n.signerName }}</span>
                  <span v-else-if="n.status === 'ACTIVE'" class="pending">待签署</span>
                </span>
              </div>
            </template>
            <el-tag v-else size="small" type="warning">审批中</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="$router.push(`/m/6.1.standard/${row.standardId}?v=${row.versionId}`)">
              查看详情
            </el-button>
            <el-button link type="warning" @click="$router.push('/m/qms-approval')">去签署</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!rows.length && !loading" description="暂无审批中的标准版本" :image-size="80" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getPendingVersionsApi } from '@/api/qms/standard'

const loading = ref(false)
const rows = ref([])

function nodeTag(s) {
  return { PASSED: 'success', ACTIVE: 'warning', REJECTED: 'danger', SKIPPED: 'info' }[s] || 'info'
}

async function load() {
  loading.value = true
  try {
    const res = await getPendingVersionsApi()
    rows.value = res.data || []
  } catch (e) { /* 拦截器提示 */ } finally { loading.value = false }
}

onMounted(load)
</script>

<style scoped>
.standard-approval { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.ttl { font-weight: bold; font-size: 16px; }
.nodes { display: flex; flex-direction: column; gap: 3px; }
.node { display: inline-flex; gap: 6px; align-items: center; font-size: 12px; }
.signer { color: #67c23a; }
.pending { color: #e6a23c; }
</style>
