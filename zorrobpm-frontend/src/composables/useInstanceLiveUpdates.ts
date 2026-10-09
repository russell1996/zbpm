import { ref, watch, onUnmounted, type Ref } from 'vue'
import { subscribeRealtimeEvents } from '@/services/realtimeBus'
import { sharedRealtimeHealth } from './useRealtimeEvents'

/**
 * WO-UI-25 (критерии 3/4/5) — живая детальная страница инстанса.
 *
 * Подписка на общую realtime-шину (транспорт один — MainLayout) с фильтром по
 * processInstanceId из envelope: события чужого инстанса игнорируются.
 *
 * - Дебаунс (по умолчанию 250 мс): всплеск activity.completed схлопывается в
 *   один refresh — шторма запросов нет (критерий 4), состояние UI (скролл,
 *   активная вкладка, раскрытые блоки, выбранный элемент) не трогаем вообще:
 *   обновляются только данные сторов, ремаунта нет.
 * - Фолбэк-опрос (по умолчанию 4 с) — пока канал не отдаёт данные
 *   (reconnecting после обрыва или realtimeDown/sessionExpired): refresh идёт
 *   по таймеру; восстановление канала опрос выключает (критерий 5).
 * - Фоновая вкладка: live-refresh на паузе (очередь — один отложенный
 *   refresh при возврате), опрос тоже не гоняем зря.
 * - Отписка при unmount — утечек подписок/таймеров нет (критерий 5).
 */
export type InstanceLiveState = 'live' | 'reconnecting' | 'polling'

export interface InstanceLiveHealth {
  isConnected: Ref<boolean>
  wasConnected: Ref<boolean>
  realtimeDown: Ref<boolean>
  sessionExpired: Ref<boolean>
}

export function useInstanceLiveUpdates(options: {
  getInstanceId: () => string | null | undefined
  refresh: () => void | Promise<void>
  debounceMs?: number
  pollIntervalMs?: number
  /** По умолчанию — общее здоровье канала; в тестах — управляемые ref. */
  health?: InstanceLiveHealth
}) {
  const debounceMs = options.debounceMs ?? 250
  const pollIntervalMs = options.pollIntervalMs ?? 4000
  const health = options.health ?? sharedRealtimeHealth

  const liveState = ref<InstanceLiveState>('live')

  let debounceTimer: ReturnType<typeof setTimeout> | null = null
  let pollTimer: ReturnType<typeof setInterval> | null = null
  let pendingWhileHidden = false
  let disposed = false

  function isHidden(): boolean {
    return typeof document !== 'undefined' && document.visibilityState === 'hidden'
  }

  function doRefresh(): void {
    try {
      const r = options.refresh()
      if (r && typeof (r as Promise<void>).catch === 'function') {
        ;(r as Promise<void>).catch((e) => console.error('[InstanceLive] refresh failed:', e))
      }
    } catch (e) {
      console.error('[InstanceLive] refresh failed:', e)
    }
  }

  function scheduleRefresh(): void {
    if (disposed || isHidden()) {
      pendingWhileHidden = true
      return
    }
    // Уже запланирован — всплеск схлопывается, лишний таймер не ставим.
    if (debounceTimer) return
    debounceTimer = setTimeout(() => {
      debounceTimer = null
      if (disposed) return
      if (isHidden()) {
        pendingWhileHidden = true
        return
      }
      doRefresh()
    }, debounceMs)
  }

  /** Ручной refresh (не используется кнопкой «Обновить» — та зовёт reloadAll
   *  напрямую; оставлен для тестов и будущих вызывающих). */
  function refreshNow(): void {
    if (debounceTimer) {
      clearTimeout(debounceTimer)
      debounceTimer = null
    }
    pendingWhileHidden = false
    if (!disposed && !isHidden()) doRefresh()
  }

  function startPoll(): void {
    if (pollTimer) return
    pollTimer = setInterval(() => {
      if (!disposed && !isHidden()) doRefresh()
    }, pollIntervalMs)
  }

  function stopPoll(): void {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  function recompute(): void {
    if (disposed) return
    const dead = health.realtimeDown.value || health.sessionExpired.value
    if (dead) {
      liveState.value = 'polling'
      startPoll()
    } else if (health.isConnected.value) {
      // Канал жив — данные идут.
      liveState.value = 'live'
      stopPoll()
    } else if (!health.wasConnected.value) {
      // B-2 (verifier): холодный старт/deep link — SSE ещё не установлен,
      // initial fetch покрывает первую отрисовку. Зелёное «live» здесь врало
      // бы; честное состояние — reconnecting (канал в пути) + опрос-страж.
      liveState.value = 'reconnecting'
      startPoll()
    } else {
      // Был жив и упал, нативный автореконнект в полёте — данные не идут,
      // опрашиваем, пока канал не поднимется или не будет объявлен мёртвым.
      liveState.value = 'reconnecting'
      startPoll()
    }
  }

  function onVisibility(): void {
    if (!isHidden() && pendingWhileHidden) {
      pendingWhileHidden = false
      scheduleRefresh()
    }
  }

  const unsubscribe = subscribeRealtimeEvents((envelope) => {
    const id = options.getInstanceId()
    if (!id || envelope.processInstanceId !== id) return
    scheduleRefresh()
  })

  const stopWatch = watch(
    () => [
      health.isConnected.value,
      health.wasConnected.value,
      health.realtimeDown.value,
      health.sessionExpired.value,
    ],
    recompute,
  )
  recompute()

  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', onVisibility)
  }

  onUnmounted(() => {
    disposed = true
    stopWatch()
    unsubscribe()
    if (debounceTimer) clearTimeout(debounceTimer)
    stopPoll()
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', onVisibility)
    }
  })

  return { liveState, refreshNow }
}
