// @vitest-environment jsdom
/**
 * WO-UI-25 критерии 3/4/5 — useInstanceLiveUpdates (живая страница инстанса).
 *
 * Управляемая подача через realtimeBus (без EventSource, без sleep — только
 * fake-таймеры): событие текущего инстанса → дебаунснутый refresh; чужой
 * инстанс → игнор; всплеск 100 событий → 1 refresh; обрыв канала → опрос;
 * восстановление → опрос стоп; unmount → отписка; скрытая вкладка → пауза.
 *
 * RED на коде до WO-UI-25: модулей realtimeBus/useInstanceLiveUpdates нет
 * (импорт падает), страница инстанса не подписана ни на что.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ref, type Ref } from 'vue'
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
  refresh?: () => void | Promise<void>
  h?: ReturnType<typeof health>
  debounceMs?: number
  pollIntervalMs?: number
}) {
  const refresh = vi.fn(() => Promise.resolve())
  const host = defineComponent({
    setup() {
      const live = useInstanceLiveUpdates({
        getInstanceId: () => opts.instanceId,
        refresh,
        debounceMs: opts.debounceMs ?? 250,
        pollIntervalMs: opts.pollIntervalMs ?? 4000,
        health: opts.h,
      })
      return () => h('div', live.liveState.value)
    },
  })
  const wrapper = mount(host)
  return { wrapper, refresh }
}

describe('useInstanceLiveUpdates (WO-UI-25)', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    resetRealtimeBusForTest()
  })

  afterEach(() => {
    vi.useRealTimers()
    resetRealtimeBusForTest()
  })

  it('criterion 3: event of the current instance triggers a debounced refresh', async () => {
    const { wrapper, refresh } = mountHook({ instanceId: 'pi-1' })
    publishRealtimeEvent(envelope('activity.completed', 'pi-1'))
    expect(refresh).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(250)
    expect(refresh).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('criterion 3: foreign-instance events are ignored', async () => {
    const { wrapper, refresh } = mountHook({ instanceId: 'pi-1' })
    publishRealtimeEvent(envelope('activity.completed', 'pi-OTHER'))
    publishRealtimeEvent(envelope('user-task.created', 'pi-OTHER'))
    await vi.advanceTimersByTimeAsync(1000)
    expect(refresh).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('criterion 4: a burst of 100 events coalesces into ONE refresh', async () => {
    const { wrapper, refresh } = mountHook({ instanceId: 'pi-1' })
    for (let i = 1; i <= 100; i++) {
      publishRealtimeEvent(envelope('activity.completed', 'pi-1', i))
    }
    await vi.advanceTimersByTimeAsync(250)
    expect(refresh).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('criterion 4: spaced events refresh once each (no lost updates)', async () => {
    const { wrapper, refresh } = mountHook({ instanceId: 'pi-1' })
    publishRealtimeEvent(envelope('activity.completed', 'pi-1', 1))
    await vi.advanceTimersByTimeAsync(250)
    publishRealtimeEvent(envelope('activity.completed', 'pi-1', 2))
    await vi.advanceTimersByTimeAsync(250)
    expect(refresh).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('criterion 5: channel loss polls while down, recovery stops it', async () => {
    const h = health(true)
    const { wrapper, refresh } = mountHook({ instanceId: 'pi-1', h })
    // Обрыв после живого соединения → reconnecting + периодический опрос
    // (нативный автореконнект в полёте, но данные не идут).
    h.isConnected.value = false
    await flushPromises()
    expect(wrapper.text()).toContain('reconnecting')
    await vi.advanceTimersByTimeAsync(4000)
    const polled = (refresh as ReturnType<typeof vi.fn>).mock.calls.length
    expect(polled).toBeGreaterThanOrEqual(1)
    // Канал объявлен мёртвым → polling, опрос продолжается.
    h.realtimeDown.value = true
    await flushPromises()
    expect(wrapper.text()).toContain('polling')
    // Восстановление → live, опрос выключается.
    h.isConnected.value = true
    h.realtimeDown.value = false
    await flushPromises()
    expect(wrapper.text()).toContain('live')
    const before = (refresh as ReturnType<typeof vi.fn>).mock.calls.length
    await vi.advanceTimersByTimeAsync(12000)
    expect((refresh as ReturnType<typeof vi.fn>).mock.calls.length).toBe(before)
    wrapper.unmount()
  })

  it('criterion 5: unmount unsubscribes (no refresh after leave, no leak)', async () => {
    const { wrapper, refresh } = mountHook({ instanceId: 'pi-1' })
    wrapper.unmount()
    publishRealtimeEvent(envelope('activity.completed', 'pi-1'))
    await vi.advanceTimersByTimeAsync(1000)
    expect(refresh).not.toHaveBeenCalled()
  })

  it('criterion 3: hidden tab pauses live refresh, visible flushes pending', async () => {
    const { wrapper, refresh } = mountHook({ instanceId: 'pi-1' })
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    publishRealtimeEvent(envelope('activity.completed', 'pi-1'))
    await vi.advanceTimersByTimeAsync(1000)
    expect(refresh).not.toHaveBeenCalled()
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(300)
    expect(refresh).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('liveState exposes reconnecting between loss and polling', async () => {
    const h: {
      isConnected: Ref<boolean>
      wasConnected: Ref<boolean>
      realtimeDown: Ref<boolean>
      sessionExpired: Ref<boolean>
    } = health(false)
    h.wasConnected.value = true
    const { wrapper } = mountHook({ instanceId: 'pi-1', h })
    await flushPromises()
    // Канал был жив и упал, но realtimeDown ещё не выставлен — переходное.
    expect(['reconnecting', 'polling']).toContain(wrapper.text())
    wrapper.unmount()
  })
})
