import { computed, type ComputedRef } from 'vue'
import { useRouter, type RouteLocationRaw } from 'vue-router'
import { readListQuery } from './useListState'

/**
 * WO-UI-41 — «назад к списку» из карточки С СОХРАНЁННЫМИ ФИЛЬТРАМИ.
 *
 * Проблема (жалоба владельца 2026-10-21): стрелка «назад» в карточке была
 * голой ссылкой на маршрут списка БЕЗ query — фильтры списка терялись
 * (WO-UI-39 держал состояние в query, но карточка открывалась по адресу
 * без него).
 *
 * Правило (осознанное, описано в отчёте):
 *  - если ПРЕДЫДУЩАЯ запись браузерной истории — этот же список, стрелка идёт
 *    по ИСТОРИИ (`router.back()`): пользователь возвращается ровно туда,
 *    откуда пришёл, вместе с фильтрами и позицией прокрутки;
 *  - иначе (карточку открыли по прямой ссылке, из закладки, из другого места)
 *    стрелка открывает список с ПОСЛЕДНИМ СОХРАНЁННЫМ query этого списка
 *    (`useListState` помнит его в sessionStorage) — фильтры не теряются и
 *    при таком входе;
 *  - переход из бокового меню на список остаётся ЧИСТЫМ (дефолты) — это
 *    осознанное поведение: из меню пользователь начинает новый просмотр.
 */

/**
 * Минимальный контракт роутера, который нужен композаблу. Проверка «есть ли
 * такой метод» — не защита от кода: в юнит-тестах страниц роутер часто
 * подменён частичным моком (`useRouter: () => ({ push })`), и стрелка «назад»
 * не должна ронять страницу из-за отсутствующего `resolve`/`back`.
 */
interface BackRouter {
  push?: (to: RouteLocationRaw) => unknown
  back?: () => void
  resolve?: (to: RouteLocationRaw) => { href: string; path: string }
  options?: { history?: { state?: { back?: unknown } } }
}

export interface UseListBack {
  /** Куда ведёт стрелка «назад» (запасной вариант — «push» списка с query). */
  to: ComputedRef<RouteLocationRaw>
  /** Адрес ссылки (с сохранёнными фильтрами) — undefined, если роутер недоступен. */
  href: ComputedRef<string | undefined>
  /**
   * Предыдущая запись истории — этот список? Проверяется ПО МОМЕНТУ клика
   * (не computed): `history.state` не реактивен, кэш computed устарел бы.
   */
  canGoBack: () => boolean
  /** Действие стрелки «назад». */
  back: () => void
}

export function useListBack(listKey: string, listRouteName: string): UseListBack {
  const router = useRouter() as unknown as BackRouter | undefined

  // Последний query списка читается ОДИН раз: карточка открывается после
  // списка, и её состояние не меняется, пока карточка на экране.
  const savedQuery = readListQuery(listKey)
  const to = computed<RouteLocationRaw>(() => ({ name: listRouteName, query: savedQuery }))

  const href = computed<string | undefined>(() => {
    if (typeof router?.resolve !== 'function') return undefined
    try {
      return router.resolve(to.value).href
    } catch {
      // Маршрута списка нет в этом роутере (частичный мок в тестах) —
      // ссылка просто без адреса, стрелка остаётся на месте.
      return undefined
    }
  })

  function canGoBack(): boolean {
    const back = router?.options?.history?.state?.back
    if (typeof back !== 'string' || back === '') return false
    if (typeof router?.resolve !== 'function') return false
    let listPath: string
    try {
      listPath = router.resolve({ name: listRouteName }).path
    } catch {
      return false
    }
    return back.split('?')[0] === listPath
  }

  function back(): void {
    if (canGoBack() && typeof router?.back === 'function') {
      router.back()
      return
    }
    if (typeof router?.push === 'function') {
      void router.push(to.value)
    }
  }

  return { to, href, canGoBack, back }
}
