import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '@/entities/user/User'
import * as authService from '@/services/authService'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const isAuthenticated = computed(() => !!user.value)
  const isAdmin = computed(() => user.value?.role === 'ADMIN')

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

  function logout() {
    user.value = null
    error.value = null
    // Clear cookie by setting maxAge to 0
    document.cookie = 'zbpm_token=; Max-Age=0; Path=/; SameSite=Strict'
    window.location.href = '/ui/login'
  }

  return {
    user,
    isLoading,
    error,
    isAuthenticated,
    isAdmin,
    login,
    logout,
    init,
  }
})
