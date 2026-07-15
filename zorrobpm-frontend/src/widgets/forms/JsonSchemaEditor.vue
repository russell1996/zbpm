<script setup lang="ts">
import { ref, computed } from 'vue'

const props = defineProps<{
  modelValue: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const JSON_SCHEMA_PLACEHOLDER = `{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {},
  "required": []
}`

const localValue = ref(props.modelValue || JSON_SCHEMA_PLACEHOLDER)

const isValid = computed(() => {
  try {
    JSON.parse(localValue.value)
    return true
  } catch {
    return false
  }
})

const errorMessage = computed(() => {
  if (isValid.value) return ''
  try {
    JSON.parse(localValue.value)
  } catch (e: any) {
    return e.message
  }
  return 'Invalid JSON'
})

function onInput(e: Event) {
  localValue.value = (e.target as HTMLTextAreaElement).value
  emit('update:modelValue', localValue.value)
}

function saveSchema(): string {
  return localValue.value
}

defineExpose({ saveSchema })
</script>

<template>
  <div class="json-schema-editor">
    <div class="mb-2 text-sm text-muted-foreground">
      JSON Schema (2020-12)
    </div>
    <textarea
      :value="localValue"
      class="w-full h-full font-mono text-sm p-3 border border-border rounded-md bg-background resize-none focus:outline-none focus:ring-1 focus:ring-ring"
      spellcheck="false"
      @input="onInput"
    />
    <div v-if="!isValid" class="mt-2 text-sm text-destructive">
      {{ errorMessage }}
    </div>
  </div>
</template>
