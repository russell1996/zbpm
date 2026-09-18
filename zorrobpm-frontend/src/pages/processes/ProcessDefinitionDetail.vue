<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useToast } from '@/composables/useToast'
import { useProcessStore } from '@/stores/process'
import { useBreadcrumbLabel } from '@/composables/useBreadcrumbLabel'
import BpmnViewer from '@/widgets/bpmn/BpmnViewer.vue'
import SchemaEditorPanel from '@/widgets/shared/SchemaEditorPanel.vue'
import * as processService from '@/services/processService'
import { listMembers, changeMemberRole, removeMember, type Member } from '@/services/adminService'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/shared/lib/utils'
import type { ProcessVariable, BpmnNode, BpmnFlow } from '@/types/api'
import CopyableId from '@/widgets/shared/CopyableId.vue'
import IoMappingTable from '@/widgets/shared/IoMappingTable.vue'
import TabsBar from '@/widgets/shared/TabsBar.vue'
import MemberAddDialog from '@/widgets/processes/MemberAddDialog.vue'
import { ArrowLeft, Download, Calendar } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { formatDateTime } = useDateFormat()
const toast = useToast()
const store = useProcessStore()
const auth = useAuthStore()

// WO-ACL-11 criterion 3 + WO-ACL-15 criterion 19: the breadcrumb leaf label
// lives in the breadcrumb store through the shared useBreadcrumbLabel() — NOT
// in a provide(): BreadcrumbNav is mounted ABOVE <router-view>, so inject()
// from this page could never reach it (P-54, the ACL-8/ACL-10 mechanism was
// impossible). The composable fills the store when the definition arrives,
// reactively follows re-loads on the same route, and clears it on unmount.
useBreadcrumbLabel(() => store.currentDefinition?.name || store.currentDefinition?.key || null)

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

// --- WO-ACL-14 criteria 9-13: adding a member happens in MemberAddDialog ---
// Candidates come from the WO-ACL-7 endpoint (GET /processes/{key}/members/candidates?q=),
// NOT from /users — the user directory stays closed from this screen. The dialog
// is gated by canManageMembers (super-admin or OWNER).
const showMemberAddDialog = ref(false)

async function onMemberAdded() {
  await loadMembers()
}

// WO-ACL-14 criteria 1-3: ONE tab component (TabsBar) — the local tab strip is gone.
const definitionTabs = computed(() => [
  { id: 'model', label: t('bpmnProcess') },
  { id: 'structure', label: t('bpmnStructure') },
  { id: 'docs', label: t('requirements') },
  { id: 'schemas', label: t('elementSchemas') },
  { id: 'members', label: t('members') },
  { id: 'versions', label: t('versions') },
])

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
  selectedNode.value
    ? Object.entries(selectedNode.value.properties || {})
        // WO-ENG-15: ioMapping declarations render in IoMappingTable below,
        // not as a raw JSON blob in the generic list.
        .filter(([k]) => k !== 'inputMappings' && k !== 'outputMappings')
    : [])

// WO-ENG-15: the "no configuration" fallback must account for the mapping
// tables (a node with ONLY mappings is configured, not empty).
function hasMappingsList(value: unknown): boolean {
  return Array.isArray(value) && value.length > 0
}
const selectedNodeHasMappings = computed(() => {
  const p = selectedNode.value?.properties || {}
  return hasMappingsList(p['inputMappings']) || hasMappingsList(p['outputMappings'])
})

// WO-ENG-15: structure-tab tree — flattened nodes with depth for indent;
// boundary events ride one level under their host.
interface TreeRow {
  node: BpmnNode
  depth: number
}
function flattenDepth(nodes: BpmnNode[], depth: number, out: TreeRow[]): void {
  for (const n of nodes) {
    out.push({ node: n, depth })
    for (const b of n.boundaryEvents || []) out.push({ node: b, depth: depth + 1 })
    if (n.children) flattenDepth(n.children.nodes, depth + 1, out)
  }
}
const structureRows = computed<TreeRow[]>(() => {
  if (!store.currentStructure) return []
  const out: TreeRow[] = []
  flattenDepth(store.currentStructure.nodes, 0, out)
  return out
})
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
    loadMembers()
  }
  try {
    bpmnXml.value = await processService.getProcessDefinitionXml(id)
  } catch {
    toast.error(t('loadError'))
  }
}

onMounted(() => loadDefinition(route.params.id as string))

// Leaving the page clears the store inside useBreadcrumbLabel's onUnmounted —
// a stale process name can not leak into the next detail page.

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
  <div class="space-y-2 min-h-full flex flex-col">
    <div v-if="store.loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
    <div v-else-if="store.error" class="text-sm text-red-500">{{ store.error }}</div>

    <template v-else-if="store.currentDefinition">
      <!-- WO-UI-14: compact enterprise header -->
      <div class="flex items-center gap-2">
        <RouterLink :to="{ name: 'process-definitions' }" class="inline-flex items-center justify-center h-9 w-9 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors shrink-0 self-center">
          <ArrowLeft class="h-5 w-5" />
        </RouterLink>
        <div class="flex flex-col gap-0.5 flex-1 min-w-0">
          <!-- Row 1: ← Определения процессов · Key ········································ [Download BPMN] [Upload] [Start] -->
          <div class="flex items-center gap-2">
            <span class="text-sm text-muted-foreground whitespace-nowrap">{{ t('processDefinitions') }}</span>
            <span class="text-foreground/80 text-xs">·</span>
            <span class="text-sm text-muted-foreground/80"><CopyableId :value="store.currentDefinition.key" /></span>
            <span class="flex-1" />
            <Button variant="outline" size="sm" class="h-8 px-3 text-xs" @click="downloadBpmn">
              <Download class="h-4 w-4" />
              {{ t('downloadBpmn') }}
            </Button>
            <Button v-if="canDeployVersion" variant="outline" size="sm" class="h-8 px-3 text-xs" @click="showDeployDialog = true">
              {{ t('uploadNewVersion') }}
            </Button>
            <Button v-if="canStart" size="sm" class="h-8 px-3 text-xs" @click="showStartModal = true">
              {{ t('startProcess') }}
            </Button>
          </div>
          <!-- Row 2: Name · v23 · created — center-aligned -->
          <div class="flex items-center gap-2 flex-wrap -mt-1">
            <span class="text-base font-semibold truncate">{{ store.currentDefinition.name || store.currentDefinition.key }}</span>
            <Select v-if="store.currentVersions.length > 1" :model-value="route.params.id" :display-value="() => `v${store.currentDefinition?.version}`" @update:model-value="(v) => openVersion(v as string)">
              <SelectTrigger class="h-6 px-2 py-0 text-[11px] font-bold rounded-full bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300 border-0 ring-0 focus:ring-0 focus:ring-offset-0 [&>span]:truncate w-auto gap-1">
                <SelectValue placeholder="v?" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem v-for="v in store.currentVersions" :key="v.id" :value="v.id">
                  v{{ v.version }} · {{ formatDateTime(v.createdAt) }}
                </SelectItem>
              </SelectContent>
            </Select>
            <span v-else class="inline-flex items-center text-[11px] font-bold px-2 py-0.5 rounded-full bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300 shrink-0">v{{ store.currentDefinition.version }}</span>
            <span class="inline-flex items-center text-[11px] px-1 py-px rounded-full bg-secondary text-muted-foreground shrink-0">
              <Calendar class="h-3 w-3 mr-0.5" />
              {{ formatDateTime(store.currentDefinition.createdAt) }}
            </span>
          </div>
        </div>
      </div>

      <!-- Tabs: WO-ACL-14 — the shared TabsBar owns the underline (border-b-2 on the
           active button, a single -mb-px on the nav, never on buttons), the keyboard
           rotation and the horizontal scroll on narrow screens. -->
      <div class="border-b border-border">
        <TabsBar :tabs="definitionTabs" :active-id="activeTab" @update:active-id="activeTab = $event" />
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
                <IoMappingTable title-key="inputMappings" :mappings="selectedNode?.properties['inputMappings']" />
                <IoMappingTable title-key="outputMappings" :mappings="selectedNode?.properties['outputMappings']" />
                <div v-if="selectedNode.documentation" class="pt-2 border-t border-border space-y-1">
                  <h4 class="text-xs font-semibold text-muted-foreground uppercase">{{ t('requirements') }}</h4>
                  <p class="text-xs whitespace-pre-wrap break-words">{{ selectedNode.documentation }}</p>
                </div>
                <div v-if="!selectedNodeProps.length && !selectedNodeHasMappings && !selectedNode.documentation" class="pt-2 border-t border-border text-xs text-muted-foreground">{{ t('noConfiguration') }}</div>
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

      <!-- Tab: Structure — WO-ENG-15: clickable tree (nested children +
           boundary events carry their depth), flows section, properties
           panel reusing the shared selectedElement machinery. -->
      <div v-if="activeTab === 'structure'">
        <div class="border border-border rounded-lg bg-card">
          <div class="px-4 py-3 border-b border-border">
            <h2 class="text-lg font-bold">{{ t('bpmnStructure') }}</h2>
          </div>
          <div v-if="store.currentStructure" class="flex">
            <div class="flex-1 min-w-0 p-4">
              <div class="space-y-1">
                <button
                  v-for="row in structureRows"
                  :key="row.node.id"
                  :style="{ paddingLeft: `${row.depth * 16}px` }"
                  class="flex items-center gap-3 text-sm w-full text-left rounded px-2 py-1 hover:bg-muted/50"
                  :class="{ 'bg-muted': selectedElement === row.node.id }"
                  @click="selectedElement = row.node.id"
                >
                  <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-mono bg-muted shrink-0">{{ row.node.type }}</span>
                  <span class="font-mono truncate">{{ row.node.id }}</span>
                  <span v-if="row.node.name" class="text-muted-foreground truncate">— {{ row.node.name }}</span>
                </button>
              </div>
              <p v-if="!store.currentStructure.nodes.length" class="text-sm text-muted-foreground">
                {{ t('noDataYet') }}
              </p>
              <div v-if="store.currentStructure.flows.length" class="mt-4 pt-2 border-t border-border space-y-1">
                <h4 class="text-xs font-semibold text-muted-foreground uppercase">{{ t('flows') }}</h4>
                <button
                  v-for="f in store.currentStructure.flows"
                  :key="f.id"
                  class="flex items-center gap-3 text-sm w-full text-left rounded px-2 py-1 hover:bg-muted/50"
                  :class="{ 'bg-muted': selectedElement === f.id }"
                  @click="selectedElement = f.id"
                >
                  <span class="font-mono truncate">{{ f.id }}</span>
                  <span class="text-xs text-muted-foreground font-mono truncate">{{ f.sourceRef }} → {{ f.targetRef }}</span>
                </button>
              </div>
            </div>
            <div v-if="selectedElement" class="w-80 border-l border-border p-4 space-y-3 bg-muted/30 overflow-y-auto shrink-0">
              <div class="flex items-center justify-between">
                <h3 class="text-sm font-bold">{{ selectedFlow ? t('sequenceFlow') : t('element') }}</h3>
                <button class="text-xs text-muted-foreground hover:text-foreground" @click="selectedElement = null">{{ t('close') }}</button>
              </div>
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
                <IoMappingTable title-key="inputMappings" :mappings="selectedNode?.properties['inputMappings']" />
                <IoMappingTable title-key="outputMappings" :mappings="selectedNode?.properties['outputMappings']" />
                <div v-if="selectedNode.documentation" class="pt-2 border-t border-border space-y-1">
                  <h4 class="text-xs font-semibold text-muted-foreground uppercase">{{ t('requirements') }}</h4>
                  <p class="text-xs whitespace-pre-wrap break-words">{{ selectedNode.documentation }}</p>
                </div>
                <div v-if="!selectedNodeProps.length && !selectedNodeHasMappings && !selectedNode.documentation" class="pt-2 border-t border-border text-xs text-muted-foreground">{{ t('noConfiguration') }}</div>
              </template>
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
            {{ t('noDataYet') }}
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
                  <!-- WO-INT-4 criterion 6: a system account is an integration, not a
                       person — marked so "who has access" is readable at a glance -->
                  <span
                    v-if="m.isSystem"
                    class="ml-2 inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wide bg-violet-100 text-violet-700"
                  >
                    {{ t('systemAccount') }}
                  </span>
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
                  <!-- WO-ACL-14 criteria 14-16: own row has NO remove button and NO
                       role select — leaving yourself is impossible by design (the
                       last-owner protection lives on the server, ACL-2). A disabled
                       red link looked like a working button; a role select in your
                       own row would silently self-demote. The marker mirrors the
                       "(you)" note next to the username. -->
                  <span v-if="m.userId === auth.user?.id" class="text-xs text-muted-foreground">{{ t('you') }}</span>
                  <div v-else class="flex items-center gap-2">
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
          <!-- WO-ACL-14 criterion 9: adding a member opens MemberAddDialog —
               the inline search is gone from the tab. Visible only to
               super-admin/OWNER (canManageMembers). -->
          <div v-if="canManageMembers" class="border-t border-border px-4 py-3">
            <button
              class="px-3 py-1.5 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity"
              @click="showMemberAddDialog = true"
            >
              {{ t('addMember') }}
            </button>
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
            {{ t('noDataYet') }}
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
      v-if="showDeployDialog && store.currentDefinition"
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

    <!-- WO-ACL-14 criteria 9-13: member adding dialog (gated by canManageMembers) -->
    <MemberAddDialog
      :open="showMemberAddDialog"
      :process-key="store.currentDefinition?.key ?? ''"
      :members="members"
      @close="showMemberAddDialog = false"
      @added="onMemberAdded"
    />
  </div>
</template>
