import { defineStore } from 'pinia'

export const useTabStore = defineStore('tab', {
  state: () => ({
    tabs: [
      { name: 'Dashboard', path: '/dashboard', title: '工作台', closable: false }
    ],
    activeTab: '/dashboard'
  }),
  actions: {
    addTab(route) {
      if (!route.meta || route.meta.hidden) {
        // Hidden routes: just update activeTab, don't add a new tab
        this.activeTab = route.path
        return
      }
      const title = route.meta.title || route.name || ''
      const closable = route.meta.closable !== false

      const exists = this.tabs.find(t => t.path === route.path)
      if (!exists) {
        const newTab = { name: route.name, path: route.path, title, closable }
        if (route.path === '/dashboard') {
          this.tabs.unshift(newTab)
        } else {
          this.tabs.push(newTab)
        }
      } else {
        exists.title = title
      }
      this.activeTab = route.path
    },
    removeTab(path) {
      const tab = this.tabs.find(t => t.path === path)
      if (tab && !tab.closable) return
      const idx = this.tabs.findIndex(t => t.path === path)
      if (idx === -1) return
      this.tabs.splice(idx, 1)
      if (this.activeTab === path) {
        const next = this.tabs[idx] || this.tabs[idx - 1]
        if (next) {
          this.activeTab = next.path
        }
      }
    },
    closeLeft(path) {
      const idx = this.tabs.findIndex(t => t.path === path)
      if (idx === -1) return
      const toRemove = []
      for (let i = 0; i < idx; i++) {
        if (this.tabs[i].closable) toRemove.push(i)
      }
      for (let i = toRemove.length - 1; i >= 0; i--) {
        this.tabs.splice(toRemove[i], 1)
      }
    },
    closeRight(path) {
      const idx = this.tabs.findIndex(t => t.path === path)
      if (idx === -1) return
      const toRemove = []
      for (let i = idx + 1; i < this.tabs.length; i++) {
        if (this.tabs[i].closable) toRemove.push(i)
      }
      for (let i = toRemove.length - 1; i >= 0; i--) {
        this.tabs.splice(toRemove[i], 1)
      }
      if (idx >= this.tabs.length) {
        const last = this.tabs[this.tabs.length - 1]
        if (last) this.activeTab = last.path
      }
    },
    closeOther(path) {
      const tab = this.tabs.find(t => t.path === path)
      if (tab) {
        this.tabs = this.tabs.filter(t => !t.closable || t.path === path)
        this.activeTab = path
      }
    },
    closeAll() {
      this.tabs = this.tabs.filter(t => !t.closable)
      this.activeTab = ''
      if (this.tabs.length > 0) {
        this.activeTab = this.tabs[0].path
      }
    }
  }
})
