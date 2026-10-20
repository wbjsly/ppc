<template>
  <div class="eam-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px;"
      title="资产分类（7.1.4）：转资时按分类带入默认折旧政策；资本化下限为 BR-4.13-09 建卡阻断依据"
      description="无审批立即生效（主数据基线）。分类含：资本化下限、默认年限/残值率/折旧方法、年限上下限、关联资产科目（转资凭证借方取数）。" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="分类编码 / 名称" clearable style="width:220px;"
          @keyup.enter="load" @clear="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <span class="spacer" />
        <el-button v-if="canEdit" type="success" @click="openCreate">新增分类</el-button>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="categoryCode" label="编码" width="100">
          <template #default="{ row }"><b>{{ row.categoryCode }}</b></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column label="资本化下限" width="120" align="right">
          <template #default="{ row }">¥ {{ fmt(row.capitalizationFloor) }}</template>
        </el-table-column>
        <el-table-column label="默认年限" width="90" align="center">
          <template #default="{ row }">{{ row.defaultLifeYears }} 年</template>
        </el-table-column>
        <el-table-column label="默认残值率" width="100" align="center">
          <template #default="{ row }">{{ pct(row.defaultResidualRate) }}</template>
        </el-table-column>
        <el-table-column label="年限范围" width="110" align="center">
          <template #default="{ row }">{{ row.lifeYearsMin }}~{{ row.lifeYearsMax }} 年</template>
        </el-table-column>
        <el-table-column prop="assetAccount" label="资产科目" width="100" align="center" />
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
              {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canEdit" label="操作" width="90" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">修改</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="formVisible" :title="editing ? '修改分类' : '新增分类'" width="520px">
      <el-form label-width="120px" size="small">
        <el-form-item label="分类编码" required>
          <el-input v-model="form.categoryCode" :disabled="editing" placeholder="如 MACH" />
        </el-form-item>
        <el-form-item label="分类名称" required>
          <el-input v-model="form.name" placeholder="如 机器设备" />
        </el-form-item>
        <el-form-item label="资本化下限" required>
          <el-input-number v-model="form.capitalizationFloor" :min="0" :precision="2" />
          <div class="hint">低于下限不可固定资产建卡（BR-4.13-09）</div>
        </el-form-item>
        <el-form-item label="默认使用年限" required>
          <el-input-number v-model="form.defaultLifeYears" :min="1" />
        </el-form-item>
        <el-form-item label="默认残值率" required>
          <el-input-number v-model="form.defaultResidualRate" :min="0" :max="0.99" :step="0.01"
            :precision="4" />
          <div class="hint">0.05 = 5%</div>
        </el-form-item>
        <el-form-item label="年限下限" required>
          <el-input-number v-model="form.lifeYearsMin" :min="1" />
        </el-form-item>
        <el-form-item label="年限上限" required>
          <el-input-number v-model="form.lifeYearsMax" :min="1" />
        </el-form-item>
        <el-form-item label="资产科目" required>
          <el-input v-model="form.assetAccount" placeholder="如 1601（转资凭证借方）" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width:140px;">
            <el-option label="启用" value="ACTIVE" />
            <el-option label="停用" value="INACTIVE" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { getCategoryPageApi, createCategoryApi, updateCategoryApi } from '@/api/eam/asset'

const userStore = useUserStore()
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const keyword = ref('')
const filters = reactive({ current: 1, size: 50 })

const formVisible = ref(false)
const editing = ref(false)
const form = reactive({
  id: '', categoryCode: '', name: '', capitalizationFloor: 5000,
  defaultLifeYears: 10, defaultResidualRate: 0.05, lifeYearsMin: 1, lifeYearsMax: 50,
  assetAccount: '1601', status: 'ACTIVE'
})

const myRoles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const canEdit = computed(() => myRoles.value.some(r =>
  ['ROLE_ADMIN', 'ROLE_ASSET_ADMIN'].includes(r)))

function fmt(v) { return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }
function pct(v) { return `${(Number(v || 0) * 100).toFixed(2)}%` }

async function load() {
  loading.value = true
  try {
    const res = await getCategoryPageApi({ ...filters, keyword: keyword.value })
    rows.value = (res.data && res.data.records) || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = false
  Object.assign(form, {
    id: '', categoryCode: '', name: '', capitalizationFloor: 5000,
    defaultLifeYears: 10, defaultResidualRate: 0.05, lifeYearsMin: 1, lifeYearsMax: 50,
    assetAccount: '1601', status: 'ACTIVE'
  })
  formVisible.value = true
}

function openEdit(row) {
  editing.value = true
  Object.assign(form, {
    id: row.id, categoryCode: row.categoryCode, name: row.name,
    capitalizationFloor: Number(row.capitalizationFloor),
    defaultLifeYears: row.defaultLifeYears,
    defaultResidualRate: Number(row.defaultResidualRate),
    lifeYearsMin: row.lifeYearsMin, lifeYearsMax: row.lifeYearsMax,
    assetAccount: row.assetAccount, status: row.status
  })
  formVisible.value = true
}

async function save() {
  saving.value = true
  try {
    if (editing.value) {
      await updateCategoryApi(form.id, { ...form })
    } else {
      await createCategoryApi({ ...form })
    }
    ElMessage.success('已保存（无审批立即生效）')
    formVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.eam-page { padding: 4px; }
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.spacer { flex: 1; }
.hint { font-size: 12px; color: #909399; line-height: 1.6; }
</style>
