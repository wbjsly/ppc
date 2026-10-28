<template>
  <div class="page-wrap">
    <!-- 事件定义注册表 -->
    <el-card shadow="never" class="mb12">
      <div class="card-head mb12">
        <h4 style="margin:0">事件定义（12.2.1）—— 注册后发布走校验，无需改代码发版</h4>
        <div>
          <el-input v-model="query.eventType" placeholder="类型码" clearable size="small"
                    style="width:180px" @keyup.enter="load" />
          <el-select v-model="query.status" placeholder="状态" clearable size="small"
                     style="width:110px;margin-left:8px" @change="load">
            <el-option label="启用" value="ENABLED" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
          <el-button size="small" style="margin-left:8px" @click="load">查询</el-button>
          <el-button size="small" type="primary" style="margin-left:8px"
                     @click="openCreate">注册事件类型</el-button>
        </div>
      </div>
      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="eventType" label="类型码" width="200" />
        <el-table-column prop="typeName" label="名称" min-width="140" />
        <el-table-column label="版本" width="80" align="center">
          <template #default="{ row }">v{{ row.versionMajor }}.{{ row.versionMinor }}</template>
        </el-table-column>
        <el-table-column label="必填字段" min-width="260">
          <template #default="{ row }">
            <el-tag v-for="f in requiredOf(row)" :key="f.field" size="small"
                    style="margin-right:4px">{{ f.field }}:{{ f.type }}</el-tag>
            <span v-if="!requiredOf(row).length" class="hint">（无必填约束）</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
              {{ row.status === 'ENABLED' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" text :type="row.status === 'ENABLED' ? 'warning' : 'success'"
                       @click="doToggle(row)">{{ row.status === 'ENABLED' ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="hint mt12">
        命名规范 <b>{业务对象}_{动作}</b>（可带域前缀，如 PROC.PO_PUSHED）。
        发布校验：必填缺失 L3 拒发、类型不符 L4 警告放行、未注册/停用拒发。
        <b>Schema 变更须升 Major 版本</b>（删必填/改类型/收紧），版本落事件信封供消费方分支处理。
      </div>
    </el-card>

    <!-- 注册/编辑 -->
    <el-dialog v-model="formVisible" :title="editing ? '编辑事件类型' : '注册事件类型'" width="640px">
      <el-form :model="form" label-width="100px" size="small">
        <el-form-item label="类型码" required>
          <el-input v-model="form.eventType" :disabled="editing"
                    placeholder="PO_CREATED / PROC.PO_PUSHED（{对象}_{动作}）" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.typeName" placeholder="如 采购订单下达推送" />
        </el-form-item>
        <el-form-item label="必填字段">
          <div style="width:100%">
            <div v-for="(f, i) in form.fields" :key="i" style="display:flex;gap:8px;margin-bottom:6px">
              <el-input v-model="f.field" placeholder="字段名，如 poNo" style="width:45%" />
              <el-select v-model="f.type" style="width:30%">
                <el-option label="string" value="string" />
                <el-option label="number" value="number" />
                <el-option label="boolean" value="boolean" />
                <el-option label="array" value="array" />
                <el-option label="object" value="object" />
              </el-select>
              <el-button text type="danger" @click="form.fields.splice(i, 1)">删除</el-button>
            </div>
            <el-button size="small" text type="primary" @click="form.fields.push({ field: '', type: 'string' })">
              + 添加字段
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="版本">
          <el-input-number v-model="form.versionMajor" :min="1" size="small" />
          <span style="margin:0 6px">.</span>
          <el-input-number v-model="form.versionMinor" :min="0" size="small" />
          <span class="hint" style="margin-left:8px">改 Schema 必须升 Major</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="doSave">{{ editing ? '保存' : '注册' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getEventTypesApi, createEventTypeApi, updateEventTypeApi, toggleEventTypeApi } from '@/api/proc/event-subscription'

const loading = ref(false)
const rows = ref([])
const query = ref({ eventType: '', status: '' })

const formVisible = ref(false)
const editing = ref(false)
const form = ref({ id: null, eventType: '', typeName: '', fields: [], versionMajor: 1, versionMinor: 0, remark: '' })

function requiredOf(row) {
  try {
    return JSON.parse(row.schemaJson || '{}').required || []
  } catch { return [] }
}

async function load() {
  loading.value = true
  try {
    const res = await getEventTypesApi({ current: 1, size: 100, ...query.value })
    rows.value = res.data?.records || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = false
  form.value = { id: null, eventType: '', typeName: '', fields: [{ field: '', type: 'string' }], versionMajor: 1, versionMinor: 0, remark: '' }
  formVisible.value = true
}

function openEdit(row) {
  editing.value = true
  form.value = {
    id: row.id, eventType: row.eventType, typeName: row.typeName,
    fields: requiredOf(row).map(f => ({ field: f.field, type: f.type })),
    versionMajor: row.versionMajor, versionMinor: row.versionMinor, remark: row.remark || ''
  }
  formVisible.value = true
}

async function doSave() {
  const f = form.value
  const schema = JSON.stringify({ required: f.fields.filter(x => x.field.trim()).map(x => ({ field: x.field.trim(), type: x.type })) })
  try {
    if (editing.value) {
      await updateEventTypeApi(f.id, { typeName: f.typeName, schemaJson: schema, versionMajor: f.versionMajor, versionMinor: f.versionMinor, remark: f.remark })
      ElMessage.success('已保存（Schema 变更须升 Major，否则 422）')
    } else {
      await createEventTypeApi({ eventType: f.eventType.trim(), typeName: f.typeName, schemaJson: schema, versionMajor: f.versionMajor, versionMinor: f.versionMinor, remark: f.remark })
      ElMessage.success('事件类型已注册，后续该类型事件可发布')
    }
    formVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message)
  }
}

async function doToggle(row) {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  await toggleEventTypeApi(row.id, next)
  ElMessage.success(next === 'DISABLED' ? '已停用（该类型新事件将被拒发）' : '已启用')
  load()
}

onMounted(load)
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
.hint { color: #909399; font-size: 12px; }
</style>
