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
  /**
   * WO-UI-25 (критерий 8): плоскость drill-down как id ФИГУРЫ подпроцесса
   * (не plane-id с суффиксом — та же форма, что в ?plane= и в elementClick).
   * null/undefined = корневая плоскость. Реставрация — тем же вызовом, что
   * делает оверлей drill-down (canvas.setRootElement(findRoot(planeId))).
   */
  planeElementId?: string | null
}>()

const emit = defineEmits<{
  elementClick: [elementId: string]
  /** WO-UI-25: смена плоскости (drill-down, крошка, программное). null = корень. */
  planeChange: [elementId: string | null]
}>()

const container = ref<HTMLDivElement>()
let viewer: InstanceType<typeof NavigatedViewer> | null = null
/** Последняя применённая плоскость — защита от петли planeChange→prop→set. */
let appliedPlane: string | null | undefined = undefined
/** Корневая плоскость (plane процесса верхнего уровня, id без суффикса
 * _plane) — возврат в корень без поиска. Вычисляется после importXML. */
let initialRoot: { id: string } | null = null

function findProcessRootPlane(): { id: string } | null {
  if (!viewer) return null
  try {
    // Живым Chromium доказано: elementRegistry.getAll() plane-roots НЕ
    // содержит — только элементы АКТИВНОЙ plane (процесса верхнего уровня
    // в реестре вообще нет). Поэтому корень ищем через definitions-модель
    // (bpmn:Process среди rootElements) + canvas.findRoot(processId).
    // API: viewer.getDefinitions(), НЕ viewer.get('definitions') (такого
    // сервиса нет — проверено по BaseViewer.js).
    const definitions = (viewer as unknown as {
      getDefinitions?: () => { rootElements?: { $type?: string; id?: string }[] } | undefined
    }).getDefinitions?.()
    const canvas = viewer.get('canvas') as {
      findRoot: (id: string) => { id: string } | null
    }
    for (const re of definitions?.rootElements ?? []) {
      if (re.$type !== 'bpmn:Process' || !re.id) continue
      const plane = canvas.findRoot(re.id)
      if (plane) return plane
    }
    return null
  } catch {
    return null
  }
}
/** Подавляем эхо root.set, вызванного нашей же реставрацией. */
let restoringPlane = false

function planeIdOf(shapeId: string): string {
  return `${shapeId}_plane`
}

function shapeIdOfPlane(planeId: string): string | null {
  return planeId.endsWith('_plane') ? planeId.slice(0, -'_plane'.length) : null
}

function currentPlaneShapeId(): string | null {
  if (!viewer) return null
  try {
    const canvas = viewer.get('canvas') as { getRootElement: () => { id: string } }
    return shapeIdOfPlane(canvas.getRootElement().id)
  } catch {
    return null
  }
}

/** Применить planeElementId к уже отрисованной диаграмме (без пересоздания). */
function applyPlane(): void {
  if (!viewer) return
  const want = props.planeElementId ?? null
  if (want === appliedPlane) return
  try {
    const canvas = viewer.get('canvas') as {
      findRoot: (id: string) => { id: string } | null
      setRootElement: (root: { id: string }) => void
      zoom: (arg: string) => void
    }
    const elementRegistry = viewer.get('elementRegistry') as {
      get: (id: string) => unknown
    }
    if (want === null) {
      // Корень: плоскость, активная сразу после importXML (сохранена в
      // initialRoot — прямой getRootElements падает внутри diagram-js на
      // диаграммах с несколькими plane).
      if (initialRoot && currentPlaneShapeId() !== null) {
        restoringPlane = true
        try {
          canvas.setRootElement(initialRoot)
        } finally {
          restoringPlane = false
        }
        try {
          canvas.zoom('fit-viewport')
        } catch {
          // нулевой размер контейнера — не фатально
        }
      }
    } else {
      // Фигура обязана существовать в реестре — иначе plane из чужой
      // диаграммы (устаревший deep link после передеплоя): остаёмся в корне.
      if (!elementRegistry.get(want)) return
      const plane = canvas.findRoot(planeIdOf(want))
      if (!plane) return
      restoringPlane = true
      canvas.setRootElement(plane)
      canvas.zoom('fit-viewport')
      restoringPlane = false
    }
    appliedPlane = want
  } catch (err) {
    console.error('[BpmnViewer] Failed to apply plane:', err)
  }
}

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
  } catch (err) {
    console.error('Failed to render BPMN:', err)
    return
  }
  // WO-UI-25: обработчики и реставрация плоскости — ДО маркеров и вне их
  // try/catch. Раньше один отсутствующий на текущей плоскости id (например
  // активность ВНУТРИ схлопнутого подпроцесса — её нет в реестре корневой
  // плоскости) ронял applyHighlights исключением, и всё ниже — клики,
  // plane-handler, applyPlane — не выполнялось вообще: viewer был немым.
  setupClickHandler()
  setupPlaneHandler()
  // WO-UI-25: реставрация плоскости ПОСЛЕ отрисовки (deep link, F5) —
  // appliedPlane сбрасывается на каждый import: новый xml = новый граф.
  // initialRoot — plane ПРОЦЕССА верхнего уровня (НЕ getRootElement сразу
  // после importXML: при нескольких plane в DI import открывает первую plane
  // по порядку DI, а это может быть плоскость подпроцесса — живым Chromium
  // доказано: SubProcess_1_plane вместо корня).
  appliedPlane = undefined
  initialRoot = findProcessRootPlane()
  applyPlane()
  try {
    const canvas = viewer.get('canvas') as { zoom: (arg: string) => void }
    canvas.zoom('fit-viewport')
  } catch {
    // нулевой размер контейнера (скрытый таб) — не фатально
  }
  try {
    applyHighlights()
  } catch (err) {
    console.error('Failed to apply BPMN highlights:', err)
  }
  try {
    applyCountOverlays()
  } catch (err) {
    console.error('Failed to apply BPMN overlays:', err)
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

  // Clear all markers (без getRootElement: до выбора плоскости он бросает
  // внутри diagram-js, а здесь вообще не нужен)
  const elementRegistry = viewer.get('elementRegistry') as { forEach: (fn: (el: { id: string }) => void) => void }
  elementRegistry.forEach((el: { id: string }) => {
    canvas.removeMarker(el.id, 'highlight-active')
    canvas.removeMarker(el.id, 'highlight-incident')
    canvas.removeMarker(el.id, 'highlight-completed')
  })

  // Маркеры — по одному id за try: активность ВНУТРИ схлопнутого подпроцесса
  // отсутствует в реестре текущей (корневой) плоскости, и addMarker на неё
  // бросает. Один такой id раньше ронял весь проход (см. render выше).
  function mark(ids: string[] | undefined, cls: string) {
    if (!ids) return
    for (const id of ids) {
      try {
        canvas.addMarker(id, cls)
      } catch {
        // id нет на текущей плоскости — не подсвечиваем, идём дальше
      }
    }
  }

  // Apply active markers
  mark(props.activeElementIds, 'highlight-active')

  // Apply incident markers
  mark(props.incidentElementIds, 'highlight-incident')

  // Apply completed markers
  mark(props.completedElementIds, 'highlight-completed')
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

/**
 * WO-UI-25 (критерий 8): пользовательская смена плоскости (drill-down
 * оверлей, крошки DrilldownBreadcrumbs) наружу через planeChange — страница
 * пишет её в ?plane=. Эхо нашей программной реставрации подавляется флагом
 * restoringPlane, иначе петля planeChange→prop→applyPlane.
 */
function setupPlaneHandler() {
  if (!viewer) return
  const eventBus = viewer.get('eventBus') as {
    on: (event: string, fn: (e: { element: { id: string } }) => void) => void
  }
  eventBus.on('root.set', (e: { element: { id: string } }) => {
    if (restoringPlane || !e.element) return
    const shapeId = shapeIdOfPlane(e.element.id)
    if (shapeId !== appliedPlane) {
      appliedPlane = shapeId
      emit('planeChange', shapeId)
    }
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
// WO-UI-25: внешняя простановка плоскости (F5/deep link/back — через prop,
// пользовательский drill-down — через planeChange наружу) без пересоздания.
watch(() => props.activeElementIds, () => { applyHighlights() }, { deep: true })
watch(() => props.incidentElementIds, () => { applyHighlights() }, { deep: true })
watch(() => props.completedElementIds, () => { applyHighlights() }, { deep: true })
watch(() => props.elementCounts, () => { applyCountOverlays() }, { deep: true })
// WO-UI-25: внешняя простановка плоскости (back/forward, deep link, сброс
// в корень) — без пересоздания viewer, только setRootElement.
watch(() => props.planeElementId, () => { applyPlane() })

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
