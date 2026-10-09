// @vitest-environment jsdom
/**
 * WO-UI-26 Доп.1 (кр.12) — диаграмма инстанса показывает ход процесса живьём.
 *
 * Проводка уже есть (UI-25: refreshLive читает activities окном, маркеры идут
 * пропсами, importXML — только при смене xml): здесь фиксируем контракт —
 * последовательность «событие → новые active/completed-id» обновляет маркеры
 * ТОЧЕЧНО (canvas.addMarker), БЕЗ повторного importXML (полной перерисовки)
 * и БЕЗ сброса зума (fit-viewport не вызывается повторно).
 * Мутация: дёрнуть render() при смене маркеров → importXML ×2 → красный.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import BpmnViewer from './BpmnViewer.vue'

const registry = vi.hoisted(() => ({ instances: [] as Array<Record<string, unknown>> }))

vi.mock('bpmn-js/lib/NavigatedViewer', () => ({
  default: class {
    importXML = vi.fn().mockResolvedValue(undefined)
    destroy = vi.fn()
    canvas = {
      zoom: vi.fn(),
      addMarker: vi.fn(),
      removeMarker: vi.fn(),
      getRootElement: () => ({ id: 'root' }),
    }
    overlays = { add: vi.fn(), remove: vi.fn() }
    elementRegistry = {
      get: () => ({}),
      forEach: (fn: (el: { id: string }) => void) => {
        for (const id of ['taskA', 'taskB']) fn({ id })
      },
    }
    eventBus = { on: vi.fn() }
    zoomScroll = { stepZoom: vi.fn() }
    constructor() {
      registry.instances.push(this as unknown as Record<string, unknown>)
    }
    get(svc: string) {
      switch (svc) {
        case 'canvas': return this.canvas
        case 'overlays': return this.overlays
        case 'elementRegistry': return this.elementRegistry
        case 'eventBus': return this.eventBus
        case 'zoomScroll': return this.zoomScroll
        default: return {}
      }
    }
  },
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}))

function canvasOf() {
  const inst = registry.instances[registry.instances.length - 1] as {
    importXML: ReturnType<typeof vi.fn>
    canvas: { zoom: ReturnType<typeof vi.fn>; addMarker: ReturnType<typeof vi.fn>; removeMarker: ReturnType<typeof vi.fn> }
  }
  return inst
}

describe('BpmnViewer live markers (WO-UI-26 кр.12)', () => {
  beforeEach(() => {
    registry.instances = []
  })

  it('последовательность событий → маркеры точечно, без importXML и без сброса зума', async () => {
    const wrapper = mount(BpmnViewer, {
      props: { xml: '<defs/>', activeElementIds: [], completedElementIds: [] },
    })
    await flushPromises()
    const v = canvasOf()
    expect(v.importXML).toHaveBeenCalledTimes(1)
    const zoomAfterRender = (v.canvas.zoom as ReturnType<typeof vi.fn>).mock.calls.length
    expect(zoomAfterRender).toBeGreaterThanOrEqual(1)

    // Шаг 1: задача A стала активной (activity.created → refresh → active=[taskA]).
    await wrapper.setProps({ activeElementIds: ['taskA'] })
    await flushPromises()
    expect(v.canvas.addMarker).toHaveBeenCalledWith('taskA', 'highlight-active')
    expect(v.importXML).toHaveBeenCalledTimes(1)

    // Шаг 2: A завершена, B активна — маркеры обновлены, перерисовки нет.
    await wrapper.setProps({ activeElementIds: ['taskB'], completedElementIds: ['taskA'] })
    await flushPromises()
    expect(v.canvas.addMarker).toHaveBeenCalledWith('taskB', 'highlight-active')
    expect(v.canvas.addMarker).toHaveBeenCalledWith('taskA', 'highlight-completed')
    expect(v.importXML).toHaveBeenCalledTimes(1)

    // Зум не сброшен: fit-viewport не вызывался повторно после рендера.
    expect((v.canvas.zoom as ReturnType<typeof vi.fn>).mock.calls.length).toBe(zoomAfterRender)
    wrapper.unmount()
  })

  it('смена xml — честный перерендер (новый граф), маркеры поверх', async () => {
    const wrapper = mount(BpmnViewer, {
      props: { xml: '<defs-v1/>', activeElementIds: ['taskA'] },
    })
    await flushPromises()
    expect(canvasOf().importXML).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ xml: '<defs-v2/>' })
    await flushPromises()
    // Новый xml = новый граф: render() создал второй viewer (вторая запись
    // реестра), у каждого — ровно один importXML; маркеры применены поверх.
    expect(registry.instances).toHaveLength(2)
    const totalImports = registry.instances.reduce(
      (n, inst) => n + (inst.importXML as ReturnType<typeof vi.fn>).mock.calls.length, 0)
    expect(totalImports).toBe(2)
    expect(canvasOf().canvas.addMarker).toHaveBeenCalledWith('taskA', 'highlight-active')
    wrapper.unmount()
  })

  it('Н-7 token-count: счётчики MI/активных токенов — бейджи поверх элементов', async () => {
    const wrapper = mount(BpmnViewer, {
      props: { xml: '<defs/>', activeElementIds: ['taskA'], elementCounts: { taskA: 2, taskB: 5 } },
    })
    await flushPromises()
    const inst = registry.instances[registry.instances.length - 1] as {
      overlays: { add: ReturnType<typeof vi.fn>; remove: ReturnType<typeof vi.fn> }
    }
    // Старые бейджи сняты, новые — по одному на элемент с count > 0.
    expect(inst.overlays.remove).toHaveBeenCalledWith({ type: 'token-count' })
    expect(inst.overlays.add).toHaveBeenCalledWith(
      'taskA', 'token-count', expect.objectContaining({ html: expect.stringContaining('>2<') }),
    )
    expect(inst.overlays.add).toHaveBeenCalledWith(
      'taskB', 'token-count', expect.objectContaining({ html: expect.stringContaining('>5<') }),
    )
    // Нулевой счётчик — бейджа нет.
    inst.overlays.add.mockClear()
    await wrapper.setProps({ elementCounts: { taskA: 0 } })
    await flushPromises()
    expect(inst.overlays.add).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('Н-6: prefers-reduced-motion guard присутствует (пульс инцидента гасится)', async () => {
    // CSS-инвариант: самого keyframes pulse-red недостаточно — проверяем guard
    // в исходнике компонента (прецедент: stream-url mirroring тест читает
    // исходник, т.к. jsdom не считает компоновку).
    const { readFileSync } = await import('fs')
    const { resolve, dirname } = await import('path')
    const { fileURLToPath } = await import('url')
    const source = readFileSync(
      resolve(dirname(fileURLToPath(import.meta.url)), 'BpmnViewer.vue'),
      'utf-8',
    )
    expect(source).toContain('prefers-reduced-motion')
    expect(source).toContain('pulse-red')
  })
})
