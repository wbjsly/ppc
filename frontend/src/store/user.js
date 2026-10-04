import { defineStore } from 'pinia'
import { loginApi, logoutApi, getUserInfoApi } from '@/api/auth'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    userInfo: null,
    permissions: []
  }),
  actions: {
    async login(username, password) {
      const res = await loginApi({ username, password })
      this.token = res.data.token
      localStorage.setItem('token', res.data.token)
      return res
    },
    async getUserInfo() {
      const res = await getUserInfoApi()
      this.userInfo = res.data
      this.permissions = res.data.permissions || []
      return res
    },
    async logout() {
      try {
        await logoutApi()
      } catch {
        // ignore logout API errors
      }
      this.token = ''
      this.userInfo = null
      this.permissions = []
      localStorage.removeItem('token')
    }
  }
})
