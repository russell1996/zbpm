<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from '@/composables/useToast'
import { listForms, deployForm, type FormSummary } from '@/services/formService'
import FormEditor from '@/widgets/forms/FormEditor.vue'

const { t } = useI18n()
const toast = useToast()

const forms = ref<FormSummary[]>([])
const loading = ref(true)
const editing = ref(false)
const editingKey = ref('')
const editorRef = ref<InstanceType<typeof FormEditor> | null>(null)

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
}

function startEdit(form: FormSummary) {
  editing.value = true
  editingKey.value = form.key
}

function cancelEdit() {
  editing.value = false
  editingKey.value = ''
}

async function saveForm() {
  if (!editorRef.value || !editingKey.value) return
  try {
    const schema = (editorRef.value as any).saveSchema()
    await deployForm(editingKey.value, schema)
    toast.success('Form saved')
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
        <h1 class="text-2xl font-bold">Forms</h1>
        <button
          v-if="!editing"
          class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 text-sm"
          @click="startCreate"
        >
          Create Form
        </button>
      </div>

      <!-- Form list -->
      <div v-if="!editing && forms.length" class="border border-border rounded-lg overflow-hidden bg-card">
        <table class="w-full text-sm">
          <thead class="bg-muted">
            <tr>
              <th class="px-4 py-3 text-left font-medium">Key</th>
              <th class="px-4 py-3 text-left font-medium">Version</th>
              <th class="px-4 py-3 text-right"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="form in forms" :key="form.key" class="border-t border-border hover:bg-muted/50">
              <td class="px-4 py-3 font-mono">{{ form.key }}</td>
              <td class="px-4 py-3 text-muted-foreground">v{{ form.version }}</td>
              <td class="px-4 py-3 text-right">
                <button class="text-primary text-xs hover:underline" @click="startEdit(form)">Edit</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else-if="!editing" class="text-sm text-muted-foreground">No forms deployed yet.</div>

      <!-- Form editor -->
      <div v-if="editing" class="border border-border rounded-lg p-4 bg-card">
        <div class="flex items-center justify-between mb-4">
          <h2 class="text-lg font-bold">{{ editingKey ? 'Edit: ' + editingKey : 'New Form' }}</h2>
          <div class="flex gap-2">
            <button class="px-3 py-1 text-sm border border-border rounded-md hover:bg-muted" @click="cancelEdit">Cancel</button>
            <button class="px-3 py-1 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90" @click="saveForm">Save</button>
          </div>
        </div>
        <FormEditor ref="editorRef" style="height: 400px;" />
      </div>
    </template>
  </div>
</template>
