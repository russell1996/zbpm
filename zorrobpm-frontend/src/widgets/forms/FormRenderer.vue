<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { Form } from '@bpmn-io/form-js'

const props = defineProps<{
  schema: Record<string, unknown>
  data?: Record<string, string>
}>()

const emit = defineEmits<{
  (e: 'submit', data: Record<string, string>): void
  (e: 'error', errors: Record<string, string>): void
}>()

const containerRef = ref<HTMLDivElement>()
let formInstance: any = null

function initForm() {
  if (!containerRef.value) return
  formInstance = new Form({
    container: containerRef.value!,
  })
  formInstance.importSchema(props.schema, props.data || {})
}

watch(
  () => props.schema,
  () => {
    if (formInstance) {
      formInstance.importSchema(props.schema, props.data || {})
    } else {
      initForm()
    }
  },
)

onMounted(() => {
  initForm()
})

onUnmounted(() => {
  if (formInstance) {
    formInstance.destroy()
    formInstance = null
  }
})

function submit(): { data: Record<string, string>; errors: Record<string, string> } | undefined {
  if (!formInstance) return undefined
  const { data, errors } = formInstance.submit()
  if (errors && Object.keys(errors).length > 0) {
    emit('error', errors)
  } else {
    emit('submit', data)
  }
  return { data, errors: errors || {} }
}

defineExpose({ submit })
</script>

<template>
  <div ref="containerRef" class="form-js-container" />
</template>

<style>
@import '@bpmn-io/form-js/dist/assets/form-js.css';
</style>
