<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from '@/composables/useToast'
import { getProcessDefinitions, getProcessDefinitionStructure } from '@/services/processService'
import type { ProcessDefinition, BpmnNode } from '@/types/api'
import { listForms, createElementBinding, type FormSummary } from '@/services/formService'

const { t } = useI18n()
const toast = useToast()

const definitions = ref<ProcessDefinition[]>([])
const selectedDef = ref<ProcessDefinition | null>(null)
const startEvents = ref<BpmnNode[]>([])
const forms = ref<FormSummary[]>([])
const selectedElement = ref('')
const selectedArtifact = ref('')
const loadingDefs = ref(true)
const loadingStructure = ref(false)
const submitting = ref(false)

onMounted(async () => {
  try {
    const result = await getProcessDefinitions({ latestVersionOnly: true, pageSize: 100 })
    definitions.value = result.data
    forms.value = await listForms()
  } catch {
    toast.error('Failed to load data')
  } finally {
    loadingDefs.value = false
  }
})

async function onSelectDef(def: ProcessDefinition) {
  selectedDef.value = def
  selectedElement.value = ''
  selectedArtifact.value = ''
  startEvents.value = []
  loadingStructure.value = true
  try {
    const structure = await getProcessDefinitionStructure(def.id)
    startEvents.value = structure.nodes.filter(n => n.type === 'START_EVENT')
  } catch {
    toast.error('Failed to load structure')
  } finally {
    loadingStructure.value = false
  }
}

const canSubmit = computed(() =>
  selectedElement.value && selectedArtifact.value && !submitting.value
)

async function bindArtifact() {
  if (!selectedDef.value || !canSubmit.value) return
  submitting.value = true
  try {
    await createElementBinding(selectedDef.value.key, selectedElement.value, selectedArtifact.value)
    toast.success(t('bindingCreated'))
    selectedElement.value = ''
    selectedArtifact.value = ''
  } catch (e: any) {
    const msg = e?.response?.data?.message || e.message
    if (e?.response?.status === 403) {
      toast.error(t('error403'))
    } else {
      toast.error(msg || t('errorGeneric'))
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="space-y-6">
    <h1 class="text-2xl font-bold">{{ t('startBindings') }}</h1>

    <div v-if="loadingDefs" class="text-sm text-muted-foreground">{{ t('loading') }}</div>

    <template v-else>
      <!-- Step 1: Select process definition -->
      <div class="border border-border rounded-lg p-4 bg-card">
        <h2 class="text-lg font-bold mb-3">{{ t('selectDefinition') }}</h2>
        <div v-if="definitions.length" class="max-h-60 overflow-y-auto border border-border rounded-md">
          <div
            v-for="def in definitions"
            :key="def.id"
            class="px-4 py-2 cursor-pointer hover:bg-muted text-sm border-b border-border last:border-b-0"
            :class="{ 'bg-primary/10 font-medium': selectedDef?.id === def.id }"
            @click="onSelectDef(def)"
          >
            {{ def.name || def.key }} <span class="text-muted-foreground">v{{ def.version }}</span>
          </div>
        </div>
        <div v-else class="text-sm text-muted-foreground">{{ t('noDefinitions') }}</div>
      </div>

      <!-- Step 2: Select start event + artifact -->
      <div v-if="selectedDef" class="border border-border rounded-lg p-4 bg-card">
        <h2 class="text-lg font-bold mb-3">{{ t('bindArtifact') }}</h2>

        <div v-if="loadingStructure" class="text-sm text-muted-foreground">{{ t('loading') }}</div>

        <template v-else>
          <div v-if="startEvents.length" class="space-y-4">
            <div>
              <label class="block text-sm font-medium mb-1">{{ t('startEvent') }}</label>
              <select
                v-model="selectedElement"
                class="w-full max-w-md px-3 py-2 border border-border rounded-md bg-background text-sm"
              >
                <option value="">{{ t('selectElement') }}</option>
                <option v-for="node in startEvents" :key="node.id" :value="node.id">
                  {{ node.name || node.id }}
                </option>
              </select>
            </div>

            <div>
              <label class="block text-sm font-medium mb-1">{{ t('artifact') }}</label>
              <select
                v-model="selectedArtifact"
                class="w-full max-w-md px-3 py-2 border border-border rounded-md bg-background text-sm"
              >
                <option value="">{{ t('selectArtifact') }}</option>
                <option v-for="form in forms" :key="form.key" :value="form.key">
                  {{ form.key }} ({{ form.kind }}, v{{ form.version }})
                </option>
              </select>
            </div>

            <button
              :disabled="!canSubmit"
              class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 text-sm disabled:opacity-50"
              @click="bindArtifact"
            >
              {{ submitting ? t('binding') : t('bind') }}
            </button>
          </div>
          <div v-else class="text-sm text-muted-foreground">{{ t('noStartEvents') }}</div>
        </template>
      </div>
    </template>
  </div>
</template>
