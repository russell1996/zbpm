<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useTaskStore } from '@/stores/task'
import { useToast } from '@/composables/useToast'
import { getTaskForm, type TaskFormResponse } from '@/services/formService'
import type { ProcessVariable } from '@/types/api'
import { dataToVariables } from '@/shared/lib/formMapping'
import CopyableId from '@/widgets/shared/CopyableId.vue'
import FormRenderer from '@/widgets/forms/FormRenderer.vue'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'

const route = useRoute()
const router = useRouter()
const store = useTaskStore()
const toast = useToast()
const { t } = useI18n()
const { formatDateTime } = useDateFormat()

const editableVars = ref<{ name: string; type: string; value: string }[]>([])
const formResponse = ref<TaskFormResponse | null>(null)
const formRef = ref<InstanceType<typeof FormRenderer> | null>(null)
const formErrors = ref<Record<string, string> | null>(null)

async function loadForm() {
  if (!store.currentTask) return
  try {
    formResponse.value = await getTaskForm(store.currentTask.id)
  } catch {
    formResponse.value = { type: 'none' }
  }
}

async function complete() {
  if (formResponse.value?.type === 'embedded' && formRef.value) {
    // Form-js submit → collect data → complete
    formErrors.value = null
    formRef.value.submit()
    return // submit handler will call doComplete
  }
  // Legacy: direct variable editing
  await doComplete(editableVars.value.map((v) => ({
    name: v.name,
    type: v.type as ProcessVariable['type'],
    value: v.value,
  })))
}

async function doComplete(variables: ProcessVariable[]) {
  await store.completeUserTask(route.params.id as string, variables)
  if (!store.error) {
    toast.success('Task completed', {
      action: { label: 'Go to Tasks', onClick: () => router.push('/tasks') },
    })
  } else {
    toast.error(store.error)
  }
}

function onFormSubmit(data: Record<string, string>) {
  doComplete(dataToVariables(data))
}

function onFormError(errors: Record<string, string>) {
  formErrors.value = errors
}

onMounted(async () => {
  await store.fetchUserTask(route.params.id as string)
  await loadForm()

  // Fallback: populate editable vars only if no embedded form
  if (formResponse.value?.type !== 'embedded') {
    editableVars.value = store.currentTaskVariables.map((v) => ({
      name: v.name,
      type: v.type,
      value: v.value,
    }))
  }
})
</script>

<template>
  <div class="space-y-6">
    <div v-if="store.loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
    <div v-else-if="store.error" class="text-sm text-red-500">{{ store.error }}</div>

    <template v-else-if="store.currentTask">
      <div>
        <h1 class="text-2xl font-bold">{{ t('userTask') }}</h1>
        <CopyableId :value="store.currentTask.id" />
      </div>

      <div class="flex items-center gap-4 text-sm">
        <StatusBadge :status="store.currentTask.completedAt ? 'COMPLETED' : 'CREATED'" />
      </div>

      <div class="grid grid-cols-2 gap-4 text-sm">
        <div><span class="text-muted-foreground">{{ t('name') }}:</span> {{ store.currentTask.name || store.currentTask.code || '—' }}</div>
        <div>
          <span class="text-muted-foreground">{{ t('process') }}:</span>
          <CopyableId :value="store.currentTask.processInstanceId" />
        </div>
        <div><span class="text-muted-foreground">{{ t('formKey') }}:</span> {{ store.currentTask.formKey || '—' }}</div>
        <div><span class="text-muted-foreground">{{ t('created') }}:</span> {{ formatDateTime(store.currentTask.createdAt) }}</div>
        <div v-if="store.currentTask.completedAt" class="col-span-2">
          <span class="text-muted-foreground">{{ t('completedAtLabel') }}:</span> {{ formatDateTime(store.currentTask.completedAt) }}
        </div>
      </div>

      <!-- FORM-3: Embedded form (form-js) -->
      <div v-if="formResponse?.type === 'embedded'" class="border border-border rounded-lg p-4 bg-card">
        <h2 class="text-lg font-bold mb-4">{{ t('form') }}</h2>
        <FormRenderer
          ref="formRef"
          :schema="formResponse.schema!"
          :data="formResponse.data || {}"
          @submit="onFormSubmit"
          @error="onFormError"
        />
        <div v-if="formErrors" class="mt-2 text-sm text-red-500">
          <div v-for="(msg, field) in formErrors" :key="field">{{ field }}: {{ msg }}</div>
        </div>
      </div>

      <!-- FORM-3: External form — show link -->
      <div v-else-if="formResponse?.type === 'external'" class="border border-border rounded-lg p-4 bg-card">
        <h2 class="text-lg font-bold mb-4">{{ t('externalForm') }}</h2>
        <a :href="formResponse.url" target="_blank" rel="noopener" class="text-primary underline">
          {{ formResponse.url }}
        </a>
      </div>

      <!-- FORM-3: No form — legacy variable editor (unchanged) -->
      <div v-else class="border border-border rounded-lg p-4 bg-card">
        <h2 class="text-lg font-bold mb-4">{{ t('variables') }}</h2>
        <div class="space-y-3">
          <div v-for="(v, i) in editableVars" :key="v.name" class="flex items-center gap-3">
            <label class="text-sm font-mono w-32">{{ v.name }}</label>
            <span class="text-xs text-muted-foreground">({{ v.type }})</span>
            <input
              v-model="editableVars[i].value"
              class="flex-1 px-2 py-1 border border-input rounded text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <div v-if="!editableVars.length" class="text-sm text-muted-foreground">{{ t('noVariables') }}</div>
        </div>
      </div>

      <div v-if="!store.currentTask.completedAt" class="flex justify-end">
        <button
          class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity text-sm"
          @click="complete"
        >
          {{ formResponse?.type === 'embedded' ? t('submitForm') : t('completeTask') }}
        </button>
      </div>
    </template>
  </div>
</template>
