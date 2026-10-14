<script setup lang="ts">
/**
 * WO-UI-35 — обрезанный текст в ячейке открывается в shadcn Dialog целиком,
 * с копированием (владелец 2026-10-14: «если текст не помещается делай как
 * делаешь с json — открывай в диалоге»).
 *
 * Контракт:
 *  - текст обрезается одной строкой (truncate), как раньше;
 *  - ТОЛЬКО если реально обрезан (scrollWidth > clientWidth; ResizeObserver,
 *    пересчёт при ресайзе и при смене value) — span становится
 *    кликабельным/фокусируемым: role="button", tabindex=0, Enter/Space,
 *    cursor-pointer, aria-label с действием;
 *  - клик/Enter/Space — shadcn Dialog с ПОЛНЫМ текстом (font-mono,
 *    whitespace-pre-wrap break-words, max-height + внутренний скролл — как
 *    JSON-диалог VariablesEditor), заголовок = название колонки/контекста
 *    (проп title), shadcn Button «Копировать» + тост «Скопировано»;
 *  - ОДНО закрытие: крестик shadcn DialogContent (кнопки «Закрыть» нет —
 *    урок WO-UI-33); Esc закрывает; фокус возвращается на ячейку;
 *  - короткий текст — без изменений, не кликабелен, клик не гасится
 *    (поведение контейнера/строки сохраняется);
 *  - клик по ОБРЕЗАНному тексту не утекает на строку (stopPropagation —
 *    в IncidentList строка навигирует, клик по сообщению не должен
 *    уводить на детальную).
 *
 * G24: только shadcn-vue Dialog/Button, без голых button/input-тегов и
 * без самописных оверлеев (guard — truncated-text-g24.test.ts).
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from '@/composables/useToast'
import { Copy } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

const { t } = useI18n()
const toast = useToast()

const props = defineProps<{
  /** Полный текст (показывается в диалоге целиком). */
  value: string | null | undefined
  /** Заголовок диалога: название колонки/контекста. */
  title: string
}>()

const span = ref<HTMLElement | null>(null)
const truncated = ref(false)
const open = ref(false)

const text = computed(() => (props.value == null ? '' : String(props.value)))

function measure() {
  const el = span.value
  if (!el) return
  // Нулевая ширина (display:none) — клиппинга нет, «не обрезан».
  truncated.value = el.clientWidth > 0 && el.scrollWidth > el.clientWidth
}

let ro: ResizeObserver | null = null

onMounted(() => {
  void nextTick(measure)
  if (typeof ResizeObserver !== 'undefined' && span.value) {
    ro = new ResizeObserver(() => measure())
    ro.observe(span.value)
  }
})

onBeforeUnmount(() => {
  ro?.disconnect()
  ro = null
})

// Смена value (данные перечитались) — пересчёт: новый текст может уже
// не помещаться (или наоборот).
watch(text, () => void nextTick(measure))

function openDialog(e?: Event) {
  if (!truncated.value) return
  // Не даём клику утечь на строку/кнопку-контейнер.
  e?.stopPropagation()
  open.value = true
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' || e.key === ' ' || e.key === 'Spacebar') {
    e.preventDefault()
    openDialog(e)
  }
}

function onOpenChange(v: boolean) {
  open.value = v
}

function onCloseAutoFocus(e: Event) {
  // reka-ui FocusScope при закрытии по умолчанию восстанавливает фокус на
  // «последний сфокусированный элемент» (может быть null → body), и эта
  // очистка выполняется ПОСЛЕ нашего state-обновления — перетирая уже
  // возвращённый на ячейку фокус (доказано браузерным тестом: фокус на
  // BODY). preventDefault отменяет восстановление библиотеки (проверка
  // `unmountEvent.defaultPrevented` в FocusScope), фокус возвращаем сами —
  // детерминированно (критерий WO (г)).
  e.preventDefault()
  const el = span.value
  if (el && el.isConnected) el.focus()
}

async function copy() {
  try {
    await navigator.clipboard.writeText(text.value)
    toast.success(t('copied'))
  } catch {
    toast.error(t('truncatedCopyFailed'))
  }
}
</script>

<template>
  <span
    ref="span"
    data-testid="truncated-text"
    class="block truncate"
    :class="{ 'cursor-pointer': truncated }"
    :title="text"
    :role="truncated ? 'button' : undefined"
    :tabindex="truncated ? 0 : undefined"
    :aria-label="truncated ? t('truncatedShowFull') : undefined"
    @click="openDialog"
    @keydown="onKeydown"
  >
    {{ text }}
  </span>

  <Dialog :open="open" @update:open="onOpenChange">
    <DialogContent class="max-w-2xl" @close-auto-focus="onCloseAutoFocus">
      <DialogHeader>
        <DialogTitle data-testid="truncated-text-title" class="min-w-0 truncate">{{ title }}</DialogTitle>
        <DialogDescription class="sr-only">{{ t('truncatedFullText') }}</DialogDescription>
      </DialogHeader>
      <pre
        data-testid="truncated-text-content"
        class="m-0 max-h-[60vh] overflow-auto rounded-md bg-muted p-3 font-mono text-sm whitespace-pre-wrap break-words"
      >{{ text }}</pre>
      <DialogFooter>
        <Button
          type="button"
          variant="outline"
          size="sm"
          data-testid="truncated-text-copy"
          @click="copy"
        >
          <Copy class="h-4 w-4" />
          {{ t('copyToClipboard') }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
