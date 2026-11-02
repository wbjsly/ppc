<template>
  <div>
    <PdaShell :title="'扫码作业 · ' + tabTitle" scan @scan="onScan" @sync="doSync">
      <!-- 单据定位输入 -->
      <el-form label-width="86px" size="small">
        <el-form-item label="拣货任务">
          <el-input v-model="form.taskId" placeholder="Pick Task ID" />
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
 * 13.2.3 拣货扫码（拆分自原扫码作业操作台）：三码校验（批次不符自动冻结货位）。
 * 表单/结果条/同步提示见 useScanning.js。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PdaShell from '../_pda/PdaShell.vue'
import { scanPickApi } from '@/api/iot/scan'
import { useScanning } from './useScanning'

const route = useRoute()
const tabTitle = computed(() => route.meta.title || '扫码作业')

const { form, result, ok, fail, doSync } = useScanning()

async function onScan(code) {
  // 扫码即三码校验拣货（货位|物料|批次）
  result.value = null
  try {
    const seg = code.split('|')
    const r = await scanPickApi({
      taskId: form.value.taskId,
      lineNo: 1,
      binCode: seg.length > 1 ? seg[0] : undefined,
      itemCode: seg.length > 1 ? seg[1] : code,
      batchNo: seg.length > 2 ? seg[2] : undefined,
    })
    ok('三码校验通过', JSON.stringify(r.data || {}).slice(0, 120))
  } catch (e) {
    fail(e)
  }
}
</script>
