<template>
  <div>
    <el-page-header content="班次模板（16.11.2）— 班次时段（供排程时段装箱）与工作中心绑定；写操作仅管理员，计划员只读"
                    style="margin-bottom: 8px" />

    <div class="toolbar">
      <strong>班次模板</strong>
      <el-button size="small" type="success" :disabled="!isAdmin" @click="openEdit(null)">新建班次</el-button>
      <el-tag v-if="!isAdmin" type="info" size="small">当前角色只读</el-tag>
      <span class="tip">工时必须 > 0；编码唯一；被工作中心引用时禁删（422）</span>
    </div>

    <el-table :data="shifts" v-loading="loading" border stripe size="small">
      <el-table-column prop="shiftCode" label="班次编码" width="140" />
      <el-table-column prop="shiftName" label="名称" min-width="130" show-overflow-tooltip />
      <el-table-column prop="startTime" label="开始" width="90" align="center" />
      <el-table-column prop="endTime" label="结束" width="90" align="center" />
      <el-table-column prop="hours" label="工时(h)" width="90" align="right" />
      <el-table-column prop="status" label="状态" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === '1' ? 'success' : 'info'" size="small">
            {{ row.status === '1' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" link :disabled="!isAdmin"
                     @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" link :disabled="!isAdmin"
                     @click="doDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- WC 绑定 -->
    <div class="toolbar" style="margin-top: 16px">
      <strong>工作中心班次绑定</strong>
      <el-input v-model="wcCode" placeholder="工作中心编码" clearable style="width: 160px"
                size="small" @keyup.enter="loadBindings" @clear="loadBindings" />
      <el-button size="small" type="primary" @click="loadBindings">查询</el-button>
      <span class="tip">未绑定的 WC 排程时按默认白班 DAY；首期单班次替换式绑定</span>
    </div>
    <el-table :data="bindings" v-loading="bLoading" border stripe size="small" max-height="260">
      <el-table-column prop="wcCode" label="工作中心" width="160" />
      <el-table-column prop="shiftCode" label="绑定班次" width="140" />
      <el-table-column label="操作" width="110" align="center">
        <template #default="{ row }">
          <el-button size="small" type="danger" link :disabled="!isAdmin"
                     @click="doUnbind(row)">解绑</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!bLoading && bindings.length === 0" description="无绑定（全部 WC 走默认白班）"
              :image-size="40" />

    <div class="toolbar" style="margin-top: 10px">
      <el-input v-model="bindWc" placeholder="WC 编码" style="width: 150px" size="small" />
      <el-select v-model="bindShift" placeholder="选择班次" style="width: 160px" size="small">
        <el-option v-for="s in shifts" :key="s.shiftCode" :label="`${s.shiftCode}（${s.hours}h）`"
                   :value="s.shiftCode" />
      </el-select>
      <el-button size="small" type="primary" :disabled="!isAdmin || !bindWc || !bindShift"
                 @click="doBind">绑定 / 改绑</el-button>
    </div>

    <el-dialog v-model="editVisible" :title="editing ? '编辑班次' : '新建班次'" width="440px">
      <el-form label-width="90px" size="small">
        <el-form-item label="编码">
          <el-input v-model="form.shiftCode" :disabled="!!editing" placeholder="如 NIGHT"
                    maxlength="20" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.shiftName" placeholder="如 夜班" maxlength="40" />
        </el-form-item>
        <el-form-item label="时段">
          <el-time-select v-model="form.startTime" start="00:00" step="00:30" end="23:30"
                          style="width: 120px" placeholder="开始" />
          <span style="margin: 0 6px">~</span>
          <el-time-select v-model="form.endTime" start="00:00" step="00:30" end="23:59"
                          style="width: 120px" placeholder="结束" />
        </el-form-item>
        <el-form-item label="工时(h)">
          <el-input-number v-model="form.hours" :min="0.5" :max="24" :step="0.5"
                           style="width: 140px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" active-value="1" inactive-value="0"
                     active-text="启用" inactive-text="停用" />
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
import { ref, computed, reactive, onMounted, onActivated } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getShiftsApi, createShiftApi, updateShiftApi, deleteShiftApi,
  getWcShiftsApi, bindWcShiftApi, unbindWcShiftApi
} from '@/api/system/calendar'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const loading = ref(false)
const bLoading = ref(false)
const shifts = ref([])
const bindings = ref([])
const wcCode = ref('')
const bindWc = ref('')
const bindShift = ref('')
const editVisible = ref(false)
const editing = ref(null)
const form = reactive({ shiftCode: '', shiftName: '', startTime: '', endTime: '', hours: 8, status: '1' })

const isAdmin = computed(() => (userStore.userInfo?.roles || []).includes('ROLE_ADMIN'))

async function load() {
  loading.value = true
  try {
    const res = await getShiftsApi()
    shifts.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function loadBindings() {
  bLoading.value = true
  try {
    const res = await getWcShiftsApi(wcCode.value || undefined)
    bindings.value = res.data || []
  } finally {
    bLoading.value = false
  }
}

function openEdit(row) {
  editing.value = row
  if (row) {
    Object.assign(form, {
      shiftCode: row.shiftCode, shiftName: row.shiftName || '',
      startTime: row.startTime || '', endTime: row.endTime || '',
      hours: Number(row.hours) || 8, status: row.status || '1'
    })
  } else {
    Object.assign(form, { shiftCode: '', shiftName: '', startTime: '', endTime: '', hours: 8, status: '1' })
  }
  editVisible.value = true
}

async function save() {
  try {
    if (editing.value) {
      await updateShiftApi(editing.value.id, { ...form })
    } else {
      await createShiftApi({ ...form })
    }
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  }
}

async function doDelete(row) {
  try {
    await ElMessageBox.confirm(`删除班次 ${row.shiftCode}？被工作中心引用将被拒绝。`, '删除', { type: 'warning' })
  } catch { return }
  try {
    await deleteShiftApi(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '删除失败')
  }
}

async function doBind() {
  try {
    await bindWcShiftApi(bindWc.value.trim(), bindShift.value)
    ElMessage.success('绑定成功（替换式）')
    await loadBindings()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '绑定失败')
  }
}

async function doUnbind(row) {
  try {
    await unbindWcShiftApi(row.id)
    ElMessage.success('已解绑（该 WC 回落默认白班）')
    await loadBindings()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '解绑失败')
  }
}

onMounted(() => { load(); loadBindings() })
onActivated(() => { load(); loadBindings() })
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
</style>
