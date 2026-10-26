<template>
  <el-container class="main-layout">
    <el-aside :width="sidebarWidth">
      <SidebarMenu v-model:collapsed="sidebarCollapsed" />
    </el-aside>
    <el-container>
      <el-header height="40px" class="tab-header">
        <el-tabs
          v-model="tabStore.activeTab"
          type="card"
          class="tab-bar"
          @tab-click="handleTabClick"
          @tab-remove="handleTabRemove"
        >
          <el-tab-pane
            v-for="tab in tabStore.tabs"
            :key="tab.path"
            :name="tab.path"
            :closable="tab.closable !== false"
          >
            <template #label>
              <span @contextmenu.prevent="(e) => showContextMenu(e, tab)">{{ tab.title }}</span>
            </template>
          </el-tab-pane>
        </el-tabs>
        <!-- 站内通知铃铛（change add-kitting-check design D4：未读角标 + 下拉最近条目 + 中心页） -->
        <el-popover placement="bottom-end" width="360" trigger="click" v-model:visible="noticePopVisible">
          <template #reference>
            <el-badge :value="unreadCount" :hidden="unreadCount === 0" :max="99" class="notice-bell">
              <el-icon class="notice-bell-icon"><Bell /></el-icon>
            </el-badge>
          </template>
          <div class="notice-pop">
            <div class="notice-pop-head">
              <span>站内通知</span>
              <el-link type="primary" :underline="false" @click="goNoticeCenter">查看全部</el-link>
            </div>
            <div v-if="notices.length === 0" class="notice-empty">暂无通知</div>
            <div v-for="n in notices" :key="n.id" class="notice-item" @click="readNotice(n)">
              <div class="notice-title">
                <el-badge is-dot :hidden="n.readFlag !== '0'" type="danger">
                  <span>{{ n.title }}</span>
                </el-badge>
              </div>
              <div class="notice-content">{{ n.content }}</div>
              <div class="notice-time">{{ (n.createDate || '').replace('T', ' ').slice(0, 16) }}</div>
            </div>
          </div>
          <template #footer>
            <el-link type="primary" :underline="false" @click="goNoticeCenter">进入通知中心 →</el-link>
          </template>
        </el-popover>
        <el-dropdown @command="handleUserCommand" class="user-dropdown">
          <span class="user-dropdown-link">
            <el-icon><UserFilled /></el-icon>
            <span class="user-name">{{ userStore.userInfo?.nickName || userStore.userInfo?.username || '' }}</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout" divided>退出/切换账号</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main-content">
        <router-view v-slot="{ Component }">
          <keep-alive>
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>
  </el-container>

  <!-- Right-click tab context menu -->
  <div v-show="contextVisible" class="context-menu" :style="{ top: contextTop + 'px', left: contextLeft + 'px' }" @contextmenu.prevent>
    <div v-if="contextTab?.closable !== false" class="context-item" @click="closeCurrent">关闭</div>
    <div class="context-item" @click="closeLeft">关闭左边</div>
    <div class="context-item" @click="closeRight">关闭右边</div>
    <div class="context-item" @click="closeOther">关闭其他</div>
    <div class="context-item" @click="closeAll">全部关闭</div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useTabStore } from '@/store/tab'
import { useUserStore } from '@/store/user'
import { useMenuStore } from '@/store/menu'
import SidebarMenu from './SidebarMenu.vue'
import { getNoticesApi, getUnreadCountApi, markNoticeReadApi } from '@/api/notice'

const router = useRouter()
const route = useRoute()
const tabStore = useTabStore()
const userStore = useUserStore()
const menuStore = useMenuStore()

// 站内通知铃铛（add-kitting-check 任务 7.1）
const noticePopVisible = ref(false)
const unreadCount = ref(0)
const notices = ref([])

async function loadNotices() {
  try {
    const [cnt, list] = await Promise.all([getUnreadCountApi(), getNoticesApi()])
    unreadCount.value = Number(cnt.data || 0)
    notices.value = (list.data || []).slice(0, 10)
  } catch {
    // 弹错已由 request.js 单点处理，静默恢复
  }
}

async function readNotice(n) {
  if (n.readFlag === '0') {
    try {
      await markNoticeReadApi(n.id)
      n.readFlag = '1'
      unreadCount.value = Math.max(0, unreadCount.value - 1)
    } catch { /* 单点弹错 */ }
  }
}

function goNoticeCenter() {
  noticePopVisible.value = false
  router.push('/notices')
}

const sidebarCollapsed = ref(false)
const sidebarWidth = computed(() => sidebarCollapsed.value ? '64px' : '220px')

const contextVisible = ref(false)
const contextTop = ref(0)
const contextLeft = ref(0)
const contextTab = ref(null)
let noticeTimer = null

function showContextMenu(e, tab) {
  if (tab) {
    contextTab.value = tab
  } else {
    contextTab.value = tabStore.tabs.find(t => t.path === tabStore.activeTab)
  }
  contextTop.value = e.clientY
  contextLeft.value = e.clientX
  contextVisible.value = true
}

function hideContextMenu() {
  contextVisible.value = false
}

function closeCurrent() {
  if (contextTab.value) {
    tabStore.removeTab(contextTab.value.path)
    if (tabStore.activeTab) {
      router.push(tabStore.activeTab)
    } else {
      router.push('/dashboard')
    }
  }
  hideContextMenu()
}

function closeLeft() {
  if (contextTab.value) {
    tabStore.closeLeft(contextTab.value.path)
  }
  hideContextMenu()
}

function closeRight() {
  if (contextTab.value) {
    tabStore.closeRight(contextTab.value.path)
    if (tabStore.activeTab) {
      router.push(tabStore.activeTab)
    } else {
      router.push('/dashboard')
    }
  }
  hideContextMenu()
}

function closeOther() {
  if (contextTab.value) {
    tabStore.closeOther(contextTab.value.path)
    router.push(contextTab.value.path)
  }
  hideContextMenu()
}

function closeAll() {
  tabStore.closeAll()
  router.push('/dashboard')
  hideContextMenu()
}

function handleTabClick(pane) {
  router.push(pane.props.name)
}

function handleTabRemove(path) {
  tabStore.removeTab(path)
  if (tabStore.activeTab) {
    router.push(tabStore.activeTab)
  } else {
    router.push('/dashboard')
  }
}

async function handleUserCommand(command) {
  if (command === 'logout') {
    await userStore.logout()
    menuStore.reset()
    tabStore.$patch({ tabs: [{ name: 'Dashboard', path: '/dashboard', title: '工作台', closable: false }], activeTab: '/dashboard' })
    router.push('/login?redirect=/dashboard')
  }
}

onMounted(() => {
  document.addEventListener('click', hideContextMenu)
  loadNotices()
  // 铃铛轮询（60s；调度器 5 分钟级通知的低成本触达，design D4）
  noticeTimer = setInterval(loadNotices, 60000)
})
onUnmounted(() => {
  document.removeEventListener('click', hideContextMenu)
  if (noticeTimer) clearInterval(noticeTimer)
})
</script>

<style scoped>
.main-layout {
  height: 100vh;
}
.el-aside {
  background-color: #304156;
  transition: width 0.3s;
}
.tab-header {
  display: flex;
  align-items: center;
  padding: 0 8px;
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
  overflow: hidden;
}
.tab-bar {
  flex: 1;
  overflow: hidden;
}
.tab-bar :deep(.el-tabs__header) {
  margin-bottom: 0;
}
.tab-bar :deep(.el-tabs__nav-wrap::after) {
  height: 0;
}
.user-dropdown {
  margin-left: 16px;
  flex-shrink: 0;
}
.notice-bell {
  margin-left: 16px;
  flex-shrink: 0;
  cursor: pointer;
}
.notice-bell-icon {
  font-size: 18px;
  color: #666;
}
.notice-bell-icon:hover {
  color: #409eff;
}
.notice-pop-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  margin-bottom: 8px;
}
.notice-empty {
  color: #909399;
  text-align: center;
  padding: 16px 0;
  font-size: 13px;
}
.notice-item {
  padding: 6px 4px;
  border-bottom: 1px solid #f0f0f0;
  cursor: pointer;
}
.notice-item:hover {
  background: #f5f7fa;
}
.notice-title {
  font-size: 13px;
  color: #303133;
}
.notice-content {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.notice-time {
  font-size: 11px;
  color: #c0c4cc;
  margin-top: 2px;
}
.user-dropdown-link {
  display: flex;
  align-items: center;
  cursor: pointer;
  color: #666;
  font-size: 14px;
  gap: 4px;
}
.user-dropdown-link:hover {
  color: #409eff;
}
.user-name {
  max-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.main-content {
  padding: 16px;
  background: #f0f2f5;
  overflow-y: auto;
}
.context-menu {
  position: fixed;
  z-index: 9999;
  background: #fff;
  border: 1px solid #e0e0e0;
  border-radius: 4px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.15);
  min-width: 120px;
}
.context-item {
  padding: 8px 16px;
  font-size: 13px;
  cursor: pointer;
  color: #333;
}
.context-item:hover {
  background: #f5f7fa;
  color: #409eff;
}
</style>
