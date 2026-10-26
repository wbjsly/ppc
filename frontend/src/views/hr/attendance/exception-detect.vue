<template>
  <div class="page">
    <!-- 今日打卡（自助入口） -->
    <el-card shadow="never" style="margin-bottom:12px">
      <div class="toolbar">
        <span class="title">今日打卡（10.3.2）</span>
        <div>
          <el-button type="primary" :disabled="todayIn" @click="punchIn">{{ todayIn ? '已上班打卡' : '上班打卡' }}</el-button>
          <el-button type="success" :disabled="todayOut" @click="punchOut">{{ todayOut ? '已下班打卡' : '下班打卡' }}</el-button>
          <el-button @click="gpsPunchNow">H5 定位打卡</el-button>
          <el-date-picker v-model="scanDate" type="date" value-format="YYYY-MM-DD" style="width:130px;margin:0 8px" />
          <el-button @click="doScan">手动重跑识别</el-button>
        </div>
      </div>
      <el-table :data="myPunchesToday" size="small">
        <el-table-column prop="punchDate" label="日期" width="120" />
        <el-table-column prop="punchTime" label="时刻" width="100" />
        <el-table-column label="方向" width="80">
          <template #default="{ row }">{{ row.dir === 'IN' ? '上班' : '下班' }}</template>
        </el-table-column>
        <el-table-column label="通道" width="90">
          <template #default="{ row }">{{ { WEB: '网页', GPS: '定位', CSV: '导入', MANUAL: '补卡' }[row.source] || row.source }}</template>
        </el-table-column>
        <el-table-column label="可疑" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.suspicious === '1'" type="warning" size="small">可疑</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 异常日报 -->
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">异常日报（按状态筛选）</span>
        <div>
          <el-select v-model="status" clearable placeholder="全部状态" style="width:160px;margin-right:8px" @change="load">
            <el-option label="待处理" value="IDENTIFIED" />
            <el-option label="申诉中" value="APPEALED" />
            <el-option label="主管已确认" value="MGR_CONFIRMED" />
            <el-option label="已终审" value="HR_FINAL" />
            <el-option label="已更正" value="CORRECTED" />
          </el-select>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-table :data="exceptions" height="420">
        <el-table-column prop="empNo" label="工号" width="110" />
        <el-table-column prop="empName" label="姓名" width="100" />
        <el-table-column prop="excDate" label="日期" width="110" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="{ LATE: 'warning', EARLY: 'warning', MISSING: 'danger', ABSENT: 'danger' }[row.excType]">
              {{ { LATE: '迟到', EARLY: '早退', MISSING: '缺卡', ABSENT: '旷工' }[row.excType] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="detailMin" label="分钟" width="80" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">{{ statusText(row.status) }}</template>
        </el-table-column>
        <el-table-column prop="appealReason" label="申诉原因" min-width="160" />
      </el-table>
    </el-card>

    <!-- CSV 导入 -->
    <el-dialog v-model="importVisible" title="CSV 批量导入（工号/日期/时刻/方向）" width="560px">
      <el-input v-model="csvText" type="textarea" :rows="8"
                placeholder="每行：工号,日期(yyyy-MM-dd),时刻(HH:mm),方向(IN/OUT)&#10;例：EMP20260001,2026-10-08,09:00,IN" />
      <template #footer>
        <el-button @click="importVisible=false">取消</el-button>
        <el-button type="primary" @click="doImport">导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  myPunches, webPunch, gpsPunch, importPunchCsv, exceptionList, manualScan
} from '@/api/hr/attendance'

const punches = ref([])
const exceptions = ref([])
const status = ref('')
const scanDate = ref(new Date().toISOString().slice(0, 10))
const importVisible = ref(false)
const csvText = ref('')

const today = new Date().toISOString().slice(0, 10)
const myPunchesToday = computed(() => punches.value.filter(p => p.punchDate === today))
const todayIn = computed(() => myPunchesToday.value.some(p => p.dir === 'IN'))
const todayOut = computed(() => myPunchesToday.value.some(p => p.dir === 'OUT'))

const statusText = s => ({ IDENTIFIED: '待处理', APPEALED: '申诉中', MGR_CONFIRMED: '主管确认', HR_FINAL: '已终审', CORRECTED: '已更正' }[s] || s)

async function load() {
  const period = new Date().toISOString().slice(0, 7).replace('-', '')
  const [p, e] = await Promise.all([myPunches({ period }), exceptionList({ status: status.value })])
  punches.value = p.data || []
  exceptions.value = e.data || []
}

async function punchIn() {
  try { await webPunch({ dir: 'IN' }); ElMessage.success('上班打卡成功'); load() }
  catch (err) { ElMessage.error(err?.response?.data?.msg || err?.message) }
}
async function punchOut() {
  try { await webPunch({ dir: 'OUT' }); ElMessage.success('下班打卡成功'); load() }
  catch (err) { ElMessage.error(err?.response?.data?.msg || err?.message) }
}
function gpsPunchNow() {
  if (!navigator.geolocation) return ElMessage.warning('当前浏览器不支持定位')
  navigator.geolocation.getCurrentPosition(async pos => {
    try {
      await gpsPunch({ dir: todayIn.value ? 'OUT' : 'IN', lat: pos.coords.latitude, lng: pos.coords.longitude })
      ElMessage.success('定位打卡成功'); load()
    } catch (err) { ElMessage.error(err?.response?.data?.msg || err?.message) }
  }, () => ElMessage.error('定位失败'))
}
async function doScan() {
  const out = await manualScan(scanDate.value)
  ElMessage.success(`识别完成：${out.data?.exceptions} 条异常（${out.data?.employees} 人）`)
  load()
}
async function doImport() {
  const rows = csvText.value.split('\n').filter(l => l.trim()).map(l => {
    const [empNo, punchDate, punchTime, dir] = l.split(',').map(s => s.trim())
    return { empNo, punchDate, punchTime, dir }
  })
  try {
    const out = await importPunchCsv(rows)
    ElMessage.success(`导入完成：成功 ${out.data?.success}，跳过重复 ${out.data?.skippedDuplicate}，报错 ${out.data?.errors?.length}`)
    importVisible.value = false; load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
</style>
