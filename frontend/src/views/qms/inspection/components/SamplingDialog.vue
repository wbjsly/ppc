<template>
  <el-dialog :model-value="modelValue" title="记录抽样（可选留痕，不阻断检验）" width="640px"
             @update:model-value="$emit('update:modelValue', $event)" @open="init">
    <el-form :model="form" label-width="110px" size="small">
      <el-form-item label="检验批">
        <span>{{ lot?.lotNo }}　{{ lot?.itemName }}　批量 {{ lot?.lotQty }}　理论样本量 {{ lot?.sampleQty }}</span>
      </el-form-item>
      <el-form-item label="抽样方法" required>
        <el-select v-model="form.sampleMethod" style="width: 240px;">
          <el-option value="RANDOM" label="随机抽样（系统辅助选样）" />
          <el-option value="NINE_POINT" label="九点取样法（上中下三层 × 前中后三段）" />
          <el-option value="FULL" label="逐件全检（A 类风险）" />
          <el-option value="OTHER" label="其他（备注说明）" />
        </el-select>
      </el-form-item>
      <el-form-item label="实际抽样数量" required>
        <el-input-number v-model="form.actualSampleQty" :min="1" :max="Number(lot?.lotQty) || 99999"
                         style="width: 180px;" />
        <span class="hint" v-if="isFullCheck">A 类全检批：数量应等于批量</span>
      </el-form-item>
      <el-form-item label="随机选样">
        <template v-if="isFullCheck">
          <el-tag type="info">A 类全检，逐件检验，无需随机抽样</el-tag>
        </template>
        <template v-else>
          <el-button size="small" @click="roll">生成随机序号</el-button>
          <el-button size="small" @click="excluded = new Set(); rolled = []" :disabled="!rolled.length">清空</el-button>
          <span class="hint">同批再次生成不重复（会话级排除已抽 {{ excluded.size }} 个）</span>
          <div v-if="rolled.length" class="sample-nums">{{ rolled.join('、') }}</div>
        </template>
      </el-form-item>
      <el-form-item label="抽样人">
        <el-input v-model="form.samplerName" style="width: 200px;" placeholder="默认当前登录" />
      </el-form-item>
      <el-form-item label="抽样时间">
        <el-date-picker v-model="form.sampledTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                        style="width: 220px;" />
      </el-form-item>
      <el-form-item label="触发原因">
        <el-select v-model="form.triggerReason" style="width: 240px;">
          <el-option value="INITIAL" label="首次抽样" />
          <el-option value="ESCALATE" label="加大抽样（发现异常）" />
          <el-option value="RESAMPLE" label="补抽（样品损坏/备样）" />
        </el-select>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="2"
                  :placeholder="form.sampleMethod === 'OTHER' ? '其他方法说明（必填建议）' : '异常描述等'" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存记录</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { createSamplingRecordApi } from '@/api/qms/inspection'

const props = defineProps({
  modelValue: Boolean,
  lot: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const saving = ref(false)
const rolled = ref([])
const excluded = ref(new Set())

const form = reactive({
  sampleMethod: 'RANDOM',
  actualSampleQty: 0,
  samplerName: '',
  sampledTime: '',
  triggerReason: 'INITIAL',
  remark: ''
})

const isFullCheck = computed(() =>
  props.lot && Number(props.lot.sampleQty) >= Number(props.lot.lotQty))

function init() {
  form.sampleMethod = isFullCheck.value ? 'FULL' : 'RANDOM'
  form.actualSampleQty = Number(props.lot?.sampleQty) || 1
  form.samplerName = ''
  form.sampledTime = new Date().toISOString().slice(0, 19)
  form.triggerReason = 'INITIAL'
  form.remark = ''
  rolled.value = []
  excluded.value = new Set()
}

/**
 * 随机选样（design D5）：1..lotQty 均匀不重复抽 n 个（Fisher–Yates 部分洗牌），
 * 排除本会话已抽序号；跨会话全局唯一不做（spec 仅要求辅助，design 记录边界）。
 */
function roll() {
  const lotQty = Number(props.lot?.lotQty) || 0
  const n = Number(form.actualSampleQty) || 0
  const pool = []
  for (let i = 1; i <= lotQty; i++) {
    if (!excluded.value.has(i)) pool.push(i)
  }
  if (n <= 0 || pool.length === 0) {
    ElMessage.warning('无可抽样序号')
    return
  }
  const take = Math.min(n, pool.length)
  for (let i = pool.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [pool[i], pool[j]] = [pool[j], pool[i]]
  }
  const picked = pool.slice(0, take).sort((a, b) => a - b)
  picked.forEach(x => excluded.value.add(x))
  rolled.value = picked
}

async function save() {
  if (props.lot?.id && form.actualSampleQty <= 0) {
    ElMessage.warning('实际抽样数量必须大于 0')
    return
  }
  saving.value = true
  try {
    await createSamplingRecordApi(props.lot.id, {
      sampleMethod: form.sampleMethod,
      actualSampleQty: form.actualSampleQty,
      samplerName: form.samplerName || undefined,
      sampledTime: form.sampledTime,
      triggerReason: form.triggerReason,
      remark: form.remark
    })
    ElMessage.success('抽样记录已保存')
    emit('update:modelValue', false)
    emit('saved')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.hint { color: #909399; font-size: 12px; margin-left: 10px; }
.sample-nums { margin-top: 6px; font-size: 12px; color: #409eff; line-height: 1.6;
  max-height: 90px; overflow-y: auto; word-break: break-all; }
</style>
