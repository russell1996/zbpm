<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useToast } from '@/composables/useToast'
import { getStartForm, type TaskFormResponse } from '@/services/formService'
import { dataToVariables } from '@/shared/lib/formMapping'
import type { ProcessVariable } from '@/types/api'
import * as instanceService from '@/services/instanceService'
import FormRenderer from '@/widgets/forms/FormRenderer.vue'

const route = useRoute()
const router = useRouter()
const toast = useToast()
const { t } = useI18n()

const formResponse = ref<TaskFormResponse | null>(null)
const loading = ref(true)
const submitting = ref(false)
const error = ref<string | null>(null)
const formRef = ref<InstanceType<typeof FormRenderer> | null>(null)
const formErrors = ref<Record<string, string> | null>(null)

const processKey = route.params.key as string

async function startProcess() {
  submitting.value = true
  error.value = null
  try {
    let variables: ProcessVariable[] = []

    if (formResponse.value?.type === 'embedded' && formRef.value) {
      formErrors.value = null
      // WO-UI-17 F24: submit() is undefined when the form-js instance never
      // initialized (no container) — starting the process with silently empty
      // variables would drop required data, so fail loudly instead.
      const result = formRef.value.submit()
      if (!result) {
        error.value = 'Failed to submit form'
        submitting.value = false
        return
      }
      const { data, errors } = result
      if (errors && Object.keys(errors).length > 0) {
        formErrors.value = errors
        submitting.value = false
        return
      }
      variables = dataToVariables(data)
    }

    const id = await instanceService.startProcessInstance({
      processDefinitionKey: processKey,
      variables,
    })
    if (id) {
      toast.success('Process started', {
        action: { label: 'View Instance', onClick: () => router.push(`/processes/instances/${id}`) },
      })
    }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to start process'
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  try {
    formResponse.value = await getStartForm(processKey)
  } catch {
    formResponse.value = { type: 'none' }
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="space-y-6">
    <div v-if="loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
    <div v-else-if="error" class="text-sm text-red-500">{{ error }}</div>

    <template v-else>
      <div>
        <h1 class="text-2xl font-bold">Start Process: {{ processKey }}</h1>
      </div>

      <!-- Embedded form -->
      <div v-if="formResponse?.type === 'embedded'" class="border border-border rounded-lg p-4 bg-card">
        <h2 class="text-lg font-bold mb-4">{{ t('form') }}</h2>
        <FormRenderer
          ref="formRef"
          :schema="formResponse.schema!"
          :data="{}"
          @error="(e) => (formErrors = e)"
        />
        <div v-if="formErrors" class="mt-2 text-sm text-red-500">
          <div v-for="(msg, field) in formErrors" :key="field">{{ field }}: {{ msg }}</div>
        </div>
      </div>

      <!-- External form -->
      <div v-else-if="formResponse?.type === 'external'" class="border border-border rounded-lg p-4 bg-card">
        <h2 class="text-lg font-bold mb-4">{{ t('externalForm') }}</h2>
        <a :href="formResponse.url" target="_blank" rel="noopener" class="text-primary underline">
          {{ formResponse.url }}
        </a>
      </div>

      <!-- No form — just a start button -->
      <div v-else class="border border-border rounded-lg p-4 bg-card">
        <p class="text-sm text-muted-foreground mb-4">{{ t('noStartFormConfigured') }}</p>
      </div>

      <div class="flex justify-end">
        <button
          class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity text-sm disabled:opacity-50"
          :disabled="submitting"
          @click="startProcess"
        >
          {{ submitting ? t('loading') : t('startProcess') }}
        </button>
      </div>
    </template>
  </div>
</template>
