<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'

/**
 * WO-VT-3: общее меню действий «⋯» (карточка переменной, элемент панели
 * шаблонов). Кнопка ≥32px, меню — role=menu, Esc закрывает, клик мимо
 * закрывает, фокус возвращается на кнопку. Без новых зависимостей.
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

const open = ref(false)
const btnRef = ref<HTMLButtonElement | null>(null)
const menuRef = ref<HTMLDivElement | null>(null)

function onDocumentClick(e: MouseEvent) {
  if (!open.value) return
  const target = e.target as Node | null
  if (menuRef.value?.contains(target)) return
  if (btnRef.value?.contains(target)) return
  close()
}

function toggle() {
  open.value ? close() : openMenu()
}

async function openMenu() {
  open.value = true
  document.addEventListener('click', onDocumentClick)
  await nextTick()
  menuRef.value?.querySelector<HTMLButtonElement>('button')?.focus()
}

function close() {
  open.value = false
  document.removeEventListener('click', onDocumentClick)
  btnRef.value?.focus()
}

function choose(id: string) {
  open.value = false
  document.removeEventListener('click', onDocumentClick)
  emit('select', id)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.stopPropagation()
    close()
  }
}

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
})

defineExpose({ close })
</script>

<template>
  <div class="relative shrink-0">
    <button
      ref="btnRef"
      type="button"
      data-testid="row-menu-button"
      class="inline-flex h-8 w-8 items-center justify-center rounded-md text-lg leading-none text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:outline-2 focus-visible:outline-primary"
      aria-haspopup="menu"
      :aria-expanded="open ? 'true' : 'false'"
      :aria-label="label"
      :title="label"
      @click="toggle"
      @keydown="onKeydown"
    >
      <span aria-hidden="true">⋯</span>
    </button>
    <div
      v-if="open"
      ref="menuRef"
      role="menu"
      class="absolute right-0 top-full z-30 mt-1 min-w-40 overflow-hidden rounded-md border border-border bg-popover py-1 shadow-lg"
      @keydown="onKeydown"
    >
      <button
        v-for="item in items"
        :key="item.id"
        type="button"
        role="menuitem"
        :data-testid="`row-menu-item-${item.id}`"
        class="flex w-full items-center px-3 py-2 text-left text-sm hover:bg-muted focus-visible:bg-muted focus-visible:outline-none"
        :class="item.danger ? 'text-red-500' : 'text-foreground'"
        @click="choose(item.id)"
      >
        {{ item.label }}
      </button>
    </div>
  </div>
</template>
