<template>
  <div class="intf-page">
    <PageInfo title="接口目录（15.1）：契约登记、评审队列、语义化版本台账" description="复用接入治理数据（interface-onboarding）。契约未登记/未发布不得签发生产凭证（BR-5.5-01）。" style="margin-bottom:12px" />

    <!-- 15.1.3 版本登记 -->
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
/**
 * 15.1.3 版本登记（拆分自原接口目录操作台）：版本台账 / 版本变更抽屉。
 * 共享状态见 useDirectory.js，状态标签映射见 contract-helpers.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { changeVersionApi } from '@/api/proc/interface-integration'
import { useDirectory } from './useDirectory'
import { stTag } from './contract-helpers'

const { contracts, load } = useDirectory()

const verDrawer = ref(false)
const verRow = ref(null)
const vf = ref({ version: '', remark: '' })

onMounted(load)

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
</style>
