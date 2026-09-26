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
// WO-UI-19: bpmn-js base CSS — without these the NavigatedViewer's built-in
// breadcrumb (.bjs-breadcrumbs, visible only via .bjs-breadcrumbs-shown) and
// drilldown icons live on UA defaults: invisible / out of flow. diagram-js.css
// covers canvas/palette/context-pad; bpmn-embedded.css covers drilldown/palette
// icons (embedded woff, no manual font files).
import 'bpmn-js/dist/assets/bpmn-js.css'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css'
import './style.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(i18n)

setupApiInterceptors()

app.mount('#app')
