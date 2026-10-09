<template>
  <el-dialog :model-value="visible" @update:model-value="v => emit('update:visible', v)"
             :title="form.id ? `变更供应商：${form.supplierCode}` : '新建供应商（待审核）'" width="760px" @open="init">
    <el-alert v-if="similarHits.length" type="warning" show-icon :closable="false" style="margin-bottom: 10px;"
      :title="`名称相似 ${similarHits.length} 条：${similarHits.map(s => s.supplierCode + ' ' + s.supplierName).join('、')}`"
      description="确认为不同供应商请填写差异说明后继续提交。" />

    <el-form label-width="130px">
      <el-divider content-position="left">基本信息</el-divider>
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="供应商编码">
            <el-input :model-value="form.id ? form.supplierCode : '保存时自动生成（SUP-NNNN）'" disabled />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="供应商名称" required>
            <el-input v-model="form.supplierName" maxlength="128" @blur="checkSimilar" />
          </el-form-item>
        </el-col>
        <el-col :span="24">
          <el-form-item label="地址" required>
            <el-input v-model="form.address" maxlength="255" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="联系人">
            <el-input v-model="form.contactName" maxlength="64" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="联系电话">
            <el-input v-model="form.contactPhone" maxlength="32" />
          </el-form-item>
        </el-col>
        <el-col :span="24">
          <el-form-item label="挂靠法人主体">
            <el-select v-model="form.legalEntityId" clearable style="width: 100%;">
              <el-option v-for="le in legalEntities" :key="le.id" :label="`${le.leCode} ${le.leName}`" :value="le.id" />
            </el-select>
            <div class="form-tip">可空 = 集团级供应商；选择后须为启用状态</div>
          </el-form-item>
        </el-col>
      </el-row>

      <el-divider content-position="left">财务信息</el-divider>
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="开户行" required>
            <el-input v-model="form.bankName" maxlength="128" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="税号" required>
            <el-input v-model="form.taxNo" maxlength="32" placeholder="全库唯一（C-4.1-07）" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="付款条件" required>
            <el-select v-model="form.paymentTerms" clearable style="width: 100%;">
              <el-option v-for="p in ['NET30', 'NET60', 'NET90', '月结30天', '款到发货']" :key="p" :label="p" :value="p" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <el-divider content-position="left">合规信息</el-divider>
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="黑名单筛查" required>
            <el-radio-group v-model="form.blacklistResult">
              <el-radio value="CLEAR">未命中</el-radio>
              <el-radio value="HIT">命中</el-radio>
            </el-radio-group>
            <div class="form-tip">命中 → 建档与变更双入口 L1 硬阻断（合规前置）</div>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="ESG 评估等级">
            <el-select v-model="form.esgRating" clearable style="width: 100%;">
              <el-option v-for="r in ['A', 'B', 'C', 'D']" :key="r" :label="r" :value="r" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col v-if="form.id" :span="24">
          <el-form-item label="变更原因" required>
            <el-input v-model="form.changeReason" maxlength="255" placeholder="必填（≥2 字）" />
          </el-form-item>
        </el-col>
        <el-col v-if="!form.id && similarHits.length" :span="24">
          <el-form-item label="差异说明">
            <el-input v-model="form.dupNote" maxlength="255" placeholder="确认非重复时必填" />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createSupplierApi, updateSupplierApi, getSupplierPageApi } from '@/api/mdm/supplier-admission'
import { getLegalEntityPageApi } from '@/api/mdm/legal-entity'

const props = defineProps({
  visible: { type: Boolean, default: false },
  row: { type: Object, default: null },
  legalEntities: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:visible', 'saved'])

const form = ref({})
const saving = ref(false)
const similarHits = ref([])

function init() {
  form.value = props.row
    ? { ...props.row, changeReason: '' }
    : {
        supplierName: '', address: '', contactName: '', contactPhone: '',
        legalEntityId: '', bankName: '', taxNo: '', paymentTerms: '',
        blacklistResult: 'CLEAR', esgRating: ''
      }
  similarHits.value = []
}

async function checkSimilar() {
  if (!form.value.supplierName || form.value.id) return
  try {
    const res = await getSupplierPageApi({ keyword: form.value.supplierName, current: 1, size: 5 })
    similarHits.value = res.data.records.filter(r => r.supplierName !== form.value.supplierName)
  } catch (e) { /* 查重失败不阻断 */ }
}

async function submit() {
  saving.value = true
  try {
    if (form.value.id) {
      await updateSupplierApi(form.value)
      ElMessage.success('变更已生效')
    } else {
      if (similarHits.value.length && !(form.value.dupNote && form.value.dupNote.trim().length >= 2)) {
        ElMessage.warning('名称相似，确认非重复请填写差异说明（≥2 字）')
        saving.value = false
        return
      }
      const res = await createSupplierApi(form.value, similarHits.value.length > 0)
      ElMessage.success(`新建成功：${res.data.supplierCode}（待审核）`)
    }
    emit('update:visible', false)
    emit('saved')
  } catch (e) {
    // 409 相似 / 422 黑名单与税号已单点提示
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.form-tip { font-size: 12px; color: #909399; line-height: 1.5; margin-top: 2px; }
</style>
