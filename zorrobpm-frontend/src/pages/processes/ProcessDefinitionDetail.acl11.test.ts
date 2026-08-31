// @vitest-environment jsdom
/**
 * WO-ACL-11 criteria 14 & 15 — ProcessDefinitionDetail:
 *  14 — the "(current)" marker next to the current version is translated
 *       (was a hardcoded literal; a ru-locale render must show the ru text);
 *  15 — the hints on the "Модель" (Model) and "Схемы элементов" (Element
 *       Schemas) tabs are translated (were English literals on the stand).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import ProcessDefinitionDetail from './ProcessDefinitionDetail.vue'
import * as adminService from '@/services/adminService'

const authMock: { user: { id: string; username: string } | null; isSuperAdmin: boolean } = {
  user: { id: 'me', username: 'me' },
  isSuperAdmin: false,
}

vi.mock('@/stores/process', () => ({
  useProcessStore: () => ({
    currentDefinition: { id: 'def-1', key: 'order', name: 'Order', version: 1 },
    currentVersions: [
      { id: 'v1', version: 1, createdAt: '2026-01-01T00:00:00Z' },
      { id: 'v2', version: 2, createdAt: '2026-01-02T00:00:00Z' },
    ],
    currentStructure: null,
    loading: false,
    error: null,
    fetchDefinition: vi.fn().mockResolvedValue(undefined),
    fetchStructure: vi.fn().mockResolvedValue(undefined),
    fetchVersions: vi.fn().mockResolvedValue(undefined),
    startInstance: vi.fn(),
  }),
}))
vi.mock('@/stores/breadcrumb', () => ({
  useBreadcrumbStore: () => ({ crumbLabel: null, setCrumbLabel: vi.fn() }),
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => authMock,
}))
vi.mock('@/services/adminService', () => ({
  listMembers: vi.fn().mockResolvedValue([]),
  addMember: vi.fn().mockResolvedValue({}),
  changeMemberRole: vi.fn(),
  removeMember: vi.fn(),
  searchMemberCandidates: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue('<definitions />'),
  addProcessDefinitionVersion: vi.fn(),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v }),
}))
vi.mock('@/widgets/bpmn/BpmnViewer.vue', () => ({
  default: { template: '<div class="bpmn-viewer-stub" @click="$emit(\'element-click\', \'e1\')" />' },
}))
vi.mock('@/widgets/shared/SchemaEditorPanel.vue', () => ({ default: { template: '<div class="schema-editor-stub" />' } }))
vi.mock('@/widgets/shared/CopyableId.vue', () => ({ default: { template: '<span class="copyable-stub" />' } }))

const i18n = createI18n({
  legacy: false,
  locale: 'ru',
  messages: {
    ru: {
      loading: 'Загрузка...', version: 'Версия', created: 'Создано', close: 'Закрыть',
      downloadBpmn: 'Скачать BPMN', uploadNewVersion: 'Загрузить новую версию', startProcess: 'Запустить',
      bpmnProcess: 'Модель', bpmnStructure: 'Структура BPMN', requirements: 'Требования',
      elementSchemas: 'Схемы элементов', members: 'Участники', versions: 'Версии',
      currentVersionMarker: ' (текущая)', current: 'текущая',
      modelTabHint: 'Нажмите на элемент, чтобы посмотреть его настройки',
      schemasTabHint: 'Привязка и редактирование схем форм и переменных',
      name: 'Название', type: 'Тип', configuration: 'Конфигурация', noConfiguration: 'Без конфигурации',
      element: 'Элемент', process: 'Процесс', extractedFromBpmn: 'Извлечено', noDataYet: 'Нет данных',
      noAccess: 'Нет доступа', noDetailsForElement: 'Нет деталей', conditionFeel: 'Условие',
      noConditionFlow: 'Условия нет', requirement: 'Требование', noMembers: 'Нет участников',
      you: 'вы', ownerRole: 'ВЛАДЕЛЕЦ', designerRole: 'ДИЗАЙНЕР', viewerRole: 'НАБЛЮДАТЕЛЬ',
      remove: 'Удалить', membersHint: 'Кто имеет доступ', failedToLoadMembers: 'Ошибка',
      username: 'Имя пользователя', fullName: 'Имя', email: 'Почта', role: 'Роль', actions: 'Действия',
      addMember: 'Добавить участника', searchCandidatePlaceholder: 'Поиск по имени пользователя',
      searchCandidateHint: 'Начните вводить имя пользователя', noCandidates: 'Никого не найдено',
      selected: 'выбрано', adding: 'Добавление…', memberAdded: 'Участник добавлен',
      failedToAddMember: 'Не удалось добавить', failedToLoadCandidates: 'Не удалось загрузить кандидатов',
    },
  },
})

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      { path: '/ui/processes/definitions/:id', name: 'process-definition-detail', component: ProcessDefinitionDetail },
      { path: '/ui/processes/definitions', name: 'process-definitions', component: { template: '<div />' } },
      { path: '/ui/processes/instances', name: 'process-instances', component: { template: '<div />' } },
    ],
  })
}

async function mountDetail(versionId = 'v2', members: { userId: string; role: string }[] = []) {
  vi.mocked(adminService.listMembers).mockResolvedValue(
    members.map((m) => ({ userId: m.userId, username: m.userId, fullName: null, email: null, role: m.role, addedBy: null, addedAt: '2026-01-01T00:00:00Z' })),
  )
  const router = makeRouter()
  router.push(`/ui/processes/definitions/${versionId}`)
  await router.isReady()
  const wrapper = mount(ProcessDefinitionDetail, {
    global: { plugins: [router, i18n] },
  })
  await flushPromises()
  return wrapper
}

async function openMembersTab(wrapper: ReturnType<typeof mountDetail> extends Promise<infer T> ? T : never) {
  const tab = wrapper.findAll('button[role="tab"]').find((b) => b.text() === 'Участники')
  await tab!.trigger('click')
  await flushPromises()
}

describe('WO-ACL-11 criteria 14–15: translated markers and tab hints', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('criterion 14: the current version is visually marked in the selector', async () => {
    const wrapper = await mountDetail('v2')
    // The selected version shows a checkmark in the version Badge dropdown
    expect(wrapper.text()).toContain('v2')
    // the badge in the versions tab uses t('current') as well
    const versionTab = wrapper.findAll('button[role="tab"]').find((b) => b.text() === 'Версии')
    await versionTab!.trigger('click')
    expect(wrapper.text()).toContain('текущая')
  })

  it('criterion 15: the Model tab hint is translated', async () => {
    const wrapper = await mountDetail('v2')
    expect(wrapper.text()).toContain('Нажмите на элемент, чтобы посмотреть его настройки')
  })

  it('criterion 15: the Element Schemas tab hint is translated', async () => {
    const wrapper = await mountDetail('v2')
    const schemasTab = wrapper.findAll('button[role="tab"]').find((b) => b.text() === 'Схемы элементов')
    await schemasTab!.trigger('click')
    expect(wrapper.text()).toContain('Привязка и редактирование схем форм и переменных')
  })
})

describe('WO-ACL-11 criterion 38: properties panel — same height as the canvas, scrolls inside itself', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('the panel has no fixed max-height and scrolls internally', async () => {
    const wrapper = await mountDetail('v2')
    // select an element in the (stubbed) viewer — the panel appears
    await wrapper.find('.bpmn-viewer-stub').trigger('click')
    await flushPromises()
    const panel = wrapper.find('.w-80')
    expect(panel.exists()).toBe(true)
    // the fixed max-height (540px) is gone — height comes from the flex row
    expect(panel.attributes('style') ?? '').not.toContain('max-height')
    expect(panel.classes()).toContain('overflow-y-auto')
  })
})

describe('WO-ACL-11 criteria 17–18 + WO-ACL-14 criterion 9: add member from the process card', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    authMock.user = { id: 'me', username: 'me' }
    authMock.isSuperAdmin = false
  })

  it('criterion 17 (WO-ACL-14: via the dialog): an OWNER opens the dialog, searches candidates and adds a member with a role', async () => {
    vi.mocked(adminService.searchMemberCandidates).mockResolvedValue([{ userId: 'u-annette', username: 'annette', fullName: '', email: '' }])
    const wrapper = await mountDetail('v2', [{ userId: 'me', role: 'OWNER' }])
    await openMembersTab(wrapper)

    // WO-ACL-14: the tab shows the "Add member" BUTTON (no inline search input)
    expect(wrapper.text()).toContain('Добавить участника')
    expect(wrapper.find('input[placeholder="Поиск по имени пользователя"]').exists()).toBe(false)

    // clicking opens the dialog with the search input
    const openButton = wrapper.findAll('button').find((b) => b.text() === 'Добавить участника')!
    await openButton.trigger('click')
    await flushPromises()
    const input = wrapper.find('input[placeholder="Поиск по имени пользователя"]')
    expect(input.exists()).toBe(true)

    // typing >= 3 chars calls the ACL-7 candidates endpoint (NOT /users)
    await input.setValue('ann')
    await flushPromises()
    expect(adminService.searchMemberCandidates).toHaveBeenCalledWith('order', 'ann')

    // the candidate appears as a row; selecting her shows the role picker
    expect(wrapper.text()).toContain('annette')
    await wrapper.findAll('button').find((b) => b.text().includes('annette'))!.trigger('click')
    expect(wrapper.text()).toContain('выбрано')

    // default role VIEWER, change to DESIGNER, then add — the submit button is
    // the LAST "Добавить участника" (the first one is the opener on the tab)
    const roleSelect = wrapper.findAll('select').at(-1)!
    await roleSelect.setValue('DESIGNER')
    const addButtons = wrapper.findAll('button').filter((b) => b.text() === 'Добавить участника')
    await addButtons.at(-1)!.trigger('click')
    await flushPromises()
    expect(adminService.addMember).toHaveBeenCalledWith('order', 'u-annette', 'DESIGNER')
    // member list reloaded after the add
    expect(adminService.listMembers).toHaveBeenCalledWith('order')
    // the dialog closed itself on success
    expect(wrapper.find('input[placeholder="Поиск по имени пользователя"]').exists()).toBe(false)
  })

  it('criterion 17: a super-admin sees the add block without being a member', async () => {
    authMock.isSuperAdmin = true
    authMock.user = { id: 'me', username: 'me' }
    const wrapper = await mountDetail('v2', [])
    await openMembersTab(wrapper)
    expect(wrapper.text()).toContain('Добавить участника')
  })

  it('criterion 18: a plain VIEWER does not see the add button at all', async () => {
    const wrapper = await mountDetail('v2', [{ userId: 'me', role: 'VIEWER' }])
    await openMembersTab(wrapper)
    expect(wrapper.text()).not.toContain('Добавить участника')
    expect(wrapper.find('input[placeholder="Поиск по имени пользователя"]').exists()).toBe(false)
  })
})