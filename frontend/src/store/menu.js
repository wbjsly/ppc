import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getUserMenusApi } from '@/api/system/menu'

export const useMenuStore = defineStore('menu', () => {
  const menuItems = ref([])
  const loaded = ref(false)
  const loading = ref(false)

  async function fetchMenus() {
    if (loaded.value || loading.value) return
    loading.value = true
    try {
      const res = await getUserMenusApi()
      menuItems.value = res.data || []
      loaded.value = true
    } catch (e) {
      console.error('Failed to fetch menus:', e)
      menuItems.value = []
    } finally {
      loading.value = false
    }
  }

  function reset() {
    menuItems.value = []
    loaded.value = false
    loading.value = false
  }

  return { menuItems, loaded, loading, fetchMenus, reset }
})
