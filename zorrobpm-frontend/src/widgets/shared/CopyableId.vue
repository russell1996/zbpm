<script setup lang="ts">
/**
 * WO-ACL-14 criteria 17-19: the id is shown in FULL, always.
 *
 * The old `length` prop truncated the visible text (uuid → 8 chars, and
 * BPMN ids like `Activity_1abc` became gibberish); the click-to-reveal
 * (showFull) existed only because of that truncation and is gone with it.
 * The cell now shows the whole id, the title carries the whole id, and the
 * click NEVER navigates (callers use @click.stop / row-level navigation).
 */
import { ref } from 'vue'
import { Copy, Check } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const props = defineProps<{
  value: string
}>()

const copied = ref(false)

async function copy() {
  await navigator.clipboard.writeText(props.value)
  copied.value = true
  setTimeout(() => { copied.value = false }, 1500)
}
</script>

<template>
  <span
    class="inline-flex items-center gap-1 text-sm group cursor-pointer"
    :title="props.value"
    @click.stop
  >
    <span>{{ props.value }}</span>
    <button
      class="opacity-0 group-hover:opacity-100 transition-opacity p-0.5 hover:bg-muted rounded"
      :title="t('copyToClipboard')"
      @click.stop="copy"
    >
      <Check v-if="copied" class="h-3 w-3 text-green-500" />
      <Copy v-else class="h-3 w-3 text-muted-foreground" />
    </button>
  </span>
</template>