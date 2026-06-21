import { ref, onMounted, onUnmounted } from 'vue'

export function useMediaQuery(query: string) {
  const matches = ref(false)
  const mql = window.matchMedia(query)

  function update() {
    matches.value = mql.matches
  }

  onMounted(() => {
    update()
    mql.addEventListener('change', update)
  })

  onUnmounted(() => {
    mql.removeEventListener('change', update)
  })

  return matches
}

export function useIsMobile() {
  return useMediaQuery('(max-width: 768px)')
}
