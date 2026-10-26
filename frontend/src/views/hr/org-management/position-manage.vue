<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">岗位管理（10.2.2）</span>
        <div>
          <el-select v-model="deptId" clearable placeholder="全部部门" style="width: 200px; margin-right: 8px"
                     @change="load">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <el-button type="primary" @click="openCreate">新建岗位</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-table :data="positions">
        <el-table-column prop="posNo" label="编号" width="120" />
        <el-table-column prop="name" label="岗位名称" min-width="160" />
        <el-table-column label="部门" width="140">
          <template #default="{ row }">{{ deptName(row.deptId) }}</template>
        </el-table-column>
        <el-table-column label="关联职级" width="140">
          <template #default="{ row }">
            <span v-if="row.gradeId">{{ gradeName(row.gradeId) }}</span>
            <el-tag v-else type="info" size="small">未关联</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="带宽（月薪）" width="160">
          <template #default="{ row }">
            <span v-if="bandOf(row).resolved">
              {{ bandOf(row).salMin }} ~ {{ bandOf(row).salMax }}
            </span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="headcount" label="编制" width="80" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '1' ? 'success' : 'info'" size="small">
              {{ row.status === '1' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openMeta(row)">职级/说明书</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建岗位 -->
    <el-dialog v-model="createVisible" title="新建岗位" width="440px">
      <el-form label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="createForm.name" />
        </el-form-item>
        <el-form-item label="所属部门">
          <el-select v-model="createForm.deptId" style="width: 100%">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="编制数">
          <el-input-number v-model="createForm.headcount" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">确定</el-button>
      </template>
    </el-dialog>

    <!-- 职级/说明书 -->
    <el-dialog v-model="metaVisible" :title="`岗位设置：${current.name}`" width="520px">
      <el-form label-width="90px">
        <el-form-item label="关联职级">
          <el-select v-model="metaForm.gradeId" clearable style="width: 100%"
                     placeholder="关联后带宽经职级间接获得">
            <el-option v-for="g in grades" :key="g.id"
                       :label="`${g.gradeNo} ${g.name}（${g.salMin}~${g.salMax}）`" :value="g.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明书要点">
          <el-input v-model="metaForm.jdJson" type="textarea" :rows="4"
                    placeholder='JSON：{"duties":"职责…","kpi":"考核标准…"}' />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="metaVisible = false">取消</el-button>
        <el-button type="primary" @click="submitMeta">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  deptList, positionList, createPosition, updatePositionMeta, gradeList, resolveBand
} from '@/api/hr/org-management'

const depts = ref([])
const grades = ref([])
const positions = ref([])
const deptId = ref(null)
const createVisible = ref(false)
const createForm = ref({ name: '', deptId: null, headcount: null })
const metaVisible = ref(false)
const metaForm = ref({ gradeId: null, jdJson: '' })
const current = ref({})
const bands = ref({})

const deptName = id => depts.value.find(d => d.id === id)?.name || id
const gradeName = id => {
  const g = grades.value.find(x => x.id === id)
  return g ? `${g.gradeNo} ${g.name}` : id
}
const bandOf = row => bands.value[row.id] || { resolved: false }

async function load() {
  const [d, g, p] = await Promise.all([deptList(), gradeList(), positionList(deptId.value)])
  depts.value = d.data || []
  grades.value = g.data || []
  positions.value = p.data || []
  // 带宽链解析（岗位→职级→带宽）
  const entries = await Promise.all(positions.value.map(async row => {
    const r = await resolveBand(row.id)
    return [row.id, r.data || {}]
  }))
  bands.value = Object.fromEntries(entries)
}

function openCreate() {
  createForm.value = { name: '', deptId: deptId.value, headcount: null }
  createVisible.value = true
}
async function submitCreate() {
  if (!createForm.value.name || !createForm.value.deptId) {
    return ElMessage.warning('名称与部门必填')
  }
  await createPosition(createForm.value)
  ElMessage.success('已创建')
  createVisible.value = false
  load()
}
function openMeta(row) {
  current.value = row
  metaForm.value = { gradeId: row.gradeId, jdJson: row.jdJson || '' }
  metaVisible.value = true
}
async function submitMeta() {
  await updatePositionMeta(current.value.id, metaForm.value)
  ElMessage.success('已保存')
  metaVisible.value = false
  load()
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
.muted { color: #999; }
</style>
