<script setup lang="ts">
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
</script>

<template>
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
        @select="emit('select', item.id)"
      >
        {{ item.label }}
      </DropdownMenuItem>
    </DropdownMenuContent>
  </DropdownMenu>
</template>
