<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useIncidentStore } from '@/stores/incident'
import { useToast } from '@/composables/useToast'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useBreadcrumbLabel } from '@/composables/useBreadcrumbLabel'
import type { ProcessVariable } from '@/types/api'
import CopyableId from '@/widgets/shared/CopyableId.vue'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'
import { ArrowLeft } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'

const route = useRoute()
const router = useRouter()
const store = useIncidentStore()
const toast = useToast()
const { t } = useI18n()
const { formatDateTime } = useDateFormat()

// WO-ACL-16 criterion 6: breadcrumb leaf — process name and element (the ACL-15 debt).
useBreadcrumbLabel(() => {
  const i = store.currentIncident
  if (!i) return null
  const parts = [i.processName, i.elementName || i.bpmnElementId].filter(Boolean)
  return parts.length ? parts.join(' · ') : null
})

function goToInstance() {
  const id = store.currentIncident?.processInstanceId
  if (id) router.push(`/processes/instances/${id}`)
}

const showResolveModal = ref(false)
const resolveVars = ref<{ name: string; type: string; value: string }[]>([])
const newVarName = ref('')
const newVarType = ref('STRING')
const newVarValue = ref('')
const jsonError = ref('')

function addVariable() {
  jsonError.value = ''
  if (newVarName.value) {
    if (newVarType.value === 'JSON') {
      try {
        JSON.parse(newVarValue.value)
      } catch {
        jsonError.value = t('invalidJson')
        return
      }
    }
    resolveVars.value.push({ name: newVarName.value, type: newVarType.value, value: newVarValue.value })
    newVarName.value = ''
    newVarValue.value = ''
  }
}

function removeVariable(index: number) {
  resolveVars.value.splice(index, 1)
}

async function resolve() {
  if (jsonError.value) return
  if (newVarName.value) addVariable()
  if (jsonError.value) return
  const variables: ProcessVariable[] = resolveVars.value.map((v) => ({
    name: v.name,
    type: v.type as ProcessVariable['type'],
    value: v.value,
  }))
  await store.resolveIncident(route.params.id as string, variables)
  if (!store.error) {
    toast.success(t('incidentResolved'))
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
  <div class="space-y-4">
    <div v-if="store.loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
    <div v-else-if="store.error" class="text-sm text-red-500">{{ store.error }}</div>

    <template v-else-if="store.currentIncident">
      <!-- WO-UI-14: compact header -->
      <div class="flex items-center gap-2">
        <RouterLink :to="{ name: 'incidents' }" class="inline-flex items-center justify-center h-9 w-9 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors shrink-0 self-center">
          <ArrowLeft class="h-5 w-5" />
        </RouterLink>
        <div class="flex flex-col gap-0.5 flex-1 min-w-0">
          <!-- Row 1: ← Инциденты · ID ········································ [Resolve] -->
          <div class="flex items-center gap-2">
            <span class="text-sm text-muted-foreground whitespace-nowrap">{{ t('incidents') }}</span>
            <span class="text-foreground/80 text-xs">·</span>
            <span class="text-sm text-muted-foreground/80"><CopyableId :value="store.currentIncident.id" /></span>
            <span class="flex-1" />
            <Button v-if="!store.currentIncident.completedAt" size="sm" class="h-8 px-3 text-xs" @click="showResolveModal = true">
              {{ t('resolveIncident') }}
            </Button>
          </div>
          <!-- Row 2: Element · Status · Process — center-aligned -->
          <div class="flex items-center gap-2 flex-wrap -mt-1">
            <span class="text-base font-semibold truncate">{{ store.currentIncident.elementName || store.currentIncident.bpmnElementId || t('incident') }}</span>
            <Badge
              variant="secondary"
              class="shrink-0 text-[11px] px-1.5 py-px rounded-full"
              :class="store.currentIncident.completedAt
                ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                : 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400'"
            >
              {{ store.currentIncident.completedAt ? t('resolved') : t('open') }}
            </Badge>
            <span class="text-muted-foreground/60 text-xs">{{ store.currentIncident.processName || '—' }}</span>
          </div>
        </div>
      </div>

<div class="grid grid-cols-2 gap-4 text-sm">
        <div>
          <span class="text-muted-foreground">{{ t('status') }}:</span>
<StatusBadge :status="store.currentIncident.completedAt ? 'RESOLVED' : 'OPEN'" />
        </div>
        <div>
          <span class="text-muted-foreground">{{ t('process') }}:</span>
          <span>{{ store.currentIncident.processName || '—' }}</span>
        </div>
        <div>
          <span class="text-muted-foreground">{{ t('element') }}:</span>
          <span class="font-mono">{{ store.currentIncident.elementName || store.currentIncident.bpmnElementId || '—' }}</span>
        </div>
        <div>
          <span class="text-muted-foreground">{{ t('processInstance') }}:</span>
          <button
            v-if="store.currentIncident.processInstanceId"
            class="text-primary hover:underline font-mono"
            @click="goToInstance"
          >
            {{ store.currentIncident.processInstanceId.slice(0, 8) }}
          </button>
          <span v-else>—</span>
        </div>
        <div class="col-span-2"><span class="text-muted-foreground">{{ t('created') }}:</span> {{ formatDateTime(store.currentIncident.createdAt) }}</div>
        <div v-if="store.currentIncident.completedAt" class="col-span-2">
          <span class="text-muted-foreground">{{ t('resolved') }}:</span> {{ formatDateTime(store.currentIncident.completedAt) }}
        </div>
      </div>

      <div class="border border-border rounded-lg p-4 bg-card">
        <h2 class="text-lg font-bold mb-2">{{ t('message') }}</h2>
        <pre class="text-sm whitespace-pre-wrap font-mono bg-muted p-3 rounded">{{ store.currentIncident.message }}</pre>
      </div>
    </template>

    <div
      v-if="showResolveModal"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="showResolveModal = false"
    >
      <div class="bg-card rounded-lg shadow-lg w-full max-w-md p-6 space-y-4">
        <h2 class="text-lg font-bold">{{ t('resolveIncident') }}</h2>
        <p class="text-sm text-muted-foreground">{{ t('resolveIncidentHint') }}</p>
        <div class="space-y-3">
          <div v-for="(v, i) in resolveVars" :key="i" class="flex items-center gap-2 text-sm">
            <span class="font-mono">{{ v.name }}</span>
            <span class="text-muted-foreground">({{ v.type }})</span>
            <span>= {{ v.value }}</span>
            <button class="text-red-500 hover:underline ml-auto" @click="removeVariable(i)">{{ t('remove') }}</button>
          </div>
          <div class="flex items-center gap-2">
            <input v-model="newVarName" :placeholder="t('name')" class="px-2 py-1 border border-input rounded text-sm w-24" />
            <select v-model="newVarType" class="px-2 py-1 border border-input rounded text-sm">
              <option>STRING</option>
              <option>UUID</option>
              <option>LONG</option>
              <option>DOUBLE</option>
              <option>BOOLEAN</option>
              <option>JSON</option>
            </select>
            <input v-if="newVarType !== 'JSON'" v-model="newVarValue" :placeholder="t('value')" class="px-2 py-1 border border-input rounded text-sm flex-1" />
            <button class="text-sm text-primary hover:underline" @click="addVariable">{{ t('add') }}</button>
          </div>
          <textarea v-if="newVarType === 'JSON'" v-model="newVarValue" :placeholder="t('jsonPlaceholder')" class="w-full px-2 py-1 border border-input rounded text-sm font-mono" rows="3"></textarea>
          <p v-if="jsonError" class="text-xs text-red-500">{{ jsonError }}</p>
        </div>
        <div class="flex justify-end gap-2 pt-2">
          <button class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted" @click="showResolveModal = false">{{ t('cancel') }}</button>
          <button class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90" @click="resolve">{{ t('resolve') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
