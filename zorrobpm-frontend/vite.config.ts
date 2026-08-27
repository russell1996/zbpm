import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'path'

export default defineConfig(({ mode }) => {
  // VITE_DEV_API_TARGET lets a local .env.local override the backend port the dev-server proxy
  // targets (e.g. when 8080 is taken by something else on the host) without touching this file.
  const env = loadEnv(mode, process.cwd(), '')
  const apiTarget = env.VITE_DEV_API_TARGET || 'http://localhost:8080'

  return {
    base: '/ui/',
    plugins: [vue(), tailwindcss()],
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src'),
      },
      dedupe: ['preact'],
    },
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: apiTarget,
          changeOrigin: true,
          rewrite: (path: string) => path.replace(/^\/api/, ''),
        },
      },
    },
  }
})
