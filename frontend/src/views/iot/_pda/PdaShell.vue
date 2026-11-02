<template>
  <div class="pda-wrap">
    <div class="pda-shell">
      <!-- 顶部：状态灯 + 模拟断网开关 -->
      <div class="pda-top">
        <span class="pda-title">{{ title }}</span>
        <span class="pda-right">
          <span class="pda-light" :class="online ? 'on' : 'off'">
            {{ online ? '在线' : '离线' }}
          </span>
          <el-switch v-model="offlineSwitch" size="small" active-text="模拟断网"
                     @change="toggleSimOffline" />
        </span>
      </div>
      <!-- 扫码输入框（自动聚焦，Enter 即提交） -->
      <div v-if="scan" class="pda-scan">
        <el-input v-model="scanCode" ref="scanInput" placeholder="扫码/手输后回车"
                  clearable @keyup.enter="onScan">
          <template #prefix><el-icon><component :is="Iphone" /></el-icon></template>
          <template #append>
            <el-button @click="onScan">确定</el-button>
          </template>
        </el-input>
      </div>
      <!-- 大按钮卡片插槽 -->
      <div class="pda-body">
        <slot />
      </div>
      <!-- 底部同步栏 -->
      <div class="pda-sync">
        <span>待同步 {{ pendingCount }} 条</span>
        <span v-if="lockHours !== null">锁定倒计时 {{ lockHours }}h</span>
        <el-button size="small" type="primary" link @click="$emit('sync')">立即同步</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * PDA 仿真壳（add-iot-mobile，任务 2.5/3.2/4.4/5.5）：
 * 480px 窄屏容器 + 在线/离线状态灯 + 模拟断网开关 + 扫码框自动聚焦 + 底部同步栏。
 * 离线态由 src/offline/netStatus.ts 全局驱动（浏览器 online 事件 + 手动开关）。
 */
import { ref, watch, onMounted, nextTick, defineAsyncComponent } from 'vue'
import { Iphone } from '@element-plus/icons-vue'
import { isOnline, setSimOffline, pendingQueueCount, lockRemainHours } from '@/offline/netStatus'

const props = defineProps({
  title: { type: String, default: '移动作业' },
  scan: { type: Boolean, default: true },
})
const emit = defineEmits(['scan', 'sync'])

const scanCode = ref('')
const scanInput = ref(null)
const online = ref(isOnline())
const offlineSwitch = ref(false)
const pendingCount = ref(0)
const lockHours = ref(null)

function onScan() {
  if (!scanCode.value.trim()) return
  emit('scan', scanCode.value.trim())
  scanCode.value = ''
  focusScan()
}

function focusScan() {
  nextTick(() => scanInput.value?.focus())
}

function toggleSimOffline(v) {
  setSimOffline(v)
}

watch(isOnline, v => (online.value = v))

onMounted(async () => {
  focusScan()
  pendingCount.value = await pendingQueueCount()
  lockHours.value = await lockRemainHours()
  // 轮询同步栏（30s）
  setInterval(async () => {
    pendingCount.value = await pendingQueueCount()
    lockHours.value = await lockRemainHours()
  }, 30000)
})
</script>

<style scoped>
.pda-wrap {
  display: flex;
  justify-content: center;
  background: #f5f6f8;
  min-height: calc(100vh - 120px);
  padding: 12px 0;
}
.pda-shell {
  width: 480px;
  max-width: 96vw;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
  min-height: 600px;
}
.pda-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  border-bottom: 1px solid #eee;
}
.pda-title { font-weight: 600; }
.pda-right { display: flex; align-items: center; gap: 8px; }
.pda-light {
  display: inline-block;
  width: 10px; height: 10px;
  border-radius: 50%;
  margin-right: 4px;
}
.pda-light.on { background: #67c23a; }
.pda-light.off { background: #f56c6c; }
.pda-scan { padding: 10px 14px 0; }
.pda-body { flex: 1; padding: 14px; }
.pda-sync {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 14px;
  border-top: 1px solid #eee;
  color: #909399;
  font-size: 12px;
}
</style>
