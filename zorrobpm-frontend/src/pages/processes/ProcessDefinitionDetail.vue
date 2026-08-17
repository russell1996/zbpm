<script setup lang="ts">
import { computed, onMounted, ref, watch, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useToast } from '@/composables/useToast'
import { useProcessStore } from '@/stores/process'
import BpmnViewer from '@/widgets/bpmn/BpmnViewer.vue'
import SchemaEditorPanel from '@/widgets/shared/SchemaEditorPanel.vue'
import * as processService from '@/services/processService'
import { listMembers, changeMemberRole, removeMember, type Member } from '@/services/adminService'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/shared/lib/utils'
import type { ProcessVariable, BpmnNode, BpmnFlow } from '@/types/api'
import CopyableId from '@/widgets/shared/CopyableId.vue'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { formatDateTime } = useDateFormat()
const toast = useToast()
const store = useProcessStore()
const auth = useAuthStore()

// --- WO-ACL-6: members and roles (ADR-8 п.4: seeing members is a member right;
// managing them belongs to the OWNER only) ---
const members = ref<Member[]>([])
const membersLoading = ref(false)
const membersError = ref<string | null>(null)

const myMembership = computed(() => {
  if (!auth.user) return null
  return members.value.find((m) => m.userId === auth.user?.id) || null
})
// WO-ACL-8 criterion 11: the super-admin manages members even without being a
// member — the backend allows everything for SUPER_ADMIN, so hiding the controls
// would make the UI contradict the API (canOperate checks isSuperAdmin first).
const canManageMembers = computed(() => auth.isSuperAdmin || myMembership.value?.role === 'OWNER')

// WO-ACL-8 criteria 3-4: START requires OWNER/DESIGNER membership (backend role
// grants), and the super-admin can do everything.
const canStart = computed(
  () => auth.isSuperAdmin || myMembership.value?.role === 'OWNER' || myMembership.value?.role === 'DESIGNER',
)
// WO-ACL-8 criterion 5: new version upload requires DEPLOY — OWNER/DESIGNER on
// the backend (ADR-8 п.3), plus the super-admin.
const canDeployVersion = computed(
  () => auth.isSuperAdmin || myMembership.value?.role === 'OWNER' || myMembership.value?.role === 'DESIGNER',
)

async function loadMembers() {
  const def = store.currentDefinition
  if (!def?.key) return
  membersLoading.value = true
  membersError.value = null
  try {
    members.value = await listMembers(def.key)
  } catch (e) {
    membersError.value = errorMessage(e, t('failedToLoadMembers'))
  } finally {
    membersLoading.value = false
  }
}

async function changeRole(member: Member, targetRole: string) {
  const def = store.currentDefinition
  if (!def?.key || targetRole === member.role) return
  try {
    await changeMemberRole(def.key, member.userId, targetRole)
    toast.success(t('roleChanged'))
    await loadMembers()
  } catch (e) {
    toast.error(errorMessage(e, t('failedToChangeRole')))
  }
}

async function removeMemberOf(member: Member) {
  const def = store.currentDefinition
  if (!def?.key) return
  try {
    await removeMember(def.key, member.userId)
    toast.success(t('memberRemoved'))
    await loadMembers()
  } catch (e) {
    toast.error(errorMessage(e, t('failedToRemoveMember')))
  }
}

const bpmnXml = ref('')
const selectedElement = ref<string | null>(null)
const showStartModal = ref(false)
const startVars = ref<{ name: string; type: string; value: string }[]>([])
const newVarName = ref('')
const newVarType = ref('STRING')
const newVarValue = ref('')
const jsonError = ref('')

// --- WO-ACL-8 criterion 5: "upload new version" (POST /process-definitions/{id}/versions) ---
const showVersionModal = ref(false)
const versionBpmn = ref('')
const versionFileName = ref('')
const versionLoading = ref(false)
const versionError = ref<string | null>(null)
const versionSuccess = ref(false)

function onVersionFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  versionFileName.value = file.name
  const reader = new FileReader()
  reader.onload = () => {
    versionBpmn.value = reader.result as string
  }
  reader.readAsText(file)
}

async function submitNewVersion() {
  const def = store.currentDefinition
  if (!def || !versionBpmn.value.trim()) {
    versionError.value = t('bpmnRequired')
    return
  }
  versionLoading.value = true
  versionError.value = null
  versionSuccess.value = false
  try {
    const created = await processService.addProcessDefinitionVersion(def.id, versionBpmn.value)
    versionSuccess.value = true
    toast.success(t('deploySuccessToast'), {
      action: { label: t('viewDefinition'), onClick: () => router.push(`/processes/definitions/${created.id}`) },
    })
    await store.fetchVersions(def.key)
    await store.fetchDefinition(created.id)
  } catch (e) {
    versionError.value = errorMessage(e, t('failedToDeploy'))
    toast.error(versionError.value)
  } finally {
    versionLoading.value = false
  }
}

function closeVersionModal() {
  showVersionModal.value = false
  versionBpmn.value = ''
  versionFileName.value = ''
  versionError.value = null
  versionSuccess.value = false
}

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
  jsonError.value = ''
  if (newVarName.value) {
    if (newVarType.value === 'JSON') {
      try {
        JSON.parse(newVarValue.value)
      } catch {
        jsonError.value = 'Invalid JSON'
        return
      }
    }
    startVars.value.push({ name: newVarName.value, type: newVarType.value, value: newVarValue.value })
    newVarName.value = ''
    newVarValue.value = ''
  }
}

function removeVariable(index: number) {
  startVars.value.splice(index, 1)
}

async function startProcess() {
  if (jsonError.value) return
  if (newVarName.value) addVariable()
  if (jsonError.value) return
  const id = await store.startInstance({
    processDefinitionId: route.params.id as string,
    variables: startVars.value.map((v) => ({
      name: v.name,
      type: v.type as ProcessVariable['type'],
      value: v.value,
    })),
  })
  if (id) {
    showStartModal.value = false
    startVars.value = []
    router.push('/processes/instances')
  }
}

async function loadDefinition(id: string) {
  bpmnXml.value = ''
  await Promise.all([
    store.fetchDefinition(id),
    store.fetchStructure(id),
  ])
  if (store.currentDefinition) {
    await store.fetchVersions(store.currentDefinition.key)
    // WO-ACL-8 criterion 12: provide the process name for the breadcrumb.
    // BreadcrumbNav injects this to replace the static "Process Definition" title.
    provide('processName', store.currentDefinition.name || store.currentDefinition.key)
    loadMembers()
  }
  try {
    bpmnXml.value = await processService.getProcessDefinitionXml(id)
  } catch {
    toast.error(t('loadError'))
  }
}

onMounted(() => loadDefinition(route.params.id as string))

// Vue Router reuses the component instance when only :id changes (same route).
// Without this watch, switching versions leaves stale data on screen.
watch(
  () => route.params.id,
  (id) => { if (id) loadDefinition(id as string) },
)

function openVersion(id: string) {
  if (id !== (route.params.id as string)) {
    router.push(`/processes/definitions/${id}`)
  }
}

async function downloadBpmn() {
  const def = store.currentDefinition
  if (!def) return
  let xml = bpmnXml.value
  if (!xml) {
    try {
      xml = await processService.getProcessDefinitionXml(def.id)
    } catch {
      toast.error(t('loadError'))
      return
    }
  }
  const ext = def.key ? `${def.key}-v${def.version}.bpmn` : `${def.id}.bpmn`
  const blob = new Blob([xml], { type: 'application/xml;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = ext
  link.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="space-y-6">
    <div v-if="store.loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
    <div v-else-if="store.error" class="text-sm text-red-500">{{ store.error }}</div>

    <template v-else-if="store.currentDefinition">
      <div class="flex items-center justify-between">
        <div>
          <h1 class="text-2xl font-bold">{{ store.currentDefinition.name || store.currentDefinition.key }}</h1>
          <p class="text-sm text-muted-foreground">
            Key: <span class="font-mono">{{ store.currentDefinition.key }}</span>
            · Version: {{ store.currentDefinition.version }}
            · Created: {{ formatDateTime(store.currentDefinition.createdAt) }}
          </p>
        </div>
        <div class="flex items-center gap-2">
          <button
            class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
            @click="downloadBpmn"
          >
            {{ t('downloadBpmn') }}
          </button>
          <button
            v-if="canDeployVersion"
            class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
            @click="showVersionModal = true"
          >
            {{ t('uploadNewVersion') }}
          </button>
          <button
            v-if="canStart"
            class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity text-sm"
            @click="showStartModal = true"
          >
            {{ t('startProcess') }}
          </button>
        </div>
      </div>

      <!-- WO-ACL-8 criterion 13: model first — the diagram is the primary reason
           users open this page; members are reference material. -->
      <div v-if="bpmnXml" class="border border-border rounded-lg bg-card">
        <div class="px-4 py-3 border-b border-border">
          <h2 class="text-lg font-bold">{{ t('bpmnProcess') }}</h2>
          <p class="text-xs text-muted-foreground">Click an element to inspect its configuration (conditions, FEEL, job type, forms…)</p>
        </div>
        <div class="flex">
          <div class="flex-1">
            <BpmnViewer :xml="bpmnXml" style="height: 500px;" @element-click="selectedElement = $event" />
          </div>
          <div v-if="selectedElement" class="w-80 border-l border-border p-4 space-y-3 bg-muted/30 overflow-y-auto" style="max-height: 540px;">
            <div class="flex items-center justify-between">
              <h3 class="text-sm font-bold">{{ selectedFlow ? 'Sequence flow' : 'Element' }}</h3>
              <button class="text-xs text-muted-foreground hover:text-foreground" @click="selectedElement = null">{{ t('close') }}</button>
            </div>

            <!-- node -->
            <template v-if="selectedNode">
              <div class="text-sm space-y-1">
                <div v-if="selectedNode.name"><span class="text-muted-foreground">{{ t('name') }}:</span> {{ selectedNode.name }}</div>
                <div v-if="selectedNode.type">
                  <span class="text-muted-foreground">{{ t('type') }}:</span>
                  <span class="ml-1 inline-flex items-center px-2 py-0.5 rounded text-xs font-mono bg-muted">{{ selectedNode.type }}<template v-if="selectedNode.eventDefinition">/{{ selectedNode.eventDefinition }}</template></span>
                </div>
                <div><span class="text-muted-foreground">ID:</span> <CopyableId :value="selectedElement" /></div>
              </div>
              <div v-if="selectedNodeProps.length" class="pt-2 border-t border-border space-y-1.5">
                <h4 class="text-xs font-semibold text-muted-foreground uppercase">{{ t('configuration') }}</h4>
                <div v-for="[k, v] in selectedNodeProps" :key="k" class="text-xs">
                  <span class="text-muted-foreground font-mono">{{ k }}:</span>
                  <span class="ml-1 font-mono break-all">{{ typeof v === 'object' ? JSON.stringify(v) : v }}</span>
                </div>
              </div>
              <div v-if="selectedNode.documentation" class="pt-2 border-t border-border space-y-1">
                <h4 class="text-xs font-semibold text-muted-foreground uppercase">{{ t('requirements') }}</h4>
                <p class="text-xs whitespace-pre-wrap break-words">{{ selectedNode.documentation }}</p>
              </div>
              <div v-if="!selectedNodeProps.length && !selectedNode.documentation" class="pt-2 border-t border-border text-xs text-muted-foreground">{{ t('noConfiguration') }}</div>
            </template>

            <!-- sequence flow -->
            <template v-else-if="selectedFlow">
              <div class="text-sm space-y-1">
                <div v-if="selectedFlow.name"><span class="text-muted-foreground">{{ t('name') }}:</span> {{ selectedFlow.name }}</div>
                <div><span class="text-muted-foreground">ID:</span> <CopyableId :value="selectedElement" /></div>
                <div class="text-xs text-muted-foreground font-mono">{{ selectedFlow.sourceRef }} → {{ selectedFlow.targetRef }}</div>
              </div>
              <div class="pt-2 border-t border-border space-y-1">
                <h4 class="text-xs font-semibold text-muted-foreground uppercase">{{ t('conditionFeel') }}</h4>
                <p v-if="selectedFlow.conditionExpression" class="text-xs font-mono break-all bg-muted rounded px-2 py-1">{{ selectedFlow.conditionExpression }}</p>
                <p v-else class="text-xs text-muted-foreground">No condition (default / unconditional flow).</p>
              </div>
            </template>

            <div v-else class="text-xs text-muted-foreground">
              <div><span class="text-muted-foreground">ID:</span> {{ selectedElement }}</div>
              <p class="mt-1">{{ t('noDetailsForElement') }}</p>
            </div>
          </div>
        </div>
      </div>

      <div v-else-if="store.currentStructure" class="border border-border rounded-lg p-6 bg-card">
        <h2 class="text-lg font-bold mb-4">{{ t('bpmnStructure') }}</h2>
        <div class="space-y-2">
          <div v-for="node in store.currentStructure.nodes" :key="node.id" class="flex items-center gap-3 text-sm">
            <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-mono bg-muted">{{ node.type }}</span>
            <span class="font-mono">{{ node.id }}</span>
            <span v-if="node.name" class="text-muted-foreground">— {{ node.name }}</span>
          </div>
        </div>
      </div>

      <!-- WO-ACL-6 criterion 2/3: members — visible to any member, managed by OWNER/SA.
           Placed AFTER the model (WO-ACL-8 criterion 13). -->
      <div class="border border-border rounded-lg overflow-hidden bg-card">
        <div class="px-4 py-3 border-b border-border">
          <h2 class="text-lg font-bold">{{ t('members') }}</h2>
          <p class="text-xs text-muted-foreground">{{ t('membersHint') }}</p>
        </div>
        <div v-if="membersLoading" class="px-4 py-3 text-sm text-muted-foreground">{{ t('loading') }}</div>
        <div v-else-if="membersError" class="px-4 py-3 text-sm text-red-500">{{ membersError }}</div>
        <table v-else-if="members.length" class="w-full text-sm">
          <thead class="bg-muted">
            <tr>
              <th class="px-4 py-2 text-left font-medium">{{ t('username') }}</th>
              <th class="px-4 py-2 text-left font-medium">{{ t('fullName') }}</th>
              <th class="px-4 py-2 text-left font-medium">{{ t('email') }}</th>
              <th class="px-4 py-2 text-left font-medium">{{ t('role') }}</th>
              <th v-if="canManageMembers" class="px-4 py-2 text-left font-medium">{{ t('actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in members" :key="m.userId" class="border-t border-border">
              <td class="px-4 py-2">
                {{ m.username || m.userId }}
                <span v-if="m.userId === auth.user?.id" class="ml-2 text-xs text-muted-foreground">({{ t('you') }})</span>
              </td>
              <td class="px-4 py-2">{{ m.fullName || '—' }}</td>
              <td class="px-4 py-2">{{ m.email || '—' }}</td>
              <td class="px-4 py-2">
                <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium" :class="m.role === 'OWNER' ? 'bg-amber-100 text-amber-800' : m.role === 'DESIGNER' ? 'bg-blue-100 text-blue-800' : 'bg-gray-100 text-gray-700'">
                  {{ m.role }}
                </span>
              </td>
              <td v-if="canManageMembers" class="px-4 py-2">
                <div class="flex items-center gap-2">
                  <select
                    class="px-2 py-1 border border-input rounded text-xs"
                    :value="m.role"
                    @change="changeRole(m, ($event.target as HTMLSelectElement).value)"
                  >
                    <option value="OWNER">{{ t('ownerRole') }}</option>
                    <option value="DESIGNER">{{ t('designerRole') }}</option>
                    <option value="VIEWER">{{ t('viewerRole') }}</option>
                  </select>
                  <button
                    class="text-xs text-red-500 hover:underline"
                    :disabled="m.userId === auth.user?.id"
                    @click="removeMemberOf(m)"
                  >
                    {{ t('remove') }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else class="px-4 py-3 text-sm text-muted-foreground">{{ t('noMembers') }}</p>
      </div>

      <!-- Requirements: process-level + per-element BPMN documentation -->
      <div v-if="requirements.length || store.currentStructure?.documentation" class="border border-border rounded-lg overflow-hidden bg-card">
        <div class="px-4 py-3 border-b border-border">
          <h2 class="text-lg font-bold">{{ t('requirements') }}</h2>
          <p class="text-xs text-muted-foreground">{{ t('extractedFromBpmn') }}</p>
        </div>
        <div v-if="store.currentStructure?.documentation" class="px-4 py-3 border-b border-border text-sm">
          <div class="text-xs font-semibold text-muted-foreground uppercase mb-1">{{ t('process') }}</div>
          <a v-if="/^https?:\/\//.test(store.currentStructure.documentation)" :href="store.currentStructure.documentation" target="_blank" rel="noopener" class="text-primary hover:underline break-all">{{ store.currentStructure.documentation }}</a>
          <p v-else class="whitespace-pre-wrap break-words">{{ store.currentStructure.documentation }}</p>
        </div>
        <table v-if="requirements.length" class="w-full text-sm">
          <thead class="bg-muted">
            <tr>
              <th class="px-4 py-3 text-left font-medium">{{ t('element') }}</th>
              <th class="px-4 py-3 text-left font-medium">{{ t('type') }}</th>
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

      <!-- WO-VM-12: Element Schemas -->
      <div v-if="store.currentDefinition" class="border border-border rounded-lg p-4 bg-card">
        <div class="px-0 pb-3">
          <h2 class="text-lg font-bold">{{ t('elementSchemas') }}</h2>
          <p class="text-xs text-muted-foreground">Bind and edit form/variable schemas for start events and user tasks</p>
        </div>
        <SchemaEditorPanel :process-key="store.currentDefinition.key" />
      </div>

      <div v-if="store.currentVersions.length > 1" class="border border-border rounded-lg overflow-hidden bg-card">
        <div class="px-4 py-3 border-b border-border">
          <h2 class="text-lg font-bold">{{ t('versions') }}</h2>
        </div>
        <table class="w-full text-sm">
          <thead class="bg-muted">
            <tr>
              <th class="px-4 py-3 text-left font-medium">{{ t('version') }}</th>
              <th class="px-4 py-3 text-left font-medium">{{ t('created') }}</th>
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
              <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(v.createdAt) }}</td>
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
        <h2 class="text-lg font-bold">{{ t('startProcessInstance') }}</h2>
        <div class="space-y-3">
          <div v-for="(v, i) in startVars" :key="i" class="flex items-center gap-2 text-sm">
            <span class="font-mono">{{ v.name }}</span>
            <span class="text-muted-foreground">({{ v.type }})</span>
            <span>= {{ v.value }}</span>
            <button class="text-red-500 hover:underline ml-auto" @click="removeVariable(i)">{{ t('remove') }}</button>
          </div>
          <div class="grid grid-cols-[6rem_5.5rem_1fr] gap-2">
            <input v-model="newVarName" placeholder="name" class="px-2 py-1 border border-input rounded text-sm" @keyup.enter="addVariable" />
            <select v-model="newVarType" class="px-2 py-1 border border-input rounded text-sm">
              <option>STRING</option>
              <option>UUID</option>
              <option>LONG</option>
              <option>DOUBLE</option>
              <option>BOOLEAN</option>
              <option>JSON</option>
            </select>
            <input v-if="newVarType !== 'JSON'" v-model="newVarValue" placeholder="value" class="px-2 py-1 border border-input rounded text-sm" @keyup.enter="addVariable" />
          </div>
          <textarea v-if="newVarType === 'JSON'" v-model="newVarValue" placeholder='e.g. ["u1","u2"] or {"key":"val"}' class="w-full px-2 py-1 border border-input rounded text-sm font-mono" rows="3"></textarea>
          <p v-if="jsonError" class="text-xs text-red-500">{{ jsonError }}</p>
          <button
            class="w-full px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors disabled:opacity-50"
            :disabled="!newVarName"
            @click="addVariable"
          >
            + {{ t('addVariable') }}
          </button>
        </div>
        <div class="flex justify-end gap-2 pt-2">
          <button class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted" @click="showStartModal = false">{{ t('cancel') }}</button>
          <button class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90" @click="startProcess">{{ t('startProcess') }}</button>
        </div>
      </div>
    </div>

    <!-- WO-ACL-8 criterion 5: upload a NEW VERSION of this process (DEPLOY-scoped). -->
    <div
      v-if="showVersionModal"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="closeVersionModal"
    >
      <div class="bg-card rounded-lg shadow-lg w-full max-w-lg p-6 space-y-4">
        <h2 class="text-lg font-bold">{{ t('uploadNewVersion') }}</h2>
        <p class="text-xs text-muted-foreground">{{ t('uploadNewVersionHint') }}</p>

        <div
          class="border-2 border-dashed border-border rounded-lg p-6 text-center hover:border-primary/50 transition-colors cursor-pointer"
          @click="($refs.versionFileInput as HTMLInputElement).click()"
        >
          <p class="text-sm font-medium mb-1">{{ t('dropBpmn') }}</p>
          <p class="text-xs text-muted-foreground">{{ versionFileName || t('supportsBpmn') }}</p>
          <input ref="versionFileInput" type="file" accept=".bpmn,.xml" class="hidden" @change="onVersionFileChange" />
        </div>

        <textarea
          v-model="versionBpmn"
          class="w-full h-48 px-4 py-3 border border-input rounded-md text-sm font-mono focus:outline-none focus:ring-2 focus:ring-ring resize-none"
          :placeholder="t('pasteBpmnHere')"
        />

        <div v-if="versionError" class="flex items-center gap-2 text-sm text-red-500">
          {{ versionError }}
        </div>
        <div v-if="versionSuccess" class="flex items-center gap-2 text-sm text-green-600">
          {{ t('deploySuccess') }}
        </div>

        <div class="flex justify-end gap-2 pt-2">
          <button class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted" @click="closeVersionModal">
            {{ t('cancel') }}
          </button>
          <button
            class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity disabled:opacity-50"
            :disabled="versionLoading || !versionBpmn.trim()"
            @click="submitNewVersion"
          >
            {{ versionLoading ? t('deploying') : t('uploadNewVersion') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
