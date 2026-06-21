<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  LayoutDashboard,
  GitBranch,
  ListTodo,
  AlertTriangle,
  Users,
  ChevronDown,
  ChevronRight,
  BarChart3,
} from 'lucide-vue-next'

const emit = defineEmits<{ navigate: [] }>()

const route = useRoute()
const router = useRouter()

interface NavItem {
  label: string
  icon: typeof LayoutDashboard
  to?: string
  children?: NavItem[]
}

const expanded = ref<Record<string, boolean>>({
  processes: true,
})

const navItems: NavItem[] = [
  { label: 'Dashboard', icon: LayoutDashboard, to: '/' },
  {
    label: 'Processes',
    icon: GitBranch,
    children: [
      { label: 'Definitions', icon: GitBranch, to: '/processes/definitions' },
      { label: 'Instances', icon: GitBranch, to: '/processes/instances' },
      { label: 'Deploy', icon: GitBranch, to: '/processes/deploy' },
    ],
  },
  { label: 'Tasks', icon: ListTodo, to: '/tasks' },
  { label: 'Incidents', icon: AlertTriangle, to: '/incidents' },
  { label: 'Timers', icon: GitBranch, to: '/timers' },
  { label: 'Messages', icon: GitBranch, to: '/messages' },
  { label: 'DMN', icon: GitBranch, to: '/dmn' },
  { label: 'Analytics', icon: BarChart3, to: '/analytics' },
  { label: 'Users', icon: Users, to: '/admin/users' },
]

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
  <aside class="w-64 bg-sidebar text-sidebar-foreground flex flex-col border-r border-border">
    <div class="h-14 flex items-center px-4 border-b border-border">
      <span class="text-lg font-bold">ZorroBPM</span>
    </div>
    <nav class="flex-1 py-2 overflow-y-auto">
      <template v-for="item in navItems" :key="item.label">
        <div v-if="item.children">
          <button
            class="w-full flex items-center justify-between px-4 py-2.5 text-sm hover:bg-sidebar-accent transition-colors"
            :class="{ 'bg-sidebar-accent': isActiveGroup(item) }"
            @click="toggle(item.label)"
          >
            <span class="flex items-center gap-3">
              <component :is="item.icon" class="h-4 w-4" />
              {{ item.label }}
            </span>
            <component :is="expanded[item.label] ? ChevronDown : ChevronRight" class="h-4 w-4" />
          </button>
          <div v-if="expanded[item.label]" class="ml-4">
            <button
              v-for="child in item.children"
              :key="child.to"
              class="w-full flex items-center gap-3 px-4 py-2 text-sm hover:bg-sidebar-accent transition-colors"
              :class="{ 'bg-sidebar-accent text-primary-foreground': isActive(child.to) }"
              @click="child.to && navigate(child.to)"
            >
              <component :is="child.icon" class="h-4 w-4" />
              {{ child.label }}
            </button>
          </div>
        </div>
        <button
          v-else
          class="w-full flex items-center gap-3 px-4 py-2.5 text-sm hover:bg-sidebar-accent transition-colors"
          :class="{ 'bg-sidebar-accent': isActive(item.to) }"
          @click="item.to && navigate(item.to)"
        >
          <component :is="item.icon" class="h-4 w-4" />
          {{ item.label }}
        </button>
      </template>
    </nav>
  </aside>
</template>
