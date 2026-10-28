<template>
  <div class="page-wrap">
    <!-- 订阅配置 -->
    <el-card shadow="never" class="mb12">
      <div class="card-head mb12">
        <h4 style="margin:0">订阅配置（12.2.2）—— 伙伴 × 事件类型独立路由</h4>
        <div>
          <el-input v-model="query.partnerCode" placeholder="伙伴编码" clearable size="small"
                    style="width:150px" @keyup.enter="load" />
          <el-input v-model="query.eventType" placeholder="事件类型" clearable size="small"
                    style="width:180px;margin-left:8px" @keyup.enter="load" />
          <el-button size="small" style="margin-left:8px" @click="load">查询</el-button>
          <el-button size="small" type="primary" style="margin-left:8px"
                     @click="openCreate">新增订阅</el-button>
        </div>
      </div>
      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="partnerCode" label="伙伴编码" width="130" />
        <el-table-column prop="eventType" label="事件类型" width="210" />
        <el-table-column label="回调 URL" min-width="240">
          <template #default="{ row }">
            <span v-if="row.callbackUrl">{{ row.callbackUrl }}</span>
            <el-tag v-else size="small" type="info">回落通道 URL</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="RETRY_MAX" width="110" align="center">
          <template #default="{ row }">{{ row.retryMax }} 次</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
              {{ row.status === 'ENABLED' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="120" />
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" text :type="row.status === 'ENABLED' ? 'warning' : 'success'"
                       @click="doToggle(row)">{{ row.status === 'ENABLED' ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="hint mt12">
        两级回落零断流：启用订阅且 URL 非空 → 按订阅投递；无订阅 / URL 空 / 停用 → 回落通道 CALLBACK_URL
        （通道也无 URL 则按台账确认投递）。RETRY_MAX 为该订阅的行级死信阈值（1~5，覆盖全局默认 3）。
        <b>修改仅对新投递生效</b>——在途投递保持建行时的快照（防中途换目标导致半投递）。
      </div>
    </el-card>

    <!-- 新增/编辑 -->
    <el-dialog v-model="formVisible" :title="editing ? '编辑订阅' : '新增订阅'" width="540px">
      <el-form :model="form" label-width="110px" size="small">
        <el-form-item label="伙伴编码" required>
          <el-input v-model="form.partnerCode" :disabled="editing" placeholder="如 SUP0001" />
        </el-form-item>
        <el-form-item label="事件类型" required>
          <el-input v-model="form.eventType" :disabled="editing" placeholder="如 PROC.PO_PUSHED（12.2.1 注册的类型码）" />
        </el-form-item>
        <el-form-item label="回调 URL">
          <el-input v-model="form.callbackUrl" placeholder="https://（留空 = 回落通道 URL）" />
        </el-form-item>
        <el-form-item label="RETRY_MAX">
          <el-input-number v-model="form.retryMax" :min="1" :max="5" />
          <span class="hint" style="margin-left:8px">1~5，默认 3（行级死信阈值）</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="doSave">{{ editing ? '保存' : '创建' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getSubscriptionsApi, createSubscriptionApi, updateSubscriptionApi, toggleSubscriptionApi } from '@/api/proc/event-subscription'

const loading = ref(false)
const rows = ref([])
const query = ref({ partnerCode: '', eventType: '' })

const formVisible = ref(false)
const editing = ref(false)
const form = ref({ id: null, partnerCode: '', eventType: '', callbackUrl: '', retryMax: 3, remark: '' })

async function load() {
  loading.value = true
  try {
    const res = await getSubscriptionsApi({ current: 1, size: 100, ...query.value })
    rows.value = res.data?.records || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = false
  form.value = { id: null, partnerCode: '', eventType: '', callbackUrl: '', retryMax: 3, remark: '' }
  formVisible.value = true
}

function openEdit(row) {
  editing.value = true
  form.value = { id: row.id, partnerCode: row.partnerCode, eventType: row.eventType, callbackUrl: row.callbackUrl || '', retryMax: row.retryMax || 3, remark: row.remark || '' }
  formVisible.value = true
}

async function doSave() {
  const f = form.value
  try {
    if (editing.value) {
      await updateSubscriptionApi(f.id, { callbackUrl: f.callbackUrl, retryMax: f.retryMax, remark: f.remark })
      ElMessage.success('已保存（对新投递生效）')
    } else {
      await createSubscriptionApi({ partnerCode: f.partnerCode.trim(), eventType: f.eventType.trim(), callbackUrl: f.callbackUrl, retryMax: f.retryMax, remark: f.remark })
      ElMessage.success('订阅已创建')
    }
    formVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message)
  }
}

async function doToggle(row) {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  await toggleSubscriptionApi(row.id, next)
  ElMessage.success(next === 'DISABLED' ? '已停用（该类型新投递回落通道 URL）' : '已启用')
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
