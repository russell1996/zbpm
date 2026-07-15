<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from '@/composables/useToast'
import { listForms, deployForm, type FormSummary, type ArtifactKind } from '@/services/formService'
import FormEditor from '@/widgets/forms/FormEditor.vue'
import JsonSchemaEditor from '@/widgets/forms/JsonSchemaEditor.vue'

const { t } = useI18n()
const toast = useToast()

const forms = ref<FormSummary[]>([])
const loading = ref(true)
const editing = ref(false)
const editingKey = ref('')
const originalKey = ref('')
const selectedKind = ref<ArtifactKind>('FORM_JS')
const jsonSchemaContent = ref('')
const formEditorRef = ref<InstanceType<typeof FormEditor> | null>(null)
const jsonEditorRef = ref<InstanceType<typeof JsonSchemaEditor> | null>(null)

onMounted(async () => {
  try {
    forms.value = await listForms()
  } catch {
    toast.error('Failed to load forms')
  } finally {
    loading.value = false
  }
})

function startCreate() {
  editing.value = true
  editingKey.value = ''
  originalKey.value = ''
  selectedKind.value = 'FORM_JS'
}

function startEdit(form: FormSummary) {
  editing.value = true
  editingKey.value = form.key
  originalKey.value = form.key
  selectedKind.value = form.kind || 'FORM_JS'
}

function cancelEdit() {
  editing.value = false
  editingKey.value = ''
  originalKey.value = ''
  selectedKind.value = 'FORM_JS'
}

async function saveForm() {
  if (!editingKey.value) {
    toast.error(t('formKeyRequired'))
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

  try {
    await deployForm(editingKey.value, schema, selectedKind.value)
    toast.success(t('formSaved'))
    editing.value = false
    forms.value = await listForms()
  } catch {
    toast.error('Failed to save form')
  }
}
</script>

<template>
  <div class="space-y-6">
    <div v-if="loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>

    <template v-else>
      <div class="flex items-center justify-between">
        <h1 class="text-2xl font-bold">{{ t('forms') }}</h1>
        <button
          v-if="!editing"
          class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 text-sm"
          @click="startCreate"
        >
          {{ t('createForm') }}
        </button>
      </div>

      <!-- Form list -->
      <div v-if="!editing && forms.length" class="border border-border rounded-lg overflow-hidden bg-card">
        <table class="w-full text-sm">
          <thead class="bg-muted">
            <tr>
              <th class="px-4 py-3 text-left font-medium">{{ t('key') }}</th>
              <th class="px-4 py-3 text-left font-medium">{{ t('kind') }}</th>
              <th class="px-4 py-3 text-left font-medium">{{ t('version') }}</th>
              <th class="px-4 py-3 text-right"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="form in forms" :key="form.key" class="border-t border-border hover:bg-muted/50">
              <td class="px-4 py-3 font-mono">{{ form.key }}</td>
              <td class="px-4 py-3">
                <span
                  class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium"
                  :class="form.kind === 'FORM_JS' ? 'bg-blue-100 text-blue-800' : 'bg-green-100 text-green-800'"
                >
                  {{ form.kind }}
                </span>
              </td>
              <td class="px-4 py-3 text-muted-foreground">v{{ form.version }}</td>
              <td class="px-4 py-3 text-right">
                <button class="text-primary text-xs hover:underline" @click="startEdit(form)">{{ t('edit') }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else-if="!editing" class="text-sm text-muted-foreground">{{ t('noForms') }}</div>

      <!-- Form editor -->
      <div v-if="editing" class="border border-border rounded-lg p-4 bg-card">
        <div class="flex items-center justify-between mb-4">
          <h2 class="text-lg font-bold">{{ editingKey ? `${t('edit')}: ${editingKey}` : t('newForm') }}</h2>
          <div class="flex gap-2">
            <button class="px-3 py-1 text-sm border border-border rounded-md hover:bg-muted" @click="cancelEdit">{{ t('cancel') }}</button>
            <button class="px-3 py-1 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90" @click="saveForm">{{ t('save') }}</button>
          </div>
        </div>

        <!-- Key input (editable when creating, read-only when editing) -->
        <div class="mb-4">
          <label class="block text-sm font-medium mb-1">{{ t('formKey') }}</label>
          <input
            v-model="editingKey"
            :readonly="originalKey !== ''"
            :placeholder="t('formKeyPlaceholder')"
            class="w-full max-w-xs px-3 py-2 border border-border rounded-md bg-background text-sm"
          />
        </div>

        <!-- Kind selector (only for new forms) -->
        <div v-if="!originalKey" class="mb-4">
          <label class="block text-sm font-medium mb-1">{{ t('kind') }}</label>
          <select
            v-model="selectedKind"
            class="w-full max-w-xs px-3 py-2 border border-border rounded-md bg-background text-sm"
          >
            <option value="FORM_JS">{{ t('kindFormJs') }}</option>
            <option value="VARIABLE_SCHEMA">{{ t('kindVariableSchema') }}</option>
          </select>
        </div>

        <!-- Editor branch by kind -->
        <FormEditor v-if="selectedKind === 'FORM_JS'" ref="formEditorRef" style="height: 400px;" />
        <JsonSchemaEditor v-else ref="jsonEditorRef" v-model="jsonSchemaContent" style="height: 400px;" />
      </div>
    </template>
  </div>
</template>
