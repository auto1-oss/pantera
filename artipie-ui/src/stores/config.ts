import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { RuntimeConfig } from '@/types'

export const useConfigStore = defineStore('config', () => {
  const apiBaseUrl = ref('/api/v1')
  const grafanaUrl = ref('')
  const appTitle = ref('Artipie')
  const defaultPageSize = ref(20)

  function loadConfig(cfg: RuntimeConfig) {
    apiBaseUrl.value = cfg.apiBaseUrl
    grafanaUrl.value = cfg.grafanaUrl
    appTitle.value = cfg.appTitle
    defaultPageSize.value = cfg.defaultPageSize
  }

  return { apiBaseUrl, grafanaUrl, appTitle, defaultPageSize, loadConfig }
})
