import { nextTick, onBeforeUnmount, ref, watch, type Ref } from 'vue'

/**
 * WO-VT-3 (критерий 8): общее поведение модалок шаблонов. Focus-trap
 * (Tab зациклен внутри), Esc закрывает, фон не скроллится (scroll-lock),
 * фокус при открытии — на заголовок/первое поле, при закрытии возвращается
 * на триггер. Один composable вместо копий в каждой модалке.
 */
export function usePresetModal(
  openRef: Ref<boolean>,
  dialogRef: Ref<HTMLElement | null>,
  onClose: () => void,
  triggerRef?: Ref<HTMLElement | null>,
) {
  const FOCUSABLE =
    'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), ' +
    'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

  function focusables(): HTMLElement[] {
    if (!dialogRef.value) return []
    return [...dialogRef.value.querySelectorAll<HTMLElement>(FOCUSABLE)].filter(
      (el) => el.offsetParent !== null || el === document.activeElement,
    )
  }

  function onKeydown(e: KeyboardEvent) {
    if (!openRef.value) return
    // WO-VT-3 HOLD r1 (RT-1): JSON-фулскрин поверх диалога обрабатывает Esc
    // сам (Esc-возврат в редактор с сохранением черновика). Ловушка диалога
    // его не трогает — иначе Esc закрывал бы весь диалог с потерей ввода
    // (бриф §2 требует ровно обратного).
    const target = e.target as HTMLElement | null
    if (target?.closest?.('[data-preset-fs]')) return
    if (e.key === 'Escape') {
      e.stopPropagation()
      onClose()
      return
    }
    if (e.key !== 'Tab' || !dialogRef.value) return
    const items = focusables()
    if (!items.length) return
    const first = items[0]
    const last = items[items.length - 1]
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault()
      last.focus()
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault()
      first.focus()
    }
  }

  function lockScroll(lock: boolean) {
    const already = document.body.dataset.presetModalCount
      ? parseInt(document.body.dataset.presetModalCount, 10)
      : 0
    const next = lock ? already + 1 : Math.max(0, already - 1)
    document.body.dataset.presetModalCount = String(next)
    document.body.style.overflow = next > 0 ? 'hidden' : ''
  }

  const stopWatch = watch(
    openRef,
    async (open) => {
      if (open) {
        lockScroll(true)
        document.addEventListener('keydown', onKeydown, true)
        await nextTick()
        // Фокус — на первое поле диалога (имя/поиск), не на кнопку закрытия.
        const first = focusables().find(
          (el) => el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT',
        )
        ;(first ?? focusables()[0])?.focus()
      } else {
        lockScroll(false)
        document.removeEventListener('keydown', onKeydown, true)
        triggerRef?.value?.focus?.()
      }
    },
    { immediate: true },
  )

  onBeforeUnmount(() => {
    document.removeEventListener('keydown', onKeydown, true)
    // Баланс счётчика: если размонтировали открытым — снять свой лок.
    if (openRef.value) lockScroll(false)
    stopWatch()
  })

  return { focusables }
}

/** Ctrl/⌘+Enter подтверждает форму (модалки завершения/шаблона). */
export function onCtrlEnter(e: KeyboardEvent, submit: () => void) {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault()
    submit()
  }
}
