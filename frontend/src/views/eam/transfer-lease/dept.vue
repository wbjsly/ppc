<template>
  <div class="eam-page">
    <PageInfo title="部门调拨（7.4.1）：共享池入口 → 申请（BR-21 四前置）→ 三节点审批 → 交接双确认 → 完成（BR-22 当月归属回溯）" description="完成当月折旧仍归调出成本中心（引擎回溯调出方），次月起自动切调入方。" style="margin-bottom:12px;" />

    <TransferSection mode="dept" />
  </div>
</template>

<script setup>
/**
 * 7.4.1 部门调拨（拆分自原调拨租赁操作台）：共享池发起、审批交接闭环。
 * 调拨区块为与法人调拨共用的 TransferSection（mode=dept）。
 */
import PageInfo from '@/components/PageInfo.vue'
import { onMounted } from 'vue'
import { loadTrfs } from './useTransferLease'
import TransferSection from './_parts/TransferSection.vue'

onMounted(() => loadTrfs('dept'))
</script>

<style scoped>
.eam-page { padding: 4px; }
</style>
