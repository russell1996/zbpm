<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  deployProcessDefinition,
  addProcessDefinitionVersion,
  getProcessDefinitions,
} from '@/services/processService'
import { submitProcessSubmission } from '@/services/submissionService'
import { listMembers } from '@/services/adminService'
import type { Member } from '@/services/adminService'
import { useToast } from '@/composables/useToast'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/shared/lib/utils'
import { Upload, FileText, AlertCircle, CheckCircle, ShieldAlert } from 'lucide-vue-next'

const router = useRouter()
const toast = useToast()
const { t } = useI18n()
const auth = useAuthStore()
const emit = defineEmits<{ done: [] }>()

// WO-ACL-11 criteria 20-22: bound mode — opened from a process card. The target
// process is known in advance: shown in the header, the mode is immediately
// 'version', and a model with a foreign key is rejected with an explaining text.
const props = defineProps<{ bound?: { id: string; key: string; name: string | null } | null }>()

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

// WO-ACL-10 criteria 3-6: after parsing, the section looks the existing process
// up and tells the user exactly what will happen:
//   'new'       — no definition with this key → "a new process will be created"
//   'version'   — key exists and the user may deploy → "a new version will be added"
//   'no-access' — key exists but the user has no deploy rights → owner shown, submit blocked
const mode = ref<'new' | 'version' | 'no-access' | null>(null)
const existingDef = ref<{ id: string; key: string; name: string | null } | null>(null)
const owner = ref<Member | null>(null)
const pendingCheck = ref(false)
const checkSeq = ref(0)
// WO-ACL-11 criterion 22: bound mode rejects a model whose process key differs
// from the target process with an explaining text (not "process already exists").
const keyMismatch = ref(false)

/** WO-ACL-6 criterion 5: the button names what will happen — SUPER_ADMIN deploys,
 * everyone else creates an approval request. Same input, two outcomes. */
const isAdmin = auth.isSuperAdmin

const canDeploy = computed(() => mode.value === 'version' || mode.value === 'new')

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
  keyMismatch.value = false
  if (props.bound) {
    // WO-ACL-11 criterion 21: bound mode — the target is known in advance, the
    // mode is 'version' right away, no directory lookup needed.
    if (parsedKey.value && parsedKey.value !== props.bound.key) {
      keyMismatch.value = true
      mode.value = null
      existingDef.value = null
      owner.value = null
      return
    }
    mode.value = 'version'
    existingDef.value = props.bound
    owner.value = null
    return
  }
  if (parsedKey.value) {
    checkExistingKey(parsedKey.value)
  } else {
    mode.value = null
    existingDef.value = null
    owner.value = null
  }
}

// WO-ACL-10 criteria 4-6: resolve what would happen with this key.
async function checkExistingKey(key: string) {
  const seq = ++checkSeq.value
  pendingCheck.value = true
  error.value = null
  try {
    const page = await getProcessDefinitions({ processDefinitionKey: key, latestVersionOnly: true })
    if (seq !== checkSeq.value) return
    const def = page.data.find((d) => d.key === key) ?? null
    existingDef.value = def ? { id: def.id, key: def.key, name: def.name ?? null } : null
    if (!def) {
      mode.value = 'new'
      owner.value = null
      return
    }
    const members = await listMembers(key)
    if (seq !== checkSeq.value) return
    const me = auth.user?.username ? members.find((m) => m.username === auth.user!.username) : undefined
    owner.value = members.find((m) => m.role === 'OWNER') ?? null
    if (isAdmin || (me && (me.role === 'OWNER' || me.role === 'DESIGNER'))) {
      mode.value = 'version'
    } else {
      mode.value = 'no-access'
    }
  } catch {
    if (seq !== checkSeq.value) return
    // look-up failed — stay neutral, keep the old behavior of attempting a deploy
    mode.value = null
    existingDef.value = null
    owner.value = null
  } finally {
    if (seq === checkSeq.value) pendingCheck.value = false
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
  // WO-ACL-11 criteria 25-26: the button is blocked FROM THE MOMENT OF THE CLICK,
  // not after the response — double-clicks in the 300-800ms window must not fire
  // a second request. The guard lives here, not only in the disabled attribute:
  // a programmatic/dispatch click would otherwise re-enter submit().
  if (loading.value) return
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
    // WO-ACL-11 criterion 20-22: bound mode can ONLY add a version to the bound
    // process — never deploy a new one, not even for the super-admin (the model
    // with a foreign key is rejected above by keyMismatch).
    if (props.bound && (mode.value !== 'version' || !existingDef.value)) {
      error.value = t('boundKeyMismatch', { actual: parsedKey.value, expected: props.bound.key })
      return
    }
    // WO-ACL-10 criterion 4: an existing key with deploy rights adds a new version.
    if (mode.value === 'version' && existingDef.value) {
      const result = await addProcessDefinitionVersion(existingDef.value.id, bpmnText.value)
      success.value = true
      emit('done')
      toast.success(t('deploySuccessToast'), {
        action: { label: t('viewDefinition'), onClick: () => router.push(`/processes/definitions/${result.id}`) },
      })
    } else if (isAdmin) {
      const result = await deployProcessDefinition(bpmnText.value)
      success.value = true
      emit('done')
      toast.success(t('deploySuccessToast'), {
        action: { label: t('viewDefinition'), onClick: () => router.push(`/processes/definitions/${result.id}`) },
      })
    } else {
      await submitProcessSubmission(bpmnText.value)
      submitted.value = true
      toast.success(t('submissionSentToast'))
      // WO-ACL-11 criterion 23: a successful submission CLOSES the dialog — the
      // parent listens to `done` (same contract as deploy/add-version above).
      emit('done')
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
  mode.value = null
  existingDef.value = null
  owner.value = null
  pendingCheck.value = false
  checkSeq.value++
  keyMismatch.value = false
}
</script>

<template>
  <div class="space-y-4">
    <div class="flex items-center gap-2 font-bold text-lg">
      <Upload class="h-5 w-5 text-primary" />
      {{ t('uploadProcess') }}
    </div>

    <!-- WO-ACL-11 criterion 21: bound mode shows the target process in the header. -->
    <div v-if="bound" class="bg-muted/50 rounded-md px-4 py-2 text-sm space-y-0.5">
      <p><span class="text-muted-foreground">{{ t('targetProcess') }}:</span> <strong>{{ bound.name || bound.key }}</strong></p>
      <p class="text-xs text-muted-foreground">Key: <code class="font-mono">{{ bound.key }}</code> — {{ t('boundModeHint') }}</p>
    </div>

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

      <!-- WO-ACL-10 criteria 4-6: tell the user exactly what will happen. -->
      <!-- WO-ACL-11 criterion 22: in bound mode a model with a foreign key is
           rejected with an explaining text instead of "process already exists". -->
      <div v-if="keyMismatch" class="bg-red-50 border border-red-200 rounded-md px-4 py-2 text-sm">
        <p class="flex items-center gap-2 text-red-600">
          <AlertCircle class="h-4 w-4 shrink-0" />
          {{ t('boundKeyMismatch', { actual: parsedKey, expected: bound?.key }) }}
        </p>
      </div>
      <div v-else-if="pendingCheck" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
      <div v-else-if="mode === 'new'" class="bg-muted/50 rounded-md px-4 py-2 text-sm">
        {{ t('willCreateProcess') }} <code class="font-mono">{{ parsedKey }}</code>
      </div>
      <div v-else-if="mode === 'version'" class="bg-muted/50 rounded-md px-4 py-2 text-sm">
        {{ t('willAddVersion') }} <strong>{{ existingDef?.name || parsedName }}</strong>
      </div>
      <div v-else-if="mode === 'no-access'" class="bg-amber-50 border border-amber-200 rounded-md px-4 py-2 text-sm space-y-1">
        <p class="flex items-center gap-2">
          <ShieldAlert class="h-4 w-4 shrink-0 text-amber-600" />
          {{ t('noDeployAccess') }}
        </p>
        <p v-if="owner" class="pl-6 text-muted-foreground">
          {{ t('owner') }}: {{ owner.fullName || owner.username }}
        </p>
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
          :disabled="loading || !bpmnText.trim() || pendingCheck || mode === 'no-access' || keyMismatch"
          @click="submit"
        >
          {{ loading ? (isAdmin ? t('deploying') : t('submitting')) : (isAdmin ? t('deployBpmn') : t('submitForApproval')) }}
        </button>
      </div>
    </template>
  </div>
</template>