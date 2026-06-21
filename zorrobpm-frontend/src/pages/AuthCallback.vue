<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

onMounted(async () => {
  await auth.handleCallback()
  if (auth.isAuthenticated) {
    router.push('/')
  } else {
    router.push('/access-denied')
  }
})
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-muted">
    <div class="text-center">
      <h1 class="text-2xl font-bold mb-4">ZBPM</h1>
      <p class="text-muted-foreground">Completing authentication...</p>
    </div>
  </div>
</template>
