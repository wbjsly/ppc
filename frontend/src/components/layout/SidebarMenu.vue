<template>
  <div class="sidebar-menu" :class="{ collapsed: props.collapsed }">
    <div class="sidebar-header">
      <div class="sidebar-logo" :class="{ collapsed: props.collapsed }">
        <span v-show="!props.collapsed">ERP 管理系统</span>
        <span v-show="props.collapsed">ERP</span>
      </div>
      <el-icon class="collapse-btn" @click="toggleCollapse">
        <Fold v-if="!props.collapsed" />
        <Expand v-else />
      </el-icon>
    </div>
    <el-input
      v-show="!props.collapsed"
      v-model="searchText"
      placeholder="搜索菜单"
      clearable
      size="small"
      :prefix-icon="Search"
      class="sidebar-search"
    />
    <el-menu
      :default-active="activeMenu"
      router
      :collapse="props.collapsed"
      :collapse-transition="false"
      background-color="#304156"
      text-color="#bfcbd9"
      active-text-color="#409EFF"
    >
      <template v-for="item in filteredTree" :key="item.id">
        <SidebarMenuItem :item="item" />
      </template>
      <div v-if="filteredTree.length === 0 && !props.collapsed" class="no-result">无匹配菜单</div>
    </el-menu>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/store/user'
import { useMenuStore } from '@/store/menu'
import SidebarMenuItem from './SidebarMenuItem.vue'
import { Search, Fold, Expand } from '@element-plus/icons-vue'

const route = useRoute()
const userStore = useUserStore()
const menuStore = useMenuStore()

const props = defineProps({
  collapsed: { type: Boolean, default: false }
})

const emit = defineEmits(['update:collapsed'])

const searchText = ref('')

function toggleCollapse() {
  emit('update:collapsed', !props.collapsed)
}

const activeMenu = computed(() => {
  const r = route.matched[route.matched.length - 1]
  return r?.path || route.path
})

// 第二道防线（01 文档 3.3）：前端按角色过滤可见性，ROLE_ADMIN 全通
const visibleItems = computed(() => {
  const roles = userStore.userInfo?.roles || []
  const isAdmin = roles.includes('ROLE_ADMIN')
  return menuStore.menuItems.filter(item => {
    if (!item.perm) return true
    if (isAdmin) return true
    const perms = item.perm.split(',').map(p => p.trim())
    return perms.some(p => roles.some(r => r.toUpperCase() === p.toUpperCase()))
  })
})

// 平铺列表 → 任意层级树，按 sortOrder 逐层排序
const treeMenu = computed(() => {
  const items = visibleItems.value
  const map = {}
  const roots = []

  items.forEach(item => {
    map[item.id] = { ...item, children: [] }
  })

  items.forEach(item => {
    const node = map[item.id]
    if (item.parentId && map[item.parentId]) {
      map[item.parentId].children.push(node)
    } else {
      roots.push(node)
    }
  })

  const sortBySort = list => list.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
  sortBySort(roots)
  const walk = list => {
    list.forEach(n => {
      if (n.children.length) {
        sortBySort(n.children)
        walk(n.children)
      }
    })
  }
  walk(roots)

  return roots
})

const filteredTree = computed(() => {
  if (!searchText.value) return treeMenu.value
  const keyword = searchText.value.toLowerCase()

  const filterNode = nodes => {
    return nodes.reduce((acc, node) => {
      const titleMatch = node.title.toLowerCase().includes(keyword)
      const filteredChildren = node.children ? filterNode(node.children) : []
      if (titleMatch || filteredChildren.length > 0) {
        acc.push({ ...node, children: titleMatch ? node.children : filteredChildren })
      }
      return acc
    }, [])
  }

  return filterNode(treeMenu.value)
})
</script>

<style scoped>
.sidebar-menu {
  height: 100%;
  overflow-y: auto;
  transition: width 0.3s;
}
.sidebar-header {
  display: flex;
  align-items: center;
  background: #263445;
  height: 50px;
}
.sidebar-logo {
  flex: 1;
  line-height: 50px;
  text-align: center;
  color: #fff;
  font-size: 16px;
  font-weight: bold;
  transition: font-size 0.3s;
}
.sidebar-logo.collapsed {
  font-size: 14px;
}
.collapse-btn {
  width: 50px;
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #bfcbd9;
  cursor: pointer;
  font-size: 18px;
  flex-shrink: 0;
}
.collapse-btn:hover {
  color: #409EFF;
}
.sidebar-search {
  margin: 8px 12px;
  max-width: calc(100% - 24px);
}
.sidebar-search :deep(.el-input__wrapper) {
  background-color: #263445;
}
.sidebar-search :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #409EFF inset;
}
.no-result {
  text-align: center;
  color: #bfcbd9;
  padding: 16px;
  font-size: 13px;
}
:deep(.el-sub-menu .el-menu-item) {
  padding-left: 50px !important;
}
</style>
