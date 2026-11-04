<template>
  <div class="page">
    <div class="card-header">
      <span style="font-weight: bold; font-size: 16px;">{{ title }}</span>
      <div style="display:flex;align-items:center;gap:6px">
        <span class="tip">2.4 收货管理 · FR-4.2-4-1 · C-4.2-04 容差 0.5% · BR-4.2-21/22/28/49</span>
        <PageInfo class="page-info--inline" title="入库过账（FR-4.2-6-1）：回写 PO 已收货数量、行完成判定、更新库存（全部进待检锁定）、生成入库凭证" description="BR-4.2-28：PO 已关闭（未按 49 重开）或供应商冻结 → 硬阻断。应付暂估与三方匹配为桩（TODO-NOTIFY）。" />
      </div>
    </div>

    <div class="toolbar">
      <el-select v-model="postQuery.status" placeholder="状态" clearable style="width: 130px;" @change="loadPosts(1)">
        <el-option label="待过账" value="CREATED" />
        <el-option label="已过账" value="POSTED" />
      </el-select>
      <el-button @click="loadPosts(1)">查询</el-button>
    </div>
    <el-table :data="postList" size="small" border v-loading="loading">
      <el-table-column prop="grNo" label="收货单号" width="160" />
      <el-table-column prop="poNo" label="PO" width="150" />
      <el-table-column prop="supplierName" label="供应商" min-width="130" />
      <el-table-column prop="arrivalDate" label="到货日期" width="105" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="postingDocNo" label="入库凭证" width="140" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'CREATED' && canPost" link type="primary"
                     :loading="postingId === row.id" @click="doPosting(row)">过账</el-button>
          <el-button link type="primary" @click="detailRef?.open(row.id)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
        <AppPagination
      style="margin-top: 8px;"
      :total="postTotal"
      v-model:page-size="postQuery.size"
      :current-page="postQuery.current"
      @current-change="loadPosts"
    />

    <GrDetailDrawer ref="detailRef" />
  </div>
</template>

<script setup>
import AppPagination from '@/components/AppPagination.vue'
/**
 * 2.4.4 入库过账（拆分自原收货管理操作台）：过账执行与收货单详情。
 * 共享状态与角色见 useGoodsReceipt.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getGrPageApi, postingGrApi } from '@/api/proc/goods-receipt'
import { useGoodsReceipt } from './useGoodsReceipt'
import { statusName, statusTag } from './gr-helpers'
import GrDetailDrawer from './_parts/GrDetailDrawer.vue'

const route = useRoute()
const title = computed(() => route.meta.title || '入库过账')
const { loading, canPost } = useGoodsReceipt()
const detailRef = ref(null)
const postingId = ref('')

const postList = ref([])
const postTotal = ref(0)
const postQuery = reactive({ status: 'CREATED', current: 1, size: 20 })
async function loadPosts(page) {
  postQuery.current = page || 1
  loading.value = true
  try {
    const { data } = await getGrPageApi({ current: postQuery.current, size: postQuery.size, status: postQuery.status })
    postList.value = data.records || []
    postTotal.value = Number(data.total) || 0
  } finally { loading.value = false }
}

async function doPosting(row) {
  postingId.value = row.id
  try {
    const res = await postingGrApi(row.id)
    ElMessage.success(`过账成功，入库凭证 ${res.data.gr?.postingDocNo}`)
    loadPosts(postQuery.current)
  } catch (e) {
    ElMessage.error(e?.message || '过账失败')
  } finally { postingId.value = '' }
}

onMounted(() => loadPosts(1))
</script>

<style scoped>
.page { padding: 4px; }
.card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.tip { color: #909399; font-size: 12px; }
</style>
