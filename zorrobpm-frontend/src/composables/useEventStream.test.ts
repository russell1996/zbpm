import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useEventStream } from './useEventStream'
import type { EventEnvelope } from '@/types/api'

class MockEventSource {
  url: string
  options: { withCredentials: boolean }
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  readyState = 0

  static CONNECTING = 0
  static OPEN = 1
  static CLOSED = 2

  static _lastInstance: MockEventSource | null = null

  constructor(url: string, options: { withCredentials: boolean }) {
    this.url = url
    this.options = options
    MockEventSource._lastInstance = this
    setTimeout(() => {
      this.readyState = 1
      if (this.onopen) this.onopen(new Event('open'))
    }, 0)
  }

  close() {
    this.readyState = 2
  }
}

describe('useEventStream', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    // @ts-expect-error mock
    global.EventSource = MockEventSource
    MockEventSource._lastInstance = null
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('connects and receives events', () => {
    const onEvent = vi.fn()
    const { connect, isConnected } = useEventStream({ onEvent })

    connect()
    vi.advanceTimersByTime(10)
    expect(isConnected.value).toBe(true)

    const envelope: EventEnvelope = {
      sequence: 1,
      id: 'test-id',
      type: 'process-instance.started',
      version: 1,
      occurredAt: '2026-07-20T10:00:00Z',
      data: {}
    }
    MockEventSource._lastInstance!.onmessage!(
      new MessageEvent('message', { data: JSON.stringify(envelope) })
    )

    expect(onEvent).toHaveBeenCalledWith(envelope)
  })

  it('updates lastEventId on event', () => {
    const { connect, lastEventId } = useEventStream({})

    connect()
    vi.advanceTimersByTime(10)

    const envelope: EventEnvelope = {
      sequence: 42,
      id: 'test-id',
      type: 'process-instance.started',
      version: 1,
      occurredAt: '2026-07-20T10:00:00Z',
      data: {}
    }
    MockEventSource._lastInstance!.onmessage!(
      new MessageEvent('message', { data: JSON.stringify(envelope) })
    )

    expect(lastEventId.value).toBe('42')
  })

  it('disconnects cleanly', () => {
    const { connect, disconnect, isConnected } = useEventStream({})

    connect()
    vi.advanceTimersByTime(10)
    expect(isConnected.value).toBe(true)

    disconnect()
    expect(isConnected.value).toBe(false)
  })

  it('reconnects on error', () => {
    const { connect, isConnected } = useEventStream({})

    connect()
    vi.advanceTimersByTime(10)
    expect(isConnected.value).toBe(true)

    MockEventSource._lastInstance!.onerror!(new Event('error'))
    expect(isConnected.value).toBe(false)

    // Wait for reconnect (1s delay) + async open
    vi.advanceTimersByTime(1000)
    vi.advanceTimersByTime(10)
    expect(isConnected.value).toBe(true)
  })
})
