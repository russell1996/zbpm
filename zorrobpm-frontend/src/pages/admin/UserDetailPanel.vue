<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import type { User, UserRole } from '@/entities/user/User'
import * as admin from '@/services/adminService'
import { adminResetPassword } from '@/services/userService'
import { useToast } from '@/composables/useToast'
import { useI18n } from 'vue-i18n'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'
import { Plus, KeyRound } from 'lucide-vue-next'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'

const props = withDefaults(defineProps<{
  user: User
  editing?: boolean
  editForm?: { fullName: string; email: string; role: UserRole; active: boolean; password?: string }
  saving?: boolean
}>(), {
  editing: false,
  editForm: () => ({ fullName: '', email: '', role: 'USER' as UserRole, active: true, password: '' }),
  saving: false,
})
const emit = defineEmits<{ close: []; edit: []; save: []; cancel: [] }>()

const toast = useToast()
const { t } = useI18n()

// --- Members ---
const members = ref<admin.Member[]>([])
const membersLoading = ref(false)
const processes = ref<admin.ProcessInfo[]>([])
const showAddMember = ref(false)
const addMemberProcessKey = ref('')
const addMemberRole = ref('DESIGNER')

// --- API Key ---
const apiKey = ref<admin.ApiKeyInfo | null>(null)
const apiKeyLoading = ref(false)
const showCreateKey = ref(false)
const showKeyModal = ref(false)
const displayedKey = ref('')  // ADR §3: key only in local ref, never in store
const keyCopied = ref(false)
const rotating = ref(false)
const showRevokeConfirm = ref(false)

// View wrapper so the API-key section renders as a list of compact cards and
// scales to multiple keys if the backend ever returns more than one.
const apiKeyList = computed<admin.ApiKeyInfo[]>(() => (apiKey.value ? [apiKey.value] : []))

const PERMISSIONS = ['START', 'FETCH_LOCK', 'COMPLETE_SERVICE_TASK', 'COMPLETE_USER_TASK', 'CORRELATE_MESSAGE'] as const

// userType comparison kept in script so the template has no literal token (untranslated-scanner).
const isSystem = computed(() => props.user?.userType === 'SYSTEM')

const availableProcesses = computed(() => {
  const memberKeys = new Set(members.value.map(m => m.processKey))
  return processes.value.filter(p => !memberKeys.has(p.key))
})

function getGrantForProcess(processKey: string): admin.ApiKeyGrant | undefined {
  return apiKey.value?.grants.find(g => g.processKey === processKey)
}

function isFullAccess(processKey: string | undefined): boolean {
  if (!processKey) return false
  return getGrantForProcess(processKey)?.full ?? false
}

function hasPermission(processKey: string | undefined, perm: string): boolean {
  if (!processKey) return false
  const grant = getGrantForProcess(processKey)
  if (!grant) return false
  if (grant.full) return true
  return grant.permissions?.split(',').includes(perm) ?? false
}

async function updateGrant(processKey: string, newPermissions: string[], full: boolean) {
  if (!apiKey.value) return
  const grants: { processKey: string; permissions?: string; full?: boolean }[] = []
  for (const g of apiKey.value.grants) {
    if (g.processKey !== processKey) {
      grants.push({ processKey: g.processKey, permissions: g.permissions ?? undefined, full: g.full })
    }
  }
  if (full || newPermissions.length > 0) {
    grants.push({ processKey, permissions: full ? undefined : newPermissions.join(','), full })
  }
  try {
    await admin.setGrants(props.user.id, grants)
    await loadApiKey()
  } catch {
      toast.error(t('failedToUpdateGrants'))
  }
}

async function toggleFull(processKey: string | undefined) {
  if (!processKey) return
  await updateGrant(processKey, [], !isFullAccess(processKey))
}

async function togglePermission(processKey: string | undefined, perm: string) {
  if (!processKey) return
  const currentPerms = getGrantForProcess(processKey)?.full
    ? [...PERMISSIONS]
    : (getGrantForProcess(processKey)?.permissions?.split(',').filter(Boolean) ?? [])
  const idx = currentPerms.indexOf(perm)
  if (idx >= 0) {
    currentPerms.splice(idx, 1)
  } else {
    currentPerms.push(perm)
  }
  await updateGrant(processKey, currentPerms, false)
}

async function loadMembers() {
  membersLoading.value = true
  try {
    members.value = await admin.listUserMemberships(props.user.id)
  } catch {
    // ignore
  } finally {
    membersLoading.value = false
  }
}

async function loadProcesses() {
  try {
    processes.value = await admin.listProcesses()
  } catch {
    // ignore
  }
}

async function loadApiKey() {
  apiKeyLoading.value = true
  try {
    apiKey.value = await admin.getApiKey(props.user.id)
  } catch {
    apiKey.value = null
  } finally {
    apiKeyLoading.value = false
  }
}

async function addMember() {
  if (!addMemberProcessKey.value) return
  try {
    await admin.addMember(addMemberProcessKey.value, props.user.id, addMemberRole.value)
    showAddMember.value = false
    await loadMembers()
  } catch (e: any) {
    const msg = e?.response?.data?.message || e?.message
    if (msg?.includes('409') || msg?.includes('Conflict') || e?.response?.status === 409) {
      toast.error(t('alreadyMember'))
    } else {
      toast.error(msg || t('failedToAddMember'))
    }
  }
}

async function changeRole(processKey: string | undefined, newRole: string) {
  if (!processKey) return
  try {
    await admin.changeMemberRole(processKey, props.user.id, newRole)
    await loadMembers()
  } catch {
    toast.error(t('failedToChangeRole'))
  }
}

async function removeMember(processKey: string | undefined) {
  if (!processKey) return
  try {
    await admin.removeMember(processKey, props.user.id)
    await loadMembers()
  } catch {
    // error handled
  }
}

async function createApiKey() {
  try {
    const result = await admin.createApiKey(props.user.id)
    displayedKey.value = result.key
    keyCopied.value = false
    showKeyModal.value = true
    showCreateKey.value = false
    await loadApiKey()
  } catch {
    // error handled
  }
}

async function rotateKey() {
  rotating.value = true
  try {
    const result = await admin.rotateApiKey(props.user.id)
    displayedKey.value = result.key
    keyCopied.value = false
    showKeyModal.value = true
    await loadApiKey()
  } catch {
    // error handled
  } finally {
    rotating.value = false
  }
}

async function revokeKey() {
  try {
    await admin.revokeApiKey(props.user.id)
    showRevokeConfirm.value = false
    await loadApiKey()
  } catch {
    // error handled
  }
}

function closeKeyModal() {
  showKeyModal.value = false
  displayedKey.value = ''
  keyCopied.value = false
}

async function copyKey() {
  try {
    await navigator.clipboard.writeText(displayedKey.value)
  } catch {
    // ignore
  }
}

onMounted(() => {
  loadApiKey()
  loadMembers()
  loadProcesses()
})

// WO-ACL-18 criterion 10/11: super-admin can trigger a password reset link for a (non-system) user.
const resettingPassword = ref(false)
const resetCooldown = ref(0)
let cooldownTimer: ReturnType<typeof setInterval> | null = null

// WO-ACL-19 (P3): the backend already throttles admin resets per-email; mirror that on the client
// so a super-admin cannot spam the button (and the user's inbox) within a single session.
async function resetUserPassword() {
  resettingPassword.value = true
  try {
    await adminResetPassword(props.user.id)
    toast.success(t('resetPasswordSent'))
    resetCooldown.value = 60
    cooldownTimer = setInterval(() => {
      resetCooldown.value -= 1
      if (resetCooldown.value <= 0 && cooldownTimer) {
        clearInterval(cooldownTimer)
        cooldownTimer = null
      }
    }, 1000)
  } catch {
    toast.error(t('failedToResetPassword'))
  } finally {
    resettingPassword.value = false
  }
}

onUnmounted(() => {
  if (cooldownTimer) clearInterval(cooldownTimer)
})
</script>

<template>
  <div class="space-y-6">
    <!-- Основные данные: user identity + the "edit account" action. -->
    <section class="space-y-3">
      <h4 class="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{{ t('basicData') }}</h4>
      <dl class="grid grid-cols-2 gap-x-4 gap-y-3">
        <div>
          <dt class="text-xs text-muted-foreground">{{ t('username') }}</dt>
          <dd class="text-sm font-mono">{{ user.username }}</dd>
        </div>
        <div>
          <dt class="text-xs text-muted-foreground">{{ t('userType') }}</dt>
          <dd class="text-sm">{{ isSystem ? t('userTypeSystem') : t('userTypeHuman') }}</dd>
        </div>
        <div>
          <dt class="text-xs text-muted-foreground">{{ t('fullName') }}</dt>
          <dd v-if="!editing" class="text-sm">{{ user.fullName || '—' }}</dd>
          <input v-else v-model="editForm.fullName" type="text" data-testid="edit-fullName" class="w-full px-2 py-1 text-sm border border-input rounded-md focus:outline-none focus:ring-2 focus:ring-ring" />
        </div>
        <div>
          <dt class="text-xs text-muted-foreground">{{ t('email') }}</dt>
          <dd v-if="!editing" class="text-sm break-all">{{ user.email || '—' }}</dd>
          <input v-else v-model="editForm.email" type="email" data-testid="edit-email" class="w-full px-2 py-1 text-sm border border-input rounded-md focus:outline-none focus:ring-2 focus:ring-ring" />
        </div>
        <div>
          <dt class="text-xs text-muted-foreground">{{ t('role') }}</dt>
          <dd v-if="!editing" class="text-sm"><span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-muted">{{ user.role }}</span></dd>
            <Select v-else v-model="editForm.role" data-testid="edit-role" class="w-full">
              <SelectTrigger data-testid="edit-role-trigger" class="w-full px-2 py-1 text-sm border border-input rounded-md bg-background">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="USER">{{ t('userRole') }}</SelectItem>
              <SelectItem value="ADMIN">{{ t('adminRole') }}</SelectItem>
              <SelectItem value="SUPER_ADMIN" data-testid="edit-role-SUPER_ADMIN">{{ t('superAdminRole') }}</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div>
          <dt class="text-xs text-muted-foreground">{{ t('status') }}</dt>
          <dd class="text-sm"><StatusBadge :status="user.active ? 'ACTIVE' : 'INACTIVE'" /></dd>
        </div>
      </dl>
      <template v-if="!editing">
        <button
          class="w-full px-3 py-1.5 text-sm border border-border rounded hover:bg-muted text-left"
          data-testid="edit-account-button"
          @click="emit('edit')"
        >
          {{ t('editAccount') }}
        </button>
      </template>
      <template v-else>
        <label class="inline-flex items-center gap-2 text-sm cursor-pointer">
          <input type="checkbox" v-model="editForm.active" class="rounded" />
          {{ t('active') }}
        </label>
        <div class="flex gap-2 pt-1">
          <button
            class="flex-1 px-3 py-1.5 text-sm bg-primary text-primary-foreground rounded hover:opacity-90 disabled:opacity-50"
            data-testid="drawer-save-user"
            :disabled="saving"
            @click="emit('save')"
          >
            {{ t('save') }}
          </button>
          <button
            class="flex-1 px-3 py-1.5 text-sm border border-border rounded hover:bg-muted"
            data-testid="drawer-cancel-user"
            @click="emit('cancel')"
          >
            {{ t('cancel') }}
          </button>
        </div>
      </template>
    </section>

    <!-- === Security Section: password and its actions only (WO-UI-10 restructure) === -->
    <section class="space-y-2">
      <h4 class="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{{ t('security') }}</h4>
      <template v-if="!editing">
        <div v-if="user.userType === 'SYSTEM'" class="text-sm text-muted-foreground">{{ t('systemAccountNoReset') }}</div>
        <div v-else class="rounded-md border border-border p-3 space-y-2">
          <div class="flex items-center gap-2 text-sm font-medium">
            <KeyRound class="h-4 w-4 text-muted-foreground" />
            {{ t('resetPassword') }}
          </div>
          <p class="text-xs text-muted-foreground">{{ t('resetPasswordHint') }}</p>
          <button
            class="px-3 py-1.5 text-sm border border-border rounded hover:bg-muted disabled:opacity-50"
            :disabled="resettingPassword || resetCooldown > 0"
            data-testid="reset-password-button"
            @click="resetUserPassword"
          >
            {{ t('resetPasswordSend') }}
          </button>
        </div>
      </template>
      <template v-else>
        <div class="space-y-1">
          <label class="text-xs text-muted-foreground">{{ t('newPasswordOptional') }}</label>
          <input v-model="editForm.password" type="password" autocomplete="new-password" class="w-full px-2 py-1 text-sm border border-input rounded-md focus:outline-none focus:ring-2 focus:ring-ring" />
        </div>
      </template>
    </section>

    <!-- === API Key Section === -->
    <section class="space-y-2">
      <h4 class="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{{ t('apiKeys') }}</h4>
      <div v-if="apiKeyLoading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
      <div v-else-if="apiKeyList.length" class="space-y-2">
        <div v-for="key in apiKeyList" :key="key.id" class="rounded-md border border-border bg-muted/30 p-3 space-y-2">
          <div class="flex items-center justify-between gap-2">
            <span class="font-mono text-sm truncate">{{ key.prefix }}…</span>
            <span v-if="key.revokedAt" class="text-xs text-red-500 shrink-0">{{ t('revoked') }}</span>
            <span v-else class="text-xs text-green-600 shrink-0">{{ t('active') }}</span>
          </div>
          <div class="flex gap-3">
            <button v-if="!key.revokedAt" class="text-xs text-primary hover:underline" @click="rotateKey">{{ t('rotate') }}</button>
            <button v-if="!key.revokedAt" class="text-xs text-red-500 hover:underline" @click="showRevokeConfirm = true">{{ t('revoke') }}</button>
          </div>
          <div v-if="key.grants.length" class="space-y-1">
            <div class="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{{ t('grants') }}</div>
            <div class="text-xs text-muted-foreground font-mono break-all">{{ key.grants.map(g => `${g.processKey}: ${g.full ? 'FULL' : g.permissions}`).join(' · ') }}</div>
          </div>
        </div>
      </div>
      <div v-else class="text-sm text-muted-foreground">
        {{ t('noApiKey') }}
        <button class="text-primary hover:underline ml-1" @click="showCreateKey = true">{{ t('createOne') }}</button>
      </div>

      <!-- Revoked key: show create new button -->
      <div v-if="apiKey && apiKey.revokedAt" class="text-sm">
        <button class="text-primary hover:underline" @click="showCreateKey = true">{{ t('createNewKey') }}</button>
      </div>

      <!-- No key hint for grants -->
      <p v-if="(!apiKey || (apiKey && apiKey.revokedAt)) && !apiKeyLoading" class="text-xs text-muted-foreground italic">
        {{ t('apiKeyHint') }}
      </p>

      <!-- Create key -->
      <div v-if="showCreateKey" class="rounded-md border border-border bg-muted/40 p-3 space-y-2">
        <p class="text-sm">{{ t('createApiKeyConfirm') }}</p>
        <div class="flex gap-2">
          <button class="px-3 py-1 text-sm bg-primary text-primary-foreground rounded" @click="createApiKey">{{ t('add') }}</button>
          <button class="px-3 py-1 text-sm border border-border rounded" @click="showCreateKey = false">{{ t('cancel') }}</button>
        </div>
      </div>
    </section>

    <!-- === Memberships Section === -->
    <section class="space-y-2">
      <div class="flex items-center justify-between">
        <h4 class="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{{ t('processAccess') }}</h4>
        <button class="inline-flex items-center gap-1 text-xs text-primary hover:underline" @click="showAddMember = !showAddMember">
          <Plus class="h-3.5 w-3.5" />
          {{ showAddMember ? t('cancel') : t('add') }}
        </button>
      </div>

      <!-- Add membership form -->
      <div v-if="showAddMember" class="rounded-md border border-border bg-muted/40 p-3 space-y-2">
        <Select v-model="addMemberProcessKey" class="w-full">
          <SelectTrigger data-testid="add-member-process" class="w-full px-2 py-1 border border-input rounded text-sm">
            <SelectValue :placeholder="t('selectProcess')" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem v-for="p in availableProcesses" :key="p.id" :value="p.key">{{ p.key }} — {{ p.name }}</SelectItem>
          </SelectContent>
        </Select>
        <Select v-model="addMemberRole" class="w-full">
          <SelectTrigger class="w-full px-2 py-1 border border-input rounded text-sm">
            <SelectValue :placeholder="t('ownerRole')" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="OWNER">{{ t('ownerRole') }}</SelectItem>
            <SelectItem value="DESIGNER">{{ t('designerRole') }}</SelectItem>
          </SelectContent>
        </Select>
        <button class="px-3 py-1 text-sm bg-primary text-primary-foreground rounded" @click="addMember">{{ t('add') }}</button>
      </div>

      <!-- Memberships list — each process is a compact card (clearly separated); role and
           key permissions are visually separated sub-blocks; permissions wrap. The whole
           Drawer body scrolls; this list has no inner scroll so the header stays put. -->
      <div v-if="membersLoading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
      <div v-else-if="members.length" class="space-y-2">
        <div v-for="m in members" :key="m.processKey" class="rounded-md border border-border p-3 space-y-2">
          <div class="flex items-center justify-between gap-2">
            <span class="font-mono text-xs truncate" :title="m.processKey">{{ m.processKey }}</span>
            <button class="text-xs text-red-500 hover:underline shrink-0" @click="removeMember(m.processKey)">{{ t('remove') }}</button>
          </div>
          <div class="space-y-1">
            <div class="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{{ t('role') }}</div>
            <Select :model-value="m.role" @update:model-value="changeRole(m.processKey, $event as string)" class="w-full">
              <SelectTrigger :data-testid="'member-role-' + m.processKey" class="w-full px-1 py-0.5 text-xs border border-input rounded bg-background">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="OWNER">{{ t('ownerRole') }}</SelectItem>
                <SelectItem value="DESIGNER">{{ t('designerRole') }}</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div v-if="apiKey && !apiKey.revokedAt" class="space-y-1">
            <div class="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{{ t('apiKeyPermissions') }}</div>
            <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
              <label class="inline-flex items-center gap-1 text-xs cursor-pointer">
                <input type="checkbox" class="rounded" :checked="isFullAccess(m.processKey)" @change="toggleFull(m.processKey)" />
                {{ t('full') }}
              </label>
              <template v-if="!isFullAccess(m.processKey)">
                <label v-for="perm in PERMISSIONS" :key="perm" class="inline-flex items-center gap-1 text-xs cursor-pointer">
                  <input type="checkbox" class="rounded" :checked="hasPermission(m.processKey, perm)" @change="togglePermission(m.processKey, perm)" />
                  {{ perm }}
                </label>
              </template>
            </div>
          </div>
        </div>
      </div>
      <p v-else class="text-sm text-muted-foreground">{{ t('noProcessMemberships') }}</p>
    </section>

    <!-- Key modal — key lives ONLY here (ADR §3) -->
    <div v-if="showKeyModal" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="closeKeyModal">
      <div class="bg-card rounded-lg shadow-lg w-full max-w-lg p-6 space-y-4">
        <h3 class="font-bold">{{ t('apiKeyCreated') }}</h3>
        <!-- WO-QW-2: v-html is safe ONLY because apiKeyNotShown is a static i18n string.
             If user-controlled input ever flows into this key — switch to {{ }} text. -->
        <p class="text-sm text-muted-foreground" v-html="t('apiKeyNotShown')"></p>
        <div class="bg-muted rounded p-3 font-mono text-sm break-all select-all border border-border">{{ displayedKey }}</div>
        <div class="flex justify-between">
          <button class="px-3 py-1.5 text-sm border border-border rounded hover:bg-muted" @click="copyKey">{{ t('copy') }}</button>
          <button class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded hover:opacity-90" @click="closeKeyModal">{{ t('savedClose') }}</button>
        </div>
      </div>
    </div>

    <!-- Revoke confirm -->
    <div v-if="showRevokeConfirm" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="showRevokeConfirm = false">
      <div class="bg-card rounded-lg shadow-lg w-full max-w-sm p-6 space-y-4">
        <h3 class="font-bold">{{ t('revokeApiKeyConfirm') }}</h3>
        <p class="text-sm text-muted-foreground">{{ t('revokeKeyHint') }}</p>
        <div class="flex justify-end gap-2">
          <button class="px-3 py-1.5 text-sm border border-border rounded" @click="showRevokeConfirm = false">{{ t('cancel') }}</button>
          <button class="px-3 py-1.5 text-sm bg-red-600 text-white rounded hover:bg-red-700" @click="revokeKey">{{ t('revoke') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
