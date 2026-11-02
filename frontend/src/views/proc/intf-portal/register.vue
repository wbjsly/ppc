<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="接口目录（15.1）：契约登记、评审队列、语义化版本台账"
      description="复用接入治理数据（interface-onboarding）。契约未登记/未发布不得签发生产凭证（BR-5.5-01）。" />

    <!-- 15.1.1 目录登记 -->
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
  </div>
</template>

<script setup>
/**
 * 15.1.1 目录登记（拆分自原接口目录操作台）：新建契约 / 契约台账。
 * 共享状态见 useDirectory.js，状态标签映射见 contract-helpers.js。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { createContractApi } from '@/api/proc/interface-integration'
import { useDirectory } from './useDirectory'
import { stTag } from './contract-helpers'

const { contracts, load } = useDirectory()

const createDrawer = ref(false)
const cf = ref({ name: '', version: '1.0.0', remark: '' })

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
</script>

<style scoped>
.intf-page { padding: 12px; }
.tab-bar { margin-bottom: 10px; display: flex; justify-content: flex-end; }
</style>
