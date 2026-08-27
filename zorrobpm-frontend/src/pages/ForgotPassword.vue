<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { requestPasswordReset } from '@/services/userService'
import { useToast } from '@/composables/useToast'

const { t } = useI18n()
const toast = useToast()

const email = ref('')
const submitting = ref(false)
const submitted = ref(false)

async function submit() {
  if (!email.value) {
    toast.warning(t('fillRequired'))
    return
  }
  submitting.value = true
  try {
    // WO-ACL-18 criterion 12: enumeration-safe — always succeeds visibly regardless of account existence.
    await requestPasswordReset(email.value)
    submitted.value = true
  } catch {
    // Even on a transport error we must not leak whether the account exists.
    submitted.value = true
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-background px-4">
    <div class="w-full max-w-sm space-y-6 bg-card border border-border rounded-lg p-6 shadow-sm">
      <h1 class="text-xl font-bold">{{ t('forgotPasswordTitle') }}</h1>
      <p class="text-sm text-muted-foreground">{{ t('forgotPasswordHint') }}</p>

      <div v-if="submitted" class="text-sm text-green-600" data-testid="forgot-password-success">
        {{ t('resetLinkSent') }}
      </div>

      <form v-else class="space-y-4" @submit.prevent="submit">
        <div>
          <label class="block text-sm font-medium mb-1">{{ t('email') }}</label>
          <input
            v-model="email"
            type="email"
            autocomplete="email"
            class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
        <button
          type="submit"
          :disabled="submitting"
          class="w-full px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 disabled:opacity-50"
        >
          {{ t('sendResetLink') }}
        </button>
      </form>

      <RouterLink to="/login" class="block text-center text-sm text-primary hover:underline">
        {{ t('backToLogin') }}
      </RouterLink>
    </div>
  </div>
</template>
