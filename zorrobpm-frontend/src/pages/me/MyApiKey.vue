<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getMyApiKey, rotateMyApiKey, revokeMyApiKey, type ApiKeyInfo } from '@/services/apiKeyService'

const apiKey = ref<ApiKeyInfo | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

// Key display modal — key lives ONLY in this local ref (ADR §3)
const showKeyModal = ref(false)
const displayedKey = ref('')
const keyCopied = ref(false)

const showRevokeConfirm = ref(false)

async function loadApiKey() {
  loading.value = true
  error.value = null
  try {
    apiKey.value = await getMyApiKey()
  } catch (e: unknown) {
    const status = (e as { response?: { status?: number } })?.response?.status
    if (status === 404) {
      apiKey.value = null // No key yet
    } else {
      error.value = 'Failed to load API key'
    }
  } finally {
    loading.value = false
  }
}

async function rotate() {
  error.value = null
  try {
    const result = await rotateMyApiKey()
    // Show key ONCE in modal — this is the ONLY place the key exists in the frontend
    displayedKey.value = result.key
    keyCopied.value = false
    showKeyModal.value = true
    await loadApiKey()
  } catch {
    error.value = 'Failed to rotate API key'
  }
}

async function revoke() {
  error.value = null
  try {
    await revokeMyApiKey()
    showRevokeConfirm.value = false
    await loadApiKey()
  } catch {
    error.value = 'Failed to revoke API key'
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

onMounted(loadApiKey)
</script>

<template>
  <div class="space-y-6 max-w-2xl">
    <h1 class="text-2xl font-bold">My API Key</h1>
    <p class="text-sm text-muted-foreground">
      Your API key is used by integrations to access process data. You can rotate or revoke it here.
      You cannot create a new key or change grants — ask a super-admin.
    </p>

    <div v-if="loading" class="text-sm text-muted-foreground">Loading...</div>
    <div v-else-if="error" class="text-sm text-red-500 bg-red-50 dark:bg-red-900/20 px-3 py-2 rounded">{{ error }}</div>

    <template v-else-if="apiKey">
      <!-- Key info -->
      <div class="border border-border rounded-lg p-4 bg-card space-y-3">
        <div class="flex items-center justify-between">
          <h2 class="font-bold">API Key</h2>
          <span v-if="apiKey.revokedAt" class="text-xs text-red-500 font-medium">Revoked</span>
          <span v-else class="text-xs text-green-600 font-medium">Active</span>
        </div>
        <div class="text-sm space-y-1">
          <div><span class="text-muted-foreground">Prefix:</span> <span class="font-mono">{{ apiKey.prefix }}…</span></div>
          <div><span class="text-muted-foreground">Created:</span> {{ new Date(apiKey.createdAt).toLocaleString() }}</div>
          <div v-if="apiKey.lastUsedAt"><span class="text-muted-foreground">Last used:</span> {{ new Date(apiKey.lastUsedAt).toLocaleString() }}</div>
        </div>
      </div>

      <!-- Grants -->
      <div class="border border-border rounded-lg overflow-hidden bg-card">
        <div class="px-4 py-3 border-b border-border">
          <h3 class="font-bold">Grants (read-only)</h3>
          <p class="text-xs text-muted-foreground">Access permissions per process — configured by a super-admin.</p>
        </div>
        <table v-if="apiKey.grants.length" class="w-full text-sm">
          <thead class="bg-muted">
            <tr>
              <th class="px-4 py-2 text-left font-medium">Process</th>
              <th class="px-4 py-2 text-left font-medium">Permissions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="g in apiKey.grants" :key="g.processId" class="border-t border-border">
              <td class="px-4 py-2 font-mono text-xs">{{ g.processKey }}</td>
              <td class="px-4 py-2">
                <span v-if="g.full" class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-green-100 text-green-800">FULL</span>
                <span v-else class="text-xs">{{ g.permissions }}</span>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else class="px-4 py-3 text-sm text-muted-foreground">No grants configured.</p>
      </div>

      <!-- Actions -->
      <div v-if="!apiKey.revokedAt" class="flex gap-2">
        <button
          class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90"
          @click="rotate"
        >
          Rotate Key
        </button>
        <button
          class="px-4 py-2 text-sm border border-red-300 text-red-600 rounded-md hover:bg-red-50"
          @click="showRevokeConfirm = true"
        >
          Revoke Key
        </button>
      </div>
      <p v-else class="text-sm text-muted-foreground">This key has been revoked and cannot be used.</p>
    </template>

    <p v-else class="text-sm text-muted-foreground">
      No API key yet. Ask a super-admin to create one for you.
    </p>

    <!-- Key display modal — key lives ONLY here, never in Pinia store -->
    <div v-if="showKeyModal" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="closeKeyModal">
      <div class="bg-card rounded-lg shadow-lg w-full max-w-lg p-6 space-y-4">
        <div class="flex items-center gap-2">
          <span class="text-amber-500 text-xl">⚠️</span>
          <h3 class="font-bold text-lg">New API Key — Save it now</h3>
        </div>
        <p class="text-sm text-muted-foreground">
          This key will <strong>not</strong> be shown again. Copy it and store it securely.
        </p>
        <div class="bg-muted rounded p-3 font-mono text-sm break-all select-all border border-border">
          {{ displayedKey }}
        </div>
        <div class="flex justify-between items-center">
          <button class="px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted" @click="copyKey">
            {{ keyCopied ? 'Copied!' : 'Copy to clipboard' }}
          </button>
          <button class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90" @click="closeKeyModal">
            I saved it — Close
          </button>
        </div>
      </div>
    </div>

    <!-- Revoke confirmation -->
    <div v-if="showRevokeConfirm" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="showRevokeConfirm = false">
      <div class="bg-card rounded-lg shadow-lg w-full max-w-sm p-6 space-y-4">
        <h3 class="font-bold">Revoke API Key?</h3>
        <p class="text-sm text-muted-foreground">This will immediately invalidate the key. Any integrations using it will stop working.</p>
        <div class="flex justify-end gap-2">
          <button class="px-3 py-1.5 text-sm border border-border rounded-md" @click="showRevokeConfirm = false">Cancel</button>
          <button class="px-3 py-1.5 text-sm bg-red-600 text-white rounded-md hover:bg-red-700" @click="revoke">Revoke</button>
        </div>
      </div>
    </div>
  </div>
</template>
