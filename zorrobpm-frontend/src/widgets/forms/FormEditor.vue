<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { FormEditor } from '@bpmn-io/form-js'
import { EMPTY_SCHEMA } from './formSchema'

const props = defineProps<{
  schema?: Record<string, unknown>
}>()

const emit = defineEmits<{
  (e: 'save', schema: string): void
}>()

const containerRef = ref<HTMLDivElement>()
let editorInstance: any = null

async function initEditor() {
  if (!containerRef.value) return
  editorInstance = new FormEditor({ container: containerRef.value })
  // importSchema is async — await it, otherwise the editor may render blank
  await editorInstance.importSchema(props.schema ?? EMPTY_SCHEMA)
}

watch(
  () => props.schema,
  async () => {
    if (editorInstance && props.schema) {
      await editorInstance.importSchema(props.schema)
    }
  },
)

onMounted(() => {
  initEditor()
})
onUnmounted(() => {
  if (editorInstance) {
    editorInstance.destroy()
    editorInstance = null
  }
})

async function importSchema(schema: Record<string, unknown>) {
  if (editorInstance) {
    await editorInstance.importSchema(schema)
  }
}

function saveSchema() {
  if (!editorInstance) return
  // form-js editor exposes saveSchema() (NOT save())
  const schema = editorInstance.saveSchema()
  emit('save', JSON.stringify(schema))
  return schema
}

defineExpose({ saveSchema, importSchema })
</script>

<template>
  <div ref="containerRef" class="form-editor-container" />
</template>

<style>
/* Base form-js styles are REQUIRED — the editor embeds a form preview.
   With only form-js-editor.css the editor renders blank (the "nothing appears" bug). */
@import '@bpmn-io/form-js/dist/assets/form-js.css';
@import '@bpmn-io/form-js/dist/assets/form-js-editor.css';
</style>
