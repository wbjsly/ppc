<template>
  <div>
    <PdaShell :title="'扫码作业 · ' + tabTitle" scan @scan="onScan" @sync="doSync">
      <el-radio-group v-model="tab" size="small" style="margin-bottom:10px">
        <el-radio-button value="receive">收货</el-radio-button>
        <el-radio-button value="putaway">上架</el-radio-button>
        <el-radio-button value="pick">拣货</el-radio-button>
        <el-radio-button value="ship">发货</el-radio-button>
        <el-radio-button value="count">盘点</el-radio-button>
      </el-radio-group>

      <!-- 单据定位输入 -->
      <el-form label-width="86px" size="small">
        <el-form-item v-if="tab === 'receive'" label="供应商">
          <el-input v-model="form.supplierId" placeholder="供应商 ID" />
        </el-form-item>
        <el-form-item v-if="tab === 'receive'" label="序列号">
          <el-input v-model="form.serialNo" placeholder="RFID 序列号（DAMAGE 开头演示拒收）" />
        </el-form-item>
        <el-form-item v-if="tab === 'putaway'" label="收货行">
          <el-input v-model="form.grNo" placeholder="GR 单号" style="width:60%" />
          <el-input-number v-model="form.lineNo" :min="1" style="width:90px;margin-left:6px" />
        </el-form-item>
        <el-form-item v-if="tab === 'pick'" label="拣货任务">
          <el-input v-model="form.taskId" placeholder="Pick Task ID" />
        </el-form-item>
        <el-form-item v-if="tab === 'ship'" label="发货单">
          <el-input v-model="form.shipId" placeholder="Shipment ID" />
        </el-form-item>
        <el-form-item v-if="tab === 'count'" label="盘点行">
          <el-input v-model="form.lineId" placeholder="Count Line ID" />
          <el-input-number v-model="form.actualQty" :min="0" :precision="2"
                           style="width:120px;margin-left:6px" placeholder="实盘" />
        </el-form-item>
        <el-form-item v-if="tab === 'putaway'" label="目标货位">
          <el-input v-model="form.binCode" placeholder="扫码目标货位" />
        </el-form-item>
        <el-form-item v-if="tab === 'ship'" label="复核数量">
          <el-input-number v-model="form.qty" :min="0" :precision="2" />
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
 * 13.2 扫码作业五叶（add-iot-mobile）：收货/上架/拣货/发货/盘点。
 * 套 PdaShell 仿真壳，扫码回车提交 → /api/iot/scan/* 薄适配端点。
 */
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PdaShell from '../_pda/PdaShell.vue'
import {
  scanReceiveApi, scanPutawayApi, scanPickApi, scanShipReviewApi, scanCountApi,
} from '@/api/iot/scan'

const route = useRoute()
const tab = ref(route.meta.tab || 'receive')
const tabTitle = computed(() => route.meta.title || '扫码作业')

const form = ref({
  supplierId: '', serialNo: '', grNo: '', lineNo: 1, binCode: '',
  taskId: '', shipId: '', qty: 10, lineId: '', actualQty: 0,
})
const result = ref(null)

function ok(title, desc) {
  result.value = { type: 'success', title, desc: desc || '' }
}
function fail(e) {
  const msg = e?.response?.data?.message || e.message || '操作失败'
  result.value = { type: 'error', title: '操作被拒', desc: msg }
}

async function onScan(code) {
  // 扫码即按当前 Tab 分发
  result.value = null
  try {
    if (tab.value === 'receive') {
      const r = await scanReceiveApi({
        supplierId: form.value.supplierId || undefined,
        arrivalDate: new Date().toISOString().slice(0, 10),
        serialNo: form.value.serialNo || code,
        lines: [{ itemCode: code.includes('|') ? code.split('|')[0] : code, receivedQty: 1 }],
      })
      ok('收货登记成功', `单号 ${r.data?.grNo || ''} 序列号 ${r.data?.serialNo || ''}`)
    } else if (tab.value === 'putaway') {
      const r = await scanPutawayApi({
        grNo: form.value.grNo, lineNo: form.value.lineNo, binCode: form.value.binCode || code,
      })
      ok('上架分配成功', `货位 ${r.data?.binCode || ''}`)
    } else if (tab.value === 'pick') {
      const seg = code.split('|')
      const r = await scanPickApi({
        taskId: form.value.taskId,
        lineNo: 1,
        binCode: seg.length > 1 ? seg[0] : undefined,
        itemCode: seg.length > 1 ? seg[1] : code,
        batchNo: seg.length > 2 ? seg[2] : undefined,
      })
      ok('三码校验通过', JSON.stringify(r.data || {}).slice(0, 120))
    } else if (tab.value === 'ship') {
      const r = await scanShipReviewApi({
        shipId: form.value.shipId, itemCode: code, qty: form.value.qty, confirm: false,
      })
      ok('发货复核通过', `清单数量 ${r.data?.planQty ?? ''}`)
    } else if (tab.value === 'count') {
      const r = await scanCountApi({
        lineId: form.value.lineId, actualQty: form.value.actualQty,
      })
      ok('实盘提交成功', `结果 ${r.data?.outcome || ''}${r.data?.levelHint ? ' · ' + r.data.levelHint : ''}`)
    }
  } catch (e) {
    fail(e)
  }
}

function doSync() {
  ElMessage.info('联网状态下队列已实时同步')
}
</script>
