<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from '@/composables/useToast'
import { getProcessDefinitions, getProcessDefinitionStructure, type ProcessDefinition, type BpmnNode } from '@/services/processService'
import { getSchemaMap, saveElementSchema, type SchemaMap, type SchemaMapElement, type ArtifactKind } from '@/services/formService'
import FormEditor from '@/widgets/forms/FormEditor.vue'
import JsonSchemaEditor from '@/widgets/forms/JsonSchemaEditor.vue'

const { t } = useI18n()
const toast = useToast()

const definitions = ref<ProcessDefinition[]>([])
const selectedDef = ref<ProcessDefinition | null>(null)
const schemaMap = ref<SchemaMap | null>(null)
const selectedElement = ref<SchemaMapElement | null>(null)
const selectedKind = ref<ArtifactKind>('FORM_JS')
const jsonSchemaContent = ref('')
const formEditorRef = ref<InstanceType<typeof FormEditor> | null>(null)
const jsonEditorRef = ref<InstanceType<typeof JsonSchemaEditor> | null>(null)
const loadingDefs = ref(true)
const loadingMap = ref(false)
const saving = ref(false)

onMounted(async () => {
  try {
    const result = await getProcessDefinitions({ latestVersionOnly: true, pageSize: 100 })
    definitions.value = result.data
  } catch {
    toast.error('Failed to load processes')
  } finally {
    loadingDefs.value = false
  }
})

async function onSelectDef(def: ProcessDefinition) {
  selectedDef.value = def
  selectedElement.value = null
  loadingMap.value = true
  try {
    schemaMap.value = await getSchemaMap(def.key)
  } catch {
    toast.error('Failed to load schema map')
  } finally {
    loadingMap.value = false
  }
}

function selectElement(el: SchemaMapElement) {
  selectedElement.value = el
  if (el.kind) {
    selectedKind.value = el.kind
  } else {
    selectedKind.value = 'FORM_JS'
  }
  jsonSchemaContent.value = ''
}

function deselectElement() {
  selectedElement.value = null
}

async function saveElement() {
  if (!selectedDef.value || !selectedElement.value) return
  if (selectedElement.value.hasExternalReference === false && selectedElement.value.type === 'USER_TASK') {
    toast.error(t('noExternalReference'))
    return
  }

  let schema: string
  if (selectedKind.value === 'FORM_JS') {
    if (!formEditorRef.value) return
    schema = (formEditorRef.value as any).saveSchema()
  } else {
    if (!jsonEditorRef.value) return
    schema = (jsonEditorRef.value as any).saveSchema()
  }

  saving.value = true
  try {
    const updated = await saveElementSchema(
      selectedDef.value.key,
      selectedElement.value.elementId,
      selectedKind.value,
      schema,
    )
    // Update the element in the list
    if (schemaMap.value) {
      const idx = schemaMap.value.elements.findIndex(e => e.elementId === updated.elementId)
      if (idx >= 0) schemaMap.value.elements[idx] = updated
    }
    selectedElement.value = updated
    toast.success(t('schemaSaved'))
  } catch (e: any) {
    const msg = e?.response?.data?.message || e.message
    if (e?.response?.status === 403) {
      toast.error(t('error403'))
    } else {
      toast.error(msg || t('errorGeneric'))
    }
  } finally {
    saving.value = false
  }
}

function statusBadge(el: SchemaMapElement) {
  if (!el.artifactKey) return { text: t('noSchema'), class: 'bg-gray-100 text-gray-600' }
  if (el.kind === 'FORM_JS') return { text: 'FORM_JS', class: 'bg-blue-100 text-blue-800' }
  if (el.kind === 'VARIABLE_SCHEMA') return { text: 'VARIABLE_SCHEMA', class: 'bg-green-100 text-green-800' }
  return { text: el.kind || '?', class: 'bg-gray-100 text-gray-600' }
}
</script>

<template>
  <div class="space-y-6">
    <h1 class="text-2xl font-bold">{{ t('processSchemas') }}</h1>

    <div v-if="loadingDefs" class="text-sm text-muted-foreground">{{ t('loading') }}</div>

    <template v-else>
      <!-- Step 1: Select process -->
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

      <!-- Step 2: Elements from schema-map -->
      <div v-if="schemaMap" class="border border-border rounded-lg p-4 bg-card">
        <div class="flex items-center justify-between mb-3">
          <h2 class="text-lg font-bold">{{ t('elements') }} <span class="text-sm font-normal text-muted-foreground">v{{ schemaMap.version }}</span></h2>
          <button v-if="selectedElement" class="text-sm text-primary hover:underline" @click="deselectElement">
            {{ t('back') }}
          </button>
        </div>

        <div v-if="loadingMap" class="text-sm text-muted-foreground">{{ t('loading') }}</div>

        <!-- Element list -->
        <div v-if="!selectedElement && !loadingMap" class="space-y-2">
          <div
            v-for="el in schemaMap.elements"
            :key="el.elementId"
            class="flex items-center justify-between px-4 py-3 border border-border rounded-md hover:bg-muted/50 cursor-pointer"
            @click="selectElement(el)"
          >
            <div class="flex items-center gap-3">
              <span class="font-mono text-sm">{{ el.elementId }}</span>
              <span class="text-xs text-muted-foreground">{{ el.name }}</span>
              <span
                class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium"
                :class="statusBadge(el).class"
              >{{ statusBadge(el).text }}</span>
              <span v-if="el.artifactVersion" class="text-xs text-muted-foreground">v{{ el.artifactVersion }}</span>
              <span v-if="el.shared" class="text-xs text-orange-600 font-medium">{{ t('shared') }}</span>
            </div>
            <span class="text-primary text-xs">{{ t('edit') }}</span>
          </div>
        </div>

        <!-- Element editor -->
        <div v-if="selectedElement" class="space-y-4">
          <div class="flex items-center gap-3 mb-2">
            <span class="font-mono text-sm font-bold">{{ selectedElement.elementId }}</span>
            <span class="text-xs text-muted-foreground">{{ selectedElement.name }}</span>
          </div>

          <!-- No external reference warning -->
          <div v-if="selectedElement.type === 'USER_TASK' && !selectedElement.hasExternalReference"
               class="p-3 bg-yellow-50 border border-yellow-200 rounded-md text-sm text-yellow-800">
            {{ t('noExternalReferenceHint') }}
          </div>

          <!-- Kind selector -->
          <div v-if="selectedElement.type !== 'USER_TASK' || selectedElement.hasExternalReference" class="mb-4">
            <label class="block text-sm font-medium mb-1">{{ t('kind') }}</label>
            <select
              v-model="selectedKind"
              class="w-full max-w-xs px-3 py-2 border border-border rounded-md bg-background text-sm"
            >
              <option value="FORM_JS">{{ t('kindFormJs') }}</option>
              <option value="VARIABLE_SCHEMA">{{ t('kindVariableSchema') }}</option>
            </select>
          </div>

          <!-- Editor -->
          <div v-if="selectedElement.type !== 'USER_TASK' || selectedElement.hasExternalReference">
            <FormEditor v-if="selectedKind === 'FORM_JS'" ref="formEditorRef" style="height: 400px;" />
            <JsonSchemaEditor v-else ref="jsonEditorRef" v-model="jsonSchemaContent" style="height: 400px;" />
          </div>

          <!-- Save button -->
          <button
            v-if="selectedElement.type !== 'USER_TASK' || selectedElement.hasExternalReference"
            :disabled="saving"
            class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 text-sm disabled:opacity-50"
            @click="saveElement"
          >
            {{ saving ? t('saving') : t('saveSchema') }}
          </button>
        </div>
      </div>
    </template>
  </div>
</template>
