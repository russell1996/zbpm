// @vitest-environment jsdom
/**
 * WO-UI-26 Доп.3 (кр.16/20) + Доп.2 (кр.14) — useInstanceLiveUpdates.
 *
 * - кр.16: терминальный инстанс → живые механизмы ВЫКЛЮЧЕНЫ (0 таймеров
 *   опроса, события инстанса игнорируются, индикатор «finished»); deep link на
 *   завершённый (сразу terminal) — вообще без живых механизмов; running→
 *   completed в открытой странице → ровно ОДИН финальный refresh, затем
 *   тишина (60 с — 0 запросов). Мутация: убрать проверку isTerminal → красный.
 * - кр.14: фолбэк-опрос при мёртвом канале — backoff 4→8→15 с (cap), не
 *   фиксированные 4 с; за 60 с ≤ 6 запросов (было бы 15). Мутация: вернуть
 *   setInterval 4 с → красный. Восстановление канала сбрасывает базу.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { publishRealtimeEvent, resetRealtimeBusForTest } from '@/services/realtimeBus'
import { useInstanceLiveUpdates } from './useInstanceLiveUpdates'
import type { EventEnvelope } from '@/types/api'

function envelope(type: string, processInstanceId: string, sequence = 1): EventEnvelope {
  return {
    sequence, id: `e-${sequence}`, type, version: 1,
    occurredAt: '2026-10-09T00:00:00Z', processInstanceId, data: {},
  }
}

function health(connected = true) {
  return {
    isConnected: ref(connected),
    wasConnected: ref(connected),
    realtimeDown: ref(false),
    sessionExpired: ref(false),
  }
}

function mountHook(opts: {
  instanceId: string | null
  h?: ReturnType<typeof health>
  isTerminal?: () => boolean
  debounceMs?: number
  pollIntervalMs?: number
}) {
  const refresh = vi.fn(() => Promise.resolve())
  let live: ReturnType<typeof useInstanceLiveUpdates> | null = null
  const host = defineComponent({
    setup() {
      live = useInstanceLiveUpdates({
        getInstanceId: () => opts.instanceId,
        refresh,
        debounceMs: opts.debounceMs ?? 250,
        pollIntervalMs: opts.pollIntervalMs ?? 4000,
        health: opts.h,
        isTerminal: opts.isTerminal,
      })
      return () => h('div', live!.liveState.value)
    },
  })
  const wrapper = mount(host)
  return { wrapper, refresh, live: () => live! }
}

describe('useInstanceLiveUpdates terminal + backoff (WO-UI-26)', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    resetRealtimeBusForTest()
  })

  afterEach(() => {
    vi.useRealTimers()
    resetRealtimeBusForTest()
  })

  it('кр.16: завершённый инстанс с монтирования — finished, 0 опросов, события игнор', async () => {
    const h = health(true)
    const { wrapper, refresh } = mountHook({ instanceId: 'pi-1', h, isTerminal: () => true })
    await flushPromises()
    expect(wrapper.text()).toBe('finished')
    // Мёртвый канал + 60 с: опрос не стартует вообще.
    h.isConnected.value = false
    h.realtimeDown.value = true
    await flushPromises()
    expect(wrapper.text()).toBe('finished')
    await vi.advanceTimersByTimeAsync(60000)
    expect(refresh).not.toHaveBeenCalled()
    // События завершённого инстанса — игнор (кроме перехода, он идёт через finalRefresh).
    publishRealtimeEvent(envelope('activity.completed', 'pi-1', 5))
    await vi.advanceTimersByTimeAsync(5000)
    expect(refresh).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('кр.16: running→completed — ровно один финальный refresh, затем тишина', async () => {
    let terminal = false
    const h = health(true)
    const { wrapper, refresh, live } = mountHook({ instanceId: 'pi-1', h, isTerminal: () => terminal })
    await flushPromises()
    expect(wrapper.text()).toBe('live')
    // Переход: страница увидела completed → finalRefresh.
    terminal = true
    live().finalRefresh()
    await flushPromises()
    expect(wrapper.text()).toBe('finished')
    expect(refresh).toHaveBeenCalledTimes(1)
    // Повторный вызов — no-op; 60 с тишины даже при мёртвом канале.
    live().finalRefresh()
    h.isConnected.value = false
    h.realtimeDown.value = true
    await flushPromises()
    await vi.advanceTimersByTimeAsync(60000)
    expect(refresh).toHaveBeenCalledTimes(1)
    // Опоздавшее событие после завершения — игнор.
    publishRealtimeEvent(envelope('activity.completed', 'pi-1', 9))
    await vi.advanceTimersByTimeAsync(5000)
    expect(refresh).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('кр.16/мутация: без isTerminal завершение НЕ останавливает живые механизмы', async () => {
    // Контроль: доказывает, что тишину выше делает именно guard, а не тест.
    // (Мутация «убрать проверку isTerminal» превращает этот кейс в поведение
    // всех остальных — опрос идёт, события планируются.)
    const h = health(true)
    const { wrapper, refresh } = mountHook({ instanceId: 'pi-1', h })
    await flushPromises()
    expect(wrapper.text()).toBe('live')
    publishRealtimeEvent(envelope('activity.completed', 'pi-1', 2))
    await vi.advanceTimersByTimeAsync(300)
    expect(refresh).toHaveBeenCalledTimes(1)
    h.isConnected.value = false
    h.realtimeDown.value = true
    await flushPromises()
    await vi.advanceTimersByTimeAsync(4000)
    expect(refresh.mock.calls.length).toBeGreaterThanOrEqual(2)
    wrapper.unmount()
  })

  it('кр.14: опрос при мёртвом канале — backoff 4→8→15 с, за 60 с ≤ 6 запросов', async () => {
    const h = health(true)
    const { wrapper, refresh, live } = mountHook({ instanceId: 'pi-1', h })
    h.isConnected.value = false
    h.realtimeDown.value = true
    await flushPromises()
    expect(wrapper.text()).toBe('polling')
    // t=4 с — первый тик (база).
    await vi.advanceTimersByTimeAsync(4000)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(live().pollDelayForTest()).toBe(8000)
    // t=12 с — второй (4+8).
    await vi.advanceTimersByTimeAsync(8000)
    expect(refresh).toHaveBeenCalledTimes(2)
    expect(live().pollDelayForTest()).toBe(15000)
    // t=27 с — третий (cap 15 с держится дальше: 27/42/57).
    await vi.advanceTimersByTimeAsync(15000)
    expect(refresh).toHaveBeenCalledTimes(3)
    expect(live().pollDelayForTest()).toBe(15000)
    await vi.advanceTimersByTimeAsync(33000)
    expect(refresh).toHaveBeenCalledTimes(5)
    expect(refresh.mock.calls.length).toBeLessThanOrEqual(6)
    wrapper.unmount()
  })

  it('кр.14: восстановление канала сбрасывает backoff к базе', async () => {
    const h = health(true)
    const { wrapper, refresh, live } = mountHook({ instanceId: 'pi-1', h })
    h.isConnected.value = false
    h.realtimeDown.value = true
    await flushPromises()
    await vi.advanceTimersByTimeAsync(12000)
    expect(refresh).toHaveBeenCalledTimes(2)
    // Канал поднялся → опрос стоп; следующий обрыв — снова с базы 4 с.
    h.isConnected.value = true
    h.realtimeDown.value = false
    await flushPromises()
    expect(wrapper.text()).toBe('live')
    expect(live().pollDelayForTest()).toBe(4000)
    const before = refresh.mock.calls.length
    await vi.advanceTimersByTimeAsync(12000)
    expect(refresh.mock.calls.length).toBe(before)
    wrapper.unmount()
  })
})
