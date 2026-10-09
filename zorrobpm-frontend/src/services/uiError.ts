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
