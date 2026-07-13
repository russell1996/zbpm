<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import type { User } from '@/entities/user/User'
import * as admin from '@/services/adminService'
import { useToast } from '@/composables/useToast'

const props = defineProps<{ user: User }>()
const emit = defineEmits<{ close: [] }>()

const toast = useToast()

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

const PERMISSIONS = ['START', 'FETCH_LOCK', 'COMPLETE_SERVICE_TASK', 'COMPLETE_USER_TASK', 'CORRELATE_MESSAGE'] as const

const availableProcesses = computed(() => {
  const memberKeys = new Set(members.value.map(m => m.processKey))
  return processes.value.filter(p => !memberKeys.has(p.key))
})

function getGrantForProcess(processKey: string): admin.ApiKeyGrant | undefined {
  return apiKey.value?.grants.find(g => g.processKey === processKey)
}

function isFullAccess(processKey: string): boolean {
  return getGrantForProcess(processKey)?.full ?? false
}

function hasPermission(processKey: string, perm: string): boolean {
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
    toast.error('Failed to update grants')
  }
}

async function toggleFull(processKey: string) {
  await updateGrant(processKey, [], !isFullAccess(processKey))
}

async function togglePermission(processKey: string, perm: string) {
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
      toast.error('User is already a member of this process')
    } else {
      toast.error(msg || 'Failed to add member')
    }
  }
}

async function changeRole(processKey: string, newRole: string) {
  try {
    await admin.changeMemberRole(processKey, props.user.id, newRole)
    await loadMembers()
  } catch {
    toast.error('Failed to change role')
  }
}

async function removeMember(processKey: string) {
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
</script>

<template>
  <div class="border-t border-border bg-muted/30 p-4 space-y-6">
    <div class="flex items-center justify-between">
      <h3 class="font-bold text-lg">{{ user.username }}</h3>
      <button class="text-sm text-muted-foreground hover:text-foreground" @click="emit('close')">Close</button>
    </div>

    <!-- === API Key Section === -->
    <div class="space-y-3">
      <h4 class="font-semibold text-sm uppercase text-muted-foreground">API Key</h4>
      <div v-if="apiKeyLoading" class="text-sm text-muted-foreground">Loading...</div>
      <div v-else-if="apiKey" class="bg-card border border-border rounded-lg p-3 space-y-2">
        <div class="flex items-center justify-between">
          <span class="font-mono text-sm">{{ apiKey.prefix }}…</span>
          <span v-if="apiKey.revokedAt" class="text-xs text-red-500">Revoked</span>
          <span v-else class="text-xs text-green-600">Active</span>
        </div>
        <div class="flex gap-2">
          <button v-if="!apiKey.revokedAt" class="text-xs text-primary hover:underline" @click="rotateKey">Rotate</button>
          <button v-if="!apiKey.revokedAt" class="text-xs text-red-500 hover:underline" @click="showRevokeConfirm = true">Revoke</button>
        </div>
        <!-- Grants -->
        <div v-if="apiKey.grants.length" class="mt-2">
          <p class="text-xs text-muted-foreground mb-1">Grants:</p>
          <div v-for="g in apiKey.grants" :key="g.processId" class="text-xs font-mono">
            {{ g.processKey }}: {{ g.full ? 'FULL' : g.permissions }}
          </div>
        </div>
      </div>
      <div v-else-if="!apiKey && !apiKeyLoading" class="text-sm text-muted-foreground">
        No API key.
        <button class="text-primary hover:underline ml-1" @click="showCreateKey = true">Create one</button>
      </div>

      <!-- Revoked key: show create new button -->
      <div v-if="apiKey && apiKey.revokedAt" class="text-sm">
        <button class="text-primary hover:underline" @click="showCreateKey = true">Create new key</button>
      </div>

      <!-- No key hint for grants -->
      <p v-if="(!apiKey || (apiKey && apiKey.revokedAt)) && !apiKeyLoading" class="text-xs text-muted-foreground italic">
        Create an API key to configure per-process permissions below.
      </p>

      <!-- Create key -->
      <div v-if="showCreateKey" class="bg-card border border-border rounded-lg p-3">
        <p class="text-sm mb-2">Create an API key for this user?</p>
        <div class="flex gap-2">
          <button class="px-3 py-1 text-sm bg-primary text-primary-foreground rounded" @click="createApiKey">Create</button>
          <button class="px-3 py-1 text-sm border border-border rounded" @click="showCreateKey = false">Cancel</button>
        </div>
      </div>

    </div>

    <!-- === Memberships Section === -->
    <div class="space-y-3">
      <div class="flex items-center justify-between">
        <h4 class="font-semibold text-sm uppercase text-muted-foreground">Process Memberships</h4>
        <button class="text-xs text-primary hover:underline" @click="showAddMember = !showAddMember">
          {{ showAddMember ? 'Cancel' : '+ Add' }}
        </button>
      </div>

      <!-- Add membership form -->
      <div v-if="showAddMember" class="bg-card border border-border rounded-lg p-3 space-y-2">
        <select v-model="addMemberProcessKey" class="w-full px-2 py-1 border border-input rounded text-sm">
          <option value="">Select process…</option>
          <option v-for="p in availableProcesses" :key="p.id" :value="p.key">{{ p.key }} — {{ p.name }}</option>
        </select>
        <select v-model="addMemberRole" class="w-full px-2 py-1 border border-input rounded text-sm">
          <option value="OWNER">OWNER</option>
          <option value="DESIGNER">DESIGNER</option>
        </select>
        <button class="px-3 py-1 text-sm bg-primary text-primary-foreground rounded" @click="addMember">Add</button>
      </div>

      <!-- Memberships list -->
      <div v-if="membersLoading" class="text-sm text-muted-foreground">Loading…</div>
      <div v-else-if="members.length" class="bg-card border border-border rounded-lg overflow-hidden">
        <table class="w-full text-sm">
          <thead class="bg-muted">
            <tr>
              <th class="px-3 py-2 text-left font-medium">Process</th>
              <th class="px-3 py-2 text-left font-medium">Role</th>
              <th v-if="apiKey && !apiKey.revokedAt" class="px-3 py-2 text-left font-medium">API Key Permissions</th>
              <th class="px-3 py-2 text-right font-medium"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in members" :key="m.processKey" class="border-t border-border">
              <td class="px-3 py-2 font-mono text-xs">{{ m.processKey }}</td>
              <td class="px-3 py-2">
                <select class="text-xs border border-input rounded px-1 py-0.5 bg-card"
                  :value="m.role"
                  @change="changeRole(m.processKey, ($event.target as HTMLSelectElement).value)">
                  <option value="OWNER">OWNER</option>
                  <option value="DESIGNER">DESIGNER</option>
                </select>
              </td>
              <td v-if="apiKey && !apiKey.revokedAt" class="px-3 py-2">
                <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <label class="inline-flex items-center gap-1 text-xs cursor-pointer">
                    <input type="checkbox" class="rounded" :checked="isFullAccess(m.processKey)" @change="toggleFull(m.processKey)" />
                    Full
                  </label>
                  <template v-if="!isFullAccess(m.processKey)">
                    <label v-for="perm in PERMISSIONS" :key="perm" class="inline-flex items-center gap-1 text-xs cursor-pointer">
                      <input type="checkbox" class="rounded" :checked="hasPermission(m.processKey, perm)" @change="togglePermission(m.processKey, perm)" />
                      {{ perm }}
                    </label>
                  </template>
                </div>
              </td>
              <td class="px-3 py-2 text-right">
                <button class="text-xs text-red-500 hover:underline" @click="removeMember(m.processKey)">Remove</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="text-sm text-muted-foreground">No process memberships.</p>
    </div>

    <!-- Key modal — key lives ONLY here (ADR §3) -->
    <div v-if="showKeyModal" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="closeKeyModal">
      <div class="bg-card rounded-lg shadow-lg w-full max-w-lg p-6 space-y-4">
        <h3 class="font-bold">API Key Created — Save it now</h3>
        <p class="text-sm text-muted-foreground">This key will <strong>not</strong> be shown again.</p>
        <div class="bg-muted rounded p-3 font-mono text-sm break-all select-all border border-border">{{ displayedKey }}</div>
        <div class="flex justify-between">
          <button class="px-3 py-1.5 text-sm border border-border rounded hover:bg-muted" @click="copyKey">Copy</button>
          <button class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded hover:opacity-90" @click="closeKeyModal">Saved — Close</button>
        </div>
      </div>
    </div>

    <!-- Revoke confirm -->
    <div v-if="showRevokeConfirm" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="showRevokeConfirm = false">
      <div class="bg-card rounded-lg shadow-lg w-full max-w-sm p-6 space-y-4">
        <h3 class="font-bold">Revoke API Key?</h3>
        <p class="text-sm text-muted-foreground">This will immediately invalidate the key.</p>
        <div class="flex justify-end gap-2">
          <button class="px-3 py-1.5 text-sm border border-border rounded" @click="showRevokeConfirm = false">Cancel</button>
          <button class="px-3 py-1.5 text-sm bg-red-600 text-white rounded hover:bg-red-700" @click="revokeKey">Revoke</button>
        </div>
      </div>
    </div>
  </div>
</template>
