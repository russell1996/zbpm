import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './app/router'
import i18n from './app/i18n'
import { setupApiInterceptors } from './services/interceptors'
import './style.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(i18n)

setupApiInterceptors()

app.mount('#app')
