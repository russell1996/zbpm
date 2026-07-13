<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { User } from '@/entities/user/User'
import * as admin from '@/services/adminService'

const props = defineProps<{ user: User }>()
const emit = defineEmits<{ close: [] }>()

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
const showGrants = ref(false)
const grantProcessKey = ref('')
const grantPermissions = ref('START')
const grantFull = ref(false)
const rotating = ref(false)
const showRevokeConfirm = ref(false)

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
  } catch {
    // error handled by toast or silent
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

async function saveGrants() {
  if (!grantProcessKey.value) return
  try {
    await admin.setGrants(props.user.id, [{
      processKey: grantProcessKey.value,
      permissions: grantFull.value ? undefined : grantPermissions.value,
      full: grantFull.value,
    }])
    showGrants.value = false
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
      <div v-else class="text-sm text-muted-foreground">
        No API key.
        <button class="text-primary hover:underline ml-1" @click="showCreateKey = true">Create one</button>
      </div>

      <!-- Create key -->
      <div v-if="showCreateKey" class="bg-card border border-border rounded-lg p-3">
        <p class="text-sm mb-2">Create an API key for this user?</p>
        <div class="flex gap-2">
          <button class="px-3 py-1 text-sm bg-primary text-primary-foreground rounded" @click="createApiKey">Create</button>
          <button class="px-3 py-1 text-sm border border-border rounded" @click="showCreateKey = false">Cancel</button>
        </div>
      </div>

      <!-- Set grants -->
      <div v-if="apiKey && !apiKey.revokedAt" class="mt-2">
        <button class="text-xs text-primary hover:underline" @click="showGrants = !showGrants">
          {{ showGrants ? 'Cancel' : 'Add Grant' }}
        </button>
        <div v-if="showGrants" class="mt-2 bg-card border border-border rounded-lg p-3 space-y-2">
          <input v-model="grantProcessKey" placeholder="Process key" class="w-full px-2 py-1 border border-input rounded text-sm" />
          <div class="flex items-center gap-2">
            <label class="text-xs">Full access:</label>
            <input v-model="grantFull" type="checkbox" class="rounded" />
          </div>
          <div v-if="!grantFull">
            <input v-model="grantPermissions" placeholder="START,FETCH_LOCK,..." class="w-full px-2 py-1 border border-input rounded text-sm" />
          </div>
          <button class="px-3 py-1 text-sm bg-primary text-primary-foreground rounded" @click="saveGrants">Save Grant</button>
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
          <option v-for="p in processes" :key="p.id" :value="p.key">{{ p.key }} — {{ p.name }}</option>
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
              <th class="px-3 py-2 text-right font-medium"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in members" :key="m.processKey" class="border-t border-border">
              <td class="px-3 py-2 font-mono text-xs">{{ m.processKey }}</td>
              <td class="px-3 py-2">
                <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium"
                  :class="m.role === 'OWNER' ? 'bg-amber-100 text-amber-800' : 'bg-blue-100 text-blue-800'">
                  {{ m.role }}
                </span>
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
