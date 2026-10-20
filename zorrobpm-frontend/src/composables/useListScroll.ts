import { nextTick, onBeforeUnmount } from 'vue'

/**
 * WO-UI-39 п.2 — восстановление позиции прокрутки списка при «Назад».
 *
 * В ZorroBPM список прокручивается НЕ окном, а внутренним контейнером
 * `<main class="flex-1 overflow-auto">` в MainLayout — поэтому штатный
 * `scrollBehavior` роутера (он работает с window.scrollTo) позицию списка
 * не восстанавливает. Здесь позиция сохраняется по ключу списка и
 * возвращается после того, как данные доехали и список перерисовался
 * (иначе контейнер ещё пустой и браузер обрежет позицию в ноль).
 *
 * Порядок событий важен и зафиксирован тестами:
 * - позиция читается в `onBeforeUnmount` — в этот момент DOM списка ещё
 *   на месте, `scrollTop` точный (браузер обрежет его уже после удаления
 *   строк, то есть позже);
 * - сразу после сохранения позиция сбрасывается в 0 — карточка (или любая
 *   следующая страница) открывается с начала, а не с середины списка;
 * - `restore()` вызывается ПОСЛЕ загрузки данных (страница ждёт `load()`).
 */

/** Контейнер прокрутки приложения — помечен в MainLayout. */
export const SCROLL_CONTAINER_SELECTOR = '[data-scroll-container]'

/** Сохранённые позиции: ключ списка → scrollTop. */
const positions = new Map<string, number>()

export interface UseListScrollOptions {
  /**
   * Резолвер контейнера прокрутки ЭТОГО списка. По умолчанию — контейнер
   * приложения (`<main>` в MainLayout). Для списка с ВНУТРЕННИМ контейнером
   * (собственная прокрутка внутри страницы) передаётся свой элемент.
   */
  container?: () => HTMLElement | null
}

export interface UseListScroll {
  /** Сохранить текущую позицию (вызывается автоматически при уходе со списка). */
  save: () => void
  /** Восстановить сохранённую позицию (после загрузки данных). */
  restore: () => Promise<void>
}

/**
 * @param key ключ списка — состояние одного списка не должно протекать в другой
 */
export function useListScroll(key: string, options: UseListScrollOptions = {}): UseListScroll {
  function resolve(): HTMLElement | null {
    if (options.container) return options.container()
    if (typeof document === 'undefined') return null
    return document.querySelector<HTMLElement>(SCROLL_CONTAINER_SELECTOR)
  }

  function save(): void {
    const el = resolve()
    if (!el) return
    positions.set(key, el.scrollTop)
  }

  async function restore(): Promise<void> {
    // Ждём перерисовку списка: контейнер должен быть достаточно высоким,
    // иначе браузер обрежет позицию в ноль.
    await nextTick()
    const el = resolve()
    if (!el) return
    // Нет сохранённой позиции — список (или страница после него) открывается
    // с начала, а не с середины предыдущей страницы.
    el.scrollTop = positions.get(key) ?? 0
  }

  onBeforeUnmount(() => {
    save()
    // Уход со списка: следующая страница открывается с начала. Позиция списка
    // уже сохранена выше — её восстановит `restore()` при возврате «Назад».
    const el = resolve()
    if (el) el.scrollTop = 0
  })

  return { save, restore }
}
