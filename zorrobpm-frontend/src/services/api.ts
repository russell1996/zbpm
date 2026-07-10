import axios from 'axios'
import { useAuthStore } from '@/stores/auth'
import { createRefreshInterceptor } from './refreshInterceptor'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/api',
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true, // Send httpOnly cookie automatically
})

createRefreshInterceptor(api)

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const url: string = error.config?.url || ''
    const isLoginCall = url.includes('/auth/login')
    // After refresh failed, sign out (but not for the login attempt itself)
    if (error.response?.status === 401 && !isLoginCall) {
      const auth = useAuthStore()
      if (auth.isAuthenticated) auth.logout()
    }
    return Promise.reject(error)
  },
)

export default api
