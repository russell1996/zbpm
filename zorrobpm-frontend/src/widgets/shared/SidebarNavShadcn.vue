<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Cookies from 'js-cookie'
import {
  LayoutDashboard,
  Workflow,
  Play,
  ListTodo,
  AlertTriangle,
  Users,
  BarChart3,
  Cpu,
  Timer,
  Mail,
  Table2,
  Inbox,
} from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import {
  SidebarProvider,
  Sidebar,
  SidebarHeader,
  SidebarContent,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarMenu,
  SidebarMenuItem,
  SidebarMenuButton,
  SidebarRail,
} from '@/components/ui/sidebar'

const props = defineProps<{ navigate?: () => void }>()
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

interface NavItem {
  labelKey: string
  icon: typeof LayoutDashboard
  to: string
}

interface NavGroup {
  labelKey: string
  items: NavItem[]
}

// WO-UI-7: grouped by domain (was a flat list in the old SidebarNav).
const navGroups = computed<NavGroup[]>(() => [
  {
    labelKey: 'dashboard',
    items: [
      { labelKey: 'dashboard', icon: LayoutDashboard, to: '/' },
    ],
  },
  {
    labelKey: 'definitions',
    items: [
      { labelKey: 'definitions', icon: Workflow, to: '/processes/definitions' },
      { labelKey: 'instances', icon: Play, to: '/processes/instances' },
    ],
  },
  {
    labelKey: 'tasks',
    items: [
      { labelKey: 'tasks', icon: ListTodo, to: '/tasks' },
      { labelKey: 'serviceTasks', icon: Cpu, to: '/service-tasks' },
    ],
  },
  {
    labelKey: 'incidents',
    items: [
      { labelKey: 'incidents', icon: AlertTriangle, to: '/incidents' },
      { labelKey: 'timers', icon: Timer, to: '/timers' },
      { labelKey: 'messages', icon: Mail, to: '/messages' },
    ],
  },
  {
    labelKey: 'dmn',
    items: [
      { labelKey: 'dmn', icon: Table2, to: '/dmn' },
      { labelKey: 'analytics', icon: BarChart3, to: '/analytics' },
    ],
  },
])

const adminItems: NavItem[] = [
  { labelKey: 'users', icon: Users, to: '/admin/users' },
  { labelKey: 'submissionQueue', icon: Inbox, to: '/admin/submissions' },
]

// WO-ACL-8 criteria 1-2: adminOnly filtering preserved.
const visibleGroups = computed<NavGroup[]>(() => {
  if (auth.isSuperAdmin) return navGroups.value
  return navGroups.value
})
const visibleAdminItems = computed<NavItem[]>(() =>
  auth.isSuperAdmin ? adminItems : [],
)

function isActive(to: string) {
  return route.path === to || route.path.startsWith(to + '/')
}

function navigate(to: string) {
  router.push(to)
  props.navigate?.()
}
</script>

<template>
  <SidebarProvider>
    <Sidebar>
      <SidebarHeader class="h-14 flex items-center px-3 border-b border-border">
        <span class="text-lg font-bold">ZBPM</span>
      </SidebarHeader>
      <SidebarContent>
        <SidebarGroup v-for="group in visibleGroups" :key="group.labelKey">
          <SidebarGroupLabel>{{ t(group.labelKey) }}</SidebarGroupLabel>
          <SidebarMenu>
            <SidebarMenuItem v-for="item in group.items" :key="item.labelKey">
              <SidebarMenuButton
                :is-active="isActive(item.to)"
                @click="navigate(item.to)"
              >
                <component :is="item.icon" class="h-4 w-4 shrink-0" />
                <span>{{ t(item.labelKey) }}</span>
              </SidebarMenuButton>
            </SidebarMenuItem>
          </SidebarMenu>
        </SidebarGroup>
        <SidebarGroup v-if="visibleAdminItems.length">
          <SidebarGroupLabel>{{ t('adminRole') }}</SidebarGroupLabel>
          <SidebarMenu>
            <SidebarMenuItem v-for="item in visibleAdminItems" :key="item.labelKey">
              <SidebarMenuButton
                :is-active="isActive(item.to)"
                @click="navigate(item.to)"
              >
                <component :is="item.icon" class="h-4 w-4 shrink-0" />
                <span>{{ t(item.labelKey) }}</span>
              </SidebarMenuButton>
            </SidebarMenuItem>
          </SidebarMenu>
        </SidebarGroup>
      </SidebarContent>
      <SidebarRail />
    </Sidebar>
  </SidebarProvider>
</template>
