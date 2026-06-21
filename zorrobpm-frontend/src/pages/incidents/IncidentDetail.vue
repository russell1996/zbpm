<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useIncidentStore } from '@/stores/incident'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const router = useRouter()
const store = useIncidentStore()
const toast = useToast()

const showResolveModal = ref(false)

async function resolve() {
  await store.resolveIncident(route.params.id as string, [])
  if (!store.error) {
    toast.success('Incident resolved')
    showResolveModal.value = false
    router.push('/incidents')
  } else {
    toast.error(store.error)
  }
}

onMounted(() => {
  store.fetchIncident(route.params.id as string)
})
</script>

<template>
  <div class="space-y-6">
    <div v-if="store.loading" class="text-sm text-muted-foreground">Loading...</div>
    <div v-else-if="store.error" class="text-sm text-red-500">{{ store.error }}</div>

    <template v-else-if="store.currentIncident">
      <div class="flex items-center justify-between">
        <div>
          <h1 class="text-2xl font-bold">Incident</h1>
          <p class="text-sm text-muted-foreground font-mono">{{ store.currentIncident.id }}</p>
        </div>
        <button
          v-if="!store.currentIncident.completedAt"
          class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity text-sm"
          @click="showResolveModal = true"
        >
          Resolve Incident
        </button>
      </div>

      <div class="grid grid-cols-2 gap-4 text-sm">
        <div>
          <span class="text-muted-foreground">Status:</span>
          <span :class="['ml-2 inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium', store.currentIncident.completedAt ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800']">
            {{ store.currentIncident.completedAt ? 'Resolved' : 'Open' }}
          </span>
        </div>
        <div><span class="text-muted-foreground">Activity:</span> <span class="font-mono">{{ store.currentIncident.activityId }}</span></div>
        <div class="col-span-2"><span class="text-muted-foreground">Created:</span> {{ new Date(store.currentIncident.createdAt).toLocaleString() }}</div>
        <div v-if="store.currentIncident.completedAt" class="col-span-2">
          <span class="text-muted-foreground">Resolved:</span> {{ new Date(store.currentIncident.completedAt).toLocaleString() }}
        </div>
      </div>

      <div class="border border-border rounded-lg p-4 bg-card">
        <h2 class="text-lg font-bold mb-2">Message</h2>
        <pre class="text-sm whitespace-pre-wrap font-mono bg-muted p-3 rounded">{{ store.currentIncident.message }}</pre>
      </div>
    </template>

    <div
      v-if="showResolveModal"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="showResolveModal = false"
    >
      <div class="bg-card rounded-lg shadow-lg w-full max-w-sm p-6 space-y-4">
        <h2 class="text-lg font-bold">Resolve Incident</h2>
        <p class="text-sm text-muted-foreground">This will re-execute the failed element.</p>
        <div class="flex justify-end gap-2 pt-2">
          <button class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted" @click="showResolveModal = false">Cancel</button>
          <button class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90" @click="resolve">Resolve</button>
        </div>
      </div>
    </div>
  </div>
</template>
