<template>
  <div class="line-editor">
    <div class="toolbar">
      <el-button size="small" type="primary" plain :disabled="disabled" @click="addRow">+ 添加子项行</el-button>
      <span class="tip">用量须 &gt; 0；损耗率 0~100%；替代料单位须与主料一致（保存时后端 L1 校验）</span>
    </div>

    <el-table :data="rows" size="small" border>
      <el-table-column label="#" width="44" type="index" />
      <el-table-column label="子项物料" min-width="210">
        <template #default="{ row }">
          <el-select v-model="row.itemCode" filterable remote :remote-method="k => searchItems(k, row)"
                     :loading="row.loading" :disabled="disabled" placeholder="搜索物料（仅启用）"
                     size="small" style="width: 100%;" @change="v => onItemChange(v, row)">
            <el-option v-for="it in row.options" :key="it.itemCode"
                       :label="`${it.itemCode} ${it.itemName}`" :value="it.itemCode" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="单位" width="70">
        <template #default="{ row }">{{ row.uom || '-' }}</template>
      </el-table-column>
      <el-table-column label="用量" width="118">
        <template #default="{ row }">
          <el-input-number v-model="row.qty" :min="0" :precision="3" :disabled="disabled"
                           size="small" style="width: 100%;" />
        </template>
      </el-table-column>
      <el-table-column label="损耗率%" width="104">
        <template #default="{ row }">
          <el-input-number v-model="row.lossRate" :min="0" :max="100" :precision="2"
                           :disabled="disabled" size="small" style="width: 100%;" />
        </template>
      </el-table-column>
      <el-table-column label="生效日期" width="138">
        <template #default="{ row }">
          <el-date-picker v-model="row.effectiveDate" type="date" value-format="YYYY-MM-DD"
                          :disabled="disabled" size="small" style="width: 100%;" />
        </template>
      </el-table-column>
      <el-table-column label="失效日期" width="138">
        <template #default="{ row }">
          <el-date-picker v-model="row.expiryDate" type="date" value-format="YYYY-MM-DD"
                          :disabled="disabled" size="small" style="width: 100%;" />
        </template>
      </el-table-column>
      <el-table-column label="替代料" width="120">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openSubs(row)">
            替代料（{{ (row.substitutes || []).length }}）
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="备注" min-width="120">
        <template #default="{ row }">
          <el-input v-model="row.remark" :disabled="disabled" size="small" maxlength="200" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="66" fixed="right">
        <template #default="{ $index }">
          <el-button link type="danger" size="small" :disabled="disabled" @click="rows.splice($index, 1)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 行级替代料编辑（FR-4.5-1-3：1:N 比例 + 优先级） -->
    <el-dialog v-model="subsVisible" :title="`行级替代料 — ${subsRow ? subsRow.itemCode : ''}`" width="820px"
               append-to-body>
      <template v-if="subsRow">
        <div class="toolbar">
          <el-button size="small" type="primary" plain @click="addSubRow">+ 添加替代料</el-button>
          <el-button size="small" type="warning" plain :loading="mdmLoading" @click="importMdm">
            从 MDM 替代关系带出候选
          </el-button>
          <span class="tip">替代比例 = 1 单位主料可替代的替代料数量（如 1.2）；优先级数值小者优先；单位不一致将被 L1 阻断</span>
        </div>
        <el-table :data="subsRow.substitutes" size="small" border>
          <el-table-column label="#" width="44" type="index" />
          <el-table-column label="替代物料" min-width="220">
            <template #default="{ row }">
              <el-select v-model="row.substituteItemCode" filterable remote
                         :remote-method="k => searchSubItems(k, row)" :loading="row.loading"
                         placeholder="搜索替代料" size="small" style="width: 100%;"
                         @change="v => onSubChange(v, row)">
                <el-option v-for="it in row.options" :key="it.itemCode"
                           :label="`${it.itemCode} ${it.itemName}`" :value="it.itemCode" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="单位" width="70">
            <template #default="{ row }">{{ row.uom || '-' }}</template>
          </el-table-column>
          <el-table-column label="替代比例" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.ratio" :min="0.0001" :precision="4" size="small"
                               style="width: 100%;" />
            </template>
          </el-table-column>
          <el-table-column label="优先级" width="110">
            <template #default="{ row }">
              <el-input-number v-model="row.priority" :min="1" :precision="0" size="small"
                               style="width: 100%;" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="66">
            <template #default="{ $index }">
              <el-button link type="danger" size="small" @click="subsRow.substitutes.splice($index, 1)">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button type="primary" @click="subsVisible = false">完成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { substituteCandidatesApi } from '@/api/mrp/bom'

// 共享行编辑器（design D6）：创建与变更两页复用；v-model = 行数组（行携带 substitutes）
const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const rows = ref(props.modelValue || [])
watch(() => props.modelValue, v => { rows.value = v || [] })
watch(rows, v => emit('update:modelValue', v), { deep: true })

const subsVisible = ref(false)
const subsRow = ref(null)
const mdmLoading = ref(false)

function newLine() {
  return {
    itemCode: '', itemName: '', uom: '',
    qty: 1, lossRate: 0,
    effectiveDate: null, expiryDate: null, remark: '',
    substitutes: [], options: [], loading: false
  }
}

function addRow() {
  rows.value.push(newLine())
}

async function searchItems(kw, row) {
  row.loading = true
  try {
    const res = await request.get('/mdm/items', {
      params: { current: 1, size: 20, keyword: kw || '', status: '1' }
    })
    row.options = res.data.records || []
  } finally {
    row.loading = false
  }
}

function onItemChange(code, row) {
  const it = (row.options || []).find(x => x.itemCode === code)
  if (it) {
    row.itemName = it.itemName
    row.uom = it.baseUnit
  }
}

function openSubs(row) {
  subsRow.value = row
  if (!row.substitutes) row.substitutes = []
  subsVisible.value = true
}

function addSubRow() {
  subsRow.value.substitutes.push({
    substituteItemCode: '', substituteItemName: '', uom: '',
    ratio: 1, priority: (subsRow.value.substitutes?.length || 0) + 1,
    options: [], loading: false
  })
}

async function searchSubItems(kw, row) {
  row.loading = true
  try {
    const res = await request.get('/mdm/items', {
      params: { current: 1, size: 20, keyword: kw || '', status: '1' }
    })
    row.options = res.data.records || []
  } finally {
    row.loading = false
  }
}

function onSubChange(code, row) {
  const it = (row.options || []).find(x => x.itemCode === code)
  if (it) {
    row.substituteItemName = it.itemName
    row.uom = it.baseUnit
  }
}

// MDM 物料替代关系（菜单 1.2.5）候选带出——只读引用，落为行级替代结构
async function importMdm() {
  if (!subsRow.value?.itemCode) {
    ElMessage.warning('请先选择该行的主料子项物料')
    return
  }
  mdmLoading.value = true
  try {
    const res = await substituteCandidatesApi(subsRow.value.itemCode)
    const cands = res.data || []
    if (!cands.length) {
      ElMessage.info('该物料在 MDM 未配置替代关系')
      return
    }
    let added = 0
    for (const c of cands) {
      const exists = subsRow.value.substitutes.some(s => s.substituteItemCode === c.itemCode)
      if (!exists) {
        subsRow.value.substitutes.push({
          substituteItemCode: c.itemCode,
          substituteItemName: c.itemName,
          uom: c.baseUnit,
          ratio: Number(c.ratio) || 1,
          priority: c.priority || (subsRow.value.substitutes.length + 1),
          options: [c], loading: false
        })
        added++
      }
    }
    ElMessage.success(added > 0 ? `已带出 ${added} 个候选替代料` : '候选已在列表中')
  } finally {
    mdmLoading.value = false
  }
}
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.tip {
  color: #909399;
  font-size: 12px;
}
.line-editor {
  width: 100%;
}
</style>
