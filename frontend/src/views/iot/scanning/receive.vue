<template>
  <div>
    <PdaShell :title="'扫码作业 · ' + tabTitle" scan @scan="onScan" @sync="doSync">
      <!-- 单据定位输入 -->
      <el-form label-width="86px" size="small">
        <el-form-item label="供应商">
          <el-input v-model="form.supplierId" placeholder="供应商 ID" />
        </el-form-item>
        <el-form-item label="序列号">
          <el-input v-model="form.serialNo" placeholder="RFID 序列号（DAMAGE 开头演示拒收）" />
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
 * 13.2.1 收货扫码（拆分自原扫码作业操作台）：扫码即登记收货行 + 序列号。
 * 表单/结果条/同步提示见 useScanning.js。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PdaShell from '../_pda/PdaShell.vue'
import { scanReceiveApi } from '@/api/iot/scan'
import { useScanning } from './useScanning'

const route = useRoute()
const tabTitle = computed(() => route.meta.title || '扫码作业')

const { form, result, ok, fail, doSync } = useScanning()

async function onScan(code) {
  // 扫码即登记收货（防重/损坏拒收 C-4.11-02）
  result.value = null
  try {
    const r = await scanReceiveApi({
      supplierId: form.value.supplierId || undefined,
      arrivalDate: new Date().toISOString().slice(0, 10),
      serialNo: form.value.serialNo || code,
      lines: [{ itemCode: code.includes('|') ? code.split('|')[0] : code, receivedQty: 1 }],
    })
    ok('收货登记成功', `单号 ${r.data?.grNo || ''} 序列号 ${r.data?.serialNo || ''}`)
  } catch (e) {
    fail(e)
  }
}
</script>
