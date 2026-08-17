<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { deployProcessDefinition } from '@/services/processService'
import { submitProcessSubmission } from '@/services/submissionService'
import { useToast } from '@/composables/useToast'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/shared/lib/utils'
import { Upload, FileText, AlertCircle, CheckCircle, ChevronDown, ChevronRight } from 'lucide-vue-next'

const router = useRouter()
const toast = useToast()
const { t } = useI18n()
const auth = useAuthStore()

const expanded = ref(true)
const bpmnText = ref('')
const fileName = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const success = ref(false)
const submitted = ref(false)

/** WO-ACL-6 criterion 5: the button names what will happen — SUPER_ADMIN deploys,
 * everyone else creates an approval request. Same input, two outcomes. */
const isAdmin = auth.isSuperAdmin

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  fileName.value = file.name
  const reader = new FileReader()
  reader.onload = () => {
    bpmnText.value = reader.result as string
  }
  reader.readAsText(file)
}

function onDrop(event: DragEvent) {
  event.preventDefault()
  const file = event.dataTransfer?.files[0]
  if (!file) return
  fileName.value = file.name
  const reader = new FileReader()
  reader.onload = () => {
    bpmnText.value = reader.result as string
  }
  reader.readAsText(file)
}

function onDragOver(event: DragEvent) {
  event.preventDefault()
}

async function submit() {
  if (!bpmnText.value.trim()) {
    error.value = t('bpmnRequired')
    return
  }
  loading.value = true
  error.value = null
  success.value = false
  submitted.value = false
  try {
    if (isAdmin) {
      const result = await deployProcessDefinition(bpmnText.value)
      success.value = true
      toast.success(t('deploySuccessToast'), {
        action: { label: t('viewDefinition'), onClick: () => router.push(`/processes/definitions/${result.id}`) },
      })
    } else {
      await submitProcessSubmission(bpmnText.value)
      submitted.value = true
      toast.success(t('submissionSentToast'))
    }
  } catch (e) {
    // WO-ACL-6 criterion 7: show the backend's own text (e.g. "Process with key 'x'
    // already exists…"), not a generic "Failed to deploy".
    error.value = errorMessage(e, t('failedToDeploy'))
    toast.error(error.value)
  } finally {
    loading.value = false
  }
}

function clear() {
  bpmnText.value = ''
  fileName.value = ''
  error.value = null
  success.value = false
  submitted.value = false
}
</script>

<template>
  <div class="border border-border rounded-lg overflow-hidden bg-card">
    <button
      class="w-full flex items-center justify-between px-4 py-3 text-left hover:bg-muted/50 transition-colors"
      @click="expanded = !expanded"
    >
      <span class="flex items-center gap-2 font-bold text-lg">
        <Upload class="h-5 w-5 text-primary" />
        {{ t('uploadProcess') }}
      </span>
      <component :is="expanded ? ChevronDown : ChevronRight" class="h-4 w-4 text-muted-foreground" />
    </button>

    <div v-if="expanded" class="px-4 pb-4 space-y-4">
      <div
        v-if="!bpmnText"
        class="border-2 border-dashed border-border rounded-lg p-10 text-center hover:border-primary/50 transition-colors cursor-pointer"
        @drop="onDrop"
        @dragover="onDragOver"
        @click="($refs.fileInput as HTMLInputElement).click()"
      >
        <Upload class="h-10 w-10 mx-auto mb-3 text-muted-foreground" />
        <p class="text-base font-medium mb-1">{{ t('dropBpmn') }}</p>
        <p class="text-sm text-muted-foreground">{{ t('supportsBpmn') }}</p>
        <input ref="fileInput" type="file" accept=".bpmn,.xml" class="hidden" @change="onFileChange" />
      </div>

      <template v-else>
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-3">
            <FileText class="h-5 w-5 text-primary" />
            <div>
              <p class="font-medium">{{ fileName || t('bpmnXml') }}</p>
              <p class="text-xs text-muted-foreground">{{ bpmnText.length }} {{ t('characters') }}</p>
            </div>
          </div>
          <button class="text-sm text-muted-foreground hover:text-foreground" @click="clear">{{ t('clear') }}</button>
        </div>

        <textarea
          v-model="bpmnText"
          class="w-full h-72 px-4 py-3 border border-input rounded-md text-sm font-mono focus:outline-none focus:ring-2 focus:ring-ring resize-none"
          :placeholder="t('pasteBpmnHere')"
        />

        <div v-if="error" class="flex items-center gap-2 text-sm text-red-500">
          <AlertCircle class="h-4 w-4 shrink-0" />
          {{ error }}
        </div>

        <div v-if="success" class="flex items-center gap-2 text-sm text-green-600">
          <CheckCircle class="h-4 w-4" />
          {{ t('deploySuccess') }}
        </div>

        <div v-if="submitted" class="flex items-center gap-2 text-sm text-green-600">
          <CheckCircle class="h-4 w-4" />
          {{ t('submissionSent') }}
        </div>

        <div class="flex justify-end gap-3">
          <button
            class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted transition-colors"
            @click="clear"
          >
            {{ t('cancel') }}
          </button>
          <button
            class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity disabled:opacity-50"
            :disabled="loading || !bpmnText.trim()"
            @click="submit"
          >
            {{ loading ? (isAdmin ? t('deploying') : t('submitting')) : (isAdmin ? t('deployBpmn') : t('submitForApproval')) }}
          </button>
        </div>
      </template>
    </div>
  </div>
</template>
