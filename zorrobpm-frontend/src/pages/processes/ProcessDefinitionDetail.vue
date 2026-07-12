<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useProcessStore } from '@/stores/process'
import BpmnViewer from '@/widgets/bpmn/BpmnViewer.vue'
import * as processService from '@/services/processService'
import type { BpmnNode, BpmnFlow } from '@/types/api'
import CopyableId from '@/widgets/shared/CopyableId.vue'

const route = useRoute()
const router = useRouter()
const store = useProcessStore()

const bpmnXml = ref('')
const selectedElement = ref<string | null>(null)
const showStartModal = ref(false)
const startVars = ref<{ name: string; type: string; value: string }[]>([])
const newVarName = ref('')
const newVarType = ref('STRING')
const newVarValue = ref('')

// --- BPMN breakdown: find / flatten nodes from the parsed structure ---
function findNode(nodes: BpmnNode[], id: string): BpmnNode | null {
  for (const n of nodes) {
    if (n.id === id) return n
    for (const b of n.boundaryEvents || []) if (b.id === id) return b
    if (n.children) {
      const found = findNode(n.children.nodes, id)
      if (found) return found
    }
  }
  return null
}

function flatten(nodes: BpmnNode[]): BpmnNode[] {
  const out: BpmnNode[] = []
  for (const n of nodes) {
    out.push(n)
    for (const b of n.boundaryEvents || []) out.push(b)
    if (n.children) out.push(...flatten(n.children.nodes))
  }
  return out
}

const allNodes = computed(() => (store.currentStructure ? flatten(store.currentStructure.nodes) : []))
const selectedNode = computed<BpmnNode | null>(() =>
  selectedElement.value && store.currentStructure ? findNode(store.currentStructure.nodes, selectedElement.value) : null)
const selectedNodeProps = computed(() =>
  selectedNode.value ? Object.entries(selectedNode.value.properties || {}) : [])
// clicking a sequence flow (arrow) -> show its FEEL condition / source / target
const selectedFlow = computed<BpmnFlow | null>(() =>
  selectedElement.value && !selectedNode.value && store.currentStructure
    ? (store.currentStructure.flows.find((f) => f.id === selectedElement.value) || null)
    : null)
// every element that carries BPMN <documentation>, surfaced as "requirements"
const requirements = computed(() => allNodes.value.filter((n) => n.documentation))

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
  if (newVarName.value) addVariable()
  const id = await store.startInstance({
    processDefinitionId: route.params.id as string,
    variables: startVars.value.map((v) => ({
      name: v.name,
      type: v.type as 'STRING' | 'LONG' | 'DOUBLE' | 'BOOLEAN',
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
          <p class="text-xs text-muted-foreground">Click an element to inspect its configuration (conditions, FEEL, job type, forms…)</p>
        </div>
        <div class="flex">
          <div class="flex-1">
            <BpmnViewer :xml="bpmnXml" style="height: 500px;" @element-click="selectedElement = $event" />
          </div>
          <div v-if="selectedElement" class="w-80 border-l border-border p-4 space-y-3 bg-muted/30 overflow-y-auto" style="max-height: 540px;">
            <div class="flex items-center justify-between">
              <h3 class="text-sm font-bold">{{ selectedFlow ? 'Sequence flow' : 'Element' }}</h3>
              <button class="text-xs text-muted-foreground hover:text-foreground" @click="selectedElement = null">Close</button>
            </div>

            <!-- node -->
            <template v-if="selectedNode">
              <div class="text-sm space-y-1">
                <div v-if="selectedNode.name"><span class="text-muted-foreground">Name:</span> {{ selectedNode.name }}</div>
                <div v-if="selectedNode.type">
                  <span class="text-muted-foreground">Type:</span>
                  <span class="ml-1 inline-flex items-center px-2 py-0.5 rounded text-xs font-mono bg-muted">{{ selectedNode.type }}<template v-if="selectedNode.eventDefinition">/{{ selectedNode.eventDefinition }}</template></span>
                </div>
                <div><span class="text-muted-foreground">ID:</span> <CopyableId :value="selectedElement" /></div>
              </div>
              <div v-if="selectedNodeProps.length" class="pt-2 border-t border-border space-y-1.5">
                <h4 class="text-xs font-semibold text-muted-foreground uppercase">Configuration</h4>
                <div v-for="[k, v] in selectedNodeProps" :key="k" class="text-xs">
                  <span class="text-muted-foreground font-mono">{{ k }}:</span>
                  <span class="ml-1 font-mono break-all">{{ typeof v === 'object' ? JSON.stringify(v) : v }}</span>
                </div>
              </div>
              <div v-if="selectedNode.documentation" class="pt-2 border-t border-border space-y-1">
                <h4 class="text-xs font-semibold text-muted-foreground uppercase">Requirements</h4>
                <p class="text-xs whitespace-pre-wrap break-words">{{ selectedNode.documentation }}</p>
              </div>
              <div v-if="!selectedNodeProps.length && !selectedNode.documentation" class="pt-2 border-t border-border text-xs text-muted-foreground">No configuration.</div>
            </template>

            <!-- sequence flow -->
            <template v-else-if="selectedFlow">
              <div class="text-sm space-y-1">
                <div v-if="selectedFlow.name"><span class="text-muted-foreground">Name:</span> {{ selectedFlow.name }}</div>
                <div><span class="text-muted-foreground">ID:</span> <CopyableId :value="selectedElement" /></div>
                <div class="text-xs text-muted-foreground font-mono">{{ selectedFlow.sourceRef }} → {{ selectedFlow.targetRef }}</div>
              </div>
              <div class="pt-2 border-t border-border space-y-1">
                <h4 class="text-xs font-semibold text-muted-foreground uppercase">Condition (FEEL)</h4>
                <p v-if="selectedFlow.conditionExpression" class="text-xs font-mono break-all bg-muted rounded px-2 py-1">{{ selectedFlow.conditionExpression }}</p>
                <p v-else class="text-xs text-muted-foreground">No condition (default / unconditional flow).</p>
              </div>
            </template>

            <div v-else class="text-xs text-muted-foreground">
              <div><span class="text-muted-foreground">ID:</span> {{ selectedElement }}</div>
              <p class="mt-1">No details for this element.</p>
            </div>
          </div>
        </div>
      </div>

      <div v-else-if="store.currentStructure" class="border border-border rounded-lg p-6 bg-card">
        <h2 class="text-lg font-bold mb-4">BPMN Structure</h2>
        <div class="space-y-2">
          <div v-for="node in store.currentStructure.nodes" :key="node.id" class="flex items-center gap-3 text-sm">
            <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-mono bg-muted">{{ node.type }}</span>
            <span class="font-mono">{{ node.id }}</span>
            <span v-if="node.name" class="text-muted-foreground">— {{ node.name }}</span>
          </div>
        </div>
      </div>

      <!-- Requirements: process-level + per-element BPMN documentation -->
      <div v-if="requirements.length || store.currentStructure?.documentation" class="border border-border rounded-lg overflow-hidden bg-card">
        <div class="px-4 py-3 border-b border-border">
          <h2 class="text-lg font-bold">Requirements</h2>
          <p class="text-xs text-muted-foreground">Extracted from BPMN documentation</p>
        </div>
        <div v-if="store.currentStructure?.documentation" class="px-4 py-3 border-b border-border text-sm">
          <div class="text-xs font-semibold text-muted-foreground uppercase mb-1">Process</div>
          <a v-if="/^https?:\/\//.test(store.currentStructure.documentation)" :href="store.currentStructure.documentation" target="_blank" rel="noopener" class="text-primary hover:underline break-all">{{ store.currentStructure.documentation }}</a>
          <p v-else class="whitespace-pre-wrap break-words">{{ store.currentStructure.documentation }}</p>
        </div>
        <table v-if="requirements.length" class="w-full text-sm">
          <thead class="bg-muted">
            <tr>
              <th class="px-4 py-3 text-left font-medium">Element</th>
              <th class="px-4 py-3 text-left font-medium">Type</th>
              <th class="px-4 py-3 text-left font-medium">Requirement</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="n in requirements" :key="n.id" class="border-t border-border align-top">
              <td class="px-4 py-3">{{ n.name || n.id }}</td>
              <td class="px-4 py-3"><span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-mono bg-muted">{{ n.type }}</span></td>
              <td class="px-4 py-3 whitespace-pre-wrap">{{ n.documentation }}</td>
            </tr>
          </tbody>
        </table>
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
          <div class="grid grid-cols-[6rem_5.5rem_1fr] gap-2">
            <input v-model="newVarName" placeholder="name" class="px-2 py-1 border border-input rounded text-sm" @keyup.enter="addVariable" />
            <select v-model="newVarType" class="px-2 py-1 border border-input rounded text-sm">
              <option>STRING</option>
              <option>LONG</option>
              <option>DOUBLE</option>
              <option>BOOLEAN</option>
            </select>
            <input v-model="newVarValue" placeholder="value" class="px-2 py-1 border border-input rounded text-sm" @keyup.enter="addVariable" />
          </div>
          <button
            class="w-full px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors disabled:opacity-50"
            :disabled="!newVarName"
            @click="addVariable"
          >
            + Add variable
          </button>
        </div>
        <div class="flex justify-end gap-2 pt-2">
          <button class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted" @click="showStartModal = false">Cancel</button>
          <button class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90" @click="startProcess">Start</button>
        </div>
      </div>
    </div>
  </div>
</template>
