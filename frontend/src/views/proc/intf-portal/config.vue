<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="沙箱联调（15.6）：沙箱配置、联调测试、生产放行"
      description="复用 interface-onboarding。沙箱通过率须达 100% 方可申请生产放行（BR-5.5-02）；放行双人会签（C-0-03）。" />

    <!-- 15.6.1 沙箱配置 -->
    <div class="tab-bar">
      <el-button type="primary" size="small" @click="caseDrawer=true">新增测试用例</el-button>
    </div>
    <el-table :data="cases" border size="small">
      <el-table-column prop="caseNo" label="用例号" width="150" />
      <el-table-column prop="contractNo" label="契约" width="150" />
      <el-table-column prop="caseName" label="用例名" min-width="160" />
      <el-table-column prop="expectResult" label="期望结果" width="110" />
      <el-table-column prop="actualResult" label="实际结果" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.actualResult" size="small"
                  :type="row.actualResult === row.expectResult ? 'success' : 'danger'">
            {{ row.actualResult }}
          </el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column prop="executedAt" label="执行时间" width="160">
        <template #default="{ row }">{{ row.executedAt || '未执行' }}</template>
      </el-table-column>
    </el-table>
    <el-alert v-if="!cases.length" type="info" :closable="false" style="margin-top:10px"
              title="暂无测试用例（沙箱环境按契约配置请求样例与期望结果）" />

    <!-- 新增用例抽屉 -->
    <el-drawer title="新增沙箱测试用例" size="440px" v-model="caseDrawer">
      <el-form label-width="90px" size="small">
        <el-form-item label="契约" required>
          <el-select v-model="cf.contractId" filterable style="width:100%" placeholder="选择契约">
            <el-option v-for="c in contracts" :key="c.id" :label="`${c.contractNo} ${c.name}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="用例名" required><el-input v-model="cf.caseName" placeholder="如：正常下单" /></el-form-item>
        <el-form-item label="期望结果" required>
          <el-select v-model="cf.expectResult" style="width:100%">
            <el-option label="PASS" value="PASS" />
            <el-option label="FAIL（负向用例）" value="FAIL" />
          </el-select>
        </el-form-item>
        <el-form-item label="请求样例"><el-input type="textarea" :rows="4" v-model="cf.payloadJson" placeholder='{"orderNo":"..."}' /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="caseDrawer=false">取消</el-button>
        <el-button type="primary" @click="addCase">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * 15.6.1 沙箱配置（拆分自原沙箱联调操作台）：测试用例维护。
 * 共享契约列表见 useSandbox.js；用例状态为本页独有，留页内。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { addSandboxCaseApi, getSandboxCasesApi } from '@/api/proc/interface-integration'
import { useSandbox } from './useSandbox'

const { contracts, loadContracts } = useSandbox()

const cases = ref([])
const caseDrawer = ref(false)
const cf = ref({ contractId: '', caseName: '', expectResult: 'PASS', payloadJson: '' })

async function load() {
  await loadContracts()
  try { const r = await getSandboxCasesApi(); cases.value = r.data?.data || [] } catch { cases.value = [] }
}
onMounted(load)

async function addCase() {
  if (!cf.value.contractId || !cf.value.caseName) { ElMessage.warning('契约与用例名必填'); return }
  await addSandboxCaseApi(cf.value)
  ElMessage.success('用例已保存')
  caseDrawer.value = false
  load()
}
</script>

<style scoped>
.intf-page { padding: 12px; }
.tab-bar { margin-bottom: 10px; display: flex; justify-content: flex-end; }
</style>
