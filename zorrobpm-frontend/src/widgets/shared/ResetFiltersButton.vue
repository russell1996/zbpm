<script setup lang="ts">
/**
 * WO-UI-41 — кнопка «Сбросить фильтры» для списков.
 *
 * Показывается ТОЛЬКО когда состояние списка отличается от дефолтного
 * (`isDefault` из `useListState`) — в дефолтном виде кнопка лишняя.
 * Один клик возвращает дефолтные значения, первую страницу и очищает query
 * списка (это делает `reset()` + watcher записи состояния в URL).
 *
 * Общая деталь для всех списков — не копия в каждой странице.
 */
import { useI18n } from 'vue-i18n'
import { FilterX } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'

defineProps<{
  /** Состояние списка == дефолтному? Тогда кнопку не показываем. */
  isDefault: boolean
}>()

const emit = defineEmits<{ (e: 'reset'): void }>()
const { t } = useI18n()
</script>

<template>
  <Button
    v-if="!isDefault"
    variant="ghost"
    size="sm"
    data-testid="reset-filters"
    :aria-label="t('resetFilters')"
    @click="emit('reset')"
  >
    <FilterX class="h-4 w-4" />
    <span>{{ t('resetFilters') }}</span>
  </Button>
</template>
