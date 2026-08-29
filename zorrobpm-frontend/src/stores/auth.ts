import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '@/entities/user/User'
import * as authService from '@/services/authService'
import api from '@/services/api'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const isAuthenticated = computed(() => !!user.value)
  const isAdmin = computed(() => user.value?.role === 'ADMIN' || user.value?.role === 'SUPER_ADMIN')
  const isSuperAdmin = computed(() => user.value?.role === 'SUPER_ADMIN')
  const forcePasswordChange = computed(() => user.value?.forcePasswordChange === true)

  async function login(username: string, password: string): Promise<boolean> {
    isLoading.value = true
    error.value = null
    try {
      const res = await authService.login({ username, password })
      // Token is now in httpOnly cookie — no localStorage needed
      user.value = res.user
      return true
    } catch (e: unknown) {
      const status = (e as { response?: { status?: number } })?.response?.status
      error.value = status === 401 ? 'Invalid username or password' : 'Login failed'
      user.value = null
      return false
    } finally {
      isLoading.value = false
    }
  }

  /** Restore the session by calling /auth/me (cookie is sent automatically). */
  async function init() {
    if (user.value) return
    try {
      isLoading.value = true
      user.value = await authService.getMe()
    } catch {
      user.value = null
    } finally {
      isLoading.value = false
    }
  }

  function setForcePasswordChange(value: boolean) {
    if (user.value) {
      user.value = { ...user.value, forcePasswordChange: value }
    }
  }

  /** Refresh user data from /auth/me (after password change). */
  async function refreshUser() {
    try {
      user.value = await authService.getMe()
    } catch {
      console.warn('refreshUser failed — resetting to guest state')
      user.value = null
    }
  }

  /**
   * Logout: invalidate server-side cookie FIRST, then clear local state.
   * Fail-safe: even if the API call fails, local state is still cleared
   * and the user is redirected to login.
   */
  async function logout() {
    try {
      await api.post('/auth/logout')
    } catch {
      console.error('Logout API call failed — clearing local state anyway')
    } finally {
      user.value = null
      error.value = null
      document.cookie = 'zbpm_token=; Max-Age=0; Path=/; SameSite=Strict'
      window.location.href = '/ui/login'
    }
  }

  return {
    user,
    isLoading,
    error,
    isAuthenticated,
    isAdmin,
    isSuperAdmin,
    forcePasswordChange,
    setForcePasswordChange,
    refreshUser,
    login,
    logout,
    init,
  }
})
