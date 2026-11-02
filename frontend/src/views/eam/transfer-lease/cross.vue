<template>
  <div class="eam-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px;"
      title="法人调拨（7.4.2）：四节点审批（+财务经理）→ 完成同事务双凭证 + 内部交易单据 + 开票待办（C-10 月结卡控）"
      description="跨法人以双方法人 USCC 不同为判定；任一凭证失败整体回滚。" />

    <TransferSection mode="cross" />
  </div>
</template>

<script setup>
/**
 * 7.4.2 法人调拨（拆分自原调拨租赁操作台）：C-10 月结卡控、内部交易联动。
 * 调拨区块为与部门调拨共用的 TransferSection（mode=cross）。
 */
import { onMounted } from 'vue'
import { loadTrfs } from './useTransferLease'
import TransferSection from './_parts/TransferSection.vue'

onMounted(() => loadTrfs('cross'))
</script>

<style scoped>
.eam-page { padding: 4px; }
</style>
