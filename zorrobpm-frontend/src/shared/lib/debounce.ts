/**
 * WO-UI-18 часть B: trailing debounce для watch на фильтры списков.
 *
 * Дополняет request-id guard сторов: guard чинит ПОРЯДОК (устаревший отклик
 * игнорируется), debounce снижает саму ЧАСТОТУ гонки — быстрое клацанье
 * чекбоксом шлёт один запрос вместо пачки. Один общий хелпер, а не пять
 * копий setTimeout по страницам (P-24).
 */
export function debounce<A extends unknown[]>(
  fn: (...args: A) => void,
  delayMs = 250,
): (...args: A) => void {
  let timer: ReturnType<typeof setTimeout> | null = null
  return (...args: A) => {
    if (timer !== null) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      fn(...args)
    }, delayMs)
  }
}
