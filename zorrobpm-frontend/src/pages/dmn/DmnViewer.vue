<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getDecision } from '@/services/mock/dmnService'
import type { DmnDecision } from '@/services/mock/dmnService'
import { Play } from 'lucide-vue-next'

const route = useRoute()
const decision = ref<DmnDecision | null>(null)
const loading = ref(false)
const showTestModal = ref(false)

// Test inputs
const testInputs = ref<Record<string, string>>({})
const testResult = ref<Record<string, string> | null>(null)

onMounted(async () => {
  loading.value = true
  try {
    decision.value = await getDecision(route.params.id as string)
    if (decision.value) {
      for (const input of decision.value.inputs) {
        testInputs.value[input.expression] = ''
      }
    }
  } catch {
    // ignore
  } finally {
    loading.value = false
  }
})

function runTest() {
  // Simple mock evaluation
  if (!decision.value) return
  testResult.value = {}
  for (const output of decision.value.outputs) {
    testResult.value[output.name] = 'Mock result'
  }
}
</script>

<template>
  <div class="space-y-6">
    <div v-if="loading" class="text-sm text-muted-foreground">Loading...</div>

    <template v-else-if="decision">
      <div class="flex items-center justify-between">
        <div>
          <h1 class="text-2xl font-bold">{{ decision.name }}</h1>
          <p class="text-sm text-muted-foreground">
            ID: <span class="font-mono">{{ decision.id }}</span>
            · Version: {{ decision.version }}
            · Hit Policy: {{ decision.hitPolicy }}
          </p>
        </div>
        <button
          class="flex items-center gap-2 px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity text-sm"
          @click="showTestModal = true"
        >
          <Play class="h-4 w-4" />
          Test
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
          <h2 class="text-lg font-bold">Test Decision</h2>
          <div class="space-y-3">
            <div v-for="input in decision.inputs" :key="input.id">
              <label class="block text-sm font-medium mb-1">{{ input.label }} ({{ input.expression }})</label>
              <input
                v-model="testInputs[input.expression]"
                class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring"
              />
            </div>
          </div>
          <button
            class="w-full px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity text-sm"
            @click="runTest"
          >
            Evaluate
          </button>
          <div v-if="testResult" class="border border-border rounded p-3 bg-muted/50">
            <h3 class="text-sm font-bold mb-2">Result</h3>
            <div v-for="(val, key) in testResult" :key="key" class="text-sm">
              <span class="font-mono">{{ key }}</span>: {{ val }}
            </div>
          </div>
          <button class="text-sm text-muted-foreground hover:text-foreground" @click="showTestModal = false">Close</button>
        </div>
      </div>
    </template>
  </div>
</template>
