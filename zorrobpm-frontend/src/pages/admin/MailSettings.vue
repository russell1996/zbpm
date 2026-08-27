<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from '@/composables/useToast'
import {
  getMailHealth,
  getMailSettings,
  saveMailSettings,
  testMailSettingsToSelf,
  type MailSettings,
  type MailHealth,
} from '@/services/adminService'

const { t } = useI18n()
const toast = useToast()

const loading = ref(true)
const saving = ref(false)
const testing = ref(false)

const health = ref<MailHealth | null>(null)

const form = ref({
  host: '',
  port: null as number | null,
  username: '',
  password: '',
  from: '',
  allowedRecipients: '',
})
const passwordSet = ref(false)
const testResult = ref<string | null>(null)
const testError = ref<string | null>(null)

onMounted(async () => {
  try {
    const [h, s] = await Promise.all([getMailHealth(), getMailSettings()])
    health.value = h
    const settings = s as MailSettings
    form.value.host = settings.host ?? ''
    form.value.port = settings.port ?? null
    form.value.username = settings.username ?? ''
    form.value.from = settings.from ?? ''
    form.value.allowedRecipients = settings.allowedRecipients ?? ''
    passwordSet.value = settings.passwordSet
  } catch {
    toast.error('Failed to load mail settings')
  } finally {
    loading.value = false
  }
})

async function onSave() {
  saving.value = true
  testResult.value = null
  testError.value = null
  try {
    const saved = await saveMailSettings({
      host: form.value.host || null,
      port: form.value.port,
      username: form.value.username || null,
      password: form.value.password ? form.value.password : null,
      from: form.value.from || null,
      allowedRecipients: form.value.allowedRecipients || null,
    })
    passwordSet.value = saved.passwordSet
    form.value.password = ''
    toast.success(t('mailSaved'))
  } catch (e: any) {
    toast.error(e?.response?.data?.message ?? 'Failed to save')
  } finally {
    saving.value = false
  }
}

async function onTest() {
  testing.value = true
  testResult.value = null
  testError.value = null
  try {
    const msg = await testMailSettingsToSelf({
      host: form.value.host || null,
      port: form.value.port,
      username: form.value.username || null,
      password: form.value.password ? form.value.password : null,
      from: form.value.from || null,
    })
    testResult.value = msg
  } catch (e: any) {
    testError.value = e?.response?.data?.message ?? 'SMTP error'
  } finally {
    testing.value = false
  }
}
</script>

<template>
  <div class="space-y-6 max-w-2xl">
    <h1 class="text-2xl font-bold">{{ t('mailSettings') }}</h1>

    <div v-if="loading" class="text-sm text-muted-foreground" data-testid="loading">{{ t('loading') }}</div>

    <template v-else>
      <div class="border border-border rounded-lg p-4 bg-card">
        <h2 class="text-lg font-bold mb-3">{{ t('mailHealth') }}</h2>
        <div class="text-sm space-y-1">
          <div>{{ t('mailConfigured') }}: {{ health?.configured ? t('mailConfigured') : t('mailNotConfigured') }}</div>
          <div>{{ t('mailLastSuccess') }}: {{ health?.lastSuccess ?? '—' }}</div>
          <div>{{ t('mailLastError') }}: {{ health?.lastErrorMessage ?? '—' }}</div>
        </div>
      </div>

      <div class="border border-border rounded-lg p-4 bg-card space-y-4">
        <div>
          <label class="block text-sm font-medium mb-1">{{ t('mailHost') }}</label>
          <input v-model="form.host" class="w-full border border-border rounded px-2 py-1 bg-background" data-testid="host" />
        </div>
        <div>
          <label class="block text-sm font-medium mb-1">{{ t('mailPort') }}</label>
          <input v-model.number="form.port" type="number" class="w-full border border-border rounded px-2 py-1 bg-background" data-testid="port" />
        </div>
        <div>
          <label class="block text-sm font-medium mb-1">{{ t('mailUsername') }}</label>
          <input v-model="form.username" class="w-full border border-border rounded px-2 py-1 bg-background" data-testid="username" />
        </div>
        <div>
          <label class="block text-sm font-medium mb-1">{{ t('mailPassword') }}</label>
          <input v-model="form.password" type="password" class="w-full border border-border rounded px-2 py-1 bg-background" data-testid="password" />
          <p class="text-xs text-muted-foreground mt-1">
            {{ passwordSet ? t('mailPasswordSet') : t('mailPasswordNotSet') }} — {{ t('mailPasswordLeaveBlank') }}
          </p>
        </div>
        <div>
          <label class="block text-sm font-medium mb-1">{{ t('mailFrom') }}</label>
          <input v-model="form.from" class="w-full border border-border rounded px-2 py-1 bg-background" data-testid="from" />
        </div>
        <div>
          <label class="block text-sm font-medium mb-1">{{ t('mailAllowedRecipients') }}</label>
          <textarea v-model="form.allowedRecipients" class="w-full border border-border rounded px-2 py-1 bg-background" data-testid="allowedRecipients"></textarea>
        </div>

        <div class="flex gap-3">
          <button :disabled="saving" class="px-4 py-2 rounded bg-primary text-primary-foreground" data-testid="save" @click="onSave">
            {{ saving ? t('mailSaving') : t('mailSave') }}
          </button>
          <button :disabled="testing" class="px-4 py-2 rounded border border-border" data-testid="test" @click="onTest">
            {{ testing ? t('loading') : t('mailTest') }}
          </button>
        </div>
      </div>

      <div v-if="testResult" class="text-sm text-green-600" data-testid="testResult">{{ testResult }}</div>
      <div v-if="testError" class="text-sm text-red-600" data-testid="testError">{{ testError }}</div>
    </template>
  </div>
</template>
