import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'

export function useAuth() {
  const store = useAuthStore()

  const isAuthenticated = computed(() => store.isAuthenticated)
  const user = computed(() => store.user)
  const isLoading = computed(() => store.isLoading)

  function login() {
    store.login()
  }

  function logout() {
    store.logout()
  }

  async function handleCallback() {
    await store.handleCallback()
  }

  async function init() {
    await store.init()
  }

  return {
    isAuthenticated,
    user,
    isLoading,
    login,
    logout,
    handleCallback,
    init,
  }
}
