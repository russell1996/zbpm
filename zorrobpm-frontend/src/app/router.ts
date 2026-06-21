import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
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
          meta: { title: 'Users' },
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
      auth.login()
      return false
    }
  }
})

export default router
