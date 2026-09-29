<template>
  <div class="max-w-5xl mx-auto p-4">
    <h1 class="text-2xl font-bold mb-6">{{ t('accountSettings') }}</h1>
    <div class="flex gap-6">
      <!-- Left nav — Settings navigation, not a card -->
      <nav ref="tablistRef" class="w-48 shrink-0 space-y-1" role="tablist" aria-orientation="vertical" :aria-label="t('accountSettings')" @keydown="onKeydown">
        <button
          id="tab-profile"
          role="tab"
          :aria-selected="activeView === 'profile' ? 'true' : 'false'"
          aria-controls="panel-profile"
          class="w-full text-left px-3 py-2 text-sm rounded-md transition-colors"
          :class="activeView === 'profile' ? 'bg-muted font-medium' : 'hover:bg-muted'"
          @click="activeView = 'profile'"
        >
          {{ t('profile') }}
        </button>
        <button
          id="tab-apikey"
          role="tab"
          :aria-selected="activeView === 'apikey' ? 'true' : 'false'"
          aria-controls="panel-apikey"
          class="w-full text-left px-3 py-2 text-sm rounded-md transition-colors"
          :class="activeView === 'apikey' ? 'bg-muted font-medium' : 'hover:bg-muted'"
          @click="activeView = 'apikey'"
        >
          {{ t('apiKey') }}
        </button>
      </nav>

      <!-- Right content — single surface -->
      <div class="flex-1 min-w-0 bg-card border border-border rounded-lg p-6">
        <!-- Профиль -->
        <div v-if="activeView === 'profile'" id="panel-profile" role="tabpanel" aria-labelledby="tab-profile" class="space-y-6">
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
        <div v-else id="panel-apikey" role="tabpanel" aria-labelledby="tab-apikey">
          <MyApiKey />
        </div>
      </div>
    </div>

    <!-- Password dialog -->
    <div
      v-if="showPasswordDialog"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="closePasswordDialog"
    >
      <div class="bg-card rounded-lg shadow-lg w-full max-w-md p-6 space-y-4">
        <div class="flex items-center justify-between">
          <h2 class="text-lg font-bold">{{ t('changePasswordTitle') }}</h2>
          <button class="p-1 text-muted-foreground hover:text-foreground" @click="closePasswordDialog">✕</button>
        </div>

        <div v-if="passwordError" data-testid="password-error" class="p-3 bg-red-50 border border-red-200 rounded text-sm text-red-700">
          {{ passwordError }}
        </div>

        <form @submit.prevent="changePassword" class="space-y-4">
          <div>
            <label for="currentPassword" class="block text-sm font-medium mb-1">{{ t('currentPassword') }}</label>
            <div class="relative">
              <input id="currentPassword" v-model="currentPassword" :type="showCurrent ? 'text' : 'password'" required
                class="w-full px-3 py-2 border border-border rounded-md bg-background pr-10" />
              <button type="button" class="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-muted-foreground hover:text-foreground" @click="showCurrent = !showCurrent" tabindex="-1">
                <EyeOff v-if="showCurrent" class="h-4 w-4" />
                <Eye v-else class="h-4 w-4" />
              </button>
            </div>
          </div>
          <div>
            <label for="newPassword" class="block text-sm font-medium mb-1">{{ t('newPassword') }}</label>
            <div class="relative">
              <input id="newPassword" v-model="newPassword" :type="showNew ? 'text' : 'password'" required minlength="12"
                class="w-full px-3 py-2 border rounded-md bg-background pr-10"
                :class="newPassword && newPasswordWeak ? 'border-red-500 focus:ring-red-500 focus:border-red-500' : 'border-border'" />
              <button type="button" class="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-muted-foreground hover:text-foreground" @click="showNew = !showNew" tabindex="-1">
                <EyeOff v-if="showNew" class="h-4 w-4" />
                <Eye v-else class="h-4 w-4" />
              </button>
            </div>
            <p v-if="newPassword && newPasswordWeak" class="text-sm text-red-600 mt-1" data-testid="password-weak">
              {{ newPassword.length < 12 ? t('passwordTooShort') : t('passwordTooWeak') }}
            </p>
          </div>
          <div>
            <label for="confirmNew" class="block text-sm font-medium mb-1">{{ t('confirmNewPassword') }}</label>
            <div class="relative">
              <input id="confirmNew" v-model="confirmNew" :type="showConfirm ? 'text' : 'password'" required
                class="w-full px-3 py-2 border rounded-md bg-background pr-10"
                :class="mismatch ? 'border-red-500 focus:ring-red-500 focus:border-red-500' : 'border-border'" />
              <button type="button" class="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-muted-foreground hover:text-foreground" @click="showConfirm = !showConfirm" tabindex="-1">
                <EyeOff v-if="showConfirm" class="h-4 w-4" />
                <Eye v-else class="h-4 w-4" />
              </button>
            </div>
            <p v-if="mismatch" class="text-sm text-red-600 mt-1">{{ t('passwordsDoNotMatch') }}</p>
          </div>

          <div class="flex justify-end gap-2">
            <button type="button" class="px-4 py-2 border border-border rounded-md text-sm hover:bg-muted" @click="closePasswordDialog">
              {{ t('cancel') }}
            </button>
            <button type="submit" :disabled="busy || mismatch || newPasswordWeak || !currentPassword || !newPassword"
              class="px-4 py-2 bg-primary text-primary-foreground rounded-md text-sm disabled:opacity-50 disabled:cursor-not-allowed">
              {{ busy ? t('changingPassword') : t('changePasswordAction') }}
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
import { useToast } from '@/composables/useToast'
import { Eye, EyeOff } from 'lucide-vue-next'
import MyApiKey from './MyApiKey.vue'
import { isWeakPassword } from '@/utils/weakPassword'

const { t } = useI18n()
const auth = useAuthStore()
const toast = useToast()

const activeView = ref<'profile' | 'apikey'>('profile')
const showPasswordDialog = ref(false)
const tablistRef = ref<HTMLElement | null>(null)

function onKeydown(e: KeyboardEvent) {
  const tabs = Array.from(tablistRef.value?.querySelectorAll<HTMLButtonElement>('[role="tab"]') ?? [])
  if (!tabs.length) return
  const idx = tabs.indexOf(document.activeElement as HTMLButtonElement)
  if (idx < 0) return
  const last = tabs.length - 1
  let target = -1
  switch (e.key) {
    case 'ArrowDown': target = idx === last ? 0 : idx + 1; break
    case 'ArrowUp': target = idx === 0 ? last : idx - 1; break
    case 'Home': target = 0; break
    case 'End': target = last; break
    default: return
  }
  e.preventDefault()
  tabs[target].focus()
  tabs[target].click()
}

const currentPassword = ref('')
const newPassword = ref('')
const confirmNew = ref('')
const showCurrent = ref(false)
const showNew = ref(false)
const showConfirm = ref(false)
const busy = ref(false)
const passwordError = ref<string | null>(null)

function closePasswordDialog() {
  showPasswordDialog.value = false
  // Clear form and error state for next open (criterion: clean form on re-open)
  currentPassword.value = ''
  newPassword.value = ''
  confirmNew.value = ''
  passwordError.value = null
}

const mismatch = computed(() => confirmNew.value !== '' && newPassword.value !== confirmNew.value)

const newPasswordWeak = computed(() => isWeakPassword(newPassword.value))

async function changePassword() {
  if (!auth.user || mismatch.value || newPasswordWeak.value || !newPassword.value || !currentPassword.value) return
  if (busy.value) return
  busy.value = true
  passwordError.value = null
  try {
    await changeMyPassword(currentPassword.value, newPassword.value)
    toast.success('Пароль успешно изменён')
    closePasswordDialog()
    await auth.refreshUser()
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
    passwordError.value = msg || t('failedToChangePassword')
  } finally {
    busy.value = false
  }
}
</script>
