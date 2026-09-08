<script setup lang="ts">
import { ref, computed } from 'vue'
import { RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { register } from '@/services/registrationService'
import { useToast } from '@/composables/useToast'
import { isWeakPassword } from '@/utils/weakPassword'
import { translatedError } from '@/shared/lib/utils'

const { t } = useI18n()
const toast = useToast()

const username = ref('')
const email = ref('')
const fullName = ref('')
const password = ref('')
const confirm = ref('')
const submitting = ref(false)
const done = ref(false)
const error = ref<string | null>(null)
const emailFieldError = ref<string | null>(null)
const usernameFieldError = ref<string | null>(null)

const passwordWeak = computed(() => isWeakPassword(password.value))

function isValidEmail(v: string): boolean {
  const at = v.indexOf('@')
  if (at <= 0 || at === v.length - 1) return false
  const domain = v.slice(at + 1)
  return domain.includes('.') && !v.includes(' ') && !domain.includes(' ')
}

async function submit() {
  error.value = null
  emailFieldError.value = null
  usernameFieldError.value = null

  if (!username.value.trim()) {
    error.value = t('fieldRequired')
    return
  }
  if (!email.value.trim() || !isValidEmail(email.value.trim())) {
    error.value = t('invalidEmail')
    return
  }
  if (password.value.length < 12) {
    error.value = t('passwordTooShort')
    return
  }
  if (passwordWeak.value) {
    error.value = t('passwordTooWeak')
    return
  }
  if (password.value !== confirm.value) {
    error.value = t('passwordMismatch')
    return
  }

  submitting.value = true
  try {
    await register({
      username: username.value.trim(),
      password: password.value,
      fullName: fullName.value.trim() || undefined,
      email: email.value.trim(),
    })
    done.value = true
    toast.success(t('registrationSuccessTitle'))
  } catch (e: unknown) {
    const msg = translatedError(e, t, t('registrationFailed'))
    // Field-level mapping: email/username taken → under field, not generic alert (criterion 2)
    if (msg.toLowerCase().includes('email already exists') || msg.toLowerCase().includes('email')) {
      // Heuristic: if message mentions email, put under email; username analogous
      if (msg.toLowerCase().includes('email')) {
        emailFieldError.value = msg
        return
      }
    }
    if (msg.toLowerCase().includes('username already exists') || msg.toLowerCase().includes('username')) {
      usernameFieldError.value = msg
      return
    }
    // Fallback: check raw response code if translatedError hid it
    const data = (e as { response?: { data?: { code?: string } } })?.response?.data
    if (data?.code === 'EMAIL_ALREADY_EXISTS' || msg.includes('Email already exists')) {
      emailFieldError.value = msg
      return
    }
    if (data?.code === 'USERNAME_ALREADY_EXISTS' || msg.includes('Username already exists')) {
      usernameFieldError.value = msg
      return
    }
    error.value = msg
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-background px-4">
    <div class="w-full max-w-sm space-y-6 bg-card border border-border rounded-lg p-6 shadow-sm">
      <h1 class="text-xl font-bold">{{ t('registerTitle') }}</h1>
      <p class="text-sm text-muted-foreground">{{ t('registerHint') }}</p>

      <div v-if="done" class="text-sm" data-testid="register-success">
        <p class="font-medium text-green-600">{{ t('registrationSuccessTitle') }}</p>
        <p class="text-muted-foreground mt-1">{{ t('registrationSuccessHint') }}</p>
        <RouterLink to="/login" class="inline-block mt-4 text-sm text-primary hover:underline">
          {{ t('backToLogin') }}
        </RouterLink>
      </div>

      <form v-else class="space-y-4" @submit.prevent="submit">
        <div v-if="error" class="text-sm text-red-600" data-testid="register-error">{{ error }}</div>

        <div>
          <label class="block text-sm font-medium mb-1">{{ t('username') }}</label>
          <input
            v-model="username"
            type="text"
            autocomplete="username"
            class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid="register-username"
          />
          <p v-if="usernameFieldError" class="text-xs text-red-600 mt-1" data-testid="register-username-error">{{ usernameFieldError }}</p>
        </div>

        <div>
          <label class="block text-sm font-medium mb-1">{{ t('email') }}</label>
          <input
            v-model="email"
            type="email"
            autocomplete="email"
            class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid="register-email"
          />
          <p v-if="emailFieldError" class="text-xs text-red-600 mt-1" data-testid="register-email-error">{{ emailFieldError }}</p>
        </div>

        <div>
          <label class="block text-sm font-medium mb-1">{{ t('fullName') }}</label>
          <input
            v-model="fullName"
            type="text"
            autocomplete="name"
            class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>

        <div>
          <label class="block text-sm font-medium mb-1">{{ t('newPassword') }}</label>
          <input
            v-model="password"
            type="password"
            autocomplete="new-password"
            class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid="register-password"
          />
          <p class="text-xs text-muted-foreground mt-1">{{ t('passwordHint') }}</p>
        </div>

        <div>
          <label class="block text-sm font-medium mb-1">{{ t('confirmPassword') }}</label>
          <input
            v-model="confirm"
            type="password"
            autocomplete="new-password"
            class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>

        <button
          type="submit"
          :disabled="submitting || passwordWeak"
          class="w-full px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 disabled:opacity-50"
          data-testid="register-submit"
        >
          {{ submitting ? t('registering') : t('registerAction') }}
        </button>
      </form>

      <RouterLink v-if="!done" to="/login" class="block text-center text-sm text-primary hover:underline">
        {{ t('backToLogin') }}
      </RouterLink>
    </div>
  </div>
</template>
