<template>
  <!-- 计划下钻抽屉（8.9.1 预提管理 / 8.9.2 待摊管理 两页共用） -->
  <el-drawer v-model="plansVisible" :title="`计划行 ${plansTitle}`" size="520px">
    <el-table :data="plans" border size="small">
      <el-table-column prop="period" label="期间" width="100" />
      <el-table-column label="金额" width="120" align="right">
        <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.genStatus === 'GENERATED' ? 'success' : 'warning'">
            {{ row.genStatus === 'GENERATED' ? '已生成' : '未生成' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="凭证" width="90">
        <template #default="{ row }">
          <span class="muted">{{ row.voucherId ? 'DRAFT' : '—' }}</span>
        </template>
      </el-table-column>
    </el-table>
  </el-drawer>
</template>

<script setup>
/**
 * 计划下钻抽屉（跨页共用，design D2：状态放 useAmort.js 单例）。
 * 供预提管理页与待摊管理页「计划」按钮渲染，接口与展示不变。
 */
import { useAmort } from '../useAmort'
import { fmt } from '../amort-helpers'

const { plansVisible, plansTitle, plans } = useAmort()
</script>

<style scoped>
.muted { color: #909399; font-size: 12px }
</style>
