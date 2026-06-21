<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useIncidentStore } from '@/stores/incident'
import { useToast } from '@/composables/useToast'
import type { ProcessVariable } from '@/types/api'
import CopyableId from '@/widgets/shared/CopyableId.vue'

const route = useRoute()
const router = useRouter()
const store = useIncidentStore()
const toast = useToast()

const showResolveModal = ref(false)
const resolveVars = ref<{ name: string; type: string; value: string }[]>([])
const newVarName = ref('')
const newVarType = ref('STRING')
const newVarValue = ref('')

function addVariable() {
  if (newVarName.value) {
    resolveVars.value.push({ name: newVarName.value, type: newVarType.value, value: newVarValue.value })
    newVarName.value = ''
    newVarValue.value = ''
  }
}

function removeVariable(index: number) {
  resolveVars.value.splice(index, 1)
}

async function resolve() {
  const variables: ProcessVariable[] = resolveVars.value.map((v) => ({
    name: v.name,
    type: v.type as ProcessVariable['type'],
    value: v.value,
  }))
  await store.resolveIncident(route.params.id as string, variables)
  if (!store.error) {
    toast.success('Incident resolved')
    showResolveModal.value = false
    resolveVars.value = []
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
          <CopyableId :value="store.currentIncident.id" />
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
      <div class="bg-card rounded-lg shadow-lg w-full max-w-md p-6 space-y-4">
        <h2 class="text-lg font-bold">Resolve Incident</h2>
        <p class="text-sm text-muted-foreground">This will re-execute the failed element.</p>
        <div class="space-y-3">
          <div v-for="(v, i) in resolveVars" :key="i" class="flex items-center gap-2 text-sm">
            <span class="font-mono">{{ v.name }}</span>
            <span class="text-muted-foreground">({{ v.type }})</span>
            <span>= {{ v.value }}</span>
            <button class="text-red-500 hover:underline ml-auto" @click="removeVariable(i)">Remove</button>
          </div>
          <div class="flex items-center gap-2">
            <input v-model="newVarName" placeholder="name" class="px-2 py-1 border border-input rounded text-sm w-24" />
            <select v-model="newVarType" class="px-2 py-1 border border-input rounded text-sm">
              <option>STRING</option>
              <option>LONG</option>
              <option>BOOLEAN</option>
            </select>
            <input v-model="newVarValue" placeholder="value" class="px-2 py-1 border border-input rounded text-sm flex-1" />
            <button class="text-sm text-primary hover:underline" @click="addVariable">Add</button>
          </div>
        </div>
        <div class="flex justify-end gap-2 pt-2">
          <button class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted" @click="showResolveModal = false">Cancel</button>
          <button class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90" @click="resolve">Resolve</button>
        </div>
      </div>
    </div>
  </div>
</template>
