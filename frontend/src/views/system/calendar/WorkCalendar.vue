<template>
  <div>
    <el-page-header content="工作日历（16.11.1）— 全局工作日/休息日维护（排程与负荷的时段地基）；写操作仅管理员，计划员只读"
                    style="margin-bottom: 8px" />

    <div class="toolbar">
      <el-date-picker v-model="range" type="daterange" size="small" value-format="YYYY-MM-DD"
                      start-placeholder="开始" end-placeholder="结束" :clearable="false"
                      style="width: 260px" />
      <el-button size="small" type="primary" @click="load">查询</el-button>
      <el-button size="small" type="success" :disabled="!isAdmin" @click="openEdit(null)">
        维护选中日
      </el-button>
      <el-tag v-if="!isAdmin" type="info" size="small">当前角色只读</el-tag>
      <span class="tip">未维护日期由参数 KITTING_CALENDAR_FALLBACK 兜底判定（WEEKDAY=工作日/周末休）；节假日每年 11 月按国务院安排补标（D13 运维）</span>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe size="small" max-height="440"
              highlight-current-row @current-change="onPick">
      <el-table-column prop="calDate" label="日期" width="130" align="center" />
      <el-table-column label="星期" width="90" align="center">
        <template #default="{ row }">{{ weekday(row.calDate) }}</template>
      </el-table-column>
      <el-table-column label="类型" width="120" align="center">
        <template #default="{ row }">
          <el-tag :type="row.isWorkday === '1' ? 'success' : 'danger'" size="small">
            {{ row.isWorkday === '1' ? '工作日' : '休息日' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
      <el-table-column prop="updateBy" label="维护人" width="120" />
      <el-table-column prop="verNo" label="版本" width="70" align="center" />
      <el-table-column label="操作" width="100" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" link :disabled="!isAdmin"
                     @click="openEdit(row)">修改</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && rows.length === 0"
              description="区间内无日历行（未维护——排程按 Fallback 参数兜底判定）" :image-size="60" />

    <el-dialog v-model="editVisible" title="维护工作日历" width="420px">
      <el-form label-width="90px" size="small">
        <el-form-item label="日期">
          <el-input :model-value="editDate" disabled />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="editWorkday">
            <el-radio value="1">工作日</el-radio>
            <el-radio value="0">休息日</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editRemark" placeholder="如：国庆节 / 调休上班" maxlength="50" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onActivated } from 'vue'
import { ElMessage } from 'element-plus'
import { getCalendarDatesApi, upsertCalendarDateApi } from '@/api/system/calendar'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const loading = ref(false)
const rows = ref([])
const range = ref([])
const picked = ref(null)
const editVisible = ref(false)
const editDate = ref('')
const editWorkday = ref('1')
const editRemark = ref('')

const isAdmin = computed(() => {
  const roles = userStore.userInfo?.roles || []
  return roles.includes('ROLE_ADMIN')
})

function defaultRange() {
  const fmt = d => d.toISOString().slice(0, 10)
  const from = new Date()
  const to = new Date()
  to.setDate(to.getDate() + 29)
  return [fmt(from), fmt(to)]
}

function weekday(d) {
  return ['日', '一', '二', '三', '四', '五', '六'][new Date(d).getDay()]
}

async function load() {
  loading.value = true
  try {
    const [from, to] = range.value.length === 2 ? range.value : defaultRange()
    const res = await getCalendarDatesApi(from, to)
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

function onPick(row) {
  picked.value = row
}

function openEdit(row) {
  const target = row || picked.value
  if (!target) {
    ElMessage.warning('请先在表格中选中一行（仅已有行可维护——新日期由种子生成）')
    return
  }
  editDate.value = target.calDate
  editWorkday.value = target.isWorkday
  editRemark.value = target.remark || ''
  editVisible.value = true
}

async function save() {
  try {
    await upsertCalendarDateApi(editDate.value, editWorkday.value, editRemark.value)
    ElMessage.success('已保存（留痕操作人与版本）')
    editVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  }
}

onMounted(() => { range.value = defaultRange(); load() })
onActivated(() => { if (range.value.length !== 2) range.value = defaultRange(); load() })
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
</style>
