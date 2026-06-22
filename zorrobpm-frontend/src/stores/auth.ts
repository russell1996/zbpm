import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '@/entities/user/User'
import * as authService from '@/services/authService'

const TOKEN_KEY = 'zbpm_token'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const accessToken = ref<string | null>(localStorage.getItem(TOKEN_KEY))
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const isAuthenticated = computed(() => !!accessToken.value && !!user.value)
  const isAdmin = computed(() => user.value?.role === 'ADMIN')

  function setToken(token: string | null) {
    accessToken.value = token
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  }

  async function login(username: string, password: string): Promise<boolean> {
    isLoading.value = true
    error.value = null
    try {
      const res = await authService.login({ username, password })
      setToken(res.token)
      user.value = res.user
      return true
    } catch (e: unknown) {
      const status = (e as { response?: { status?: number } })?.response?.status
      error.value = status === 401 ? 'Invalid username or password' : 'Login failed'
      setToken(null)
      user.value = null
      return false
    } finally {
      isLoading.value = false
    }
  }

  /** Restore the session from a stored token (called by the route guard on first navigation/reload). */
  async function init() {
    if (user.value) return
    if (!accessToken.value) return
    try {
      isLoading.value = true
      user.value = await authService.getMe()
    } catch {
      setToken(null)
      user.value = null
    } finally {
      isLoading.value = false
    }
  }

  function logout() {
    setToken(null)
    user.value = null
    error.value = null
    window.location.href = '/ui/login'
  }

  return {
    user,
    accessToken,
    isLoading,
    error,
    isAuthenticated,
    isAdmin,
    login,
    logout,
    init,
  }
})
