import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

const service = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// Request interceptor
service.interceptors.request.use(
  config => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`
    }
    return config
  },
  error => Promise.reject(error)
)

// Response interceptor：弹错单点化（01 文档 3.5），页面 catch 分支禁止重复提示
service.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code && res.code !== 200) {
      // silent: widget 单卡失败只落卡片错误态，不弹全局（与 HTTP 分支 silent 对称）
      if (!response.config?.silent) {
        ElMessage.error(res.message || '请求失败')
      }
      if (res.code === 401) {
        localStorage.removeItem('token')
        router.push('/login')
      }
      return Promise.reject(new Error(res.message))
    }
    return res
  },
  error => {
    if (error.response) {
      const silent = !!error.config?.silent
      if ((error.response.status === 401 || error.response.status === 403) && !silent) {
        // silent 豁免登出：widget 单卡的 401/403（如角色差异）只落卡片错误态，
        // 不得把用户踢回登录页（spec「单卡失败不传播」的终级形态）
        localStorage.removeItem('token')
        router.push('/login')
      } else if (!silent) {
        // silent: 调用方自带降级分支（端点差异按缺失处理），不弹全局错误
        ElMessage.error(error.response.data?.message || '系统错误')
      }
    } else if (!error.config?.silent) {
      ElMessage.error('网络异常')
    }
    return Promise.reject(error)
  }
)

export default service
