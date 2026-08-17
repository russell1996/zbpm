<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import {
  LayoutDashboard,
  GitBranch,
  ListTodo,
  AlertTriangle,
  Users,
  ChevronDown,
  ChevronRight,
  BarChart3,
  Cpu,
  PanelLeftClose,
  PanelLeftOpen,
} from 'lucide-vue-next'

const props = defineProps<{ collapsed?: boolean }>()
const emit = defineEmits<{ navigate: []; toggleCollapse: [] }>()

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const auth = useAuthStore()

interface NavItem {
  labelKey: string
  icon: typeof LayoutDashboard
  to?: string
  children?: NavItem[]
  /** WO-ACL-8 criteria 1-2: admin-only items are hidden from non-super-admins. */
  adminOnly?: boolean
}

const expanded = ref<Record<string, boolean>>({
  processes: true,
})

const navItems = computed<NavItem[]>(() => [
  { labelKey: 'dashboard', icon: LayoutDashboard, to: '/' },
  {
    labelKey: 'processes',
    icon: GitBranch,
    children: [
      { labelKey: 'definitions', icon: GitBranch, to: '/processes/definitions' },
      { labelKey: 'instances', icon: GitBranch, to: '/processes/instances' },
      { labelKey: 'mySubmissions', icon: GitBranch, to: '/processes/submissions' },
    ],
  },
  { labelKey: 'tasks', icon: ListTodo, to: '/tasks' },
  { labelKey: 'serviceTasks', icon: Cpu, to: '/service-tasks' },
  { labelKey: 'incidents', icon: AlertTriangle, to: '/incidents' },
  { labelKey: 'timers', icon: GitBranch, to: '/timers' },
  { labelKey: 'messages', icon: GitBranch, to: '/messages' },
  { labelKey: 'dmn', icon: GitBranch, to: '/dmn' },
  { labelKey: 'analytics', icon: BarChart3, to: '/analytics' },
  { labelKey: 'users', icon: Users, to: '/admin/users', adminOnly: true },
  { labelKey: 'submissionQueue', icon: Users, to: '/admin/submissions', adminOnly: true },
])

// WO-ACL-8 criteria 1-2: non-super-admins don't see admin-only items.
const visibleNavItems = computed<NavItem[]>(() =>
  auth.isSuperAdmin ? navItems.value : navItems.value.filter((i) => !i.adminOnly),
)

function toggle(key: string) {
  expanded.value[key] = !expanded.value[key]
}

function isActive(to?: string) {
  if (!to) return false
  return route.path === to || route.path.startsWith(to + '/')
}

function isActiveGroup(item: NavItem) {
  if (item.to) return isActive(item.to)
  if (item.children) return item.children.some((c) => isActive(c.to))
  return false
}

function navigate(to: string) {
  router.push(to)
  emit('navigate')
}
</script>

<template>
  <aside
    class="h-full bg-sidebar text-sidebar-foreground flex flex-col border-r border-border transition-all duration-200"
    :class="collapsed ? 'w-14' : 'w-64'"
  >
    <div class="h-14 flex items-center justify-between px-3 border-b border-border shrink-0">
      <span v-if="!collapsed" class="text-lg font-bold">ZBPM</span>
      <button
        class="p-1.5 text-muted-foreground hover:text-foreground hover:bg-sidebar-accent rounded-md transition-colors"
        :title="collapsed ? t('expandSidebar') : t('collapseSidebar')"
        @click="emit('toggleCollapse')"
      >
        <PanelLeftClose v-if="!collapsed" class="h-4 w-4" />
        <PanelLeftOpen v-else class="h-4 w-4" />
      </button>
    </div>
    <nav class="flex-1 py-2 overflow-y-auto min-h-0">
      <template v-for="item in visibleNavItems" :key="item.labelKey">
        <div v-if="item.children && !collapsed">
          <button
            class="w-full flex items-center justify-between px-4 py-2.5 text-sm hover:bg-sidebar-accent transition-colors"
            :class="{ 'bg-sidebar-accent font-medium': isActiveGroup(item) }"
            @click="toggle(item.labelKey)"
          >
            <span class="flex items-center gap-3">
              <component :is="item.icon" class="h-4 w-4" />
              {{ t(item.labelKey) }}
            </span>
            <component :is="expanded[item.labelKey] ? ChevronDown : ChevronRight" class="h-4 w-4" />
          </button>
          <div v-if="expanded[item.labelKey]" class="ml-4">
            <button
              v-for="child in item.children"
              :key="child.to"
              class="w-full flex items-center gap-3 px-4 py-2 text-sm hover:bg-sidebar-accent transition-colors"
              :class="{ 'bg-sidebar-accent font-medium': isActive(child.to) }"
              @click="child.to && navigate(child.to)"
            >
              <component :is="child.icon" class="h-4 w-4" />
              {{ t(child.labelKey) }}
            </button>
          </div>
        </div>
        <button
          v-else
          class="w-full flex items-center gap-3 px-4 py-2.5 text-sm hover:bg-sidebar-accent transition-colors"
          :class="[
            { 'bg-sidebar-accent font-medium': isActive(item.to) },
            collapsed ? 'justify-center px-2' : '',
          ]"
          :title="collapsed ? t(item.labelKey) : undefined"
          @click="item.children ? (collapsed ? null : toggle(item.labelKey)) : item.to && navigate(item.to)"
        >
          <component :is="item.icon" class="h-4 w-4 shrink-0" />
          <span v-if="!collapsed">{{ t(item.labelKey) }}</span>
        </button>
      </template>
    </nav>
  </aside>
</template>
