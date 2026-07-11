<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { ServiceAccount } from '@/types/api'
import * as serviceAccountService from '@/services/serviceAccountService'

const props = defineProps<{ processKey: string }>()

const accounts = ref<ServiceAccount[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

// Create form
const showCreateForm = ref(false)
const newName = ref('')
const newPermissions = ref<string[]>([])

// Key display modal — key is ONLY in this local ref, never in Pinia
const showKeyModal = ref(false)
const displayedKey = ref('')
const keyCopied = ref(false)

// Rotate confirmation
const rotatingId = ref<string | null>(null)

const ALL_PERMISSIONS = ['START', 'COMPLETE_SERVICE_TASK', 'CORRELATE_MESSAGE', 'FETCH_LOCK']

async function loadAccounts() {
  loading.value = true
  error.value = null
  try {
    accounts.value = await serviceAccountService.listServiceAccounts(props.processKey)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to load service accounts'
  } finally {
    loading.value = false
  }
}

async function createAccount() {
  if (!newName.value) return
  error.value = null
  try {
    const result = await serviceAccountService.createServiceAccount(props.processKey, {
      name: newName.value,
      permissions: newPermissions.value,
    })
    // Show key ONCE in modal — this is the ONLY place the key exists in the frontend
    displayedKey.value = result.key
    keyCopied.value = false
    showKeyModal.value = true
    newName.value = ''
    newPermissions.value = []
    showCreateForm.value = false
    await loadAccounts()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create service account'
  }
}

async function rotateKey(saId: string) {
  rotatingId.value = saId
  error.value = null
  try {
    const result = await serviceAccountService.rotateServiceAccountKey(props.processKey, saId)
    displayedKey.value = result.key
    keyCopied.value = false
    showKeyModal.value = true
    await loadAccounts()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to rotate key'
  } finally {
    rotatingId.value = null
  }
}

async function revokeAccount(saId: string) {
  error.value = null
  try {
    await serviceAccountService.revokeServiceAccount(props.processKey, saId)
    await loadAccounts()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to revoke service account'
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
    keyCopied.value = true
  } catch {
    // Fallback: select text for manual copy
  }
}

function togglePermission(perm: string) {
  const idx = newPermissions.value.indexOf(perm)
  if (idx >= 0) newPermissions.value.splice(idx, 1)
  else newPermissions.value.push(perm)
}

onMounted(loadAccounts)
</script>

<template>
  <div class="space-y-4">
    <div class="flex items-center justify-between">
      <h3 class="text-lg font-bold">Service Accounts</h3>
      <button
        class="px-3 py-1.5 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90"
        @click="showCreateForm = !showCreateForm"
      >
        {{ showCreateForm ? 'Cancel' : '+ Create' }}
      </button>
    </div>

    <div v-if="error" class="text-sm text-red-500 bg-red-50 dark:bg-red-900/20 px-3 py-2 rounded">{{ error }}</div>

    <!-- Create form -->
    <div v-if="showCreateForm" class="border border-border rounded-lg p-4 bg-card space-y-3">
      <input
        v-model="newName"
        placeholder="Service account name"
        class="w-full px-3 py-1.5 border border-input rounded text-sm"
      />
      <div class="space-y-1">
        <p class="text-xs text-muted-foreground font-medium">Permissions:</p>
        <label v-for="perm in ALL_PERMISSIONS" :key="perm" class="flex items-center gap-2 text-sm cursor-pointer">
          <input type="checkbox" :checked="newPermissions.includes(perm)" @change="togglePermission(perm)" />
          {{ perm }}
        </label>
      </div>
      <button
        class="px-3 py-1.5 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 disabled:opacity-50"
        :disabled="!newName"
        @click="createAccount"
      >
        Create
      </button>
    </div>

    <!-- Accounts list -->
    <div v-if="loading" class="text-sm text-muted-foreground">Loading...</div>

    <table v-else-if="accounts.length" class="w-full text-sm border border-border rounded-lg overflow-hidden">
      <thead class="bg-muted">
        <tr>
          <th class="px-4 py-2 text-left font-medium">Name</th>
          <th class="px-4 py-2 text-left font-medium">Prefix</th>
          <th class="px-4 py-2 text-left font-medium">Permissions</th>
          <th class="px-4 py-2 text-left font-medium">Status</th>
          <th class="px-4 py-2 text-right font-medium">Actions</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="sa in accounts" :key="sa.id" class="border-t border-border">
          <td class="px-4 py-2 font-medium">{{ sa.name }}</td>
          <td class="px-4 py-2 font-mono text-xs">{{ sa.prefix }}…</td>
          <td class="px-4 py-2 text-xs">{{ sa.permissions.join(', ') || '—' }}</td>
          <td class="px-4 py-2">
            <span v-if="sa.revokedAt" class="text-xs text-red-500">Revoked</span>
            <span v-else class="text-xs text-green-600">Active</span>
          </td>
          <td class="px-4 py-2 text-right space-x-2">
            <button
              v-if="!sa.revokedAt"
              class="text-xs text-primary hover:underline disabled:opacity-50"
              :disabled="rotatingId === sa.id"
              @click="rotateKey(sa.id)"
            >
              {{ rotatingId === sa.id ? 'Rotating…' : 'Rotate' }}
            </button>
            <button
              v-if="!sa.revokedAt"
              class="text-xs text-red-500 hover:underline"
              @click="revokeAccount(sa.id)"
            >
              Revoke
            </button>
          </td>
        </tr>
      </tbody>
    </table>

    <p v-else class="text-sm text-muted-foreground">No service accounts yet.</p>

    <!-- Key display modal — key lives ONLY here, never in Pinia store -->
    <div v-if="showKeyModal" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="closeKeyModal">
      <div class="bg-card rounded-lg shadow-lg w-full max-w-lg p-6 space-y-4">
        <div class="flex items-center gap-2">
          <span class="text-amber-500 text-xl">⚠️</span>
          <h3 class="font-bold text-lg">API Key — Save it now</h3>
        </div>
        <p class="text-sm text-muted-foreground">
          This key will <strong>not</strong> be shown again. Copy it and store it securely.
        </p>
        <div class="bg-muted rounded p-3 font-mono text-sm break-all select-all border border-border">
          {{ displayedKey }}
        </div>
        <div class="flex justify-between items-center">
          <button
            class="px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted"
            @click="copyKey"
          >
            {{ keyCopied ? 'Copied!' : 'Copy to clipboard' }}
          </button>
          <button
            class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90"
            @click="closeKeyModal"
          >
            I saved it — Close
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
