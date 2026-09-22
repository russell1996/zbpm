/**
 * WO-UI-19 — browser proof: breadcrumb out of a collapsed subprocess.
 *
 * jsdom cannot compute layout (P-53), so the "breadcrumb is VISIBLE and
 * clickable" criterion is asserted HERE, in a real headless Chromium (vitest
 * browser mode + Playwright provider). The REAL bpmn-js NavigatedViewer
 * renders the collapsed-subprocess fixture; drilldown opens the plane
 * programmatically (same call the dblclick handler issues internally);
 * assertions are geometry + computed style, not screenshots.
 *
 * The REAL bpmn-js CSS bundle is imported exactly as main.ts loads it. NOTE the
 * scope limit (proven by mutation, see report): these imports apply ONLY to
 * this test's iframe — they prove the CSS exists and styles the breadcrumb,
 * but they do NOT prove main.ts loads them (a main.ts without the import
 * still passes here). The main.ts-import proof is the jsdom test; browser +
 * jsdom together close the loop (P-47 lesson from WO-ACL-8).
 */
import { describe, it, expect } from 'vitest'
import { page } from 'vitest/browser'
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer'

// Same three files as src/main.ts (WO-UI-19) — the test fails closed if they
// drift apart: the file-level jsdom test guards main.ts textually, this file
// guards the rendered effect. NOTE: these imports apply ONLY to this test's
// iframe — they prove the CSS exists and styles the breadcrumb, but they do
// NOT prove main.ts loads them (a main.ts without the import still passes
// here — proven by mutation). The main.ts-import proof is the jsdom test
// above; browser + jsdom together close the loop (P-47 lesson from WO-ACL-8).
import 'bpmn-js/dist/assets/bpmn-js.css'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css'

const COLLAPSED_XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Process_1" isExecutable="true">
    <bpmn:startEvent id="StartEvent_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:subProcess id="SubProcess_1" name="sub"><bpmn:incoming>Flow_1</bpmn:incoming><bpmn:outgoing>Flow_2</bpmn:outgoing>
      <bpmn:startEvent id="Sub_Start"><bpmn:outgoing>Sub_Flow</bpmn:outgoing></bpmn:startEvent>
      <bpmn:task id="Sub_Task" name="inner"><bpmn:incoming>Sub_Flow</bpmn:incoming></bpmn:task>
    </bpmn:subProcess>
    <bpmn:endEvent id="EndEvent_1"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="SubProcess_1" />
    <bpmn:sequenceFlow id="Flow_2" sourceRef="SubProcess_1" targetRef="EndEvent_1" />
    <bpmn:sequenceFlow id="Sub_Flow" sourceRef="Sub_Start" targetRef="Sub_Task" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_1">
      <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="StartEvent_1"><dc:Bounds x="150" y="100" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="SubProcess_1_di" bpmnElement="SubProcess_1" isExpanded="false"><dc:Bounds x="250" y="80" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="EndEvent_1_di" bpmnElement="EndEvent_1"><dc:Bounds x="400" y="100" width="36" height="36" /></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`

async function openDrilldown(): Promise<{ container: HTMLElement; cleanup: () => void }> {
  const container = document.createElement('div')
  container.style.width = '900px'
  container.style.height = '600px'
  container.style.position = 'relative'
  document.body.appendChild(container)
  const viewer = new NavigatedViewer({ container })
  await viewer.importXML(COLLAPSED_XML)
  const canvas = viewer.get('canvas') as {
    zoom: (z: string) => void
    getRootElement: () => { id: string }
  }
  canvas.zoom('fit-viewport')
  // Same effect as clicking the drilldown button on the collapsed subprocess:
  // DrilldownOverlayBehavior wires the .bjs-drilldown overlay button to
  // canvas.setRootElement(canvas.findRoot(getPlaneIdFromShape(element))).
  // We click the REAL overlay button (not canvas.setRootElement directly),
  // so the whole chain — overlay → plane switch → DrilldownBreadcrumbs
  // (listens root.set) — is exercised honestly.
  const drilldownBtn = container.querySelector('.bjs-drilldown') as HTMLElement
  if (!drilldownBtn) {
    throw new Error('no .bjs-drilldown overlay button — fixture subprocess is not collapsed/drillable')
  }
  drilldownBtn.click()
  await new Promise((r) => setTimeout(r, 300))
  const cleanup = () => {
    viewer.destroy()
    container.remove()
  }
  return { container, cleanup }
}

describe('BpmnViewer breadcrumb (WO-UI-19, browser)', () => {
  it('breadcrumb shows after drilldown (toggle class present)', async () => {
    const { container, cleanup } = await openDrilldown()
    try {
      const crumbs = container.querySelector('.bjs-breadcrumbs')
      expect(crumbs, 'viewer must render .bjs-breadcrumbs on drilldown').not.toBeNull()
      const shown = container.querySelector('.bjs-breadcrumbs-shown')
        ?? document.querySelector('.bjs-breadcrumbs-shown')
      expect(shown, 'drilldown must add .bjs-breadcrumbs-shown').not.toBeNull()
    } finally {
      cleanup()
    }
  })

  it('breadcrumb is positioned over the canvas and visible (computed style)', async () => {
    const { container, cleanup } = await openDrilldown()
    try {
      const crumbs = container.querySelector('.bjs-breadcrumbs') as HTMLElement
      expect(crumbs).not.toBeNull()
      const cs = getComputedStyle(crumbs)
      // Without bpmn-js.css these are UA defaults (static, visible) — with it,
      // absolute + flex ONLY when the toggle class is on (visible breadcrumb).
      expect(cs.position).toBe('absolute')
      expect(cs.display).toBe('flex')
      const rect = crumbs.getBoundingClientRect()
      const host = container.getBoundingClientRect()
      expect(rect.width).toBeGreaterThan(0)
      expect(rect.height).toBeGreaterThan(0)
      // inside the canvas, top-left area (top:30px left:30px per the CSS)
      expect(rect.left).toBeGreaterThanOrEqual(host.left)
      expect(rect.top).toBeGreaterThanOrEqual(host.top)
      expect(rect.left - host.left).toBeLessThan(200)
      expect(rect.top - host.top).toBeLessThan(200)
    } finally {
      cleanup()
    }
  })

  it('clicking the root crumb navigates back to the top plane', async () => {
    const { container, cleanup } = await openDrilldown()
    try {
      const links = [...container.querySelectorAll('.bjs-breadcrumbs a, .bjs-breadcrumbs [data-id]')] as HTMLElement[]
      expect(links.length).toBeGreaterThan(0)
      links[0].click()
      await new Promise((r) => setTimeout(r, 300))
      const canvas = document.querySelector('.bjs-breadcrumbs-shown')
      // after navigating back the toggle is gone (root plane has no breadcrumbs)
      expect(canvas, 'root crumb click must leave the subprocess plane').toBeNull()
    } finally {
      cleanup()
    }
  })
})
