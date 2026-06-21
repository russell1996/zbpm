import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '@/entities/user/User'
import { getMe } from '@/services/mock/userService'

const KEYCLOAK_URL = import.meta.env.VITE_KEYCLOAK_URL || 'https://keycloak.example.com'
const KEYCLOAK_REALM = import.meta.env.VITE_KEYCLOAK_REALM || 'KT'
const KEYCLOAK_CLIENT_ID = import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'zorrobpm-frontend'
const REDIRECT_URI = import.meta.env.VITE_KEYCLOAK_REDIRECT_URI || window.location.origin

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const accessToken = ref<string | null>(null)
  const refreshToken = ref<string | null>(null)
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const isAuthenticated = computed(() => !!accessToken.value && !!user.value)

  function login() {
    const state = crypto.randomUUID()
    const codeVerifier = crypto.randomUUID()
    sessionStorage.setItem('oidc_state', state)
    sessionStorage.setItem('oidc_code_verifier', codeVerifier)

    const params = new URLSearchParams({
      client_id: KEYCLOAK_CLIENT_ID,
      redirect_uri: REDIRECT_URI,
      response_type: 'code',
      scope: 'openid profile email',
      state,
      code_challenge: codeVerifier,
      code_challenge_method: 'plain',
    })

    window.location.href = `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/auth?${params}`
  }

  async function handleCallback() {
    const url = new URL(window.location.href)
    const code = url.searchParams.get('code')
    const state = url.searchParams.get('state')

    if (!code || !state) {
      error.value = 'Invalid callback parameters'
      return
    }

    const savedState = sessionStorage.getItem('oidc_state')
    if (state !== savedState) {
      error.value = 'State mismatch'
      return
    }

    isLoading.value = true
    try {
      // Mock: in real app, exchange code for tokens via backend
      accessToken.value = 'mock-access-token'
      refreshToken.value = 'mock-refresh-token'
      user.value = await getMe()

      window.history.replaceState({}, '', REDIRECT_URI)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Authentication failed'
    } finally {
      isLoading.value = false
    }
  }

  async function init() {
    // Check if already authenticated (e.g., after page reload)
    if (accessToken.value && user.value) return

    // Mock: try to get current user
    try {
      isLoading.value = true
      accessToken.value = 'mock-access-token'
      user.value = await getMe()
    } catch {
      user.value = null
      accessToken.value = null
    } finally {
      isLoading.value = false
    }
  }

  function logout() {
    user.value = null
    accessToken.value = null
    refreshToken.value = null
    error.value = null
    window.location.href = `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/logout?client_id=${KEYCLOAK_CLIENT_ID}&post_logout_redirect_uri=${REDIRECT_URI}`
  }

  return {
    user,
    accessToken,
    refreshToken,
    isLoading,
    error,
    isAuthenticated,
    login,
    logout,
    handleCallback,
    init,
  }
})
