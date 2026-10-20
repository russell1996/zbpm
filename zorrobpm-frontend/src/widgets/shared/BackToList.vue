<script setup lang="ts">
/**
 * WO-UI-41 — стрелка «назад к списку» в карточке.
 *
 * Общая деталь для ВСЕХ пар «список ↔ карточка»: инстансы, задачи,
 * сервисные задачи, инциденты, схемы процессов. Раньше каждая карточка
 * держала свою голую ссылку на маршрут списка (БЕЗ query) — фильтры списка
 * при возврате терялись.
 *
 * Что делает стрелка:
 *  - предыдущая запись истории — этот список → возврат ПО ИСТОРИИ
 *    (фильтры, страница и позиция прокрутки сохраняются как есть);
 *  - иначе → список с последним сохранённым query этого списка
 *    (`useListState` помнит его в sessionStorage).
 *
 * Ссылки — обычные `<a href>` (оформлены shadcn-кнопкой), поэтому работает
 * и «открыть в новой вкладке» (правый клик): href — это тот же список с
 * сохранёнными фильтрами.
 */
import { useI18n } from 'vue-i18n'
import { ArrowLeft } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { useListBack } from '@/composables/useListBack'

const props = defineProps<{
  /** Ключ списка в `useListState` (например 'instances'). */
  listKey: string
  /** Имя маршрута списка (например 'process-instances'). */
  listRouteName: string
}>()

const { t } = useI18n()
const { href, back } = useListBack(props.listKey, props.listRouteName)
</script>

<template>
  <Button variant="ghost" size="icon" as-child>
    <a
      :href="href"
      :aria-label="t('backToList')"
      data-testid="back-to-list"
      class="shrink-0 self-center"
      @click.prevent="back"
    >
      <ArrowLeft class="h-5 w-5" />
    </a>
  </Button>
</template>
