// @vitest-environment jsdom
// POF test: importSchema is REALLY called with saved schema (not mock stub importSchema:()=>{}).
// Uses REAL FormEditor.vue (not mocked component) with spy on @bpmn-io/form-js.
// RED before fix: importSchema not exposed → undefined → schema never imported.
// GREEN after fix: importSchema exposed → called with saved schema after FormEditor mounts.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SchemaEditorPanel from './SchemaEditorPanel.vue'

// Spy on form-js's importSchema to verify it's actually called with real schema data
const mockFormJsImportSchema = vi.fn().mockResolvedValue(undefined)

vi.mock('@bpmn-io/form-js', () => ({
  FormEditor: class MockFormEditor {
    constructor() {}
    importSchema = mockFormJsImportSchema
    saveSchema = vi.fn().mockReturnValue({ type: 'default', components: [] })
    destroy = vi.fn()
  },
}))

// Mock services (but NOT FormEditor.vue — use real component for defineExpose)
vi.mock('@/services/formService', () => ({
  getSchemaMap: vi.fn(),
  saveElementSchema: vi.fn(),
  getForm: vi.fn(),
  generateSchema: vi.fn(),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}))

vi.mock('@/widgets/forms/JsonSchemaEditor.vue', () => ({
  default: { name: 'JsonSchemaEditor', template: '<div />', methods: { saveSchema: () => '{}' } },
}))

vi.mock('@/widgets/shared/SchemaFieldBuilder.vue', () => ({
  default: {
    name: 'SchemaFieldBuilder',
    template: '<div data-testid="field-builder" />',
    props: ['modelValue'],
    emits: ['update:modelValue'],
  },
}))

import { getSchemaMap, getForm } from '@/services/formService'

describe('SchemaEditorPanel POF: importSchema integration', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getSchemaMap as any).mockResolvedValue({
      processDefinitionKey: 'proc1',
      version: 1,
      elements: [{
        elementId: 'userTask',
        name: 'Form Task',
        type: 'USER_TASK',
        artifactKey: 'fj-key',
        kind: 'FORM_JS',
        artifactVersion: 1,
        hasExternalReference: true,
        shared: false,
      }],
    })
  })

  it('POF GREEN: importSchema called with saved schema when FORM_JS element selected', async () => {
    const testSchema = { type: 'default', components: [{ key: 'f1', type: 'textfield', label: 'Field 1' }] }
    ;(getForm as any).mockResolvedValue({
      key: 'fj-key',
      version: 1,
      kind: 'FORM_JS',
      schema: JSON.stringify(testSchema),
    })

    const wrapper = mount(SchemaEditorPanel, { props: { processKey: 'proc1' } })
    await flushPromises()

    // Click the FORM_JS element to select it
    await wrapper.find('.cursor-pointer').trigger('click')
    await flushPromises()

    // After fix: FormEditor exposes importSchema → form-js importSchema is called
    // The mock receives 2 calls:
    //   call 1: initEditor with EMPTY_SCHEMA ({ type:'default', components:[], schemaVersion:16 })
    //   call 2: selectElement with saved schema from backend
    expect(mockFormJsImportSchema).toHaveBeenCalledTimes(2)

    // The LAST call should be with the saved schema from getForm()
    const lastCallArg = mockFormJsImportSchema.mock.calls[1][0]
    expect(lastCallArg).toEqual(testSchema)
  })
})
