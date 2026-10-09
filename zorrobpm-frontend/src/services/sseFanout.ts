/**
 * WO-UI-26 Доп.5 (критерий 21): ОДНО SSE-соединение на браузер, а не на вкладку.
 *
 * Причина (измерено живьём, см. отчёт): edge отвечает по HTTP/1.1 — максимум
 * 6 соединений на origin. Каждая вкладка ZBPM держала свой EventSource, и при
 * 6+ вкладках API-запросы висели 30–52 с (`/api/auth/me`: 7 мс с 1 вкладкой →
 * TIMEOUT с 6 потоками). Решение: лидер-вкладка держит EventSource и
 * ретранслирует события остальным через BroadcastChannel; остальные вкладки
 * EventSource НЕ открывают; при закрытии лидера — переизбрание с Last-Event-ID.
 *
 * Механизм лидерства: Web Locks API (`zbpm-sse-leader`) — лок удерживает
 * только лидер, остальные ждут в `request()`; смерть лидера = автоматическое
 * освобождение лока = мгновенное переизбрание без heartbeat-протокола.
 * Fallback без Web Locks (старый браузер): каждая вкладка работает
 * самостоятельно, как до WO (деградация, не поломка).
 *
 * Протокол канала `zbpm-sse-fanout`:
 *  - лидер публикует `{ kind: 'event', envelope }` на каждое SSE-событие;
 *  - лидер публикует `{ kind: 'health', patch }` на смену здоровья канала;
 *  - новый лидер после избрания публикует `{ kind: 'leader', tabId }`;
 *  - подписчик, получивший `leader`, переподписывается молча (без мигания).
 */
import type { EventEnvelope } from '@/types/api'

export const SSE_FANOUT_CHANNEL = 'zbpm-sse-fanout'
export const SSE_LEADER_LOCK = 'zbpm-sse-leader'

export interface SseHealthPatch {
  isConnected?: boolean
  wasConnected?: boolean
  realtimeDown?: boolean
  sessionExpired?: boolean
  lastEventId?: string | null
}

export type SseFanoutMessage =
  | { kind: 'event'; envelope: EventEnvelope }
  | { kind: 'health'; patch: SseHealthPatch }
  | { kind: 'leader'; tabId: string }

export type SseFanoutListener = (msg: SseFanoutMessage) => void

const listeners = new Set<SseFanoutListener>()
let channel: BroadcastChannel | null = null
let tabId: string | null = null
let isLeader = false
let lockHeld = false
/** Отпустить лок лидерства (закрытие вкладки/размонтирование). */
let releaseLock: (() => void) | null = null

function ensureChannel(): BroadcastChannel | null {
  if (typeof BroadcastChannel === 'undefined') return null
  if (!channel) {
    channel = new BroadcastChannel(SSE_FANOUT_CHANNEL)
    channel.onmessage = (ev: MessageEvent<SseFanoutMessage>) => {
      const msg = ev.data
      if (!msg || typeof msg !== 'object') return
      // Свои же публикации лидер тоже получает — это нормально: подписчики
      // обрабатывают их как чужие (идемпотентно по sequence, см. Доп.4).
      // Но объявление о СВОЁМ лидерстве себе не транслируем обратно.
      if (msg.kind === 'leader' && msg.tabId === tabId) return
      for (const fn of [...listeners]) {
        try {
          fn(msg)
        } catch (e) {
          console.error('[SseFanout] listener failed:', e)
        }
      }
    }
  }
  return channel
}

function ensureTabId(): string {
  if (!tabId) {
    tabId = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
  }
  return tabId
}

export function getSseTabId(): string {
  return ensureTabId()
}

export function isSseLeader(): boolean {
  return isLeader
}

/** Опубликовать событие остальным вкладкам (зовёт только лидер). */
export function publishFanout(msg: SseFanoutMessage): void {
  // BroadcastChannel НЕ доставляет сообщение отправителю (спецификация) —
  // в проде это и нужно (вкладки разные). Но подписчики ЭТОГО ЖЕ контекста
  // (тесты, два экземпляра composable в одном окне) иначе тишину получат:
  // доставляем им локально, синхронно. Дубль для кросс-вкладочных
  // подписчиков исключён идемпотентностью по sequence (Доп.4).
  for (const fn of [...listeners]) {
    try {
      fn(msg)
    } catch (e) {
      console.error('[SseFanout] listener failed:', e)
    }
  }
  ensureChannel()?.postMessage(msg)
}

/** Подписаться на ретрансляцию лидера (зовут НЕ-лидеры). Возвращает отписку. */
export function subscribeFanout(fn: SseFanoutListener): () => void {
  ensureChannel()
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}

/**
 * Попытаться стать лидером. Резолвится `true` — эта вкладка держит EventSource;
 * `false` — другая вкладка лидер (или нет Web Locks — тогда каждая сама).
 *
 * Переизбрание (кр.21, HOLD раунд 1): follower НЕ засыпает на 1500 мс таймауте
 * навсегда. `locks.request` без ограничения висит в очереди: смерть лидера =
 * браузер отдаёт лок следующему = колбэк follower вызывается = handle
 * вызывает `onPromoted` (follower открывает свой EventSource с Last-Event-ID
 * и объявляет себя `leader`). 1500 мс таймаут — только чтобы НЕ-лидер не
 * висел без UI-статуса: после него резолвится `leader:false` (подписка на
 * ретрансляцию), а висящий request продолжает ждать повышения.
 */
export function acquireSseLeadership(onRevoked: () => void): Promise<{
  leader: boolean
  held: () => void
  release: () => void
  /**
   * Ставится вызывающим: смерть лидера → follower становится лидером.
   * Колбэк получает НАСТОЯЩИЙ release нового холда (а не no-op из
   * follower-резолва): повышенный лидер обязан отпускать лок в disconnect,
   * иначе цепочка переизбраний рвётся (лок висит за закрытым соединением,
   * следующий кандидат ждёт вечно). Self-review раунда 2, тест ниже.
   */
  onPromoted: (fn: (release: () => void) => void) => void
}> {
  ensureTabId()
  ensureChannel()
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined
  if (!locks || typeof locks.request !== 'function') {
    // Fallback: без Web Locks каждая вкладка сама (как до WO).
    return Promise.resolve({ leader: true, held: () => {}, release: () => {}, onPromoted: () => {} })
  }
  return new Promise((resolve) => {
    let settled = false
    // Повышение follower после смерти лидера (ставит вызывающий;
    // получает настоящий release — см. тип выше).
    let promoted: ((release: () => void) => void) | null = null
    const onPromoted = (fn: (release: () => void) => void): void => {
      promoted = fn
    }
    const release = () => {
      if (releaseLock) {
        const r = releaseLock
        releaseLock = null
        isLeader = false
        lockHeld = false
        try {
          r()
        } catch {
          // лок уже отпущен браузером — не фатально
        }
        onRevoked()
      }
    }
    // Web Locks: колбэк держится, пока удерживаем лок; при уходе вызываем release.
    void locks.request(
      SSE_LEADER_LOCK,
      { steal: false },
      () =>
        new Promise<void>((releaseResolve) => {
          releaseLock = () => releaseResolve()
          if (!settled) {
            settled = true
            isLeader = true
            lockHeld = true
            publishFanout({ kind: 'leader', tabId: ensureTabId() })
            resolve({ leader: true, held: () => {}, release, onPromoted })
          } else if (promoted) {
            // Лок дождался очереди ПОСЛЕ follower-таймаута: повышение.
            // Передаём настоящий release этого холда (а не no-op резолва):
            // иначе повышенный лидер не отпустит лок в disconnect.
            isLeader = true
            lockHeld = true
            publishFanout({ kind: 'leader', tabId: ensureTabId() })
            const p = promoted
            promoted = null
            p(release)
          }
        }),
    )
    // Если лок занят другой вкладкой — request ждёт; через таймаут считаем
    // себя follower (лок освободится — станет лидером через onPromoted).
    setTimeout(() => {
      if (!settled) {
        settled = true
        isLeader = false
        resolve({
          leader: false,
          held: () => {},
          release: () => {
            // follower ничего не держит — нечего отпускать
          },
          onPromoted,
        })
      }
    }, 1500)
  })
}

/** Жив ли лок лидерства прямо сейчас (для тестов/диагностики). */
export function isLeadershipHeldForTest(): boolean {
  return lockHeld
}

/** Только для тестов: сброс состояния модуля между кейсами. */
export function resetSseFanoutForTest(): void {
  listeners.clear()
  try {
    channel?.close()
  } catch {
    // ignore
  }
  channel = null
  tabId = null
  isLeader = false
  lockHeld = false
  releaseLock = null
}
