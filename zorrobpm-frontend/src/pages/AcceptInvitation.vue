<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { acceptInvitation } from '@/services/userService'
import { useToast } from '@/composables/useToast'
import { isWeakPassword } from '@/utils/weakPassword'

const { t } = useI18n()
const toast = useToast()
const route = useRoute()
const router = useRouter()

const token = ref((route.query.token as string) || '')
const password = ref('')
const confirm = ref('')
const submitting = ref(false)
const done = ref(false)
const error = ref<string | null>(null)
const passwordWeak = computed(() => isWeakPassword(password.value))

async function submit() {
  error.value = null
  if (!token.value) {
    error.value = t('acceptInvitationTokenMissing')
    return
  }
  if (password.value.length < 12) {
    error.value = t('passwordTooShort')
    return
  }
  // WO-ACL-19 (P2): apply the shared weak-password blocklist, not just length.
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
    await acceptInvitation(token.value, password.value)
    done.value = true
    toast.success(t('invitationAccepted'))
    setTimeout(() => router.push('/login'), 1500)
  } catch {
    error.value = t('invitationAcceptFailed')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-background px-4">
    <div class="w-full max-w-sm space-y-6 bg-card border border-border rounded-lg p-6 shadow-sm">
      <h1 class="text-xl font-bold">{{ t('acceptInvitationTitle') }}</h1>
      <p class="text-sm text-muted-foreground">{{ t('acceptInvitationHint') }}</p>

      <div v-if="done" class="text-sm text-green-600" data-testid="accept-invitation-success">
        {{ t('invitationAccepted') }}
      </div>

      <form v-else class="space-y-4" @submit.prevent="submit">
        <div v-if="error" class="text-sm text-red-600" data-testid="accept-invitation-error">{{ error }}</div>
        <div>
          <label class="block text-sm font-medium mb-1">{{ t('newPassword') }}</label>
          <input
            v-model="password"
            type="password"
            autocomplete="new-password"
            class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
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
        >
          {{ t('setNewPassword') }}
        </button>
      </form>

      <RouterLink to="/login" class="block text-center text-sm text-primary hover:underline">
        {{ t('backToLogin') }}
      </RouterLink>
    </div>
  </div>
</template>
