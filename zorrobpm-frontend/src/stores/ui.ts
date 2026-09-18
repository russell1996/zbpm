import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export const useUiStore = defineStore('ui', () => {
  const darkMode = ref(localStorage.getItem('theme') === 'dark')

  function toggleDarkMode() {
    darkMode.value = !darkMode.value
  }

  watch(darkMode, (val) => {
    localStorage.setItem('theme', val ? 'dark' : 'light')
    document.documentElement.classList.toggle('dark', val)
  }, { immediate: true })

  return { darkMode, toggleDarkMode }
})
