<script setup lang="ts">
import { ref, computed } from 'vue'
import { Copy, Check } from 'lucide-vue-next'

const props = defineProps<{
  value: string
  length?: number
}>()

const copied = ref(false)
const showFull = ref(false)

async function copy() {
  await navigator.clipboard.writeText(props.value)
  copied.value = true
  setTimeout(() => { copied.value = false }, 1500)
}

const display = computed(() => {
  if (showFull.value) return props.value
  return props.length ? props.value.slice(0, props.length) : props.value
})

const truncated = computed(() => {
  const len = props.length || 8
  return props.value.length > len
})
</script>

<template>
  <span
    class="inline-flex items-center gap-1 font-mono text-xs group cursor-pointer"
    :title="value"
    @click="showFull = !showFull"
  >
    <span>{{ display }}<span v-if="truncated && !showFull">...</span></span>
    <button
      class="opacity-0 group-hover:opacity-100 transition-opacity p-0.5 hover:bg-muted rounded"
      title="Copy to clipboard"
      @click.stop="copy"
    >
      <Check v-if="copied" class="h-3 w-3 text-green-500" />
      <Copy v-else class="h-3 w-3 text-muted-foreground" />
    </button>
  </span>
</template>
