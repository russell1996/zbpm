<template>
  <div class="min-h-screen flex items-center justify-center bg-gray-50">
    <div class="w-full max-w-md bg-white rounded-lg shadow-md p-8">
      <h1 class="text-2xl font-bold text-gray-900 mb-6">Change Password</h1>
      <p class="text-sm text-gray-600 mb-6">
        You must change your password before continuing.
      </p>

      <div v-if="error" class="mb-4 p-3 bg-red-50 border border-red-200 rounded text-sm text-red-700">
        {{ error }}
      </div>

      <form @submit.prevent="handleChangePassword" class="space-y-4">
        <div>
          <label for="newPassword" class="block text-sm font-medium text-gray-700 mb-1">
            New Password
          </label>
          <input
            id="newPassword"
            v-model="newPassword"
            type="password"
            required
            minlength="8"
            class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
          />
        </div>

        <div>
          <label for="confirmPassword" class="block text-sm font-medium text-gray-700 mb-1">
            Confirm Password
          </label>
          <input
            id="confirmPassword"
            v-model="confirmPassword"
            type="password"
            required
            minlength="8"
            class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
          />
        </div>

        <div v-if="mismatch" class="text-sm text-red-600">
          Passwords do not match.
        </div>

        <button
          type="submit"
          :disabled="isLoading || mismatch || !newPassword"
          class="w-full py-2 px-4 bg-blue-600 text-white rounded-md hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed font-medium"
        >
          {{ isLoading ? 'Changing...' : 'Change Password' }}
        </button>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { updateUser } from '@/services/userService'

const router = useRouter()
const auth = useAuthStore()

const newPassword = ref('')
const confirmPassword = ref('')
const isLoading = ref(false)
const error = ref<string | null>(null)

const mismatch = computed(() => confirmPassword.value !== '' && newPassword.value !== confirmPassword.value)

async function handleChangePassword() {
  if (!auth.user || mismatch.value || !newPassword.value) return

  isLoading.value = true
  error.value = null
  try {
    await updateUser(auth.user.id, {
      fullName: auth.user.fullName,
      email: auth.user.email,
      role: auth.user.role,
      active: auth.user.active,
      password: newPassword.value,
    })
    // Refresh user data — forcePasswordChange should now be false
    await auth.refreshUser()
    if (!auth.forcePasswordChange) {
      router.push({ name: 'dashboard' })
    } else {
      error.value = 'Password change did not take effect. Please try again.'
    }
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
    error.value = msg || 'Failed to change password. Please try again.'
  } finally {
    isLoading.value = false
  }
}
</script>
