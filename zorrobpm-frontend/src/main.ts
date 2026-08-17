import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './app/router'
import i18n from './app/i18n'
import { setupApiInterceptors } from './services/interceptors'
// WO-ACL-8 criterion 38: Golos Text font via @fontsource (self-hosted, CSP-safe).
import '@fontsource/golos-text/400.css'
import '@fontsource/golos-text/500.css'
import '@fontsource/golos-text/600.css'
import '@fontsource/golos-text/700.css'
import './style.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(i18n)

setupApiInterceptors()

app.mount('#app')
