<template>
  <div class="intf-page">
    <PageInfo title="接口目录：契约登记、评审队列、语义化版本台账" description="复用接入治理数据（interface-onboarding）。契约未登记/未发布不得签发生产凭证（BR-5.5-01）。" style="margin-bottom:12px" />

    <!-- 15.1.2 契约评审 -->
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
  </div>
</template>

<script setup>
/**
 * 15.1.2 契约评审（拆分自原接口目录操作台）：提交评审 / 通过 / 驳回。
 * 共享状态见 useDirectory.js，状态标签映射见 contract-helpers.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { submitReviewApi, reviewContractApi } from '@/api/proc/interface-integration'
import { useDirectory } from './useDirectory'
import { stTag } from './contract-helpers'

const { contracts, load } = useDirectory()

const reviewQueue = computed(() =>
  contracts.value.filter(c => c.status === 'DRAFT' || c.status === 'REVIEWING'))

onMounted(load)

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
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
