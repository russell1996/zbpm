<template>
  <div class="max-w-4xl mx-auto space-y-6 p-4">
    <!-- Who am I (read-only) -->
    <section class="bg-card border border-border rounded-lg p-6">
      <h2 class="text-lg font-semibold mb-4">{{ t('whoAmI') }}</h2>
      <dl class="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-3 text-sm">
        <div><dt class="text-muted-foreground">{{ t('username') }}</dt><dd>{{ auth.user?.username }}</dd></div>
        <div><dt class="text-muted-foreground">{{ t('fullName') }}</dt><dd>{{ auth.user?.fullName || '—' }}</dd></div>
        <div><dt class="text-muted-foreground">{{ t('email') }}</dt><dd>{{ auth.user?.email || '—' }}</dd></div>
        <div><dt class="text-muted-foreground">{{ t('role') }}</dt><dd>{{ auth.user?.role }}</dd></div>
      </dl>
    </section>

    <!-- Password change -->
    <section class="bg-card border border-border rounded-lg p-6">
      <h2 class="text-lg font-semibold mb-4">{{ t('changePassword') }}</h2>

      <div v-if="passwordError" data-testid="password-error" class="mb-4 p-3 bg-red-50 border border-red-200 rounded text-sm text-red-700">
        {{ passwordError }}
      </div>
      <div v-if="passwordSuccess" data-testid="password-success" class="mb-4 p-3 bg-green-50 border border-green-200 rounded text-sm text-green-700">
        {{ t('passwordChangedOk') }}
      </div>

      <form @submit.prevent="changePassword" class="space-y-4 max-w-md">
        <div>
          <label for="currentPassword" class="block text-sm font-medium mb-1">{{ t('currentPassword') }}</label>
          <input id="currentPassword" v-model="currentPassword" type="password" required
            class="w-full px-3 py-2 border border-border rounded-md bg-background" />
        </div>
        <div>
          <label for="newPassword" class="block text-sm font-medium mb-1">{{ t('newPassword') }}</label>
          <input id="newPassword" v-model="newPassword" type="password" required minlength="8"
            class="w-full px-3 py-2 border border-border rounded-md bg-background" />
        </div>
        <div>
          <label for="confirmNew" class="block text-sm font-medium mb-1">{{ t('confirmPassword') }}</label>
          <input id="confirmNew" v-model="confirmNew" type="password" required
            class="w-full px-3 py-2 border border-border rounded-md bg-background" />
        </div>
        <div v-if="mismatch" class="text-sm text-red-600">{{ t('passwordsDoNotMatch') }}</div>

        <button type="submit" :disabled="busy || mismatch || !currentPassword || !newPassword"
          class="px-4 py-2 bg-primary text-primary-foreground rounded-md disabled:opacity-50 disabled:cursor-not-allowed">
          {{ busy ? t('changingPassword') : t('changePassword') }}
        </button>
      </form>
    </section>

    <!-- API key (reused page component) -->
    <section>
      <h2 class="text-lg font-semibold mb-4">{{ t('myApiKey') }}</h2>
      <MyApiKey />
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { changeMyPassword } from '@/services/userService'
import MyApiKey from './MyApiKey.vue'

const { t } = useI18n()
const auth = useAuthStore()

const currentPassword = ref('')
const newPassword = ref('')
const confirmNew = ref('')
const busy = ref(false)
const passwordError = ref<string | null>(null)
const passwordSuccess = ref(false)

const mismatch = computed(() => confirmNew.value !== '' && newPassword.value !== confirmNew.value)

async function changePassword() {
  if (!auth.user || mismatch.value || !newPassword.value || !currentPassword.value) return
  busy.value = true
  passwordError.value = null
  passwordSuccess.value = false
  try {
    await changeMyPassword(currentPassword.value, newPassword.value)
    passwordSuccess.value = true
    currentPassword.value = ''
    newPassword.value = ''
    confirmNew.value = ''
    // forcePasswordChange cleared server-side — refresh the flag
    await auth.refreshUser()
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
    passwordError.value = msg || t('failedToChangePassword')
  } finally {
    busy.value = false
  }
}
</script>
