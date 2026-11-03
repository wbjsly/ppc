<template>
  <div class="dashboard-page" v-loading="dash.loading.value">
    <!-- 顶栏 -->
    <div class="dash-toolbar">
      <div class="dash-toolbar-left">
        <span class="dash-title">工作台</span>
        <el-tag v-if="dash.dirty.value" type="warning" size="small" effect="plain">未保存</el-tag>
        <el-tag v-else-if="dash.initialized.value" type="success" size="small" effect="plain" round>已保存</el-tag>
      </div>
      <div class="dash-toolbar-right">
        <el-popover placement="bottom-end" :width="360" trigger="click">
          <template #reference>
            <el-button type="primary" plain :icon="Plus">添加组件</el-button>
          </template>
          <div class="widget-pick">
            <div class="widget-pick-title">可添加组件（{{ addable.length }}）· 灰显占位不可添加</div>
            <div class="widget-pick-list">
              <div
                v-for="w in pickList" :key="w.id"
                class="widget-pick-item"
                :class="{ disabled: w.status !== 'ready' || onCanvas(w.id) }"
                @click="onPick(w)"
              >
                <span>{{ w.title }}</span>
                <el-tag v-if="w.status !== 'ready'" size="small" type="info" effect="plain">占位</el-tag>
                <el-tag v-else-if="onCanvas(w.id)" size="small" type="success" effect="plain">已添加</el-tag>
                <el-tag v-else size="small" effect="plain">{{ w.w }}×{{ w.h }}</el-tag>
              </div>
            </div>
          </div>
        </el-popover>
        <el-button type="primary" :icon="Check" :disabled="!dash.dirty.value" @click="onSave">保存布局</el-button>
        <el-popconfirm title="恢复为角色模板默认布局？个人位置与移除记录将清除。" width="260" @confirm="onReset">
          <template #reference>
            <el-button :icon="RefreshLeft">恢复默认</el-button>
          </template>
        </el-popconfirm>
        <el-button :icon="Setting" @click="settingsVisible = true">布局设置</el-button>
      </div>
    </div>

    <LayoutSettingsDrawer
      v-model="settingsVisible"
      :get-current-canvas="snapshotCanvas"
      @reset="onReset"
    />

    <!-- 全页加载错误（403/网络） -->
    <el-result v-if="dash.loadError.value" icon="error" :title="dash.loadError.value">
      <template #extra>
        <el-button type="primary" @click="reload">重试</el-button>
      </template>
    </el-result>

    <!-- gridstack 12 列画布（模板不带 data-gs-*：避免 init 自动扫描与 makeWidget 双重注册，
         位置只经显式 makeWidget(options) 单路径进入 gridstack，design D5） -->
    <div v-else ref="containerRef" class="grid-stack dash-grid" @dash-remove="onRemoveEvent">
      <div
        v-for="item in dash.items.value"
        :key="item.id"
        class="grid-stack-item"
        :data-widget-id="item.id"
      >
        <div class="grid-stack-item-content">
          <component :is="compOf(item.id)" v-if="compOf(item.id)" />
          <el-card v-else shadow="never" class="widget-missing">组件未实现</el-card>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 可配置工作台容器（add-configurable-workbench，spec personal-workbench，design D5）：
 * 内容归 Vue（卡片/组件渲染）、位置归 gridstack（拖拽/调宽/序列化）。
 * - 增：addWidget → nextTick → makeWidget（新项 autoPosition）
 * - 删：WidgetShell 以 bubbling CustomEvent('dash-remove') 上抛 → 容器先
 *      grid.removeWidget(el, false)（不动 DOM）→ dash.removeWidget（Vue 卸载 DOM）
 * - 存：grid.save(false) 序列化 → dash.save（全量 items + excluded + shortcuts）
 * - 重建（恢复默认/重试加载）：load → nextTick → removeAll(false) → 逐项 makeWidget
 */
import { ref, onMounted, onBeforeUnmount, nextTick, computed } from 'vue'
import { Check, Plus, RefreshLeft, Setting } from '@element-plus/icons-vue'
import { GridStack } from 'gridstack'
import { useDashboard } from './useDashboard'
import { useUserStore } from '@/store/user'
import { addableWidgets, widgetById, visibleWidgets } from './dash-registry'
import LayoutSettingsDrawer from './_parts/LayoutSettingsDrawer.vue'

const dash = useDashboard()
const userStore = useUserStore()
const roles = computed(() => userStore.userInfo?.roles || [])
const settingsVisible = ref(false)

/** 当前画布的 grid.save 快照（保存与模板推送共用） */
function snapshotCanvas() {
  if (!grid) return []
  return grid.save(false).map(n => ({ id: n.id, x: n.x, y: n.y, w: n.w, h: n.h }))
}

// ---- widget 组件解析（registry 存 file 名，glob 在容器侧，registry 保持纯数据可单测） ----
// 注意：glob 的 () => import() 直接喂 :is 会被 Vue 当函数式组件执行（渲染成 [object Promise]），
// 必须经 defineAsyncComponent 包装为异步组件。
import { defineAsyncComponent } from 'vue'
const widgetModules = import.meta.glob('./widgets/*.vue')
const asyncWidgetCache = {}
function compOf(id) {
  const w = widgetById(id)
  if (!w || !w.file) return null
  if (!(w.file in asyncWidgetCache)) {
    const loader = widgetModules[`./widgets/${w.file}.vue`]
    asyncWidgetCache[w.file] = loader ? defineAsyncComponent(loader) : null
  }
  return asyncWidgetCache[w.file]
}

// ---- gridstack ----
const containerRef = ref(null)
let grid = null
let rebuilding = false

function itemOpts(it) {
  return it.y >= 9999
    ? { id: it.id, w: it.w, h: it.h, autoPosition: true } // 目录兜底新项自动排布
    : { id: it.id, x: it.x, y: it.y, w: it.w, h: it.h, autoPosition: false }
}

function queryEl(id) {
  return containerRef.value?.querySelector(`[data-widget-id="${CSS.escape(id)}"]`) || null
}

function makeAll() {
  if (!grid || !containerRef.value) return
  rebuilding = true
  grid.batchUpdate() // v14：无 commit()，批量以 batchUpdate(false) 收尾
  for (const it of dash.items.value) {
    const el = queryEl(it.id)
    if (el) grid.makeWidget(el, itemOpts(it))
  }
  grid.batchUpdate(false)
  rebuilding = false
}

async function rebuild() {
  await dash.load()
  await nextTick()
  if (!grid || !containerRef.value) return
  rebuilding = true
  grid.removeAll(false) // 清 grid 记录，DOM 留给 Vue 管理
  await nextTick()
  grid.batchUpdate()
  for (const it of dash.items.value) {
    const el = queryEl(it.id)
    if (el) grid.makeWidget(el, itemOpts(it))
  }
  grid.batchUpdate(false)
  rebuilding = false
}

onMounted(async () => {
  // 时序关键（v14）：init 会自动扫描已有子元素并按默认 1×1 抢先注册，
  // 随后 makeWidget 对已注册 el 变 no-op（id/options 全丢）。因此必须
  // ① 先对空容器 init（此刻 items 为空，v-for 尚无子节点）
  // ② 再 load + nextTick 渲染 DOM ③ 最后逐项 makeWidget（首次注册，options 生效）
  if (!containerRef.value) return
  grid = GridStack.init({
    column: 12,
    // 小屏决策（任务 4.4，2026-11-03 实测 720×900）：不开 columnOpts 单列降级——
    // 12 列下无横向溢出（docScrollW 705 ≤ 720）、组件零重叠、顶栏不换行，
    // 桌面 ERP 窄窗仅需"不坏"；若后续要手机端单列体验，加 columnOpts: {layout: 'mobile'} 即可。
    cellHeight: 64,
    margin: 8,
    float: false,
    animate: true,
    resizable: { handles: 'e, s, se' },
    draggable: { handle: '.ws-header' }, // 仅标题栏可拖：卡片内文本/按钮不被拖拽劫持
  }, containerRef.value)
  grid.on('change', () => {
    if (!rebuilding) dash.dirty.value = true
  })
  await dash.load()
  await nextTick()
  makeAll()
})

onBeforeUnmount(() => {
  if (grid) { grid.destroy(false); grid = null }
})

// ---- 顶栏动作 ----
const pickList = computed(() => visibleWidgets(roles.value))
const addable = computed(() => addableWidgets(roles.value))

function onCanvas(id) {
  return dash.items.value.some(i => i.id === id)
}

async function onPick(w) {
  if (w.status !== 'ready' || onCanvas(w.id)) return
  const item = dash.addWidget(w.id)
  if (!item) return
  await nextTick()
  const el = queryEl(w.id)
  if (el && grid) {
    rebuilding = true
    grid.makeWidget(el, itemOpts({ ...item, y: 9999 })) // 新增一律自动排布
    rebuilding = false
  }
}

async function onSave() {
  if (!grid) return
  await dash.save(snapshotCanvas())
}

async function onReset() {
  const ok = await dash.resetDefault()
  if (ok) await rebuild()
}

function reload() {
  rebuild()
}

function onRemoveEvent(e) {
  const id = e.detail?.id
  if (!id) return
  const el = queryEl(id)
  if (el && grid) grid.removeWidget(el, false) // 只出 grid，DOM 交 Vue 卸载
  dash.removeWidget(id)
}
</script>

<style scoped>
.dash-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 12px;
}
.dash-toolbar-left { display: flex; align-items: center; gap: 10px; }
.dash-toolbar-right { display: flex; align-items: center; gap: 8px; }
.dash-title { font-size: 18px; font-weight: 700; color: var(--el-text-color-primary); }
.widget-pick-title { font-size: 13px; color: var(--el-text-color-secondary); margin-bottom: 8px; }
.widget-pick-list { max-height: 320px; overflow: auto; display: flex; flex-direction: column; gap: 6px; }
.widget-pick-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 10px; border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px; cursor: pointer; font-size: 13px;
}
.widget-pick-item:hover { border-color: var(--el-color-primary); }
.widget-pick-item.disabled { opacity: 0.55; cursor: not-allowed; }
.dash-grid { min-height: 320px; }
.grid-stack-item-content { inset: 0 4px 4px 0 !important; }
.widget-missing {
  height: 100%; display: flex; align-items: center; justify-content: center;
  color: var(--el-text-color-secondary); font-size: 13px;
}
</style>
