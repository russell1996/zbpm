<template>
  <div class="max-w-7xl mx-auto p-4">
    <h1 class="text-2xl font-bold mb-6">{{ t('accountSettings') }}</h1>
    <div class="flex gap-6">
      <!-- Left nav — Settings navigation, not a card -->
      <nav class="w-48 shrink-0 space-y-1" :aria-label="t('accountSettings')">
        <button
          class="w-full text-left px-3 py-2 text-sm rounded-md transition-colors"
          :class="activeView === 'profile' ? 'bg-muted font-medium' : 'hover:bg-muted'"
          :aria-selected="activeView === 'profile' ? 'true' : 'false'"
          role="tab"
          @click="activeView = 'profile'"
        >
          {{ t('profile') }}
        </button>
        <button
          class="w-full text-left px-3 py-2 text-sm rounded-md transition-colors"
          :class="activeView === 'apikey' ? 'bg-muted font-medium' : 'hover:bg-muted'"
          :aria-selected="activeView === 'apikey' ? 'true' : 'false'"
          role="tab"
          @click="activeView = 'apikey'"
        >
          {{ t('apiKey') }}
        </button>
      </nav>

      <!-- Right content — single surface -->
      <div class="flex-1 min-w-0 bg-card border border-border rounded-lg p-6">
        <!-- Профиль -->
        <div v-if="activeView === 'profile'" class="space-y-6">
          <section>
            <h2 class="text-lg font-semibold mb-4">{{ t('profile') }}</h2>
            <dl class="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-3 text-sm">
              <div><dt class="text-muted-foreground">{{ t('username') }}</dt><dd>{{ auth.user?.username }}</dd></div>
              <div><dt class="text-muted-foreground">{{ t('fullName') }}</dt><dd>{{ auth.user?.fullName || '—' }}</dd></div>
              <div><dt class="text-muted-foreground">{{ t('email') }}</dt><dd>{{ auth.user?.email || '—' }}</dd></div>
              <div><dt class="text-muted-foreground">{{ t('role') }}</dt><dd>{{ auth.user?.role }}</dd></div>
            </dl>
          </section>

          <div class="border-t border-border" />

          <section>
            <div class="flex items-center justify-between">
              <div>
                <h3 class="text-sm font-semibold">{{ t('security') }}</h3>
                <p class="text-sm text-muted-foreground">{{ t('changePassword') }}</p>
              </div>
              <button
                class="px-4 py-2 bg-primary text-primary-foreground rounded-md text-sm hover:opacity-90 transition-opacity"
                @click="showPasswordDialog = true"
              >
                {{ t('changePasswordAction') }}
              </button>
            </div>
          </section>
        </div>

        <!-- API-ключ -->
        <div v-else>
          <MyApiKey />
        </div>
      </div>
    </div>

    <!-- Password dialog -->
    <div
      v-if="showPasswordDialog"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="showPasswordDialog = false"
    >
      <div class="bg-card rounded-lg shadow-lg w-full max-w-md p-6 space-y-4">
        <div class="flex items-center justify-between">
          <h2 class="text-lg font-bold">{{ t('changePassword') }}</h2>
          <button class="p-1 text-muted-foreground hover:text-foreground" @click="showPasswordDialog = false">✕</button>
        </div>

        <div v-if="passwordError" data-testid="password-error" class="p-3 bg-red-50 border border-red-200 rounded text-sm text-red-700">
          {{ passwordError }}
        </div>
        <div v-if="passwordSuccess" data-testid="password-success" class="p-3 bg-green-50 border border-green-200 rounded text-sm text-green-700">
          {{ t('passwordChangedOk') }}
        </div>

        <form @submit.prevent="changePassword" class="space-y-4">
          <div>
            <label for="currentPassword" class="block text-sm font-medium mb-1">{{ t('currentPassword') }}</label>
            <input id="currentPassword" v-model="currentPassword" type="password" required
              class="w-full px-3 py-2 border border-border rounded-md bg-background" />
          </div>
          <div>
            <label for="newPassword" class="block text-sm font-medium mb-1">{{ t('newPassword') }}</label>
            <input id="newPassword" v-model="newPassword" type="password" required minlength="12"
              class="w-full px-3 py-2 border border-border rounded-md bg-background" />
            <p v-if="newPassword && newPasswordWeak" class="text-sm text-red-600 mt-1" data-testid="password-weak">
              {{ newPassword.length < 12 ? t('passwordTooShort') : t('passwordTooWeak') }}
            </p>
          </div>
          <div>
            <label for="confirmNew" class="block text-sm font-medium mb-1">{{ t('confirmPassword') }}</label>
            <input id="confirmNew" v-model="confirmNew" type="password" required
              class="w-full px-3 py-2 border border-border rounded-md bg-background" />
          </div>
          <div v-if="mismatch" class="text-sm text-red-600">{{ t('passwordsDoNotMatch') }}</div>

          <div class="flex justify-end gap-2">
            <button type="button" class="px-4 py-2 border border-border rounded-md text-sm hover:bg-muted" @click="showPasswordDialog = false">
              {{ t('cancel') }}
            </button>
            <button type="submit" :disabled="busy || mismatch || newPasswordWeak || !currentPassword || !newPassword"
              class="px-4 py-2 bg-primary text-primary-foreground rounded-md text-sm disabled:opacity-50 disabled:cursor-not-allowed">
              {{ busy ? t('changingPassword') : t('changePassword') }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { changeMyPassword } from '@/services/userService'
import MyApiKey from './MyApiKey.vue'

const { t } = useI18n()
const auth = useAuthStore()

const activeView = ref<'profile' | 'apikey'>('profile')
const showPasswordDialog = ref(false)

const currentPassword = ref('')
const newPassword = ref('')
const confirmNew = ref('')
const busy = ref(false)
const passwordError = ref<string | null>(null)
const passwordSuccess = ref(false)

const mismatch = computed(() => confirmNew.value !== '' && newPassword.value !== confirmNew.value)

const weakBlocklist = new Set(['admin', 'password', 'zorrodev', '123456', 'qwerty', 'letmein', 'welcome', 'monkey', 'dragon', 'master', 'abc123', 'passw0rd', 'changeme', 'default', 'root', 'toor', 'test', 'demo', 'sample'])
const newPasswordWeak = computed(() => {
  const v = newPassword.value
  if (!v) return false
  if (v.length < 12) return true
  return weakBlocklist.has(v.toLowerCase())
})

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
    await auth.refreshUser()
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
    passwordError.value = msg || t('failedToChangePassword')
  } finally {
    busy.value = false
  }
}
</script>
