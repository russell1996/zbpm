<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer'
import { ZoomIn, ZoomOut, Maximize, Map } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const props = defineProps<{
  xml: string
  activeElementIds?: string[]
  incidentElementIds?: string[]
  completedElementIds?: string[]
  /** Camunda Operate-style token counts shown as a badge on top of each element. */
  elementCounts?: Record<string, number>
}>()

const emit = defineEmits<{
  elementClick: [elementId: string]
}>()

const container = ref<HTMLDivElement>()
let viewer: InstanceType<typeof NavigatedViewer> | null = null

async function render() {
  if (!container.value || !props.xml) return

  if (viewer) {
    viewer.destroy()
  }

  viewer = new NavigatedViewer({
    container: container.value,
  })

  try {
    await viewer.importXML(props.xml)
    const canvas = viewer.get('canvas') as { zoom: (arg: string) => void; addMarker: (id: string, cls: string) => void; removeMarker: (id: string, cls: string) => void; getRootElement: () => { id: string } }
    canvas.zoom('fit-viewport')

    applyHighlights()
    applyCountOverlays()
    setupClickHandler()
  } catch (err) {
    console.error('Failed to render BPMN:', err)
  }
}

// Camunda Operate-style token-count badges on top of elements that hold active tokens
function applyCountOverlays() {
  if (!viewer) return
  const overlays = viewer.get('overlays') as {
    add: (id: string, type: string, opts: { position: object; html: string }) => void
    remove: (filter: { type: string }) => void
  }
  overlays.remove({ type: 'token-count' })
  if (!props.elementCounts) return
  for (const [id, count] of Object.entries(props.elementCounts)) {
    if (!count) continue
    try {
      overlays.add(id, 'token-count', {
        position: { top: -14, right: 14 },
        html: `<div class="bpmn-token-count" title="${count} active">${count}</div>`,
      })
    } catch {
      // element id not present in this diagram — ignore
    }
  }
}

function applyHighlights() {
  if (!viewer) return
  const canvas = viewer.get('canvas') as { addMarker: (id: string, cls: string) => void; removeMarker: (id: string, cls: string) => void }

  // Clear all markers
  const root = (viewer.get('canvas') as { getRootElement: () => { id: string } }).getRootElement()
  const elementRegistry = viewer.get('elementRegistry') as { forEach: (fn: (el: { id: string }) => void) => void }
  elementRegistry.forEach((el: { id: string }) => {
    canvas.removeMarker(el.id, 'highlight-active')
    canvas.removeMarker(el.id, 'highlight-incident')
    canvas.removeMarker(el.id, 'highlight-completed')
  })

  // Apply active markers
  if (props.activeElementIds) {
    for (const id of props.activeElementIds) {
      canvas.addMarker(id, 'highlight-active')
    }
  }

  // Apply incident markers
  if (props.incidentElementIds) {
    for (const id of props.incidentElementIds) {
      canvas.addMarker(id, 'highlight-incident')
    }
  }

  // Apply completed markers
  if (props.completedElementIds) {
    for (const id of props.completedElementIds) {
      canvas.addMarker(id, 'highlight-completed')
    }
  }
}

function setupClickHandler() {
  if (!viewer) return
  const eventBus = viewer.get('eventBus') as { on: (event: string, fn: (e: { element?: { id: string; labelTarget?: { id: string } } }) => void) => void }
  eventBus.on('element.click', (e: { element?: { id: string; labelTarget?: { id: string } } }) => {
    if (!e.element) return
    // clicking a text label yields the "<id>_label" element — resolve it to the real element
    const id = e.element.labelTarget?.id || e.element.id
    if (id) emit('elementClick', id)
  })
}

function zoomIn() {
  if (!viewer) return
  const zoomScroll = viewer.get('zoomScroll') as { stepZoom: (delta: number) => void }
  zoomScroll.stepZoom(1)
}

function zoomOut() {
  if (!viewer) return
  const zoomScroll = viewer.get('zoomScroll') as { stepZoom: (delta: number) => void }
  zoomScroll.stepZoom(-1)
}

function fitViewport() {
  if (!viewer) return
  const canvas = viewer.get('canvas') as { zoom: (arg: string) => void }
  canvas.zoom('fit-viewport')
}

watch(() => props.xml, () => { render() })
watch(() => props.activeElementIds, () => { applyHighlights() }, { deep: true })
watch(() => props.incidentElementIds, () => { applyHighlights() }, { deep: true })
watch(() => props.completedElementIds, () => { applyHighlights() }, { deep: true })
watch(() => props.elementCounts, () => { applyCountOverlays() }, { deep: true })

// WO-ACL-11 criterion 39: on window resize the diagram is recalculated — bpmn-js
// has its own call for that (canvas.zoom('fit-viewport')), we just re-invoke it.
onMounted(() => {
  render()
  window.addEventListener('resize', fitViewport)
})
onUnmounted(() => {
  window.removeEventListener('resize', fitViewport)
  viewer?.destroy()
})
</script>

<template>
  <!-- WO-ACL-11 criterion 37: the diagram stretches with its parent (layout),
       not with a fixed pixel height — the parent card provides the height. -->
  <div class="bpmn-viewer-wrapper border border-border rounded-lg overflow-hidden h-full flex flex-col">
    <div class="flex items-center gap-1 px-3 py-2 border-b border-border bg-muted/50">
      <button class="p-1.5 hover:bg-muted rounded transition-colors" :title="t('zoomIn')" @click="zoomIn">
        <ZoomIn class="h-4 w-4" />
      </button>
      <button class="p-1.5 hover:bg-muted rounded transition-colors" :title="t('zoomOut')" @click="zoomOut">
        <ZoomOut class="h-4 w-4" />
      </button>
      <button class="p-1.5 hover:bg-muted rounded transition-colors" :title="t('fitToViewport')" @click="fitViewport">
        <Maximize class="h-4 w-4" />
      </button>
    </div>
    <!-- WO-ACL-15 criterion 15: EXPLICIT light background for the canvas — the
         wrapper follows the theme, the diagram surface never inherits the dark
         card background (bpmn-js strokes are black by design). -->
    <div ref="container" class="bpmn-container h-full w-full flex-1 min-h-0" />
  </div>
</template>

<style scoped>
/* WO-ACL-15 criterion 15: the canvas surface is explicitly light — the token
   is the same #ffffff in both palettes, so the dark theme cannot eat the
   diagram (bpmn-js draws shapes/flows black by default). */
.bpmn-container {
  background: var(--color-bpmn-canvas);
}

/* shapes (tasks/events/gateways): stroke + light fill */
.bpmn-container :deep(.djs-shape.highlight-active .djs-visual > :is(rect, path, circle, polygon)) {
  stroke: #3b82f6 !important;
  stroke-width: 3px;
  filter: drop-shadow(0 0 6px rgba(59, 130, 246, 0.5));
}
.bpmn-container :deep(.djs-shape.highlight-completed .djs-visual > :is(rect, path, circle, polygon)) {
  stroke: #22c55e !important;
  fill: #f0fdf4 !important;
}
.bpmn-container :deep(.djs-shape.highlight-incident .djs-visual > :is(rect, path, circle, polygon)) {
  stroke: #ef4444 !important;
  fill: #fef2f2 !important;
  animation: pulse-red 2s infinite;
}

/* connections (sequence flows): stroke only — never fill an open path, that overlaps neighbours */
.bpmn-container :deep(.djs-connection.highlight-active .djs-visual path) {
  stroke: #3b82f6 !important;
  stroke-width: 2.5px;
  fill: none !important;
}
.bpmn-container :deep(.djs-connection.highlight-completed .djs-visual path) {
  stroke: #22c55e !important;
  fill: none !important;
}
.bpmn-container :deep(.djs-connection.highlight-incident .djs-visual path) {
  stroke: #ef4444 !important;
  fill: none !important;
}

@keyframes pulse-red {
  0%, 100% { filter: drop-shadow(0 0 2px rgba(239, 68, 68, 0.3)); }
  50% { filter: drop-shadow(0 0 8px rgba(239, 68, 68, 0.6)); }
}
</style>

<!-- not scoped: bpmn-js overlay HTML is injected outside this component's scope -->
<style>
.bpmn-token-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 10px;
  background: #3b82f6;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.25);
  border: 2px solid #fff;
}
</style>
