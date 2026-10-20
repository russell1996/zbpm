import { nextTick, onBeforeUnmount } from 'vue'

/**
 * WO-UI-39 п.2 — восстановление позиции прокрутки списка при «Назад».
 *
 * В ZorroBPM список прокручивается НЕ окном, а внутренним контейнером
 * `<main class="flex-1 overflow-auto">` в MainLayout — поэтому штатный
 * `scrollBehavior` роутера (он работает с window.scrollTo) позицию списка
 * не восстанавливает. Здесь позиция сохраняется в памяти по ключу списка и
 * возвращается после загрузки данных.
 *
 * Позиция сохраняется при размонтировании списка (уход в карточку) и
 * восстанавливается после того, как данные доехали и список перерисовался
 * (иначе контейнер ещё пустой и браузер обрежет позицию в ноль).
 */

/** Контейнер прокрутки приложения — помечен в MainLayout. */
export const SCROLL_CONTAINER_SELECTOR = '[data-scroll-container]'

/** Сохранённые позиции: ключ списка → scrollTop. */
const positions = new Map<string, number>()

function scrollContainer(): HTMLElement | null {
  if (typeof document === 'undefined') return null
  return document.querySelector<HTMLElement>(SCROLL_CONTAINER_SELECTOR)
}

export interface UseListScroll {
  /** Сохранить текущую позицию (вызывается автоматически при размонтировании). */
  save: () => void
  /** Восстановить сохранённую позицию (после загрузки данных). */
  restore: () => Promise<void>
  /** Забыть сохранённую позицию (например, список перезагрузили с другим фильтром). */
  forget: () => void
}

export function useListScroll(key: string): UseListScroll {
  function save() {
    const el = scrollContainer()
    if (!el) return
    positions.set(key, el.scrollTop)
  }

  async function restore(): Promise<void> {
    const saved = positions.get(key)
    if (saved === undefined) return
    // Ждём перерисовку списка: контейнер должен быть достаточно высоким,
    // иначе браузер обрежет позицию в ноль.
    await nextTick()
    const el = scrollContainer()
    if (!el) return
    el.scrollTop = saved
  }

  function forget() {
    positions.delete(key)
  }

  // Уход в карточку размонтирует список — запоминаем позицию.
  onBeforeUnmount(save)

  return { save, restore, forget }
}
