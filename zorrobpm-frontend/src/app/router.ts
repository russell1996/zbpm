import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { resolveGuard } from './guard'

const router = createRouter({
  history: createWebHistory('/ui/'),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/pages/Login.vue'),
    },
    {
      path: '/auth/callback',
      name: 'auth-callback',
      component: () => import('@/pages/AuthCallback.vue'),
    },
    {
      path: '/access-denied',
      name: 'access-denied',
      component: () => import('@/pages/AccessDenied.vue'),
    },
    {
      // WO-ACL-18: public, unauthenticated password recovery / invitation acceptance.
      path: '/forgot-password',
      name: 'forgot-password',
      component: () => import('@/pages/ForgotPassword.vue'),
    },
    {
      path: '/reset-password',
      name: 'reset-password',
      component: () => import('@/pages/ResetPassword.vue'),
    },
    {
      path: '/accept-invitation',
      name: 'accept-invitation',
      component: () => import('@/pages/AcceptInvitation.vue'),
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/pages/Register.vue'),
    },
    {
      path: '/verify-email',
      name: 'verify-email',
      component: () => import('@/pages/VerifyEmail.vue'),
    },
    {
      path: '/change-password',
      name: 'change-password',
      component: () => import('@/pages/ChangePassword.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/',
      component: () => import('@/layouts/MainLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: '',
          name: 'dashboard',
          component: () => import('@/pages/Dashboard.vue'),
          meta: { titleKey: 'dashboard' },
        },
        {
          path: 'processes/definitions',
          name: 'process-definitions',
          component: () => import('@/pages/processes/ProcessDefinitionList.vue'),
          meta: { titleKey: 'processDefinitions' },
        },
        {
          path: 'processes/definitions/:id',
          name: 'process-definition-detail',
          component: () => import('@/pages/processes/ProcessDefinitionDetail.vue'),
          meta: { titleKey: 'processDefinition', parentTitleKey: 'processDefinitions', parentTo: { name: 'process-definitions' } },
        },
        {
          path: 'processes/:key/start',
          name: 'start-form',
          component: () => import('@/pages/processes/StartForm.vue'),
          meta: { titleKey: 'startProcess' },
        },
        {
          path: 'processes/instances',
          name: 'process-instances',
          component: () => import('@/pages/processes/ProcessInstanceList.vue'),
          meta: { titleKey: 'processInstances' },
        },
        {
          path: 'processes/instances/:id',
          name: 'process-instance-detail',
          component: () => import('@/pages/processes/ProcessInstanceDetail.vue'),
          meta: { titleKey: 'processInstance', parentTitleKey: 'processInstances', parentTo: { name: 'process-instances' } },
        },
        {
          path: 'tasks',
          name: 'my-tasks',
          component: () => import('@/pages/tasks/TaskList.vue'),
          meta: { titleKey: 'myTasks' },
        },
        {
          path: 'tasks/:id',
          name: 'task-detail',
          component: () => import('@/pages/tasks/TaskDetail.vue'),
          meta: { titleKey: 'task', parentTitleKey: 'myTasks', parentTo: { name: 'my-tasks' } },
        },
        {
          path: 'service-tasks',
          name: 'service-tasks',
          component: () => import('@/pages/tasks/ServiceTaskList.vue'),
          meta: { titleKey: 'serviceTasks' },
        },
        {
          path: 'service-tasks/:id',
          name: 'service-task-detail',
          component: () => import('@/pages/tasks/ServiceTaskDetail.vue'),
          meta: { titleKey: 'serviceTask', parentTitleKey: 'serviceTasks', parentTo: { name: 'service-tasks' } },
        },
        {
          path: 'incidents',
          name: 'incidents',
          component: () => import('@/pages/incidents/IncidentList.vue'),
          meta: { titleKey: 'incidents' },
        },
        {
          path: 'incidents/:id',
          name: 'incident-detail',
          component: () => import('@/pages/incidents/IncidentDetail.vue'),
          meta: { titleKey: 'incident', parentTitleKey: 'incidents', parentTo: { name: 'incidents' } },
        },
        {
          path: 'timers',
          name: 'timers',
          component: () => import('@/pages/timers/TimerList.vue'),
          meta: { titleKey: 'timers' },
        },
        {
          path: 'messages',
          name: 'messages',
          component: () => import('@/pages/messages/MessageList.vue'),
          meta: { titleKey: 'messages' },
        },
        {
          path: 'dmn',
          name: 'dmn-list',
          component: () => import('@/pages/dmn/DmnList.vue'),
          meta: { titleKey: 'dmnDecisions' },
        },
        {
          path: 'dmn/:id',
          name: 'dmn-detail',
          component: () => import('@/pages/dmn/DmnViewer.vue'),
          meta: { titleKey: 'dmnDecision', parentTitleKey: 'dmnDecisions', parentTo: { name: 'dmn-list' } },
        },
        {
          path: 'admin/users',
          name: 'admin-users',
          component: () => import('@/pages/admin/UserList.vue'),
          meta: { titleKey: 'users', requiresSuperAdmin: true },
        },
        {
          path: 'admin/submissions',
          name: 'admin-submissions',
          component: () => import('@/pages/admin/SubmissionQueue.vue'),
          meta: { titleKey: 'submissionQueue', requiresSuperAdmin: true },
        },
        {
          path: 'admin/registrations',
          name: 'admin-registrations',
          component: () => import('@/pages/admin/RegistrationQueue.vue'),
          meta: { titleKey: 'registrationQueue', requiresSuperAdmin: true },
        },
        {
          path: 'admin/forms',
          name: 'admin-forms',
          component: () => import('@/pages/admin/FormsAdmin.vue'),
          meta: { titleKey: 'forms', requiresSuperAdmin: true },
        },
        {
          path: 'admin/process-schemas',
          name: 'admin-process-schemas',
          component: () => import('@/pages/admin/ProcessSchemaAdmin.vue'),
          meta: { titleKey: 'processSchemas', requiresSuperAdmin: true },
        },
        {
          // WO-UI-10: consolidated admin settings hub with tabs. The standalone
          // /admin/users, /admin/submissions, /admin/mail-settings routes are kept
          // (deep links / backward compat) but the sidebar now points here.
          path: 'admin/settings',
          name: 'admin-settings',
          component: () => import('@/pages/admin/AdminSettings.vue'),
          meta: { titleKey: 'adminSettings', requiresSuperAdmin: true },
          children: [
            { path: '', redirect: { name: 'admin-settings-mail' } },
            {
              path: 'mail-settings',
              name: 'admin-settings-mail',
              component: () => import('@/pages/admin/MailSettings.vue'),
              meta: { titleKey: 'mailSettings', requiresSuperAdmin: true },
            },
            {
              path: 'users',
              name: 'admin-settings-users',
              component: () => import('@/pages/admin/UserList.vue'),
              meta: { titleKey: 'users', requiresSuperAdmin: true },
            },
            {
              path: 'submissions',
              name: 'admin-settings-submissions',
              component: () => import('@/pages/admin/SubmissionQueue.vue'),
              meta: { titleKey: 'submissionQueue', requiresSuperAdmin: true },
            },
            // WO-UI-15: registration queue consolidated into admin settings hub.
            // Standalone /admin/registrations kept for deep-link / backward compat.
            {
              path: 'registrations',
              name: 'admin-settings-registrations',
              component: () => import('@/pages/admin/RegistrationQueue.vue'),
              meta: { titleKey: 'registrationQueue', requiresSuperAdmin: true },
            },
          ],
        },
        {
          path: 'admin/mail-settings',
          name: 'admin-mail-settings',
          component: () => import('@/pages/admin/MailSettings.vue'),
          meta: { titleKey: 'mailSettings', requiresSuperAdmin: true },
        },
        {
          path: 'me/profile',
          name: 'my-profile',
          component: () => import('@/pages/me/MyProfile.vue'),
          meta: { titleKey: 'myProfile' },
        },
        {
          // WO-SEC-58: /me/api-key moved into /me/profile; old route stays as a redirect
          path: 'me/api-key',
          redirect: { name: 'my-profile' },
        },
        {
          path: 'analytics',
          name: 'analytics',
          component: () => import('@/pages/analytics/AnalyticsOverview.vue'),
          meta: { titleKey: 'analytics' },
        },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    await auth.init()
    if (!auth.isAuthenticated) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }

  // Role + forcePasswordChange guard
  const guardResult = resolveGuard(to, {
    isAuthenticated: auth.isAuthenticated,
    isAdmin: auth.isAdmin,
    isSuperAdmin: auth.isSuperAdmin,
    forcePasswordChange: auth.forcePasswordChange,
  })
  if (guardResult) return guardResult

  // already signed in -> keep the login page out of reach
  if (to.name === 'login' && auth.isAuthenticated) {
    return { name: 'dashboard' }
  }
})

// WO-MT-9e: reload on chunk-loading failure (stale index referencing deleted chunks).
// Without this, a failed dynamic import leaves the user on a blank screen.
// NOTE: `to.fullPath` is app-relative (no base) — a hard `window.location.assign` to it
// would navigate the browser to a path outside the configured public base (`/ui/`) and
// Vite's baseMiddleware would answer with a 404 ("did you mean to visit /ui/..."). Always
// prefix the public base so the reload stays inside the SPA.
let chunkReloaded = false
function reloadAfterChunkError(fullPath: string) {
  const base = (import.meta.env.BASE_URL || '/').replace(/\/$/, '')
  window.location.assign(base + fullPath)
}
router.onError((err, to) => {
  if (!chunkReloaded && /Failed to fetch|dynamically imported module/i.test(err.message)) {
    chunkReloaded = true
    reloadAfterChunkError(to.fullPath)
  }
})

// Exported for testing
export function handleError(err: Error, to: { fullPath: string }) {
  if (!chunkReloaded && /Failed to fetch|dynamically imported module/i.test(err.message)) {
    chunkReloaded = true
    reloadAfterChunkError(to.fullPath)
  }
}

export default router
