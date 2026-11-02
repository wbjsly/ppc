<template>
  <div>
    <PdaShell :title="'扫码作业 · ' + tabTitle" scan @scan="onScan" @sync="doSync">
      <!-- 单据定位输入 -->
      <el-form label-width="86px" size="small">
        <el-form-item label="收货行">
          <el-input v-model="form.grNo" placeholder="GR 单号" style="width:60%" />
          <el-input-number v-model="form.lineNo" :min="1" style="width:90px;margin-left:6px" />
        </el-form-item>
        <el-form-item label="目标货位">
          <el-input v-model="form.binCode" placeholder="扫码目标货位" />
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
 * 13.2.2 上架扫码（拆分自原扫码作业操作台）：扫目标货位分配上架。
 * 表单/结果条/同步提示见 useScanning.js。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PdaShell from '../_pda/PdaShell.vue'
import { scanPutawayApi } from '@/api/iot/scan'
import { useScanning } from './useScanning'

const route = useRoute()
const tabTitle = computed(() => route.meta.title || '扫码作业')

const { form, result, ok, fail, doSync } = useScanning()

async function onScan(code) {
  // 扫码即按上架分配
  result.value = null
  try {
    const r = await scanPutawayApi({
      grNo: form.value.grNo, lineNo: form.value.lineNo, binCode: form.value.binCode || code,
    })
    ok('上架分配成功', `货位 ${r.data?.binCode || ''}`)
  } catch (e) {
    fail(e)
  }
}
</script>
