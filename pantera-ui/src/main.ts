import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { definePreset } from '@primeuix/themes'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import Tooltip from 'primevue/tooltip'
import { ApmVuePlugin } from '@elastic/apm-rum-vue'
import App from './App.vue'
import { useConfigStore } from './stores/config'
import { initApiClient } from './api/client'
import { createAppRouter } from './router'
import type { RuntimeConfig } from './types'

import './assets/main.css'

const Auto1Preset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '#fdf5ec',
      100: '#fae6cf',
      200: '#f5cda0',
      300: '#edb06d',
      400: '#e8944a',
      500: '#d97b2a',
      600: '#c06a1f',
      700: '#a0571a',
      800: '#804518',
      900: '#673816',
      950: '#3a1d0a',
    },
  },
})

async function bootstrap() {
  const resp = await fetch('/config.json')
  const config: RuntimeConfig = await resp.json()

  const app = createApp(App)
  const pinia = createPinia()
  app.use(pinia)

  const configStore = useConfigStore()
  configStore.loadConfig(config)
  initApiClient(configStore.apiBaseUrl)

  app.use(PrimeVue, {
    theme: {
      preset: Auto1Preset,
      options: {
        darkModeSelector: '.dark',
      },
    },
  })
  app.use(ToastService)
  app.use(ConfirmationService)
  app.directive('tooltip', Tooltip)

  const router = createAppRouter()
  app.use(router)

  if (config.apmEnabled && config.apmServerUrl) {
    app.use(ApmVuePlugin, {
      router,
      config: {
        serviceName: config.apmServiceName,
        serverUrl: config.apmServerUrl,
        environment: config.apmEnvironment,
        serviceVersion: '2.0.0',
      },
    })
  }

  app.mount('#app')
}

bootstrap()
