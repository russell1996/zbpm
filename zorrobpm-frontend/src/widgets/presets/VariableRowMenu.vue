<script setup lang="ts">
import { ref } from 'vue'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

/**
 * WO-VT-3: общее меню действий «⋯» (карточка переменной, элемент панели
 * шаблонов). WO-UI-27 доп.3: shadcn-DropdownMenu (позиционирование,
 * клавиатура, aria из коробки) вместо самодельного absolute-меню.
 */
export interface RowMenuItem {
  id: string
  label: string
  danger?: boolean
}

defineProps<{
  items: RowMenuItem[]
  label: string
}>()

const emit = defineEmits<{
  (e: 'select', id: string): void
}>()

const root = ref<HTMLElement | null>(null)

/**
 * WO-VT-3 RT-3 (сохранено в WO-UI-27): выбор пункта возвращает фокус на
 * кнопку меню. Сам shadcn-DropdownMenu этого не делает (фокус остаётся в
 * dismissable-слое) — доводим явно после закрытия.
 */
function choose(id: string) {
  emit('select', id)
  // Dismissable-слой shadcn закрывается асинхронно и перетягивает фокус;
  // возвращаем фокус после закрытия (не nextTick — слой живёт дольше).
  window.setTimeout(() => {
    root.value?.querySelector<HTMLElement>('[data-testid="row-menu-button"]')?.focus()
  }, 30)
}
</script>

<template>
  <!-- Обёртка несёт внешние attrs (напр. data-testid="instance-actions-menu"):
       корень DropdownMenu ничего не рендерит, без неё attrs теряются. -->
  <div ref="root" class="relative shrink-0">
    <DropdownMenu>
      <DropdownMenuTrigger as-child>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          data-testid="row-menu-button"
          :aria-label="label"
          :title="label"
        >
          <span aria-hidden="true">⋯</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" class="min-w-40">
        <DropdownMenuItem
          v-for="item in items"
          :key="item.id"
          :data-testid="`row-menu-item-${item.id}`"
          :class="item.danger ? 'text-red-500' : ''"
          @select="choose(item.id)"
        >
          {{ item.label }}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  </div>
</template>
