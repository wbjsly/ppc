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
      <el-button size="small" type="warning" :disabled="!isAdmin" :loading="syncing"
                 @click="doSync">
        同步法定假日
      </el-button>
      <el-tag v-if="!isAdmin" type="info" size="small">当前角色只读</el-tag>
      <span class="tip">未维护日期由参数 KITTING_CALENDAR_FALLBACK 兜底判定（WEEKDAY=工作日/周末休）；法定假日以 HR 假日日历为源，点「同步法定假日」拉取（幂等，下方假日子区可维护源头）</span>
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

    <!-- 法定假日源头维护（HR 假日日历；同步按钮把这里的数据映射到上方工作日历） -->
    <el-divider content-position="left">法定假日源头（HR 假日日历 — 点「同步法定假日」映射到工作日历）</el-divider>
    <div class="toolbar">
      <el-button size="small" type="primary" @click="loadHolidays">刷新</el-button>
      <el-button size="small" type="success" :disabled="!isAdmin" @click="openHolidayEdit(null)">
        新增假日
      </el-button>
    </div>
    <el-table :data="holidays" v-loading="holidayLoading" border stripe size="small" max-height="260">
      <el-table-column prop="holidayDate" label="日期" width="130" align="center" />
      <el-table-column label="星期" width="80" align="center">
        <template #default="{ row }">{{ weekday(row.holidayDate) }}</template>
      </el-table-column>
      <el-table-column prop="name" label="假日名称" min-width="140" />
      <el-table-column label="类型" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="row.type === 'WORKDAY' ? 'success' : (row.type === 'STATUTORY' ? 'danger' : 'warning')"
                  size="small">{{ typeLabel(row.type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
      <el-table-column label="操作" width="130" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" link :disabled="!isAdmin"
                     @click="openHolidayEdit(row)">修改</el-button>
          <el-button size="small" type="danger" link :disabled="!isAdmin"
                     @click="removeHoliday(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!holidayLoading && holidays.length === 0" description="无假日记录" :image-size="50" />

    <el-dialog v-model="holidayVisible" :title="holidayId ? '修改法定假日' : '新增法定假日'" width="420px">
      <el-form label-width="90px" size="small">
        <el-form-item label="日期">
          <el-date-picker v-model="holidayDate" type="date" value-format="YYYY-MM-DD"
                          :disabled="!!holidayId" placeholder="选择日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="假日名称">
          <el-input v-model="holidayName" placeholder="如：元旦 / 国庆节" maxlength="64" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="holidayType">
            <el-radio value="STATUTORY">法定假</el-radio>
            <el-radio value="RESTDAY">休息日</el-radio>
            <el-radio value="WORKDAY">调休上班</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="holidayRemark" maxlength="128" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="holidayVisible = false">取消</el-button>
        <el-button type="primary" @click="saveHoliday">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onActivated } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getCalendarDatesApi, upsertCalendarDateApi,
  getHolidaysApi, createHolidayApi, updateHolidayApi, deleteHolidayApi, syncHolidaysApi
} from '@/api/system/calendar'
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

// 法定假日源头维护（close-work-calendar-gaps）
const holidays = ref([])
const holidayLoading = ref(false)
const holidayVisible = ref(false)
const holidayId = ref('')
const holidayDate = ref('')
const holidayName = ref('')
const holidayType = ref('STATUTORY')
const holidayRemark = ref('')
const syncing = ref(false)

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

// ---------- 法定假日源头维护与同步（close-work-calendar-gaps，spec capacity-load ADDED） ----------

function typeLabel(type) {
  return { STATUTORY: '法定假', RESTDAY: '休息日', WORKDAY: '调休上班' }[type] || type
}

async function loadHolidays() {
  holidayLoading.value = true
  try {
    const res = await getHolidaysApi()
    holidays.value = res.data || []
  } finally {
    holidayLoading.value = false
  }
}

function openHolidayEdit(row) {
  holidayId.value = row ? row.id : ''
  holidayDate.value = row ? row.holidayDate : ''
  holidayName.value = row ? row.name : ''
  holidayType.value = row ? row.type : 'STATUTORY'
  holidayRemark.value = row ? (row.remark || '') : ''
  holidayVisible.value = true
}

async function saveHoliday() {
  if (!holidayDate.value || !holidayName.value) {
    ElMessage.warning('日期与假日名称必填')
    return
  }
  try {
    const payload = { holidayDate: holidayDate.value, name: holidayName.value,
      type: holidayType.value, remark: holidayRemark.value }
    if (holidayId.value) {
      await updateHolidayApi(holidayId.value, payload)
    } else {
      await createHolidayApi(payload)
    }
    ElMessage.success('已保存')
    holidayVisible.value = false
    await loadHolidays()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  }
}

async function removeHoliday(row) {
  try {
    await ElMessageBox.confirm(`确认删除假日「${row.name}」（${row.holidayDate}）？`, '删除法定假日',
      { type: 'warning' })
  } catch { return }
  try {
    await deleteHolidayApi(row.id)
    ElMessage.success('已删除')
    await loadHolidays()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '删除失败')
  }
}

async function doSync() {
  syncing.value = true
  try {
    const res = await syncHolidaysApi()
    const affected = res.data?.affected ?? 0
    ElMessage.success(`同步完成：变更 ${affected} 行（幂等重跑安全）`)
    await Promise.all([load(), loadHolidays()])
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '同步失败')
  } finally {
    syncing.value = false
  }
}

onMounted(() => { range.value = defaultRange(); load(); loadHolidays() })
onActivated(() => {
  if (range.value.length !== 2) range.value = defaultRange()
  load(); loadHolidays()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
</style>
