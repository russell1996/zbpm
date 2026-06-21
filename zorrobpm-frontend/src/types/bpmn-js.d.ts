declare module 'bpmn-js/lib/Viewer' {
  export default class BpmnViewer {
    constructor(options: { container: HTMLElement; additionalModules?: unknown[]; moddleExtensions?: unknown[] })
    importXML(xml: string): Promise<{ warnings: string[] }>
    get(module: string): unknown
    destroy(): void
  }
}

declare module 'bpmn-js/lib/NavigatedViewer' {
  export default class NavigatedViewer {
    constructor(options: { container: HTMLElement; additionalModules?: unknown[]; moddleExtensions?: unknown[] })
    importXML(xml: string): Promise<{ warnings: string[] }>
    get(module: string): unknown
    destroy(): void
  }
}

declare module 'diagram-js-minimap' {
  const value: unknown
  export default value
}
