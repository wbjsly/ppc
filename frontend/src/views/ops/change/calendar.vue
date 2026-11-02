<template>
  <div class="page">
    <el-table :data="tickets" border size="small">
      <el-table-column prop="ticketNo" label="单号" width="180" />
      <el-table-column prop="title" label="标题" />
      <el-table-column prop="changeType" label="类型" width="110" />
      <el-table-column prop="status" label="状态" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="stTag(row.status)">{{ stLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="inWindow" label="窗口" width="80">
        <template #default="{ row }">{{ row.inWindow === '1' ? '窗口内' : row.inWindow === '0' ? '窗口外' : '—' }}</template>
      </el-table-column>
      <el-table-column prop="createDate" label="提交时间" width="160">
        <template #default="{ row }">{{ fmt(row.createDate) }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
/**
 * 18.1.2 变更日历（拆分自原变更管理操作台）：按状态排布的变更单台账。
 * 列表数据来自 useChange.js，状态映射见 change-helpers.js。
 */
import { onMounted } from 'vue'
import { useChange } from './useChange'
import { stLabel, stTag } from './change-helpers'

const { tickets, load } = useChange()

function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '' }

onMounted(load)
</script>
