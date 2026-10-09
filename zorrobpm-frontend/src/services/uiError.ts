import { ref, type Ref } from 'vue'

/**
 * WO-UI-26 Доп.1 (кр.11) — последний необработанный UI-сбой.
 *
 * Белый экран владельца: ошибка рендера/динамического импорта уносила всё
 * дерево (app.config.errorHandler не было — исключение всплывало в консоль,
 * экран оставался пустым навсегда). Теперь: errorHandler пишет сюда (причина
 * НЕ проглатывается — console.error остаётся), App.vue показывает понятную
 * панель с кнопкой «Перезагрузить» вместо белого экрана. Смена чанков при
 * плановом обновлении покрыта отдельно (router.onError → reload, WO-MT-9e).
 */
const lastUiError: Ref<{ message: string; at: string } | null> = ref(null)

export function reportUiError(err: unknown): void {
  // eslint-disable-next-line no-console
  console.error('[UI error boundary]', err)
  const message = err instanceof Error ? err.message : String(err)
  lastUiError.value = { message, at: new Date().toISOString() }
}

export function clearUiError(): void {
  lastUiError.value = null
}

export function useLastUiError(): Ref<{ message: string; at: string } | null> {
  return lastUiError
}

/**
 * WO-UI-26 Н-5: предикат «упал dynamic-import чанка» (плановое обновление,
 * обрыв сети посреди подгрузки). router.onError (WO-MT-9e) видит только
 * навигационные чанки; НЕ-навигационные dynamic import вне роутера падают в
 * unhandledrejection — их ведём на ту же панель App.vue.
 */
export function isChunkLoadError(reason: unknown): boolean {
  const message = reason instanceof Error ? reason.message : String(reason)
  return /Failed to fetch dynamically imported module|Loading chunk .* failed/i.test(message)
}

/**
 * WO-UI-26 Н-5: обработчик unhandledrejection для main.ts. Чанковые ошибки —
 * на панель (с preventDefault, причина в консоли через reportUiError);
 * остальные — НЕ трогаем (не проглатываем чужую логику, return false).
 */
export function handleUnhandledRejection(event: PromiseRejectionEvent): boolean {
  if (isChunkLoadError(event.reason)) {
    event.preventDefault()
    reportUiError(event.reason)
    return true
  }
  return false
}
