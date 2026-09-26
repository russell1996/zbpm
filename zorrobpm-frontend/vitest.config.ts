import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'path'

// Main (jsdom/node) test config. Browser-geometry tests live in *.browser.test.ts
// and are excluded here — they are run by `npm run test:browser`
// (vitest.browser.config.ts) in a real Chromium via Playwright (WO-TEST-6).
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
    dedupe: ['preact'],
  },
  test: {
    exclude: [
      '**/node_modules/**',
      '**/dist/**',
      '**/*.browser.test.ts',
    ],
  },
})