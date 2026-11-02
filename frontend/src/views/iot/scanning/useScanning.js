import { ref } from 'vue'
import { ElMessage } from 'element-plus'

/**
 * 13.2 扫码作业五页共享状态（拆分自原扫码作业操作台，design D2 composable 规则）。
 * 模块级单例：单据定位表单、扫码结果条与同步提示在收货/上架/拣货/发货/盘点
 * 五页间共享，等价原「同一组件被五条路由复用 + keep-alive」的行为。
 */
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

function doSync() {
  ElMessage.info('联网状态下队列已实时同步')
}

export function useScanning() {
  return { form, result, ok, fail, doSync }
}
