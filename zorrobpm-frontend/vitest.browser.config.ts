import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'path'
import { playwright } from '@vitest/browser-playwright'

// Browser-geometry test config (WO-TEST-6): runs *.browser.test.ts in a real
// headless Chromium through Playwright. jsdom does not compute layout, so the
// visual criteria (tab underline, flyout clipping, scroll overflow, BPMN
// canvas height, properties panel height) can only be asserted here.
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
    dedupe: ['preact'],
  },
  optimizeDeps: {
    // Vite 8 (rolldown) pre-bundling miswires @vue/test-utils's namespace import
    // of vue (`init_shared_esm_bundler is not defined`, rolldown#9502). Serving
    // deps as raw ESM in the browser avoids the optimizer bug entirely.
    exclude: ['@vue/test-utils', 'vue'],
  },
  test: {
    deps: {
      optimizer: {
        web: { enabled: false },
      },
    },
    include: ['src/**/*.browser.test.ts'],
    exclude: ['**/node_modules/**', '**/dist/**'],
    browser: {
      enabled: true,
      headless: true,
      provider: playwright({ launchOptions: { args: ['--no-sandbox'] } }),
      screenshotFailures: false,
      instances: [{ browser: 'chromium', viewport: { width: 1280, height: 720 } }],
    },
  },
})