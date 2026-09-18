<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
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
  adminOnly?: boolean
}

const navItems = computed<NavItem[]>(() => [
  { labelKey: 'dashboard', icon: LayoutDashboard, to: '/' },
  // WO-ACL-8 criterion 14: two top-level sections instead of a collapsible group.
  // criterion 10 moved My Submissions into a dialog on the definitions page.
  // criterion 34: each menu item has a unique icon for readability in collapsed mode.
  { labelKey: 'definitions', icon: Workflow, to: '/processes/definitions' },
  { labelKey: 'instances', icon: Play, to: '/processes/instances' },
  { labelKey: 'tasks', icon: ListTodo, to: '/tasks' },
  { labelKey: 'serviceTasks', icon: Cpu, to: '/service-tasks' },
  { labelKey: 'incidents', icon: AlertTriangle, to: '/incidents' },
  { labelKey: 'timers', icon: Timer, to: '/timers' },
  { labelKey: 'messages', icon: Mail, to: '/messages' },
  { labelKey: 'dmn', icon: Table2, to: '/dmn' },
  { labelKey: 'analytics', icon: BarChart3, to: '/analytics' },
  { labelKey: 'users', icon: Users, to: '/admin/users', adminOnly: true },
  { labelKey: 'submissionQueue', icon: Inbox, to: '/admin/submissions', adminOnly: true },
])

// WO-ACL-8 criteria 1-2: non-super-admins don't see admin-only items.
const visibleNavItems = computed<NavItem[]>(() =>
  auth.isSuperAdmin ? navItems.value : navItems.value.filter((i) => !i.adminOnly),
)

function isActive(to?: string) {
  if (!to) return false
  return route.path === to || route.path.startsWith(to + '/')
}

function navigate(to: string) {
  router.push(to)
  emit('navigate')
}

// WO-ACL-10 criteria 1/21: the flyout is rendered OUTSIDE the scroll container
// and positioned with position:fixed from the hovered button's rect, so neither
// the inner vertical scroller nor the nav itself can clip it, and collapsed
// mode creates no horizontal overflow.
const flyout = ref<{ labelKey: string; x: number; y: number } | null>(null)

function showFlyoutFor(el: HTMLElement, item: NavItem) {
  const rect = el.getBoundingClientRect()
  flyout.value = { labelKey: item.labelKey, x: rect.right + 8, y: rect.top + rect.height / 2 }
}

function onItemEnter(item: NavItem, e: MouseEvent) {
  if (!props.collapsed) return
  showFlyoutFor(e.currentTarget as HTMLElement, item)
}

function onItemLeave() {
  flyout.value = null
}

// WO-ACL-10 criterion 23: in expanded mode a truncated label shows the full
// text on hover; labels that fit never show a tooltip.
function onLabelEnter(item: NavItem, e: MouseEvent) {
  const el = e.currentTarget as HTMLElement
  if (el.scrollWidth > el.clientWidth) {
    showFlyoutFor(el, item)
  }
}

function onLabelLeave() {
  flyout.value = null
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
    <!-- WO-ACL-10 criteria 1/21: <nav> is NOT a scroll container. Only the inner
         div scrolls, so the flyout rendered below is never clipped by it and the
         collapsed state produces no horizontal scrollbar. -->
    <nav class="flex-1 min-h-0 relative">
      <div class="h-full overflow-y-auto py-2">
        <template v-for="item in visibleNavItems" :key="item.labelKey">
          <div class="relative">
            <button
              class="w-full flex items-center gap-3 px-4 py-2.5 text-sm hover:bg-sidebar-accent transition-colors"
              :class="[
                { 'bg-sidebar-accent font-medium': isActive(item.to) },
                collapsed ? 'justify-center px-2' : '',
              ]"
              @click="item.to && navigate(item.to)"
              @mouseenter="onItemEnter(item, $event)"
              @mouseleave="onItemLeave"
            >
              <component :is="item.icon" class="h-4 w-4 shrink-0" />
              <!-- WO-ACL-10 criteria 15/22: labels never wrap (whitespace-nowrap)
                   and truncate with an ellipsis (min-w-0 lets truncate work inside
                   the flex button). -->
              <span
                v-if="!collapsed"
                class="truncate min-w-0 whitespace-nowrap"
                @mouseenter="onLabelEnter(item, $event)"
                @mouseleave="onLabelLeave"
              >{{ t(item.labelKey) }}</span>
            </button>
          </div>
        </template>
      </div>
      <!-- Flyout: outside the scroll container, fixed position keeps it clear of
           any ancestor clipping; shown via JS on hover (WO-ACL-10 criteria 1/21/23). -->
      <div
        v-if="flyout"
        class="sidebar-flyout fixed z-50 px-3 py-1.5 bg-popover text-popover-foreground text-sm rounded-md shadow-md border border-border whitespace-nowrap pointer-events-none"
        :style="{ left: flyout.x + 'px', top: flyout.y + 'px', transform: 'translateY(-50%)' }"
      >
        {{ t(flyout.labelKey) }}
      </div>
    </nav>
  </aside>
</template>
