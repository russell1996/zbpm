// @vitest-environment jsdom
/**
 * WO-ACL-11 criteria 37 & 39 — BpmnViewer:
 *  37 — no fixed pixel height anywhere (the diagram stretches with the layout);
 *       a file-level regression guard over the three files the WO names;
 *  39 — on window resize the diagram is recalculated via the bpmn-js call
 *       canvas.zoom('fit-viewport') — re-invoked, not re-invented.
 * jsdom cannot prove the "reaches the bottom edge" part (P-53) — these tests
 * are the regression guard the WO asks for; the visual proof needs a browser.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import BpmnViewer from './BpmnViewer.vue'

const mockZoom = vi.hoisted(() => vi.fn())
vi.mock('bpmn-js/lib/NavigatedViewer', () => ({
  default: class {
    importXML = vi.fn().mockResolvedValue(undefined)
    destroy = vi.fn()
    get(svc: string) {
      switch (svc) {
        case 'canvas':
          return { zoom: mockZoom, addMarker: vi.fn(), removeMarker: vi.fn(), getRootElement: () => ({ id: 'root' }) }
        case 'overlays':
          return { add: vi.fn(), remove: vi.fn() }
        case 'elementRegistry':
          return { forEach: vi.fn() }
        case 'eventBus':
          return { on: vi.fn() }
        case 'zoomScroll':
          return { stepZoom: vi.fn() }
        default:
          return {}
      }
    }
  },
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}))

const FRONTEND_ROOT = process.cwd() // vitest runs from the frontend root (npm test)

describe('BpmnViewer (WO-ACL-11 criteria 37, 39)', () => {
  beforeEach(() => { mockZoom.mockClear() })

  it('criterion 37 (regression guard): the diagram container has no fixed pixel height', async () => {
    const wrapper = mount(BpmnViewer, { props: { xml: '<bpmn />' } })
    await flushPromises()
    const container = wrapper.find('.bpmn-container')
    expect(container.attributes('style') ?? '').not.toMatch(/height/i)
    // height comes from the layout chain: h-full inside a flex column
    expect(container.classes()).toContain('h-full')
    expect(container.classes()).toContain('flex-1')
  })

  it('criterion 37 (regression guard): the three files the WO names contain no fixed pixel heights', () => {
    const files = [
      'src/widgets/bpmn/BpmnViewer.vue',
      'src/pages/processes/ProcessDefinitionDetail.vue',
      'src/pages/processes/ProcessInstanceDetail.vue',
    ]
    for (const f of files) {
      const src = readFileSync(join(FRONTEND_ROOT, f), 'utf8')
      // only the TEMPLATE matters: a fixed height there is the bug the WO names.
      // (CSS rules may legitimately size small decorations, e.g. the token badge.)
      const template = src.slice(0, src.indexOf('<style'))
      const found = template.match(/height:\s*\d+px|max-height:\s*\d+px/g)
      expect(found, `${f} template must not contain fixed pixel heights`).toBeNull()
    }
  })

  it('criterion 39: the diagram is recalculated on window resize (canvas.zoom("fit-viewport"))', async () => {
    mount(BpmnViewer, { props: { xml: '<bpmn />' } })
    await flushPromises()
    const afterMount = mockZoom.mock.calls.length
    expect(afterMount).toBeGreaterThan(0) // initial fit-viewport after import
    window.dispatchEvent(new Event('resize'))
    await flushPromises()
    expect(mockZoom.mock.calls.length).toBeGreaterThan(afterMount)
  })

  it('criterion 39 POF guard: the resize listener is removed on unmount', async () => {
    const removeSpy = vi.spyOn(window, 'removeEventListener')
    const wrapper = mount(BpmnViewer, { props: { xml: '<bpmn />' } })
    await flushPromises()
    wrapper.unmount()
    expect(removeSpy).toHaveBeenCalledWith('resize', expect.any(Function))
  })
})