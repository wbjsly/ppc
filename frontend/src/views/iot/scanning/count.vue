<template>
  <div>
    <PdaShell :title="'扫码作业 · ' + tabTitle" scan @scan="onScan" @sync="doSync">
      <!-- 单据定位输入 -->
      <el-form label-width="86px" size="small">
        <el-form-item label="盘点行">
          <el-input v-model="form.lineId" placeholder="Count Line ID" />
          <el-input-number v-model="form.actualQty" :min="0" :precision="2"
                           style="width:120px;margin-left:6px" placeholder="实盘" />
        </el-form-item>
      </el-form>

      <el-alert v-if="result" :type="result.type" :closable="false" style="margin-bottom:8px"
                :title="result.title" :description="result.desc" />
      <div style="font-size:12px;color:#909399">
        提示：扫码框回车即提交；收货扫序列号（防重/损坏拒收 C-4.11-02）、
        上架扫货位、拣货按三码校验（批次不符自动冻结货位）、发货比对清单、盘点录实盘数。
      </div>
    </PdaShell>
  </div>
</template>

<script setup>
/**
 * 13.2.5 盘点扫码（拆分自原扫码作业操作台）：提交实盘数（差异分级提示）。
 * 表单/结果条/同步提示见 useScanning.js。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PdaShell from '../_pda/PdaShell.vue'
import { scanCountApi } from '@/api/iot/scan'
import { useScanning } from './useScanning'

const route = useRoute()
const tabTitle = computed(() => route.meta.title || '扫码作业')

const { form, result, ok, fail, doSync } = useScanning()

async function onScan(code) {
  // 扫码即提交实盘数
  result.value = null
  try {
    const r = await scanCountApi({
      lineId: form.value.lineId, actualQty: form.value.actualQty,
    })
    ok('实盘提交成功', `结果 ${r.data?.outcome || ''}${r.data?.levelHint ? ' · ' + r.data.levelHint : ''}`)
  } catch (e) {
    fail(e)
  }
}
</script>
