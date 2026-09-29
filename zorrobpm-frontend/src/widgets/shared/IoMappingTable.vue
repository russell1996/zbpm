<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

export interface IoMappingRow {
  source: string
  target: string
}

const COLLAPSE_AFTER = 5

const props = defineProps<{
  titleKey: string
  mappings: unknown
}>()

const { t } = useI18n()

const rows = computed<IoMappingRow[]>(() => {
  if (!Array.isArray(props.mappings)) return []
  return (props.mappings as unknown[]).filter(
    (m): m is IoMappingRow =>
      !!m &&
      typeof (m as IoMappingRow).source === 'string' &&
      typeof (m as IoMappingRow).target === 'string',
  )
})

const expanded = ref(false)
watch(
  () => props.mappings,
  () => {
    expanded.value = false
  },
)

const visibleRows = computed(() =>
  expanded.value ? rows.value : rows.value.slice(0, COLLAPSE_AFTER),
)
</script>

<template>
  <div v-if="rows.length" class="pt-2 border-t border-border space-y-1">
    <h4 class="text-xs font-semibold text-muted-foreground uppercase">
      {{ t(titleKey) }} ({{ rows.length }})
    </h4>
    <table class="w-full text-xs">
      <tbody>
        <tr v-for="(m, i) in visibleRows" :key="i" class="border-t border-border">
          <td class="py-1 pr-2 font-mono break-all">{{ m.source }}</td>
          <td class="py-1 pr-2 text-muted-foreground">→</td>
          <td class="py-1 font-mono break-all">{{ m.target }}</td>
        </tr>
      </tbody>
    </table>
    <button
      v-if="rows.length > COLLAPSE_AFTER"
      class="text-xs text-primary hover:underline"
      @click="expanded = !expanded"
    >
      {{ expanded ? t('showLess') : t('showMore') }}
    </button>
  </div>
</template>
