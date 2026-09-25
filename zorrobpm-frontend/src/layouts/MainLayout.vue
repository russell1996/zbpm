<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useIsMobile } from '@/shared/lib/responsive'
import { SidebarProvider } from '@/components/ui/sidebar'
import SidebarNavShadcn from '@/widgets/shared/SidebarNavShadcn.vue'
import HeaderBar from '@/widgets/shared/HeaderBar.vue'
import { useRealtimeEvents } from '@/composables/useRealtimeEvents'
import { LogIn } from 'lucide-vue-next'

const isMobile = useIsMobile()
const sidebarOpen = ref(!isMobile.value)
const router = useRouter()
const { t } = useI18n()

// WO-UI-18 часть A: единая точка realtime-подписки. MainLayout монтируется
// один раз для всех страниц, требующих auth (router.beforeEach), и переживает
// навигацию между ними — соединение не пересоздаётся на каждый переход.
const realtime = useRealtimeEvents()
onMounted(() => realtime.connect())
onUnmounted(() => realtime.disconnect())
</script>

<template>
  <SidebarProvider class="h-screen">
    <div
      v-if="isMobile && sidebarOpen"
      class="fixed inset-0 bg-black/50 z-40"
      @click="sidebarOpen = false"
    />
    <div
      class="z-50 transition-transform duration-200 h-full shrink-0"
      :class="[
        isMobile ? 'fixed inset-y-0 left-0' : 'relative',
        isMobile && !sidebarOpen ? '-translate-x-full' : 'translate-x-0',
      ]"
    >
      <SidebarNavShadcn
        @navigate="sidebarOpen = false"
      />
    </div>
    <div class="flex-1 flex flex-col overflow-hidden min-w-0">
      <HeaderBar @toggle-sidebar="sidebarOpen = !sidebarOpen" :show-menu-button="isMobile" />
      <!-- WO-UI-22: честный статус вместо вечного «retrying» — сессия истекла
           целиком (refresh тоже не удался), realtime восстановить нельзя. -->
      <div
        v-if="realtime.sessionExpired.value"
        class="bg-amber-50 border-b border-amber-200 px-4 py-2 text-sm flex items-center gap-2"
        role="alert"
      >
        <LogIn class="h-4 w-4 shrink-0 text-amber-600" />
        <span>{{ t('realtimeSessionExpired') }}</span>
        <button class="ml-auto underline font-medium" @click="router.push({ name: 'login' })">
          {{ t('realtimeSignIn') }}
        </button>
      </div>
      <main class="flex-1 overflow-auto p-2 md:p-3">
        <router-view />
      </main>
    </div>
  </SidebarProvider>
</template>
