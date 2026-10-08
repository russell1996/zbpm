<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getDecision, evaluateDecision, inferVariable, type DmnDecision } from '@/services/dmnService'
import { getProcessDefinitions } from '@/services/processService'
import PresetPicker from '@/widgets/presets/PresetPicker.vue'
import type { PresetVariable } from '@/types/presets'
import { useI18n } from 'vue-i18n'
import { useToast } from '@/composables/useToast'
import { useBreadcrumbLabel } from '@/composables/useBreadcrumbLabel'
import { Play } from 'lucide-vue-next'

const route = useRoute()
const { t } = useI18n()
const toast = useToast()
const decision = ref<DmnDecision | null>(null)
const loading = ref(false)
const showTestModal = ref(false)
const evaluating = ref(false)
const testError = ref<string | null>(null)

// WO-ACL-15 criterion 17: the DMN crumb shows the decision name. Filled via the
// shared useBreadcrumbLabel() once getDecision resolves, cleared on unmount
// (criterion 18).
useBreadcrumbLabel(() => decision.value?.name ?? null)

// Test inputs keyed by the input's FEEL expression (the variable name)
const testInputs = ref<Record<string, string>>({})
const testResult = ref<Record<string, unknown> | null>(null)

// WO-VT-1: evaluate из шаблона. DMN-страница не привязана к процессу, а
// шаблон требует processDefinitionKey — ключ выбирается в модалке
// (список определений), ref = decisionId.
const pickerRef = ref<InstanceType<typeof PresetPicker> | null>(null)
const askMissing = ref<string[]>([])
const pickerInvalid = ref(false)
const presetProcessKey = ref('')
const processKeys = ref<string[]>([])

function onPickerChange() {
  askMissing.value = pickerRef.value?.missingAsk ?? []
  pickerInvalid.value = pickerRef.value?.hasErrors ?? false
}

async function loadProcessKeys() {
  try {
    const page = await getProcessDefinitions({ pageIndex: 0, pageSize: 100, latestVersionOnly: true })
    processKeys.value = [...new Set((page.data ?? []).map((d) => d.key))]
  } catch {
    processKeys.value = []
  }
}

onMounted(async () => {
  loading.value = true
  try {
    decision.value = await getDecision(route.params.id as string)
    if (decision.value) {
      for (const input of decision.value.inputs) {
        testInputs.value[input.expression] = ''
      }
    }
    await loadProcessKeys()
  } catch {
    toast.error(t('loadError'))
  } finally {
    loading.value = false
  }
})

async function runTest() {
  if (!decision.value) return
  evaluating.value = true
  testError.value = null
  testResult.value = null
  try {
    // WO-VT-1: при выбранном ключе процесса переменные берёт PresetPicker
    // (ручной ввод или шаблон с развёрнутыми плейсхолдерами), иначе —
    // прежние inputs модалки.
    const variables = presetProcessKey.value && pickerRef.value
      ? pickerRef.value.getVariables().map((v: PresetVariable) => ({
          name: v.name,
          type: v.type,
          value: v.value,
        }))
      : decision.value.inputs
          .filter((input) => (testInputs.value[input.expression] ?? '') !== '')
          .map((input) => inferVariable(input.expression, testInputs.value[input.expression]))
    testResult.value = await evaluateDecision(decision.value.id, variables)
  } catch (e: unknown) {
    testError.value = (e as { response?: { data?: { message?: string } } })?.response?.data?.message || t('evaluationFailed')
  } finally {
    evaluating.value = false
  }
}
</script>

<template>
  <div class="space-y-6">
    <div v-if="loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>

    <template v-else-if="decision">
      <div class="flex items-center justify-between">
        <div>
          <h1 class="text-2xl font-bold">{{ decision.name }}</h1>
          <p class="text-sm text-muted-foreground">
            {{ t('id') }}: <span class="font-mono">{{ decision.id }}</span>
            · {{ t('version') }}: {{ decision.version }}
            · {{ t('hitPolicy') }}: {{ decision.hitPolicy }}
          </p>
        </div>
        <button
          class="flex items-center gap-2 px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity text-sm"
          @click="showTestModal = true"
        >
          <Play class="h-4 w-4" />
          {{ t('test') }}
        </button>
      </div>

      <div class="border border-border rounded-lg overflow-hidden">
        <table class="w-full text-sm">
          <thead class="bg-muted">
            <tr>
              <th v-for="input in decision.inputs" :key="input.id" class="px-4 py-3 text-left font-medium">
                {{ input.label }}
              </th>
              <th v-for="output in decision.outputs" :key="output.id" class="px-4 py-3 text-left font-medium">
                {{ output.label }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="rule in decision.rules" :key="rule.id" class="border-t border-border">
              <td v-for="(entry, i) in rule.inputEntries" :key="i" class="px-4 py-3 font-mono text-xs">{{ entry }}</td>
              <td v-for="(entry, i) in rule.outputEntries" :key="i" class="px-4 py-3 font-mono text-xs">{{ entry }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div
        v-if="showTestModal"
        class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
        @click.self="showTestModal = false"
      >
        <div class="bg-card rounded-lg shadow-lg w-full max-w-md p-6 space-y-4">
          <h2 class="text-lg font-bold">{{ t('testDecision') }}</h2>
          <!-- WO-VT-1: evaluate из шаблона (ключ процесса — для привязки шаблона) -->
          <div v-if="processKeys.length">
            <label for="dmn-preset-key" class="block text-xs font-medium mb-1">{{ t('presetProcessKey') }}</label>
            <select
              id="dmn-preset-key"
              v-model="presetProcessKey"
              class="w-full px-2 py-1.5 border border-input rounded text-sm"
            >
              <option value="">{{ t('presetNoProcessKey') }}</option>
              <option v-for="k in processKeys" :key="k" :value="k">{{ k }}</option>
            </select>
          </div>
          <PresetPicker
            v-if="presetProcessKey && decision"
            ref="pickerRef"
            :process-key="presetProcessKey"
            target-kind="DMN"
            :target-ref="decision.id"
            @change="onPickerChange"
          />
          <div v-else class="space-y-3">
            <div v-for="input in decision.inputs" :key="input.id">
              <label class="block text-sm font-medium mb-1">{{ input.label }} ({{ input.expression }})</label>
              <input
                v-model="testInputs[input.expression]"
                class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
              />
            </div>
          </div>
          <button
            class="w-full px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity text-sm disabled:opacity-50"
            :disabled="evaluating || askMissing.length > 0 || pickerInvalid"
            :title="askMissing.length ? t('presetFillAskFields', { fields: askMissing.join(', ') }) : ''"
            @click="runTest"
          >
            {{ evaluating ? t('evaluating') : t('evaluate') }}
          </button>
          <p v-if="testError" class="text-sm text-red-500">{{ testError }}</p>
          <div v-if="testResult" class="border border-border rounded p-3 bg-muted/50">
            <h3 class="text-sm font-bold mb-2">{{ t('result') }}</h3>
            <div v-for="(val, key) in testResult" :key="key" class="text-sm">
              <span class="font-mono">{{ key }}</span>: {{ typeof val === 'object' ? JSON.stringify(val) : val }}
            </div>
          </div>
          <button class="text-sm text-muted-foreground hover:text-foreground" @click="showTestModal = false">{{ t('close') }}</button>
        </div>
      </div>
    </template>
  </div>
</template>
