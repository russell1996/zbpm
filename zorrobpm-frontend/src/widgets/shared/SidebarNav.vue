<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
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
} from 'lucide-vue-next'

const emit = defineEmits<{ navigate: [] }>()

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

interface NavItem {
  labelKey: string
  icon: typeof LayoutDashboard
  to?: string
  children?: NavItem[]
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
      { labelKey: 'deploy', icon: GitBranch, to: '/processes/deploy' },
    ],
  },
  { labelKey: 'tasks', icon: ListTodo, to: '/tasks' },
  { labelKey: 'serviceTasks', icon: Cpu, to: '/service-tasks' },
  { labelKey: 'incidents', icon: AlertTriangle, to: '/incidents' },
  { labelKey: 'timers', icon: GitBranch, to: '/timers' },
  { labelKey: 'messages', icon: GitBranch, to: '/messages' },
  { labelKey: 'dmn', icon: GitBranch, to: '/dmn' },
  { labelKey: 'analytics', icon: BarChart3, to: '/analytics' },
  { labelKey: 'users', icon: Users, to: '/admin/users' },
])

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
  <aside class="w-64 h-full bg-sidebar text-sidebar-foreground flex flex-col border-r border-border">
    <div class="h-14 flex items-center px-4 border-b border-border shrink-0">
      <span class="text-lg font-bold">ZBPM</span>
    </div>
    <nav class="flex-1 py-2 overflow-y-auto min-h-0">
      <template v-for="item in navItems" :key="item.labelKey">
        <div v-if="item.children">
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
          :class="{ 'bg-sidebar-accent font-medium': isActive(item.to) }"
          @click="item.to && navigate(item.to)"
        >
          <component :is="item.icon" class="h-4 w-4" />
          {{ t(item.labelKey) }}
        </button>
      </template>
    </nav>
  </aside>
</template>
