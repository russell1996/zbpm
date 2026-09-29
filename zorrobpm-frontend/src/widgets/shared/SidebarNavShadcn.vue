<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
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
  Settings,
} from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import {
  Sidebar,
  SidebarContent,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarMenu,
  SidebarMenuItem,
  SidebarMenuButton,
  useSidebar,
} from '@/components/ui/sidebar'

const props = defineProps<{ navigate?: () => void }>()
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { state } = useSidebar()
const isCollapsed = computed(() => state.value === 'collapsed')

interface NavItem {
  labelKey: string
  icon: typeof LayoutDashboard
  to: string
}

interface NavGroup {
  labelKey: string
  items: NavItem[]
}

// WO-UI-7: groups match the user-approved structure:
// ОБЗОР / ПРОЕКТИРОВАНИЕ / ВЫПОЛНЕНИЕ / МОНИТОРИНГ / АДМИНИСТРИРОВАНИЕ
const navGroups = computed<NavGroup[]>(() => [
  {
    labelKey: 'navOverview',
    items: [
      { labelKey: 'dashboard', icon: LayoutDashboard, to: '/' },
    ],
  },
  {
    labelKey: 'navDesign',
    items: [
      { labelKey: 'definitions', icon: Workflow, to: '/processes/definitions' },
      { labelKey: 'dmn', icon: Table2, to: '/dmn' },
    ],
  },
  {
    labelKey: 'navExecution',
    items: [
      { labelKey: 'instances', icon: Play, to: '/processes/instances' },
      { labelKey: 'tasks', icon: ListTodo, to: '/tasks' },
      { labelKey: 'serviceTasks', icon: Cpu, to: '/service-tasks' },
    ],
  },
  {
    labelKey: 'navMonitoring',
    items: [
      { labelKey: 'incidents', icon: AlertTriangle, to: '/incidents' },
      { labelKey: 'timers', icon: Timer, to: '/timers' },
      { labelKey: 'messages', icon: Mail, to: '/messages' },
      { labelKey: 'analytics', icon: BarChart3, to: '/analytics' },
    ],
  },
])

// WO-UI-10/15: consolidated admin hub with tabs (mail-settings / users / submissions / registrations).
const adminItems: NavItem[] = [
  { labelKey: 'adminSettings', icon: Settings, to: '/admin/settings' },
]

// WO-ACL-8 criteria 1-2: adminOnly filtering preserved.
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
    <Sidebar collapsible="icon">
      <div class="h-14 flex flex-row items-center justify-center px-2 border-b border-border">
        <!-- WO-UI-8: bigger logo; in collapsed mode the lone "Z" carries the brand at text-2xl -->
        <span class="text-2xl font-bold tracking-tight transition-all">
          <span class="text-primary font-black">Z</span><template v-if="!isCollapsed"><span class="text-foreground">BPM</span></template>
        </span>
      </div>
      <SidebarContent>
        <SidebarGroup v-for="group in navGroups" :key="group.labelKey" class="p-2 py-1.5">
          <SidebarGroupLabel class="text-xs font-bold uppercase tracking-wider text-muted-foreground/80">
            {{ t(group.labelKey) }}
          </SidebarGroupLabel>
          <SidebarMenu>
            <SidebarMenuItem v-for="item in group.items" :key="item.labelKey">
              <SidebarMenuButton
                :is-active="isActive(item.to)"
                :tooltip="t(item.labelKey)"
                @click="navigate(item.to)"
              >
                <component :is="item.icon" class="h-4 w-4 shrink-0" />
                <span class="group-data-[collapsible=icon]:hidden">{{ t(item.labelKey) }}</span>
              </SidebarMenuButton>
            </SidebarMenuItem>
          </SidebarMenu>
        </SidebarGroup>
        <SidebarGroup v-if="visibleAdminItems.length">
          <SidebarGroupLabel class="text-xs font-bold uppercase tracking-wider text-muted-foreground/80">
            {{ t('navAdministration') }}
          </SidebarGroupLabel>
          <SidebarMenu>
            <SidebarMenuItem v-for="item in visibleAdminItems" :key="item.labelKey">
              <SidebarMenuButton
                :is-active="isActive(item.to)"
                :tooltip="t(item.labelKey)"
                @click="navigate(item.to)"
              >
                <component :is="item.icon" class="h-4 w-4 shrink-0" />
                <span class="group-data-[collapsible=icon]:hidden">{{ t(item.labelKey) }}</span>
              </SidebarMenuButton>
            </SidebarMenuItem>
          </SidebarMenu>
        </SidebarGroup>
      </SidebarContent>
    </Sidebar>
</template>
