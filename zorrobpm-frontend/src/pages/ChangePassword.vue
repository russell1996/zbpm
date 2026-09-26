<template>
  <div class="min-h-screen flex items-center justify-center bg-gray-50">
    <div class="w-full max-w-md bg-white rounded-lg shadow-md p-8">
      <h1 class="text-2xl font-bold text-gray-900 mb-6">{{ t('changePassword') }}</h1>
      <p class="text-sm text-gray-600 mb-6">{{ t('mustChangePassword') }}</p>

      <div v-if="error" class="mb-4 p-3 bg-red-50 border border-red-200 rounded text-sm text-red-700">
        {{ error }}
      </div>

      <form @submit.prevent="handleChangePassword" class="space-y-4">
        <div>
          <label for="currentPassword" class="block text-sm font-medium text-gray-700 mb-1">
            {{ t('currentPassword') }}
          </label>
          <input
            id="currentPassword"
            data-testid="change-current"
            v-model="currentPassword"
            type="password"
            required
            class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
          />
        </div>

        <div>
          <label for="newPassword" class="block text-sm font-medium text-gray-700 mb-1">
            {{ t('newPassword') }}
          </label>
          <input
            id="newPassword"
            data-testid="change-new"
            v-model="newPassword"
            type="password"
            required
            minlength="12"
            class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
          />
        </div>

        <div>
          <label for="confirmPassword" class="block text-sm font-medium text-gray-700 mb-1">
            {{ t('confirmPassword') }}
          </label>
          <input
            id="confirmPassword"
            data-testid="change-confirm"
            v-model="confirmPassword"
            type="password"
            required
            minlength="12"
            class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
          />
        </div>

        <div v-if="mismatch" class="text-sm text-red-600">
          {{ t('passwordsDoNotMatch') }}
        </div>

        <button
          type="submit"
          data-testid="change-submit"
          :disabled="isLoading || mismatch || !newPassword"
          class="w-full py-2 px-4 bg-blue-600 text-white rounded-md hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed font-medium"
        >
          {{ isLoading ? t('changingPassword') : t('changePassword') }}
        </button>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { changeMyPassword } from '@/services/userService'

const { t } = useI18n()

const router = useRouter()
const auth = useAuthStore()

const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const isLoading = ref(false)
const error = ref<string | null>(null)

const mismatch = computed(() => confirmPassword.value !== '' && newPassword.value !== confirmPassword.value)

// WO-SEC-58 (P-65 fix): the locked-out user's screen now calls the self-service
// endpoint PUT /me/password (identity from the JWT) — the old call went to
// PUT /users/{id}, which is SUPER_ADMIN-only and returned 403 for exactly the
// users this screen exists for.
async function handleChangePassword() {
  if (!auth.user || mismatch.value || !newPassword.value || !currentPassword.value) return

  isLoading.value = true
  error.value = null
  try {
    await changeMyPassword(currentPassword.value, newPassword.value)
    // Refresh user data — forcePasswordChange should now be false
    await auth.refreshUser()
    if (!auth.forcePasswordChange) {
      router.push({ name: 'dashboard' })
    } else {
      error.value = t('passwordChangeFailed')
    }
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
    error.value = msg || t('failedToChangePassword')
  } finally {
    isLoading.value = false
  }
}
</script>
