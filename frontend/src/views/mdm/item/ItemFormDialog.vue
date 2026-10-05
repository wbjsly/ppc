<template>
  <el-dialog :model-value="visible" @update:model-value="v => emit('update:visible', v)"
             :title="form.id ? '变更物料' : '新建物料'" width="760px" @open="initForm">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="140px">
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="物料编码">
            <el-input v-model="form.itemCode" :disabled="autoCode"
                      :placeholder="autoCode ? '保存时自动生成' : '手工输入 12 位 / 编辑可改（提交将被阻断）'" />
            <el-checkbox v-model="autoCode" :disabled="!!form.id" style="margin-top: 4px;">系统自动编码</el-checkbox>
          </el-form-item>
        </el-col>
        <el-col v-if="form.id" :span="12">
          <el-form-item label="变更原因" prop="changeReason">
            <el-input v-model="form.changeReason" maxlength="255" placeholder="必填，说明为何变更（≥2字）" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="物料名称" prop="itemName">
            <el-input v-model="form.itemName" maxlength="128" @blur="checkSimilar" />
            <div class="form-tip">规范：通用名+规格型号+材质，如「不锈钢管 DN50 304L」</div>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="物料分类" prop="categoryCode">
            <el-select v-model="form.categoryCode" style="width: 100%;">
              <el-option v-for="c in categories" :key="c.code" :label="`${c.code} ${c.name}`" :value="c.code" />
            </el-select>
            <div v-if="currentCategory" class="form-tip">编码前缀：{{ currentCategory.prefix }} + 分类码 {{ currentCategory.code }} + 6位流水</div>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="风险等级" prop="riskGrade">
            <el-select v-model="form.riskGrade" style="width: 100%;"
                       placeholder="待检时限 A24/B48/C72h（收货 2.4.2）">
              <el-option label="A 高风险（24h 内检验）" value="A" />
              <el-option label="B 中风险（48h 内检验）" value="B" />
              <el-option label="C 低风险（72h 内检验）" value="C" />
            </el-select>
            <div class="form-tip">决定收货待检时限（FR-4.2-4-2），默认 C</div>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="基本计量单位" prop="baseUnit">
            <el-select v-model="form.baseUnit" style="width: 100%;">
              <el-option v-for="d in dicts.UNIT" :key="d.code" :label="`${d.name}（${d.code}）`" :value="d.code" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="物料组" prop="materialGroup">
            <el-select v-model="form.materialGroup" style="width: 100%;">
              <el-option v-for="d in dicts.MATERIAL_GROUP" :key="d.code" :label="d.name" :value="d.code" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="采购类型" prop="purchaseType">
            <el-select v-model="form.purchaseType" style="width: 100%;">
              <el-option label="外购" value="BUY" />
              <el-option label="自制" value="MAKE" />
              <el-option label="委外" value="OUTSOURCE" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="存储条件" prop="storageCondition">
            <el-select v-model="form.storageCondition" style="width: 100%;">
              <el-option v-for="d in dicts.STORAGE" :key="d.code" :label="d.name" :value="d.code" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="批次管理">
            <el-switch v-model="form.batchFlag" active-value="1" inactive-value="0" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="保质期（天）" :prop="form.batchFlag === '1' ? 'shelfLifeDays' : undefined">
            <el-input-number v-model="form.shelfLifeDays" :min="0" :disabled="form.batchFlag !== '1'" style="width: 100%;" />
            <div v-if="form.batchFlag === '1'" class="form-tip">批次管理物料必填（BR-4.1-08）</div>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="安全库存量">
            <el-input-number v-model="form.safetyStock" :min="0" :precision="3" style="width: 100%;" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="采购提前期（天）">
            <el-input-number v-model="form.leadTimeDays" :min="0" style="width: 100%;" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="替代物料编码">
            <el-input v-model="form.altItemCode" maxlength="32" placeholder="可空" @blur="checkSubstitute" />
            <div v-if="substituteTip" class="form-tip" :style="{ color: substituteTipOk ? '#67c23a' : '#f56c6c' }">
              {{ substituteTip }}
            </div>
            <div v-else class="form-tip">行 477：替代物料须已发布、非自身、替代链不成环</div>
          </el-form-item>
        </el-col>
        <template v-if="currentCategory && currentCategory.prefix === 'FG'">
          <el-col :span="12">
            <el-form-item label="包装规格" prop="packingSpec">
              <el-input v-model="form.packingSpec" maxlength="255" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="条码">
              <el-input v-model="form.barcode" maxlength="64" />
            </el-form-item>
          </el-col>
        </template>
        <el-col v-if="form.purchaseType === 'MAKE'" :span="12">
          <el-form-item label="BOM 版本号">
            <el-input v-model="form.bomVersion" maxlength="32" placeholder="可后补（BR-4.1-09）" />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>

    <template v-if="form.id">
      <el-alert
        v-if="isCriticalChange"
        type="warning" show-icon :closable="false" style="margin-bottom: 10px;"
        title="关键属性变更（计量单位 / 物料分类 / 采购类型）"
        description="本次变更将被判定为 CRITICAL 并记入版本历史；后续版本将纳入双人复核审批。"
      />
      <el-divider content-position="left" style="margin-top: 0;">变更预览</el-divider>
      <el-table v-if="changeDiff.length" :data="changeDiff" size="small" border max-height="200">
        <el-table-column prop="label" label="字段" width="150" />
        <el-table-column prop="from" label="旧值" show-overflow-tooltip />
        <el-table-column prop="to" label="新值" show-overflow-tooltip />
      </el-table>
      <div v-else class="form-tip">暂无变更内容 —— 修改上方字段后此处实时显示差异</div>
    </template>

    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createItemApi, updateItemApi, getSimilarApi } from '@/api/mdm/item'
import { checkSubstituteApi } from '@/api/mdm/substitute'

const props = defineProps({
  visible: { type: Boolean, default: false },
  row: { type: Object, default: null },
  /** BR-4.1-13 编码阻断跳转向导的预填属性（新建态） */
  prefill: { type: Object, default: null },
  categories: { type: Array, default: () => [] },
  dicts: { type: Object, default: () => ({ UNIT: [], MATERIAL_GROUP: [], STORAGE: [] }) }
})
const emit = defineEmits(['update:visible', 'saved', 'jump-create'])

const formRef = ref(null)
const saving = ref(false)
const form = ref({})
const autoCode = ref(true)
/** 打开时的存量快照（差异预览基准） */
const baseline = ref(null)
const substituteTip = ref('')
const substituteTipOk = ref(false)

const rules = {
  itemName: [{ required: true, message: '请输入物料名称', trigger: 'blur' }],
  categoryCode: [{ required: true, message: '请选择物料分类', trigger: 'change' }],
  baseUnit: [{ required: true, message: '请选择基本计量单位', trigger: 'change' }],
  materialGroup: [{ required: true, message: '请选择物料组', trigger: 'change' }],
  purchaseType: [{ required: true, message: '请选择采购类型', trigger: 'change' }],
  storageCondition: [{ required: true, message: '请选择存储条件', trigger: 'change' }],
  shelfLifeDays: [{ required: true, message: '批次管理物料必填保质期', trigger: 'change' }],
  packingSpec: [{ required: true, message: '成品物料必须填写包装规格', trigger: 'blur' }],
  changeReason: [{
    validator: (rule, value, cb) => {
      if (form.value.id && (!value || value.trim().length < 2)) cb(new Error('变更原因必填（至少 2 字）'))
      else cb()
    },
    trigger: 'blur'
  }]
}

const fieldLabels = {
  itemCode: '物料编码',
  itemName: '物料名称', categoryCode: '物料分类', baseUnit: '计量单位',
  materialGroup: '物料组', purchaseType: '采购类型', storageCondition: '存储条件',
  riskGrade: '风险等级',
  batchFlag: '批次管理', shelfLifeDays: '保质期', safetyStock: '安全库存',
  leadTimeDays: '采购提前期', altItemCode: '替代物料', packingSpec: '包装规格',
  barcode: '条码', bomVersion: 'BOM 版本'
}
/** 关键属性清单（FR-4.1-2-2） */
const CRITICAL_FIELDS = ['baseUnit', 'categoryCode', 'purchaseType']

const changeDiff = computed(() => {
  if (!form.value.id || !baseline.value) return []
  const out = []
  for (const [key, label] of Object.entries(fieldLabels)) {
    const from = baseline.value[key] ?? ''
    const to = form.value[key] ?? ''
    if (String(from) !== String(to)) out.push({ field: key, label, from: String(from) || '（空）', to: String(to) || '（空）' })
  }
  return out
})

const isCriticalChange = computed(() =>
  changeDiff.value.some(d => CRITICAL_FIELDS.includes(d.field)))

const currentCategory = computed(() =>
  props.categories.find(c => c.code === form.value.categoryCode))

/** @open 时初始化（row=变更对象；prefill=跳转向导预填；否则新建） */
function initForm() {
  const row = props.row
  form.value = row
    ? { ...row, riskGrade: row.riskGrade || 'C', changeReason: '' }
    : (props.prefill || {
        itemName: '', categoryCode: '', baseUnit: '', materialGroup: '',
        purchaseType: 'BUY', storageCondition: 'NORMAL', batchFlag: '0',
        riskGrade: 'C',
        shelfLifeDays: null, safetyStock: null, leadTimeDays: null,
        altItemCode: '', packingSpec: '', barcode: '', bomVersion: ''
      })
  baseline.value = row ? { ...row } : null
  autoCode.value = !row
  substituteTip.value = ''
}

async function checkSubstitute() {
  const code = form.value.altItemCode
  if (!code) {
    substituteTip.value = ''
    return
  }
  if (form.value.itemCode && code === form.value.itemCode) {
    substituteTipOk.value = false
    substituteTip.value = '物料不能替代自身'
    return
  }
  if (!form.value.itemCode) {
    substituteTipOk.value = true
    substituteTip.value = '提交时将校验替代物料已发布与链路不成环'
    return
  }
  try {
    await checkSubstituteApi(form.value.itemCode, code)
    substituteTipOk.value = true
    substituteTip.value = '替代物料校验通过（已发布 / 非自身 / 链路无环）'
  } catch (e) {
    substituteTipOk.value = false
    substituteTip.value = e?.message || '替代物料校验未通过'
  }
}

async function checkSimilar() {
  if (!form.value.itemName || form.value.id) return
  try {
    const res = await getSimilarApi(form.value.itemName)
    if (res.data.length) {
      ElMessage.warning(`发现 ${res.data.length} 条相似物料：${res.data.map(i => i.itemCode).join('、')}`)
    }
  } catch (e) { /* 查重接口失败不阻断表单 */ }
}

async function submitForm() {
  await formRef.value.validate()
  if (form.value.id && !changeDiff.value.length) {
    ElMessage.warning('无变更内容')
    return
  }
  saving.value = true
  try {
    const payload = { ...form.value, itemCode: autoCode.value && !form.value.id ? '' : form.value.itemCode }
    if (payload.id) {
      await updateItemApi(payload)
      ElMessage.success('变更已生效')
    } else {
      const res = await createItemApi(payload, false)
      if (res.data.bomPending) {
        ElMessage.warning(`已创建 ${res.data.itemCode}：自制件 BOM 待补录（可后续在变更中补充）`)
      } else {
        ElMessage.success(`新建成功：${res.data.itemCode}`)
      }
    }
    emit('update:visible', false)
    emit('saved')
  } catch (e) {
    const msg = e?.message || ''
    // BR-4.1-13：编码修改被阻断 → 跳转向导，预填可继承属性
    if (msg.includes('编码创建后不可修改')) {
      try {
        await ElMessageBox.confirm(
          '物料编码创建后不可修改。可「去新建」创建新编码物料，原物料属性将自动预填，之后停用旧编码。',
          '跳转新建向导', { confirmButtonText: '去新建', cancelButtonText: '留在当前' }
        )
        const inherit = baseline.value
        emit('update:visible', false)
        // 预填继承属性，父组件以新建态重开表单
        emit('jump-create', {
          itemName: inherit.itemName, categoryCode: inherit.categoryCode,
          baseUnit: inherit.baseUnit, materialGroup: inherit.materialGroup,
          purchaseType: inherit.purchaseType, storageCondition: inherit.storageCondition,
          batchFlag: inherit.batchFlag, shelfLifeDays: inherit.shelfLifeDays,
          packingSpec: inherit.packingSpec, barcode: inherit.barcode,
          altItemCode: inherit.itemCode, safetyStock: inherit.safetyStock,
          leadTimeDays: inherit.leadTimeDays, bomVersion: inherit.bomVersion
        })
        ElMessage.info('已预填可继承属性，编码将自动生成；完成后请停用旧编码')
      } catch (_) { /* 留在当前 */ }
      return
    }
    // 查重 409：确认非重复 + 差异说明（流程一第 2 步异常处理）
    if (msg.includes('高度相似') && !form.value.id) {
      try {
        const { value } = await ElMessageBox.prompt(
          `系统判定名称相似：${msg}\n确认为不同物料请输入差异说明后继续：`,
          '确认非重复', { inputPlaceholder: '差异说明（必填）', inputValidator: v => (v && v.trim().length >= 2) || '请填写至少 2 字差异说明' }
        )
        saving.value = false
        const payload = { ...form.value, itemCode: autoCode.value ? '' : form.value.itemCode, dupNote: value.trim() }
        await createItemApi(payload, true)
        ElMessage.success('已按确认非重复创建')
        emit('update:visible', false)
        emit('saved')
      } catch (_) { /* 用户取消 */ }
    }
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.form-tip { font-size: 12px; color: #909399; line-height: 1.5; margin-top: 2px; }
</style>
