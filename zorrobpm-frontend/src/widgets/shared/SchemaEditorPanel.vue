<script setup lang="ts">
import { ref, onMounted, watch, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from '@/composables/useToast'
import { getSchemaMap, saveElementSchema, getForm, generateSchema, type SchemaMap, type SchemaMapElement, type ArtifactKind, type SchemaField } from '@/services/formService'
import FormEditor from '@/widgets/forms/FormEditor.vue'
import JsonSchemaEditor from '@/widgets/forms/JsonSchemaEditor.vue'
import SchemaFieldBuilder from '@/widgets/shared/SchemaFieldBuilder.vue'

const props = defineProps<{ processKey: string }>()

const { t } = useI18n()
const toast = useToast()

const schemaMap = ref<SchemaMap | null>(null)
const selectedElement = ref<SchemaMapElement | null>(null)
const selectedKind = ref<ArtifactKind>('FORM_JS')
const jsonSchemaContent = ref('')
const formEditorRef = ref<InstanceType<typeof FormEditor> | null>(null)
const jsonEditorRef = ref<InstanceType<typeof JsonSchemaEditor> | null>(null)
const loadingMap = ref(false)
const loadingSchema = ref(false)
const saving = ref(false)

// Constructor/JSON mode for VARIABLE_SCHEMA
const editorMode = ref<'constructor' | 'json'>('constructor')
const schemaFields = ref<SchemaField[]>([])
const hasXBuilder = ref(false)

async function loadSchemaMap() {
  loadingMap.value = true
  try {
    schemaMap.value = await getSchemaMap(props.processKey)
  } catch {
    toast.error('Failed to load schema map')
  } finally {
    loadingMap.value = false
  }
}

onMounted(loadSchemaMap)
watch(() => props.processKey, loadSchemaMap)

async function selectElement(el: SchemaMapElement) {
  selectedElement.value = el
  if (el.kind) {
    selectedKind.value = el.kind
  } else {
    selectedKind.value = 'FORM_JS'
  }
  jsonSchemaContent.value = ''
  schemaFields.value = []
  hasXBuilder.value = false
  editorMode.value = 'constructor'

  if (el.artifactKey) {
    loadingSchema.value = true
    let formSchema: string | null = null
    try {
      const form = await getForm(el.artifactKey)
      if (selectedKind.value === 'VARIABLE_SCHEMA' && form.schema) {
        jsonSchemaContent.value = form.schema
        // Try to extract x-builder fields for round-trip
        try {
          const parsed = JSON.parse(form.schema)
          if (parsed['x-builder'] && Array.isArray(parsed['x-builder'].fields)) {
            schemaFields.value = parsed['x-builder'].fields
            hasXBuilder.value = true
            editorMode.value = 'constructor'
          } else {
            hasXBuilder.value = false
            editorMode.value = 'json'
          }
        } catch {
          hasXBuilder.value = false
          editorMode.value = 'json'
        }
      } else if (selectedKind.value === 'FORM_JS' && form.schema) {
        // Store schema; importSchema called after FormEditor mounts (finally + nextTick)
        formSchema = form.schema
      }
    } catch {
      toast.error(t('failedToLoadSchema'))
    } finally {
      loadingSchema.value = false
    }
    // FormEditor renders after loadingSchema = false (v-else-if).
    // Wait a tick for Vue to mount FormEditor, then import the schema.
    if (selectedKind.value === 'FORM_JS' && formSchema) {
      await nextTick()
      if (formEditorRef.value) {
        await formEditorRef.value.importSchema(JSON.parse(formSchema))
      }
    }
  }
}

function deselectElement() {
  selectedElement.value = null
}

async function saveElement() {
  if (!selectedElement.value) return
  if (selectedElement.value.hasExternalReference === false && selectedElement.value.type === 'USER_TASK') {
    toast.error(t('noExternalReference'))
    return
  }

  let schema: string
  if (selectedKind.value === 'FORM_JS') {
    if (!formEditorRef.value) return
    schema = (formEditorRef.value as any).saveSchema()
  } else if (selectedKind.value === 'VARIABLE_SCHEMA' && editorMode.value === 'constructor') {
    // Generate schema from fields via backend VM-13
    if (schemaFields.value.length === 0) {
      toast.error(t('noFields'))
      return
    }
    try {
      schema = await generateSchema(schemaFields.value)
    } catch (e: any) {
      const msg = e?.response?.data?.message || e.message
      toast.error(msg || t('errorGeneric'))
      return
    }
  } else {
    if (!jsonEditorRef.value) return
    schema = (jsonEditorRef.value as any).saveSchema()
  }

  saving.value = true
  try {
    const updated = await saveElementSchema(
      props.processKey,
      selectedElement.value.elementId,
      selectedKind.value,
      schema,
    )
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

defineExpose({ loadSchemaMap })
</script>

<template>
  <div class="space-y-4">
    <div v-if="loadingMap" class="text-sm text-muted-foreground">{{ t('loading') }}</div>

    <template v-else-if="schemaMap">
      <div v-if="!selectedElement" class="space-y-2">
        <div
          v-for="el in schemaMap.elements"
          :key="el.elementId"
          class="flex items-center justify-between px-4 py-3 border border-border rounded-md hover:bg-muted/50 cursor-pointer"
          @click="selectElement(el)"
        >
          <div class="flex items-center gap-3">
            <span class="font-mono text-sm">{{ el.elementId }}</span>
            <span class="text-xs text-muted-foreground">{{ el.name }}</span>
            <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium" :class="statusBadge(el).class">{{ statusBadge(el).text }}</span>
            <span v-if="el.artifactVersion" class="text-xs text-muted-foreground">v{{ el.artifactVersion }}</span>
            <span v-if="el.shared" class="text-xs text-orange-600 font-medium">{{ t('shared') }}</span>
          </div>
          <span class="text-primary text-xs">{{ t('edit') }}</span>
        </div>
      </div>

      <div v-if="selectedElement" class="space-y-4">
        <div class="flex items-center gap-3 mb-2">
          <span class="font-mono text-sm font-bold">{{ selectedElement.elementId }}</span>
          <span class="text-xs text-muted-foreground">{{ selectedElement.name }}</span>
          <button class="text-sm text-primary hover:underline ml-auto" @click="deselectElement">{{ t('back') }}</button>
        </div>

        <div v-if="selectedElement.type === 'USER_TASK' && !selectedElement.hasExternalReference"
             class="p-3 bg-yellow-50 border border-yellow-200 rounded-md text-sm text-yellow-800">
          {{ t('noExternalReferenceHint') }}
        </div>

        <div v-if="selectedElement.type !== 'USER_TASK' || selectedElement.hasExternalReference" class="mb-4">
          <label class="block text-sm font-medium mb-1">{{ t('kind') }}</label>
          <select v-model="selectedKind" class="w-full max-w-xs px-3 py-2 border border-border rounded-md bg-background text-sm">
            <option value="FORM_JS">{{ t('kindFormJs') }}</option>
            <option value="VARIABLE_SCHEMA">{{ t('kindVariableSchema') }}</option>
          </select>
        </div>

        <!-- VARIABLE_SCHEMA: Constructor/JSON mode toggle -->
        <div v-if="selectedKind === 'VARIABLE_SCHEMA' && (selectedElement.type !== 'USER_TASK' || selectedElement.hasExternalReference)"
             class="flex gap-2 mb-2">
          <button
            class="px-3 py-1 text-xs rounded-md border"
            :class="editorMode === 'constructor' ? 'bg-primary text-primary-foreground' : 'bg-background text-foreground border-border'"
            @click="editorMode = 'constructor'"
          >{{ t('constructor') }}</button>
          <button
            class="px-3 py-1 text-xs rounded-md border"
            :class="editorMode === 'json' ? 'bg-primary text-primary-foreground' : 'bg-background text-foreground border-border'"
            @click="editorMode = 'json'"
          >{{ t('jsonMode') }}</button>
        </div>

        <!-- No x-builder hint -->
        <div v-if="selectedKind === 'VARIABLE_SCHEMA' && !hasXBuilder && editorMode === 'constructor' && jsonSchemaContent"
             class="p-2 bg-yellow-50 border border-yellow-200 rounded-md text-xs text-yellow-800">
          {{ t('constructorUnavailable') }}
        </div>

        <div v-if="selectedElement.type !== 'USER_TASK' || selectedElement.hasExternalReference">
          <div v-if="loadingSchema" class="text-sm text-muted-foreground py-4">{{ t('loadingSchema') }}</div>
          <FormEditor v-else-if="selectedKind === 'FORM_JS'" ref="formEditorRef" style="height: 400px;" />
          <template v-else-if="selectedKind === 'VARIABLE_SCHEMA'">
            <SchemaFieldBuilder
              v-if="editorMode === 'constructor'"
              v-model="schemaFields"
            />
            <JsonSchemaEditor
              v-else
              ref="jsonEditorRef"
              v-model="jsonSchemaContent"
              style="height: 400px;"
            />
          </template>
        </div>

        <button
          v-if="selectedElement.type !== 'USER_TASK' || selectedElement.hasExternalReference"
          :disabled="saving"
          class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 text-sm disabled:opacity-50"
          @click="saveElement"
        >
          {{ saving ? t('saving') : t('saveSchema') }}
        </button>
      </div>
    </template>
  </div>
</template>
