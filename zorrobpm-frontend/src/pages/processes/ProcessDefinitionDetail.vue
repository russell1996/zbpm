<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useToast } from '@/composables/useToast'
import { useProcessStore } from '@/stores/process'
import { useBreadcrumbStore } from '@/stores/breadcrumb'
import BpmnViewer from '@/widgets/bpmn/BpmnViewer.vue'
import SchemaEditorPanel from '@/widgets/shared/SchemaEditorPanel.vue'
import * as processService from '@/services/processService'
import { listMembers, changeMemberRole, removeMember, addMember, searchMemberCandidates, type Member, type MemberCandidate } from '@/services/adminService'
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

// WO-ACL-11 criterion 3: the breadcrumb process name lives in the breadcrumb
// store (filled when the definition arrives, cleared on unmount) — NOT in a
// provide(): BreadcrumbNav is mounted ABOVE <router-view>, so inject() from this
// page could never reach it (P-54, the ACL-8/ACL-10 mechanism was impossible).
const breadcrumb = useBreadcrumbStore()

// --- WO-ACL-6: members and roles (ADR-8 п.4: seeing members is a member right;
// managing them belongs to the OWNER only) ---
const members = ref<Member[]>([])
const membersLoading = ref(false)
const membersError = ref<string | null>(null)

const myMembership = computed(() => {
  if (!auth.user) return null
  return members.value.find((m) => m.userId === auth.user?.id) || null
})
// WO-ACL-8 criterion 27: "is the user a member?" — used to distinguish
// "no access" from "no data" empty states in tabs.
const isMember = computed(() => auth.isSuperAdmin || myMembership.value !== null)
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

// --- WO-ACL-11 criteria 17-19: add a member straight from the process card ---
// Candidates come from the WO-ACL-7 endpoint (GET /processes/{key}/members/candidates?q=),
// NOT from /users — the user directory stays closed from this screen. The whole
// block is gated by canManageMembers (super-admin or OWNER, see above).
const candidateQuery = ref('')
const candidates = ref<MemberCandidate[]>([])
const candidatesLoading = ref(false)
const candidatesError = ref<string | null>(null)
const newMemberUserId = ref('')
const newMemberRole = ref('VIEWER')
const addingMember = ref(false)

async function searchCandidates() {
  const def = store.currentDefinition
  const q = candidateQuery.value.trim()
  if (!def?.key || q.length < 3) {
    candidates.value = []
    candidatesError.value = null
    return
  }
  candidatesLoading.value = true
  candidatesError.value = null
  try {
    candidates.value = await searchMemberCandidates(def.key, q)
  } catch (e) {
    candidates.value = []
    candidatesError.value = errorMessage(e, t('failedToLoadCandidates'))
  } finally {
    candidatesLoading.value = false
  }
}

async function addMemberToProcess() {
  const def = store.currentDefinition
  if (!def?.key || !newMemberUserId.value) return
  addingMember.value = true
  try {
    await addMember(def.key, newMemberUserId.value, newMemberRole.value)
    toast.success(t('memberAdded'))
    candidateQuery.value = ''
    candidates.value = []
    newMemberUserId.value = ''
    newMemberRole.value = 'VIEWER'
    await loadMembers()
  } catch (e) {
    toast.error(errorMessage(e, t('failedToAddMember')))
  } finally {
    addingMember.value = false
  }
}

const bpmnXml = ref('')
const selectedElement = ref<string | null>(null)
const activeTab = ref('model')
const showStartModal = ref(false)
const startVars = ref<{ name: string; type: string; value: string }[]>([])
const newVarName = ref('')
const newVarType = ref('STRING')
const newVarValue = ref('')
const jsonError = ref('')

// --- WO-ACL-11 criteria 20-22: ONE upload component, two ways to open it ---
// The card opens ProcessDeploySection BOUND to this process (target shown,
// mode "new version", foreign keys rejected). The old inline showVersionModal
// is gone — it was a second implementation of the same action (WO-ACL-10 defect).
import ProcessDeploySection from '@/widgets/processes/ProcessDeploySection.vue'

const showDeployDialog = ref(false)

function onDeployDone() {
  showDeployDialog.value = false
  const def = store.currentDefinition
  if (def?.key) store.fetchVersions(def.key)
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
    // WO-ACL-11 criterion 3: fill the breadcrumb store — the crumb shows the real
    // process name (was provide/inject, physically impossible above router-view).
    breadcrumb.setProcessName(store.currentDefinition.name || store.currentDefinition.key)
    loadMembers()
  }
  try {
    bpmnXml.value = await processService.getProcessDefinitionXml(id)
  } catch {
    toast.error(t('loadError'))
  }
}

onMounted(() => loadDefinition(route.params.id as string))

// WO-ACL-11 criterion 3: leaving the page must NOT leave a stale process name in
// the store — the next detail page (e.g. an instance) would show it instead of
// its own title.
onUnmounted(() => breadcrumb.setProcessName(null))

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
  <!-- WO-ACL-11 criteria 37-38: min-h-full + flex so the model tab can stretch
       to the bottom edge of the window (layout, not fixed pixels). -->
  <div class="space-y-6 min-h-full flex flex-col">
    <div v-if="store.loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
    <div v-else-if="store.error" class="text-sm text-red-500">{{ store.error }}</div>

    <template v-else-if="store.currentDefinition">
      <!-- Header: always visible -->
      <div class="flex items-center justify-between">
        <div>
          <h1 class="text-2xl font-bold">{{ store.currentDefinition.name || store.currentDefinition.key }}</h1>
          <p class="text-sm text-muted-foreground">
            Key: <span class="font-mono">{{ store.currentDefinition.key }}</span>
            <template v-if="store.currentVersions.length > 1">
              · {{ t('version') }}:
              <select
                class="ml-1 px-1.5 py-0.5 border border-input rounded text-xs bg-background"
                :value="route.params.id"
                @change="openVersion(($event.target as HTMLSelectElement).value)"
              >
                <option v-for="v in store.currentVersions" :key="v.id" :value="v.id">
                  v{{ v.version }} — {{ formatDateTime(v.createdAt) }}{{ v.id === route.params.id ? t('currentVersionMarker') : '' }}
                </option>
              </select>
            </template>
            <template v-else>
              · {{ t('version') }}: {{ store.currentDefinition.version }}
            </template>
            · {{ t('created') }}: {{ formatDateTime(store.currentDefinition.createdAt) }}
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
            @click="showDeployDialog = true"
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

      <!-- Tabs: only the <nav> carries -mb-px; the buttons must NOT repeat it,
           otherwise the active border-b-2 is pushed under the container border
           and the highlight disappears (WO-ACL-10 criteria 9-10). -->
      <div class="border-b border-border">
        <nav class="flex gap-0 -mb-px" role="tablist">
          <button
            v-for="tab in [
              { id: 'model', label: t('bpmnProcess') },
              { id: 'structure', label: t('bpmnStructure') },
              { id: 'docs', label: t('requirements') },
              { id: 'schemas', label: t('elementSchemas') },
              { id: 'members', label: t('members') },
              { id: 'versions', label: t('versions') },
            ]"
            :key="tab.id"
            role="tab"
            class="px-4 py-2.5 text-sm font-medium border-b-2 transition-colors"
            :class="activeTab === tab.id
              ? 'border-primary text-foreground'
              : 'border-transparent text-muted-foreground hover:text-foreground hover:border-border'"
            @click="activeTab = tab.id"
          >
            {{ tab.label }}
          </button>
        </nav>
      </div>

      <!-- Tab: Model — stretches to the bottom edge (criterion 37). -->
      <div v-if="activeTab === 'model'" class="flex-1 min-h-0 flex flex-col">
        <div v-if="bpmnXml" class="border border-border rounded-lg bg-card flex-1 min-h-0 flex flex-col">
          <div class="px-4 py-3 border-b border-border">
            <h2 class="text-lg font-bold">{{ t('bpmnProcess') }}</h2>
            <p class="text-xs text-muted-foreground">{{ t('modelTabHint') }}</p>
          </div>
          <div class="flex flex-1 min-h-0">
            <div class="flex-1 min-h-0">
              <BpmnViewer :xml="bpmnXml" @element-click="selectedElement = $event" />
            </div>
            <!-- WO-ACL-11 criterion 38: the properties panel is the same height as
                 the canvas (flex stretch) and scrolls INSIDE itself (overflow-y-auto);
                 the fixed max-height is gone. -->
            <div v-if="selectedElement" class="w-80 border-l border-border p-4 space-y-3 bg-muted/30 overflow-y-auto">
              <div class="flex items-center justify-between">
                <h3 class="text-sm font-bold">{{ selectedFlow ? t('sequenceFlow') : t('element') }}</h3>
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
                  <p v-else class="text-xs text-muted-foreground">{{ t('noConditionFlow') }}</p>
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
      </div>

      <!-- Tab: Structure -->
      <div v-if="activeTab === 'structure'">
        <div class="border border-border rounded-lg bg-card">
          <div class="px-4 py-3 border-b border-border">
            <h2 class="text-lg font-bold">{{ t('bpmnStructure') }}</h2>
          </div>
          <div v-if="store.currentStructure" class="p-4">
            <div class="space-y-2">
              <div v-for="node in store.currentStructure.nodes" :key="node.id" class="flex items-center gap-3 text-sm">
                <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-mono bg-muted">{{ node.type }}</span>
                <span class="font-mono">{{ node.id }}</span>
                <span v-if="node.name" class="text-muted-foreground">— {{ node.name }}</span>
              </div>
            </div>
            <p v-if="!store.currentStructure.nodes.length" class="text-sm text-muted-foreground">
              {{ isMember ? t('noDataYet') : t('noAccess') }}
            </p>
          </div>
          <p v-else class="p-4 text-sm text-muted-foreground">{{ t('loading') }}</p>
        </div>
      </div>

      <!-- Tab: Documentation (Requirements) -->
      <div v-if="activeTab === 'docs'">
        <div class="border border-border rounded-lg overflow-hidden bg-card">
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
                <th class="px-4 py-3 text-left font-medium">{{ t('requirement') }}</th>
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
          <p v-if="!requirements.length && !store.currentStructure?.documentation" class="p-4 text-sm text-muted-foreground">
            {{ isMember ? t('noDataYet') : t('noAccess') }}
          </p>
        </div>
      </div>

      <!-- Tab: Element Schemas -->
      <div v-if="activeTab === 'schemas'">
        <div class="border border-border rounded-lg p-4 bg-card">
          <div class="px-0 pb-3">
            <h2 class="text-lg font-bold">{{ t('elementSchemas') }}</h2>
            <p class="text-xs text-muted-foreground">{{ t('schemasTabHint') }}</p>
          </div>
          <SchemaEditorPanel :process-key="store.currentDefinition.key" />
        </div>
      </div>

      <!-- Tab: Members -->
      <div v-if="activeTab === 'members'">
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
          <!-- WO-ACL-10 criterion 14: since ACL-9 the member list is readable by any
               authenticated user — a non-member sees the people, not a noAccess state. -->
          <p v-else class="px-4 py-3 text-sm text-muted-foreground">
            {{ t('noMembers') }}
          </p>
          <!-- WO-ACL-11 criteria 17-18: add a member from the card. Visible only to
               super-admin/OWNER (canManageMembers); candidates come from the ACL-7
               endpoint, the /users directory is not touched. -->
          <div v-if="canManageMembers" class="border-t border-border px-4 py-3 space-y-3">
            <h3 class="text-sm font-semibold">{{ t('addMember') }}</h3>
            <input
              v-model="candidateQuery"
              class="w-full px-2 py-1.5 border border-input rounded text-sm"
              :placeholder="t('searchCandidatePlaceholder')"
              @input="searchCandidates"
            />
            <div v-if="candidatesLoading" class="text-xs text-muted-foreground">{{ t('loading') }}</div>
            <div v-else-if="candidatesError" class="text-xs text-red-500">{{ candidatesError }}</div>
            <div v-else-if="candidates.length" class="space-y-1">
              <button
                v-for="c in candidates"
                :key="c.userId"
                class="w-full flex items-center justify-between px-2 py-1.5 rounded text-sm hover:bg-muted transition-colors"
                :class="newMemberUserId === c.userId ? 'bg-sidebar-accent font-medium' : ''"
                @click="newMemberUserId = c.userId"
              >
                <span>{{ c.username }}</span>
                <span v-if="newMemberUserId === c.userId" class="text-xs text-muted-foreground">{{ t('selected') }}</span>
              </button>
            </div>
            <p v-else-if="candidateQuery.trim().length >= 3" class="text-xs text-muted-foreground">{{ t('noCandidates') }}</p>
            <p v-else class="text-xs text-muted-foreground">{{ t('searchCandidateHint') }}</p>
            <div v-if="newMemberUserId" class="flex items-center gap-2 pt-1">
              <select v-model="newMemberRole" class="px-2 py-1 border border-input rounded text-xs">
                <option value="OWNER">{{ t('ownerRole') }}</option>
                <option value="DESIGNER">{{ t('designerRole') }}</option>
                <option value="VIEWER">{{ t('viewerRole') }}</option>
              </select>
              <button
                class="px-3 py-1.5 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity disabled:opacity-50"
                :disabled="addingMember"
                @click="addMemberToProcess"
              >
                {{ addingMember ? t('adding') : t('addMember') }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Tab: Versions -->
      <div v-if="activeTab === 'versions'">
        <div class="border border-border rounded-lg overflow-hidden bg-card">
          <div class="px-4 py-3 border-b border-border">
            <h2 class="text-lg font-bold">{{ t('versions') }}</h2>
          </div>
          <table v-if="store.currentVersions.length" class="w-full text-sm">
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
                  <span v-if="v.id === (route.params.id as string)" class="ml-2 inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800">{{ t('current') }}</span>
                </td>
                <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(v.createdAt) }}</td>
                <td class="px-4 py-3 text-right text-primary text-xs">{{ v.id === (route.params.id as string) ? '' : t('openVersion') }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else class="px-4 py-3 text-sm text-muted-foreground">
            {{ isMember ? t('noMembers') : t('noAccess') }}
          </p>
        </div>
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
            <input v-model="newVarName" :placeholder="t('name')" class="px-2 py-1 border border-input rounded text-sm" @keyup.enter="addVariable" />
            <select v-model="newVarType" class="px-2 py-1 border border-input rounded text-sm">
              <option>STRING</option>
              <option>UUID</option>
              <option>LONG</option>
              <option>DOUBLE</option>
              <option>BOOLEAN</option>
              <option>JSON</option>
            </select>
            <input v-if="newVarType !== 'JSON'" v-model="newVarValue" :placeholder="t('value')" class="px-2 py-1 border border-input rounded text-sm" @keyup.enter="addVariable" />
          </div>
          <textarea v-if="newVarType === 'JSON'" v-model="newVarValue" :placeholder="t('jsonPlaceholder')" class="w-full px-2 py-1 border border-input rounded text-sm font-mono" rows="3"></textarea>
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

    <!-- WO-ACL-11 criteria 20-22: ONE upload component — the card opens the shared
      ProcessDeploySection BOUND to this process. All ACL-10 behavior (parse key
      and name before submit, close only on success, owner shown, rights block)
      lives in that single component now. -->
    <div
      v-if="showDeployDialog"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="showDeployDialog = false"
    >
      <div class="bg-card rounded-lg shadow-lg w-full max-w-2xl p-6 max-h-[90vh] overflow-y-auto">
        <div class="flex items-center justify-between mb-4">
          <h2 class="text-lg font-bold">{{ t('uploadNewVersion') }}</h2>
          <button class="text-sm text-muted-foreground hover:text-foreground" @click="showDeployDialog = false">
            {{ t('close') }}
          </button>
        </div>
        <ProcessDeploySection
          :bound="{ id: store.currentDefinition.id, key: store.currentDefinition.key, name: store.currentDefinition.name }"
          @done="onDeployDone"
        />
      </div>
    </div>
  </div>
</template>
