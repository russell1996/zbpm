<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
// @ts-expect-error form-js has no TS types
import { FormEditor } from '@bpmn-io/form-js'

const props = defineProps<{
  schema?: Record<string, unknown>
}>()

const emit = defineEmits<{
  (e: 'save', schema: string): void
}>()

const containerRef = ref<HTMLDivElement>()
let editorInstance: any = null

function initEditor() {
  if (!containerRef.value) return
  editorInstance = new FormEditor({ container: containerRef.value })
  if (props.schema) {
    editorInstance.importSchema(props.schema)
  } else {
    editorInstance.importSchema({ type: 'form', components: [] })
  }
}

watch(
  () => props.schema,
  () => {
    if (editorInstance && props.schema) {
      editorInstance.importSchema(props.schema)
    }
  },
)

onMounted(() => { initEditor() })
onUnmounted(() => { if (editorInstance) { editorInstance.destroy(); editorInstance = null } })

function saveSchema() {
  if (!editorInstance) return
  const schema = editorInstance.save()
  emit('save', JSON.stringify(schema))
}

defineExpose({ saveSchema })
</script>

<template>
  <div ref="containerRef" class="form-editor-container" />
</template>

<style>
@import '@bpmn-io/form-js/dist/assets/form-js-editor.css';
</style>
