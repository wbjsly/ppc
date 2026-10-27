<template>
  <div class="closure-archive">
    <el-card style="margin-bottom: 12px;">
      <div class="hint">
        文档归档（S-4.8-07 / BR-4.8-30）：交付物验收凭证与项目文档挂检查项（BIZ_TYPE=PROJECT_CLOSURE，
        bizId=检查项 ID）；《项目总结报告》挂项目（bizId=项目 ID），缺失时结项审批通过动作 422 阻断。
        附件经通用附件中心上传（同名不覆盖）；归档后项目全域只读，数据保留 ≥10 年（BR-4.8-29）。
      </div>
    </el-card>

    <el-card style="margin-bottom: 12px;">
      <template #header>
        <div class="card-head">
          <b>《项目总结报告》（BR-4.8-30）</b>
          <div>
            <el-input v-model="projectId" placeholder="项目 ID" style="width: 240px;" clearable />
            <el-button type="primary" style="margin-left: 8px;" @click="load">加载</el-button>
          </div>
        </div>
      </template>
      <template v-if="projectId">
        <div class="upload-row">
          <el-button size="small" type="primary" @click="uploadReport">上传总结报告</el-button>
          <el-tag :type="reportFiles.length ? 'success' : 'danger'" size="small" style="margin-left: 8px;">
            {{ reportFiles.length ? `已上传 ${reportFiles.length} 份` : '未上传（审批通过将被阻断）' }}
          </el-tag>
        </div>
        <el-table v-if="reportFiles.length" :data="reportFiles" size="small" border
          style="margin-top: 10px;">
          <el-table-column prop="fileName" label="文件名" min-width="220" />
          <el-table-column prop="fileSize" label="大小(B)" width="100" />
          <el-table-column prop="createBy" label="上传人" width="110" />
          <el-table-column label="操作" width="90" align="center">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="download(row)">下载</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>

    <el-card>
      <template #header><b>归档清单（检查单附件凭证汇总）</b></template>
      <template v-if="items.length">
        <el-table :data="items" size="small" border>
          <el-table-column prop="itemName" label="检查项" min-width="200" />
          <el-table-column label="结果" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.result === 'PASS' ? 'success'
                : row.result === 'FAIL' ? 'danger' : 'info'">{{ row.result }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="凭证附件" min-width="260">
            <template #default="{ row }">
              <template v-if="row.autoFlag === '0'">
                <el-button size="small" link type="primary" @click="uploadItemAttach(row)">
                  上传凭证
                </el-button>
                <el-tag v-if="row.attachIds" type="success" size="small" style="margin-left: 6px;">
                  已挂凭证
                </el-tag>
              </template>
              <span v-else>自动校验（数值证据见结项检查页）</span>
            </template>
          </el-table-column>
          <el-table-column label="已传文件" min-width="220">
            <template #default="{ row }">
              <template v-if="itemFiles[row.id] && itemFiles[row.id].length">
                <div v-for="f in itemFiles[row.id]" :key="f.id" class="file-line">
                  <el-button type="primary" link size="small" @click="download(f)">{{ f.fileName }}</el-button>
                </div>
              </template>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty v-else description="加载项目后展示归档清单（须先发起结项）" />
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as api from '@/api/pm/closure'

const projectId = ref('')
const items = ref([])
const reportFiles = ref([])
const itemFiles = ref({})

async function load() {
  if (!projectId.value) return
  const { data } = await api.closureDetail(projectId.value)
  items.value = (data.items || []).filter(i => i.id)
  const rep = await api.attachList('PROJECT_CLOSURE', projectId.value)
  reportFiles.value = rep.data || []
  const map = {}
  for (const it of items.value) {
    const f = await api.attachList('PROJECT_CLOSURE', it.id)
    map[it.id] = f.data || []
  }
  itemFiles.value = map
}

async function uploadReport() {
  const file = await pickFile()
  if (!file) return
  await api.uploadAttach(file, 'PROJECT_CLOSURE', projectId.value)
  ElMessage.success('总结报告已上传')
  load()
}

async function uploadItemAttach(row) {
  const file = await pickFile()
  if (!file) return
  await api.uploadAttach(file, 'PROJECT_CLOSURE', row.id)
  await api.confirmItem(row.id, { result: 'PASS', attachIds: 'uploaded' })
  ElMessage.success('凭证已上传并确认该项通过')
  load()
}

function download(row) {
  window.open(`/api/common/attachments/download?id=${row.id}`, '_blank')
}

function pickFile() {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.onchange = () => resolve(input.files && input.files[0])
    input.oncancel = () => resolve(null)
    input.click()
  })
}
</script>

<style scoped>
.hint { color: #909399; font-size: 12px; line-height: 1.7; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.file-line { line-height: 1.9; }
</style>
