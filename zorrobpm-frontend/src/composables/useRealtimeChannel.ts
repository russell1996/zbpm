import type { useRealtimeEvents } from './useRealtimeEvents'

/**
 * WO-UI-26 Доп.6/Доп.7 — модульный синглтон «канал MainLayout».
 *
 * Зачем не provide/inject: MainLayout создаёт экземпляр useRealtimeEvents в
 * своём setup и должен отдать ЕГО ЖЕ в HeaderBar→ChannelStatusDot. provide в
 * setup родителя + inject в setup потомка в том же тике монтирования не
 * стыкуются детерминированно (потомок резолвит inject до provide родителя),
 * а refs живут внутри вызова composable — «свой экземпляр в точке» всегда
 * мёртв. Модульный синглтон: set — один раз из MainLayout.setup, get —
 * из ChannelStatusDot; clear — в тестах между кейсами. Последний set
 * побеждает (в приложении MainLayout один).
 */
type RealtimeHandle = ReturnType<typeof useRealtimeEvents>

let current: RealtimeHandle | null = null

export function useRealtimeChannel() {
  return {
    set(handle: RealtimeHandle): void {
      current = handle
    },
    get(): RealtimeHandle | null {
      return current
    },
    /** Только для тестов: сброс между кейсами. */
    resetForTest(): void {
      current = null
    },
  }
}
