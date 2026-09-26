import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'

export function useAuth() {
  const store = useAuthStore()

  const isAuthenticated = computed(() => store.isAuthenticated)
  const isAdmin = computed(() => store.isAdmin)
  const user = computed(() => store.user)
  const isLoading = computed(() => store.isLoading)
  const error = computed(() => store.error)

  async function login(username: string, password: string) {
    return store.login(username, password)
  }

  function logout() {
    store.logout()
  }

  async function init() {
    await store.init()
  }

  return {
    isAuthenticated,
    isAdmin,
    user,
    isLoading,
    error,
    login,
    logout,
    init,
  }
}
