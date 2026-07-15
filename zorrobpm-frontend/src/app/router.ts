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
          meta: { title: 'Dashboard' },
        },
        {
          path: 'processes/definitions',
          name: 'process-definitions',
          component: () => import('@/pages/processes/ProcessDefinitionList.vue'),
          meta: { title: 'Process Definitions' },
        },
        {
          path: 'processes/definitions/:id',
          name: 'process-definition-detail',
          component: () => import('@/pages/processes/ProcessDefinitionDetail.vue'),
          meta: { title: 'Process Definition' },
        },
        {
          path: 'processes/:key/start',
          name: 'start-form',
          component: () => import('@/pages/processes/StartForm.vue'),
          meta: { title: 'Start Process' },
        },
        {
          path: 'processes/instances',
          name: 'process-instances',
          component: () => import('@/pages/processes/ProcessInstanceList.vue'),
          meta: { title: 'Process Instances' },
        },
        {
          path: 'processes/instances/:id',
          name: 'process-instance-detail',
          component: () => import('@/pages/processes/ProcessInstanceDetail.vue'),
          meta: { title: 'Process Instance' },
        },
        {
          path: 'processes/deploy',
          name: 'deploy-process',
          component: () => import('@/pages/processes/DeployProcess.vue'),
          meta: { title: 'Deploy Process' },
        },
        {
          path: 'tasks',
          name: 'my-tasks',
          component: () => import('@/pages/tasks/TaskList.vue'),
          meta: { title: 'My Tasks' },
        },
        {
          path: 'tasks/:id',
          name: 'task-detail',
          component: () => import('@/pages/tasks/TaskDetail.vue'),
          meta: { title: 'Task' },
        },
        {
          path: 'service-tasks',
          name: 'service-tasks',
          component: () => import('@/pages/tasks/ServiceTaskList.vue'),
          meta: { title: 'Service Tasks' },
        },
        {
          path: 'service-tasks/:id',
          name: 'service-task-detail',
          component: () => import('@/pages/tasks/ServiceTaskDetail.vue'),
          meta: { title: 'Service Task' },
        },
        {
          path: 'incidents',
          name: 'incidents',
          component: () => import('@/pages/incidents/IncidentList.vue'),
          meta: { title: 'Incidents' },
        },
        {
          path: 'incidents/:id',
          name: 'incident-detail',
          component: () => import('@/pages/incidents/IncidentDetail.vue'),
          meta: { title: 'Incident' },
        },
        {
          path: 'timers',
          name: 'timers',
          component: () => import('@/pages/timers/TimerList.vue'),
          meta: { title: 'Timers' },
        },
        {
          path: 'messages',
          name: 'messages',
          component: () => import('@/pages/messages/MessageList.vue'),
          meta: { title: 'Messages' },
        },
        {
          path: 'dmn',
          name: 'dmn-list',
          component: () => import('@/pages/dmn/DmnList.vue'),
          meta: { title: 'DMN Decisions' },
        },
        {
          path: 'dmn/:id',
          name: 'dmn-detail',
          component: () => import('@/pages/dmn/DmnViewer.vue'),
          meta: { title: 'DMN Decision' },
        },
        {
          path: 'admin/users',
          name: 'admin-users',
          component: () => import('@/pages/admin/UserList.vue'),
          meta: { title: 'Users', requiresSuperAdmin: true },
        },
        {
          path: 'admin/forms',
          name: 'admin-forms',
          component: () => import('@/pages/admin/FormsAdmin.vue'),
          meta: { title: 'Forms', requiresSuperAdmin: true },
        },
        {
          path: 'admin/process-schemas',
          name: 'admin-process-schemas',
          component: () => import('@/pages/admin/ProcessSchemaAdmin.vue'),
          meta: { title: 'Process Schemas', requiresSuperAdmin: true },
        },
        {
          path: 'me/api-key',
          name: 'my-api-key',
          component: () => import('@/pages/me/MyApiKey.vue'),
          meta: { title: 'My API Key' },
        },
        {
          path: 'analytics',
          name: 'analytics',
          component: () => import('@/pages/analytics/AnalyticsOverview.vue'),
          meta: { title: 'Analytics' },
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
let chunkReloaded = false
router.onError((err, to) => {
  if (!chunkReloaded && /Failed to fetch|dynamically imported module/i.test(err.message)) {
    chunkReloaded = true
    window.location.assign(to.fullPath)
  }
})

// Exported for testing
export function handleError(err: Error, to: { fullPath: string }) {
  if (!chunkReloaded && /Failed to fetch|dynamically imported module/i.test(err.message)) {
    chunkReloaded = true
    window.location.assign(to.fullPath)
  }
}

export default router
