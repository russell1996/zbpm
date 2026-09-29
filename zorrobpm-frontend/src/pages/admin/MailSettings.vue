<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from '@/composables/useToast'
import { CheckCircle2, XCircle, Eye, EyeOff, Loader2 } from 'lucide-vue-next'
import { Skeleton } from '@/components/ui/skeleton'
import {
  getMailHealth,
  getMailSettings,
  saveMailSettings,
  checkMailSettings,
  testMailSettingsToSelf,
  type MailSettings,
  type MailHealth,
} from '@/services/adminService'

const { t } = useI18n()
const toast = useToast()

const loading = ref(true)
const healthLoading = ref(true)
const lastCheck = computed(() => {
  if (!health.value) return t('mailCheckNotPerformed')
  return health.value.lastSuccess ?? health.value.lastError ?? t('mailCheckNotPerformed')
})
const saving = ref(false)
const checking = ref(false)
const testSending = ref(false)
const showPassword = ref(false)
const editMode = ref(false)

const health = ref<MailHealth | null>(null)

// Editable buffer (edit mode)
const form = ref({
  host: '',
  port: null as number | null,
  username: '',
  password: '',
  from: '',
  allowedRecipients: '',
})
// Saved config snapshot for read-only view mode
const viewConfig = ref({
  host: '',
  port: null as number | null,
  username: '',
  from: '',
  allowedRecipients: '',
  passwordSet: false,
})
const passwordSet = ref(false)

function applySettings(s: MailSettings) {
  form.value.host = s.host ?? ''
  form.value.port = s.port ?? null
  form.value.username = s.username ?? ''
  form.value.from = s.from ?? ''
  form.value.allowedRecipients = s.allowedRecipients ?? ''
  passwordSet.value = s.passwordSet
  viewConfig.value = {
    host: s.host ?? '',
    port: s.port ?? null,
    username: s.username ?? '',
    from: s.from ?? '',
    allowedRecipients: s.allowedRecipients ?? '',
    passwordSet: s.passwordSet,
  }
}

onMounted(async () => {
  // Lazy background load: the health endpoint is slow, so it must not block the form.
  loadHealth()
  try {
    const settings = (await getMailSettings()) as MailSettings
    applySettings(settings)
    // No host yet -> open directly in edit mode
    editMode.value = !settings.host
  } catch {
    toast.error('Failed to load mail settings')
  } finally {
    loading.value = false
  }
})

async function loadHealth() {
  healthLoading.value = true
  try {
    health.value = await getMailHealth()
    // Authoritative config state (overrides the proxy from settings.host)
    editMode.value = !health.value.configured
  } catch {
    toast.error('Failed to load mail health')
  } finally {
    healthLoading.value = false
  }
}

async function onSave() {
  saving.value = true
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
    applySettings(saved)
    editMode.value = false
    toast.success(t('mailSaved'))
  } catch (e: any) {
    toast.error(e?.response?.data?.message ?? 'Failed to save')
  } finally {
    saving.value = false
  }
}

function onCancel() {
  // Discard edits: restore form from the saved snapshot
  form.value.host = viewConfig.value.host
  form.value.port = viewConfig.value.port
  form.value.username = viewConfig.value.username
  form.value.password = ''
  form.value.from = viewConfig.value.from
  form.value.allowedRecipients = viewConfig.value.allowedRecipients
  editMode.value = false
}

// WO-INT-8 criterion 1: "Проверить" — probes the CURRENT (possibly unsaved) form values,
// sends no email. No ready-made text from the backend — only reachable/errorCode, localized here.
async function onCheck() {
  checking.value = true
  try {
    const result = await checkMailSettings({
      host: form.value.host || null,
      port: form.value.port,
      username: form.value.username || null,
      password: form.value.password ? form.value.password : null,
      from: form.value.from || null,
    })
    if (result.reachable) {
      toast.success(t('mailCheckOk'))
    } else {
      toast.error(t('mailCheckFailed'))
    }
  } catch {
    // WO-INT-8 criterion 4: no ready-made text from the backend, ever — including error text.
    // A prior version showed e.response.data.message here, which meant a real production message
    // (e.g. "Too many mail check/test requests, try again later") reached the user in raw English
    // regardless of locale. Always localized, no exceptions.
    toast.error(t('mailCheckFailed'))
  } finally {
    checking.value = false
  }
}

// WO-INT-8 criterion 2: "Отправить тестовое письмо" — a real send, but only from the SAVED
// config: no body, no password leaves the browser. Only reachable from view mode, i.e. only once
// a config is actually saved and nothing is being edited unsaved.
async function onTestSend() {
  testSending.value = true
  try {
    await testMailSettingsToSelf()
    toast.success(t('mailTestSentToSelf'))
  } catch {
    // WO-INT-8 criterion 4: see onCheck — no backend .message here either.
    toast.error(t('mailTestSendFailed'))
  } finally {
    testSending.value = false
  }
}
</script>

<template>
  <div class="space-y-6 max-w-2xl">
    <h1 class="text-2xl font-bold">{{ t('mailSettings') }}</h1>

    <div v-if="loading" class="space-y-4" data-testid="loading">
      <Skeleton class="h-24 w-full rounded-lg" />
      <Skeleton class="h-72 w-full rounded-lg" />
    </div>

    <template v-else>
      <!-- Connection state (lazy): configuration saved vs real send/check result -->
      <div v-if="!editMode" class="border border-border rounded-lg p-4 bg-card">
        <h2 class="text-lg font-bold mb-3">{{ t('mailHealth') }}</h2>
        <div v-if="healthLoading" class="flex items-center gap-2 text-sm text-muted-foreground" data-testid="healthLoading">
          <Loader2 class="h-4 w-4 animate-spin" />
          <span>{{ t('mailHealthLoading') }}</span>
        </div>
        <div v-else-if="health" class="text-sm space-y-1">
          <div class="flex items-center gap-2">
            <span class="text-muted-foreground">{{ t('mailConfigLabel') }}:</span>
            <span v-if="health.configured" class="inline-flex items-center gap-1 text-green-600 font-medium">
              <CheckCircle2 class="h-4 w-4" /> {{ t('mailConfigured') }}
            </span>
            <span v-else class="inline-flex items-center gap-1 text-red-500 font-medium">
              <XCircle class="h-4 w-4" /> {{ t('mailNotConfigured') }}
            </span>
          </div>
          <div>{{ t('mailLastCheck') }}: {{ lastCheck }}</div>
        </div>
        <div v-else class="text-sm text-muted-foreground">{{ t('mailHealthNotChecked') }}</div>
      </div>

      <!-- View mode: saved configuration as a read-only card -->
      <div v-if="!editMode" class="space-y-4">
          <div class="border border-border rounded-lg p-4 bg-card space-y-1 text-sm">
            <h2 class="text-lg font-bold mb-3">{{ t('mailSavedConfig') }}</h2>
            <div class="flex gap-2"><span class="text-muted-foreground w-52 shrink-0">{{ t('mailHost') }}:</span><span>{{ viewConfig.host || '—' }}</span></div>
            <div class="flex gap-2"><span class="text-muted-foreground w-52 shrink-0">{{ t('mailPort') }}:</span><span>{{ viewConfig.port ?? '—' }}</span></div>
            <div class="flex gap-2"><span class="text-muted-foreground w-52 shrink-0">{{ t('mailUsername') }}:</span><span>{{ viewConfig.username || '—' }}</span></div>
            <div class="flex gap-2"><span class="text-muted-foreground w-52 shrink-0">{{ t('mailPassword') }}:</span><span>{{ viewConfig.passwordSet ? '••••••••' : '—' }}</span></div>
            <div class="flex gap-2"><span class="text-muted-foreground w-52 shrink-0">{{ t('mailFrom') }}:</span><span>{{ viewConfig.from || '—' }}</span></div>
            <div class="flex gap-2"><span class="text-muted-foreground w-52 shrink-0">{{ t('mailAllowedRecipients') }}:</span><span>{{ viewConfig.allowedRecipients || t('mailNoRestriction') }}</span></div>
        </div>
        <div class="flex gap-3">
          <button class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90" data-testid="edit" @click="editMode = true">
            {{ t('mailEdit') }}
          </button>
          <button
            v-if="viewConfig.host"
            :disabled="testSending"
            class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted"
            data-testid="testSend"
            @click="onTestSend"
          >
            {{ testSending ? t('loading') : t('mailTestSend') }}
          </button>
        </div>
      </div>

      <!-- Edit mode: the form -->
      <div v-else class="border border-border rounded-lg p-4 bg-card space-y-4">
        <div class="space-y-3">
          <h2 class="text-lg font-bold mb-3">{{ t('mailTransport') }}</h2>
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
            <div class="relative">
              <input v-model="form.password" :type="showPassword ? 'text' : 'password'" class="w-full border border-border rounded px-2 py-1 bg-background pr-10" data-testid="password" />
              <button type="button" class="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-muted-foreground hover:text-foreground" tabindex="-1" data-testid="togglePassword" :aria-label="showPassword ? t('mailHidePassword') : t('mailShowPassword')" @click="showPassword = !showPassword">
                <EyeOff v-if="showPassword" class="h-4 w-4" />
                <Eye v-else class="h-4 w-4" />
              </button>
            </div>
            <p class="text-xs text-muted-foreground mt-1">
              {{ passwordSet ? t('mailPasswordSet') : t('mailPasswordNotSet') }} — {{ t('mailPasswordLeaveBlank') }}
            </p>
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">{{ t('mailFrom') }}</label>
            <input v-model="form.from" class="w-full border border-border rounded px-2 py-1 bg-background" data-testid="from" />
          </div>
        </div>

        <div class="space-y-3">
          <h2 class="text-lg font-bold mb-3">{{ t('mailRestrictions') }}</h2>
          <div>
            <label class="block text-sm font-medium mb-1">{{ t('mailAllowedRecipients') }}</label>
            <textarea v-model="form.allowedRecipients" :placeholder="t('mailAllowedRecipientsPlaceholder')" class="w-full border border-border rounded px-2 py-1 bg-background" data-testid="allowedRecipients"></textarea>
          </div>

          <div class="flex gap-3 pt-2">
            <button :disabled="saving" class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90" data-testid="save" @click="onSave">
              {{ saving ? t('mailSaving') : t('mailSave') }}
            </button>
            <button class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted" data-testid="cancel" @click="onCancel">
              {{ t('mailCancel') }}
            </button>
            <button :disabled="checking" class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted" data-testid="check" @click="onCheck">
              {{ checking ? t('loading') : t('mailCheckButton') }}
            </button>
            <button
              disabled
              :title="t('mailTestSendDisabledHint')"
              class="px-4 py-2 text-sm border border-border rounded-md opacity-50 cursor-not-allowed"
              data-testid="testSendDisabledInEdit"
            >
              {{ t('mailTestSend') }}
            </button>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
