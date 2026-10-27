<template>
  <div class="ip-judge">
    <el-card style="margin-bottom: 12px;">
      <div class="hint">
        IP 归属协议维护（FR-4.8-6-2，BR-4.8-24/25）：协议结构化承载合作模式（独立/合作/委托）与权属比例。
        合作研发公司+合作方比例合计必须 = 100%（不可模糊）；每项目一份生效协议，后签覆盖前签并留痕历史。
        协议是权属自动判定的数据基座，也是立项卡控（无协议不可立项）的依据。维护权限：法务/管理员。
      </div>
    </el-card>

    <el-card style="margin-bottom: 12px;">
      <template #header><b>新建/编辑协议</b></template>
      <el-form label-width="120px" size="default">
        <el-row :gutter="16">
          <el-col :span="10">
            <el-form-item label="关联项目" required>
              <el-select v-model="form.projectId" filterable placeholder="选择项目" style="width: 100%;">
                <el-option v-for="p in projects" :key="p.id"
                  :label="`${p.projectNo || ''} ${p.name}`" :value="p.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="7">
            <el-form-item label="合作模式" required>
              <el-select v-model="form.coopMode" style="width: 100%;">
                <el-option value="INDEP" label="独立研发" />
                <el-option value="COOP" label="合作研发" />
                <el-option value="ENTRUST" label="委托研发" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="7">
            <el-form-item label="签署日期" required>
              <el-date-picker v-model="form.signDate" type="date" value-format="YYYY-MM-DD"
                style="width: 100%;" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16" v-if="form.coopMode === 'COOP'">
          <el-col :span="8">
            <el-form-item label="公司比例%" required>
              <el-input-number v-model="form.companyRate" :min="0" :max="100" style="width: 100%;" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="合作方比例%" required>
              <el-input-number v-model="form.partnerRate" :min="0" :max="100" style="width: 100%;" />
              <div class="field-hint">合计必须 = 100%（BR-4.8-25）</div>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="合作方名称" required>
              <el-input v-model="form.partnerName" placeholder="合作方全称" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16" v-if="form.coopMode === 'ENTRUST'">
          <el-col :span="12">
            <el-form-item label="委托方名称" required>
              <el-input v-model="form.entrusteeName" placeholder="委托方全称（权属归其所有）" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="协议条款补充说明" />
        </el-form-item>
        <el-button type="primary" :loading="saving" :disabled="!form.projectId" @click="doSave">保存协议</el-button>
      </el-form>
    </el-card>

    <el-card>
      <template #header><b>协议台账</b></template>
      <el-table :data="list" size="small" border>
        <el-table-column prop="projectId" label="项目" min-width="140" />
        <el-table-column label="合作模式" width="110">
          <template #default="{ row }">{{ modeName(row.coopMode) }}</template>
        </el-table-column>
        <el-table-column label="权属比例" min-width="160">
          <template #default="{ row }">
            <span v-if="row.coopMode === 'COOP'">公司 {{ row.companyRate }}% / {{ row.partnerName }} {{ row.partnerRate }}%</span>
            <span v-else-if="row.coopMode === 'ENTRUST'">归委托方 {{ row.entrusteeName }}</span>
            <span v-else>公司 100%</span>
          </template>
        </el-table-column>
        <el-table-column prop="signDate" label="签署日期" width="110" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.effective === '1' ? 'success' : 'info'" size="small">
              {{ row.effective === '1' ? '生效' : '失效' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as api from '@/api/pm/ip'
import { projectList } from '@/api/pm/project'

const projects = ref([])
const list = ref([])
const saving = ref(false)

const form = ref({ projectId: '', coopMode: 'INDEP', signDate: '', companyRate: 100, partnerRate: 0, partnerName: '', entrusteeName: '', remark: '' })

const MODE_NAMES = { INDEP: '独立研发', COOP: '合作研发', ENTRUST: '委托研发' }
const modeName = m => MODE_NAMES[m] || m

async function load() {
  const { data } = await api.agreementList({})
  list.value = data?.items || []
}

async function doSave() {
  if (form.value.coopMode === 'COOP' && Number(form.value.companyRate) + Number(form.value.partnerRate) !== 100) {
    ElMessage.error('合作研发权属比例合计必须为 100%')
    return
  }
  saving.value = true
  try {
    await api.saveAgreement(form.value)
    ElMessage.success('协议已保存（后签覆盖前签）')
    form.value = { projectId: '', coopMode: 'INDEP', signDate: '', companyRate: 100, partnerRate: 0, partnerName: '', entrusteeName: '', remark: '' }
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  const { data } = await projectList({})
  projects.value = (data || []).map(p => p.project || p)
  await load()
})
</script>

<style scoped>
.hint { color: #909399; font-size: 13px; line-height: 1.7; }
.field-hint { color: #c0c4cc; font-size: 12px; margin-top: 4px; }
</style>
