<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

// Legacy OIDC redirect target. Auth is now username/password, so just route based on session state.
onMounted(async () => {
  await auth.init()
  router.replace(auth.isAuthenticated ? '/' : { name: 'login' })
})
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-muted">
    <div class="text-center">
      <h1 class="text-2xl font-bold mb-4">ZBPM</h1>
      <p class="text-muted-foreground">{{ t('signingIn') }}</p>
    </div>
  </div>
</template>
