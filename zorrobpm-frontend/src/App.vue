<script setup lang="ts">
import { RouterView } from 'vue-router'
import { Toaster } from 'vue-sonner'
</script>

<template>
  <Toaster position="top-center" :rich-colors="true" :duration="5000" close-button />
  <RouterView />
</template>
