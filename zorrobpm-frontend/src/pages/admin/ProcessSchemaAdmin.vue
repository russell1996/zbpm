<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from '@/composables/useToast'
import { getProcessDefinitions } from '@/services/processService'
import type { ProcessDefinition } from '@/types/api'
import SchemaEditorPanel from '@/widgets/shared/SchemaEditorPanel.vue'

const { t } = useI18n()
const toast = useToast()

const definitions = ref<ProcessDefinition[]>([])
const selectedDef = ref<ProcessDefinition | null>(null)
const loadingDefs = ref(true)
const panelRef = ref<InstanceType<typeof SchemaEditorPanel> | null>(null)

onMounted(async () => {
  try {
    const result = await getProcessDefinitions({ latestVersionOnly: true, pageSize: 100 })
    definitions.value = result.data
  } catch {
    toast.error('Failed to load processes')
  } finally {
    loadingDefs.value = false
  }
})

function onSelectDef(def: ProcessDefinition) {
  selectedDef.value = def
}
</script>

<template>
  <div class="space-y-6">
    <h1 class="text-2xl font-bold">{{ t('processSchemas') }}</h1>

    <div v-if="loadingDefs" class="text-sm text-muted-foreground">{{ t('loading') }}</div>

    <template v-else>
      <!-- Process selector -->
      <div v-if="!selectedDef" class="border border-border rounded-lg p-4 bg-card">
        <h2 class="text-lg font-bold mb-3">{{ t('selectDefinition') }}</h2>
        <div v-if="definitions.length" class="max-h-60 overflow-y-auto border border-border rounded-md">
          <div
            v-for="def in definitions"
            :key="def.id"
            class="px-4 py-2 cursor-pointer hover:bg-muted text-sm border-b border-border last:border-b-0"
            @click="onSelectDef(def)"
          >
            {{ def.name || def.key }} <span class="text-muted-foreground">v{{ def.version }}</span>
          </div>
        </div>
        <div v-else class="text-sm text-muted-foreground">{{ t('noDefinitions') }}</div>
      </div>

      <!-- Schema editor panel -->
      <div v-else class="border border-border rounded-lg p-4 bg-card">
        <div class="flex items-center justify-between mb-3">
          <h2 class="text-lg font-bold">{{ selectedDef.name || selectedDef.key }} <span class="text-sm font-normal text-muted-foreground">v{{ selectedDef.version }}</span></h2>
          <button class="text-sm text-primary hover:underline" @click="selectedDef = null">{{ t('back') }}</button>
        </div>
        <SchemaEditorPanel ref="panelRef" :process-key="selectedDef.key" />
      </div>
    </template>
  </div>
</template>
