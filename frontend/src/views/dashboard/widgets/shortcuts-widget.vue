<template>
  <WidgetShell title="常用快捷入口" :loading="false" @refresh="noop">
    <div v-if="dash.shortcuts.value.length === 0" class="wk-empty">
      尚未添加快捷入口，点右上「添加」从菜单中收藏常用功能
    </div>
    <div class="wk-chips">
      <el-tag
        v-for="s in dash.shortcuts.value" :key="s.path" class="wk-chip"
        closable size="large" @click="go(s)" @close="removeOne(s)"
      >{{ s.title }}</el-tag>
    </div>

    <el-popover placement="bottom" :width="300" trigger="click" v-model:visible="pickVisible">
      <template #reference>
        <el-button link type="primary" size="small" class="wk-add">+ 添加</el-button>
      </template>
      <div class="wk-pick">
        <el-input v-model="kw" placeholder="搜索菜单" size="small" clearable />
        <div class="wk-pick-list">
          <div
            v-for="m in filteredMenus" :key="m.id"
            class="wk-pick-item"
            @click="addOne(m)"
          >{{ m.title }}</div>
          <div v-if="filteredMenus.length === 0" class="wk-empty">无匹配菜单</div>
        </div>
      </div>
    </el-popover>
  </WidgetShell>
</template>

<script setup>
/**
 * 常用快捷入口（add-configurable-workbench，段3 核心件）：
 * 收藏存于布局 JSON 顶层 shortcuts（[{title,path}]），随「保存布局」统一持久；
 * 菜单源 = menuStore 可见叶子（path 非空），与菜单权限天然一致。
 */
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useDashboard } from '../useDashboard'
import { useMenuStore } from '@/store/menu'
import WidgetShell from '../_parts/WidgetShell.vue'

const dash = useDashboard()
const menuStore = useMenuStore()
const router = useRouter()

const pickVisible = ref(false)
const kw = ref('')

const leafMenus = computed(() =>
  (menuStore.menuItems || []).filter(m => m.status === '1' && m.path && m.path !== '/dashboard'))

const filteredMenus = computed(() => {
  const k = kw.value.trim()
  const list = k ? leafMenus.value.filter(m => m.title.includes(k)) : leafMenus.value
  return list.slice(0, 40)
})

function marked() {
  return new Set(dash.shortcuts.value.map(s => s.path))
}

function addOne(m) {
  if (marked().has(m.path)) return
  dash.shortcuts.value = [...dash.shortcuts.value, { title: m.title, path: m.path }]
  dash.dirty.value = true
  pickVisible.value = false
  kw.value = ''
}

function removeOne(s) {
  dash.shortcuts.value = dash.shortcuts.value.filter(x => x.path !== s.path)
  dash.dirty.value = true
}

function go(s) { router.push(s.path) }
function noop() { /* 纯本地收藏，无远程数据 */ }
</script>

<style scoped>
.wk-empty { color: var(--el-text-color-secondary); font-size: 12px; text-align: center; padding: 14px 0; }
.wk-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.wk-chip { cursor: pointer; }
.wk-add { margin-top: 10px; }
.wk-pick-list { max-height: 240px; overflow: auto; margin-top: 8px; }
.wk-pick-item { padding: 6px 8px; font-size: 13px; border-radius: 4px; cursor: pointer; }
.wk-pick-item:hover { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
</style>
