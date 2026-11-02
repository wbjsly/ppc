<template>
  <div>
    <PdaShell :title="'移动审批 · ' + tabTitle" :scan="false" @sync="doSync">
      <!-- 签审双因子提示（BR-4.11-45：密码二次确认 + 终端注册态） -->
      <el-alert type="info" :closable="false" style="margin-bottom:8px"
                :title="`签审双因子：密码二次确认${webAuthn ? ' + WebAuthn 生物识别可用' : '（WebAuthn 不可用，密码必选）'}`" />

      <el-table :data="filtered" size="small" v-loading="loading">
        <el-table-column prop="title" label="待办" show-overflow-tooltip />
        <el-table-column prop="assignee" label="处理人" width="110" />
        <el-table-column label="财务签署" width="76">
          <template #default="{ row }">
            <el-tag v-if="row.needFinSign === '1'" size="small" type="danger">需</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dueAt" label="超时计时" width="150" />
        <el-table-column prop="status" label="状态" width="86" />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button size="small" type="success" link @click="openSign(row, true)">通过</el-button>
              <el-button size="small" type="danger" link @click="openSign(row, false)">驳回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <div style="margin-top:8px;display:flex;gap:8px">
        <el-button size="small" @click="load">刷新</el-button>
        <el-button size="small" @click="scanEscalate">30min 超时升级扫描</el-button>
      </div>

      <!-- 签审弹窗（跨页共用） -->
      <SignDialog />

      <el-alert v-if="result" :type="result.type" :closable="false" style="margin-top:8px"
                :title="result.title" :description="result.desc" />
    </PdaShell>
  </div>
</template>

<script setup>
/**
 * 13.7.2 异常审批（拆分自原移动审批操作台）：异常待办聚合 + 双因子签审。
 * 待办/签审弹窗态/结果条见 useApproval.js。
 */
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import PdaShell from '../_pda/PdaShell.vue'
import SignDialog from './_parts/SignDialog.vue'
import { useApproval } from './useApproval'

const route = useRoute()
const tabTitle = computed(() => route.meta.title || '移动审批')

const { loading, result, webAuthn, filterTodos, openSign, load, scanEscalate, doSync } = useApproval()

const filtered = computed(() => filterTodos('exception'))

onMounted(() => {
  load()
})
</script>
