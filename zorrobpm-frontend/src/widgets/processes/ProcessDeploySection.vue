<script setup lang="ts">
import { ref, computed } from 'vue'
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
// WO-ACL-8 criterion 29: segment toggle for file vs XML text input.
const inputMode = ref<'file' | 'xml'>('file')
// WO-ACL-8 criterion 30: parsed key and name shown before submit.
const parsedKey = ref<string | null>(null)
const parsedName = ref<string | null>(null)
// WO-ACL-8 criterion 31: error "already exists" shows a button to navigate.
const existingProcessKey = ref<string | null>(null)

/** WO-ACL-6 criterion 5: the button names what will happen — SUPER_ADMIN deploys,
 * everyone else creates an approval request. Same input, two outcomes. */
const isAdmin = auth.isSuperAdmin

function parseBpmnMetadata(xml: string) {
  try {
    const parser = new DOMParser()
    const doc = parser.parseFromString(xml, 'text/xml')
    const process = doc.querySelector('process')
    parsedKey.value = process?.getAttribute('id') || null
    parsedName.value = process?.getAttribute('name') || null
  } catch {
    parsedKey.value = null
    parsedName.value = null
  }
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  fileName.value = file.name
  const reader = new FileReader()
  reader.onload = () => {
    bpmnText.value = reader.result as string
    parseBpmnMetadata(bpmnText.value)
  }
  reader.readAsText(file)
}

function onXmlInput() {
  parseBpmnMetadata(bpmnText.value)
}

function onDrop(event: DragEvent) {
  event.preventDefault()
  const file = event.dataTransfer?.files[0]
  if (!file) return
  fileName.value = file.name
  const reader = new FileReader()
  reader.onload = () => {
    bpmnText.value = reader.result as string
    parseBpmnMetadata(bpmnText.value)
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
  existingProcessKey.value = null
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
    const msg = errorMessage(e, t('failedToDeploy'))
    error.value = msg
    // WO-ACL-8 criterion 31: extract process key from "already exists" error
    // and offer a direct navigation button.
    const keyMatch = msg.match(/key\s+'([^']+)'/i)
    if (keyMatch && msg.toLowerCase().includes('already exists')) {
      existingProcessKey.value = keyMatch[1]
    }
    toast.error(msg)
  } finally {
    loading.value = false
  }
}

function navigateToExisting() {
  if (existingProcessKey.value) {
    // Search for the process by key and navigate to its detail page.
    router.push(`/processes/definitions?search=${existingProcessKey.value}`)
    clear()
  }
}

function clear() {
  bpmnText.value = ''
  fileName.value = ''
  error.value = null
  success.value = false
  submitted.value = false
  parsedKey.value = null
  parsedName.value = null
  existingProcessKey.value = null
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
      <!-- WO-ACL-8 criterion 29: segment toggle — File or XML text, one at a time. -->
      <div v-if="!bpmnText" class="flex items-center border border-border rounded-md overflow-hidden text-sm">
        <button
          class="px-3 py-1.5 transition-colors"
          :class="inputMode === 'file' ? 'bg-primary text-primary-foreground font-medium' : 'hover:bg-muted'"
          @click="inputMode = 'file'"
        >{{ t('file') }}</button>
        <button
          class="px-3 py-1.5 transition-colors"
          :class="inputMode === 'xml' ? 'bg-primary text-primary-foreground font-medium' : 'hover:bg-muted'"
          @click="inputMode = 'xml'"
        >XML</button>
      </div>

      <!-- File mode -->
      <div
        v-if="!bpmnText && inputMode === 'file'"
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

      <!-- XML text mode -->
      <div v-if="!bpmnText && inputMode === 'xml'" class="space-y-3">
        <textarea
          v-model="bpmnText"
          class="w-full h-72 px-4 py-3 border border-input rounded-md text-sm font-mono focus:outline-none focus:ring-2 focus:ring-ring resize-none"
          :placeholder="t('pasteBpmnHere')"
          @input="onXmlInput"
        />
      </div>

      <template v-if="bpmnText">
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

        <!-- WO-ACL-8 criterion 30: parsed key and name before submit. -->
        <div v-if="parsedKey" class="bg-muted/50 rounded-md px-4 py-2 text-sm space-y-1">
          <p><span class="text-muted-foreground">{{ t('key') }}:</span> <code class="font-mono">{{ parsedKey }}</code></p>
          <p v-if="parsedName"><span class="text-muted-foreground">{{ t('name') }}:</span> {{ parsedName }}</p>
        </div>

        <textarea
          v-model="bpmnText"
          class="w-full h-72 px-4 py-3 border border-input rounded-md text-sm font-mono focus:outline-none focus:ring-2 focus:ring-ring resize-none"
          :placeholder="t('pasteBpmnHere')"
          @input="onXmlInput"
        />

        <div v-if="error" class="space-y-2">
          <div class="flex items-center gap-2 text-sm text-red-500">
            <AlertCircle class="h-4 w-4 shrink-0" />
            {{ error }}
          </div>
          <!-- WO-ACL-8 criterion 31: "already exists" error offers a button to navigate. -->
          <button
            v-if="existingProcessKey"
            class="text-sm text-primary hover:underline"
            @click="navigateToExisting"
          >
            {{ t('openExistingProcess') }} →
          </button>
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
