<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useProcessStore } from '@/stores/process'
import BpmnViewer from '@/widgets/bpmn/BpmnViewer.vue'
import * as processService from '@/services/processService'

const route = useRoute()
const router = useRouter()
const store = useProcessStore()

const bpmnXml = ref('')
const showStartModal = ref(false)
const startVars = ref<{ name: string; type: string; value: string }[]>([])
const newVarName = ref('')
const newVarType = ref('STRING')
const newVarValue = ref('')

function addVariable() {
  if (newVarName.value) {
    startVars.value.push({ name: newVarName.value, type: newVarType.value, value: newVarValue.value })
    newVarName.value = ''
    newVarValue.value = ''
  }
}

function removeVariable(index: number) {
  startVars.value.splice(index, 1)
}

async function startProcess() {
  const id = await store.startInstance({
    processDefinitionId: route.params.id as string,
    variables: startVars.value.map((v) => ({
      name: v.name,
      type: v.type as 'STRING' | 'LONG' | 'BOOLEAN',
      value: v.value,
    })),
  })
  if (id) {
    showStartModal.value = false
    startVars.value = []
    router.push('/processes/instances')
  }
}

onMounted(async () => {
  const id = route.params.id as string
  await Promise.all([
    store.fetchDefinition(id),
    store.fetchStructure(id),
  ])
  if (store.currentDefinition) {
    await store.fetchVersions(store.currentDefinition.key)
  }
  try {
    bpmnXml.value = await processService.getProcessDefinitionXml(id)
  } catch {
    // XML not available, structure-only view
  }
})

function openVersion(id: string) {
  if (id !== (route.params.id as string)) {
    router.push(`/processes/definitions/${id}`)
  }
}
</script>

<template>
  <div class="space-y-6">
    <div v-if="store.loading" class="text-sm text-muted-foreground">Loading...</div>
    <div v-else-if="store.error" class="text-sm text-red-500">{{ store.error }}</div>

    <template v-else-if="store.currentDefinition">
      <div class="flex items-center justify-between">
        <div>
          <h1 class="text-2xl font-bold">{{ store.currentDefinition.name || store.currentDefinition.key }}</h1>
          <p class="text-sm text-muted-foreground">
            Key: <span class="font-mono">{{ store.currentDefinition.key }}</span>
            · Version: {{ store.currentDefinition.version }}
            · Created: {{ new Date(store.currentDefinition.createdAt).toLocaleString() }}
          </p>
        </div>
        <button
          class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity text-sm"
          @click="showStartModal = true"
        >
          Start Process
        </button>
      </div>

      <div v-if="bpmnXml" class="border border-border rounded-lg bg-card">
        <div class="px-4 py-3 border-b border-border">
          <h2 class="text-lg font-bold">BPMN Process</h2>
        </div>
        <BpmnViewer :xml="bpmnXml" style="height: 500px;" />
      </div>

      <div v-else-if="store.currentStructure" class="border border-border rounded-lg p-6 bg-card">
        <h2 class="text-lg font-bold mb-4">BPMN Structure</h2>
        <div class="space-y-2">
          <div v-for="node in store.currentStructure.nodes" :key="node.id" class="flex items-center gap-3 text-sm">
            <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-mono bg-muted">
              {{ node.type }}
            </span>
            <span class="font-mono">{{ node.id }}</span>
            <span v-if="node.name" class="text-muted-foreground">— {{ node.name }}</span>
          </div>
        </div>
      </div>

      <div v-if="store.currentVersions.length > 1" class="border border-border rounded-lg overflow-hidden bg-card">
        <div class="px-4 py-3 border-b border-border">
          <h2 class="text-lg font-bold">Versions</h2>
        </div>
        <table class="w-full text-sm">
          <thead class="bg-muted">
            <tr>
              <th class="px-4 py-3 text-left font-medium">Version</th>
              <th class="px-4 py-3 text-left font-medium">Created</th>
              <th class="px-4 py-3 text-left font-medium"></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="v in store.currentVersions"
              :key="v.id"
              class="border-t border-border hover:bg-muted/50 cursor-pointer"
              @click="openVersion(v.id)"
            >
              <td class="px-4 py-3">
                v{{ v.version }}
                <span v-if="v.id === (route.params.id as string)" class="ml-2 inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800">current</span>
              </td>
              <td class="px-4 py-3 text-muted-foreground">{{ new Date(v.createdAt).toLocaleString() }}</td>
              <td class="px-4 py-3 text-right text-primary text-xs">{{ v.id === (route.params.id as string) ? '' : 'Open →' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <div
      v-if="showStartModal"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="showStartModal = false"
    >
      <div class="bg-card rounded-lg shadow-lg w-full max-w-md p-6 space-y-4">
        <h2 class="text-lg font-bold">Start Process Instance</h2>
        <div class="space-y-3">
          <div v-for="(v, i) in startVars" :key="i" class="flex items-center gap-2 text-sm">
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
          <button class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted" @click="showStartModal = false">Cancel</button>
          <button class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90" @click="startProcess">Start</button>
        </div>
      </div>
    </div>
  </div>
</template>
